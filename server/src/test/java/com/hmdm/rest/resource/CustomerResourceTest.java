/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.mapper.CustomerMapper;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.rest.json.CustomerSearchRequest;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Customer accounts are super-admin business: every route of {@link CustomerResource} refuses anyone else before it
 * reads or writes a customer, and the DAO reads behind the routes refuse too.
 */
public class CustomerResourceTest {

    private Fakes.FakeMapper<CustomerMapper> customers;
    private Fakes.FakeMapper<UserMapper> users;
    private CustomerDAO customerDAO;
    private CustomerResource resource;

    @Before
    public void setUp() {
        customers = new Fakes.FakeMapper<>(CustomerMapper.class);
        users = new Fakes.FakeMapper<>(UserMapper.class);
        Customer stored = new Customer();
        stored.setId(2);
        stored.setName("tenant");
        stored.setFilesDir("secret-files-dir");
        customers.answer("findCustomerByIdForUpdate", a -> stored);
        customers.answer("findCustomerById", a -> stored);
        UserDAO userDAO = new UserDAO(users.mapper, 2);
        customerDAO = new CustomerDAO(customers.mapper, null, null, null, userDAO, null, null,
                "target/customer-resource-test", 2, null);
        UnsecureDAO unsecureDAO = new UnsecureDAO(null, users.mapper, null, null, null, null, null, null, userDAO, null,
                customerDAO, null, customers.mapper, null, "target/customer-resource-test", 2, "com.hmdm.launcher");
        resource = new CustomerResource(customerDAO, unsecureDAO, userDAO, null);
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void everyRouteRefusesAnOrgAdmin() throws Exception {
        SecurityContext.init(Fakes.user(10, 2, "settings", "configurations", "edit_devices"));

        List<String> routes = new ArrayList<>();
        for (Method m : CustomerResource.class.getDeclaredMethods()) {
            if (!isRoute(m)) {
                continue;
            }
            routes.add(m.getName());
            Response response = (Response) m.invoke(resource, arguments(m));
            assertDenied(m.getName(), response);
        }
        assertTrue("the scan found the routes: " + routes, routes.size() >= 8);
        assertEquals("no customer was read or written: " + customers.calls, 0, customers.calls.size());
        assertEquals("no user was read or written: " + users.calls, 0, users.calls.size());
    }

    @Test
    public void everyRouteRefusesASessionWithoutUser() throws Exception {
        SecurityContext.init(2);     // a customer context without a user, as the public device paths set

        for (Method m : CustomerResource.class.getDeclaredMethods()) {
            if (isRoute(m)) {
                assertDenied(m.getName(), (Response) m.invoke(resource, arguments(m)));
            }
        }
        assertEquals(0, customers.calls.size());
    }

    @Test
    public void theEditFormIsForSuperAdmins() {
        SecurityContext.init(Fakes.superAdmin(1, 1));

        Response response = resource.getCustomerForUpdate(2);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals("tenant", ((Customer) response.getData()).getName());
    }

    @Test
    public void thePrefixCheckIsForSuperAdmins() {
        SecurityContext.init(Fakes.superAdmin(1, 1));

        assertEquals(Response.ResponseStatus.OK, resource.validatePrefix("ab").getStatus());
        assertEquals(1, customers.callsTo("isPrefixUsed").size());
    }

    @Test
    public void theDaoReadsRefuseAnOrgAdmin() {
        // Where every path meets: a future caller of these reads cannot skip the check.
        SecurityContext.init(Fakes.user(10, 2, "settings"));

        assertThrowsSecurity(() -> customerDAO.findByIdForUpdate(2));
        assertThrowsSecurity(() -> customerDAO.isPrefixUsed("ab"));
        assertEquals(0, customers.calls.size());
    }

    @Test
    public void aSuperAdminStillImpersonatesACustomersAdmin() throws Exception {
        // The route is a POST now (ImpersonationIsAPostTest); the method and its checks are unchanged.
        SecurityContext.init(Fakes.superAdmin(1, 1));
        com.hmdm.persistence.domain.User admin = new com.hmdm.persistence.domain.User();
        admin.setId(20);
        admin.setLogin("tenant");
        admin.setCustomerId(2);
        admin.setAuthToken("token");
        com.hmdm.persistence.domain.UserRole role = new com.hmdm.persistence.domain.UserRole();
        role.setId(2);
        admin.setUserRole(role);
        users.answer("findAll", a -> java.util.Collections.singletonList(admin));
        List<Object> sessionWrites = new ArrayList<>();

        Response response = resource.impersonateCustomer(2, request(sessionWrites), null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(java.util.Collections.singletonList(admin), sessionWrites);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static boolean isRoute(Method m) {
        return m.isAnnotationPresent(GET.class) || m.isAnnotationPresent(POST.class)
                || m.isAnnotationPresent(PUT.class) || m.isAnnotationPresent(DELETE.class);
    }

    private static Object[] arguments(Method m) {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == Integer.class || types[i] == int.class) {
                args[i] = 2;
            } else if (types[i] == String.class) {
                args[i] = "tenant";
            } else if (types[i] == Customer.class) {
                Customer c = new Customer();
                c.setName("evil");
                c.setEmail("evil@example.com");
                args[i] = c;
            } else if (types[i] == CustomerSearchRequest.class) {
                args[i] = new CustomerSearchRequest();
            } else {
                args[i] = null;  // the servlet request/response: must not be reached
            }
        }
        return args;
    }

    /** A request whose session records what is stored in it. */
    private static javax.servlet.http.HttpServletRequest request(List<Object> sessionWrites) {
        javax.servlet.http.HttpSession session = (javax.servlet.http.HttpSession) java.lang.reflect.Proxy.newProxyInstance(
                CustomerResourceTest.class.getClassLoader(), new Class<?>[]{javax.servlet.http.HttpSession.class},
                (proxy, method, args) -> {
                    if ("setAttribute".equals(method.getName())) {
                        sessionWrites.add(args[1]);
                    }
                    return null;
                });
        return (javax.servlet.http.HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
                CustomerResourceTest.class.getClassLoader(), new Class<?>[]{javax.servlet.http.HttpServletRequest.class},
                (proxy, method, args) -> "getSession".equals(method.getName()) ? session : null);
    }

    private static void assertDenied(String route, Response response) {
        assertEquals(route, Response.ResponseStatus.ERROR, response.getStatus());
        assertEquals(route, Response.PERMISSION_DENIED().getMessage(), response.getMessage());
    }

    private static void assertThrowsSecurity(Runnable r) {
        try {
            r.run();
        } catch (com.hmdm.security.SecurityException expected) {
            return;
        }
        throw new AssertionError("expected a SecurityException");
    }
}
