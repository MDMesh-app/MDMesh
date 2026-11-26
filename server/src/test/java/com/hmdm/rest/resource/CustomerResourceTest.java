/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
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
import com.hmdm.security.LoginThrottle;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.FakeSessions;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.PasswordUtil;
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
    private Customer stored;

    @Before
    public void setUp() {
        customers = new Fakes.FakeMapper<>(CustomerMapper.class);
        users = new Fakes.FakeMapper<>(UserMapper.class);
        stored = new Customer();
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
        resource = new CustomerResource(customerDAO, unsecureDAO, userDAO, null, new LoginThrottle("", false,
                "0123456789abcdef0123456789abcdef0123456789abcdef", ""));
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

    /** A super-admin (MD5 of their password below), and customer 2 with an admin account on old@example.com. */
    private static final String CALLER_MD5 = "5F4DCC3B5AA765D61D8327DEB882CF99";

    private void customerWithAdmin() {
        com.hmdm.persistence.domain.User caller = Fakes.superAdmin(1, 1);
        caller.setLogin("root");
        caller.setPassword(PasswordUtil.getHashFromMd5(CALLER_MD5));
        SecurityContext.init(caller);
        com.hmdm.persistence.domain.User admin = new com.hmdm.persistence.domain.User();
        admin.setId(20);
        admin.setLogin("tenant");
        admin.setEmail("old@example.com");
        admin.setCustomerId(2);
        com.hmdm.persistence.domain.UserRole role = new com.hmdm.persistence.domain.UserRole();
        role.setId(2);
        admin.setUserRole(role);
        users.answer("findByEmail", a -> "old@example.com".equals(a[0]) ? admin : null);
        users.answer("findById", a -> Integer.valueOf(20).equals(a[0]) ? admin : null);
        users.answer("findByLogin", a -> "root".equals(a[0]) ? caller : null);
        stored.setEmail("old@example.com");
    }

    private static Customer edit(String email) {
        Customer customer = new Customer();
        customer.setId(2);
        customer.setName("tenant");
        customer.setEmail(email);
        return customer;
    }

    @Test
    public void changingTheEmailOfACustomersAdminTakesTheCallersCurrentPassword() {
        customerWithAdmin();

        Customer change = edit("new@example.com");
        assertEquals("error.password.wrong", resource.updateCustomer(change, null).getMessage());
        change.setOldPassword("00000000000000000000000000000001");
        assertEquals("error.password.wrong", resource.updateCustomer(change, null).getMessage());
        assertEquals(0, users.callsTo("updateIdentity").size());
        assertEquals(0, customers.callsTo("update").size());

        change.setOldPassword(CALLER_MD5);
        assertEquals(Response.ResponseStatus.OK, resource.updateCustomer(change, null).getStatus());
        assertEquals(1, users.callsTo("updateIdentity").size());
    }

    @Test
    public void anEditKeepingTheEmailTakesNoPassword() {
        customerWithAdmin();

        assertEquals(Response.ResponseStatus.OK, resource.updateCustomer(edit("old@example.com"), null).getStatus());
    }

    @Test
    public void theCurrentPasswordNeverLeavesInACustomer() throws Exception {
        Customer customer = edit("a@example.com");
        customer.setOldPassword(CALLER_MD5);

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(customer);

        assertTrue(json, !json.contains("oldPassword") && !json.contains(CALLER_MD5));
        assertTrue(customer.toString(), !customer.toString().contains(CALLER_MD5));
        assertEquals(CALLER_MD5, new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"oldPassword\":\"" + CALLER_MD5 + "\"}", Customer.class).getOldPassword());
    }

    private static Customer newCustomer() {
        Customer customer = new Customer();
        customer.setName("acme");
        customer.setEmail("acme@example.com");
        return customer;
    }

    private static com.hmdm.rest.json.CurrentPassword currentPassword(String md5) {
        com.hmdm.rest.json.CurrentPassword body = new com.hmdm.rest.json.CurrentPassword();
        body.setOldPassword(md5);
        return body;
    }

    @Test
    public void creatingACustomerTakesTheCallersCurrentPassword() {
        customerWithAdmin();

        Customer create = newCustomer();
        assertEquals("error.password.wrong", resource.updateCustomer(create, null).getMessage());
        create.setOldPassword("00000000000000000000000000000001");
        assertEquals("error.password.wrong", resource.updateCustomer(create, null).getMessage());
        assertEquals(0, customers.callsTo("insert").size());
        assertEquals(0, users.callsTo("insert").size());

        create.setOldPassword(CALLER_MD5);
        resource.updateCustomer(create, null);      // the rest of the setup is not faked here
        assertEquals(1, customers.callsTo("insert").size());
    }

    @Test
    public void deletingACustomerTakesTheCallersCurrentPassword() {
        customerWithAdmin();

        assertEquals("error.password.wrong", resource.removeCustomer(2, null, null).getMessage());
        assertEquals("error.password.wrong",
                resource.removeCustomer(2, currentPassword("00000000000000000000000000000001"), null).getMessage());
        assertEquals(0, customers.callsTo("delete").size());

        assertEquals(Response.ResponseStatus.OK,
                resource.removeCustomer(2, currentPassword(CALLER_MD5), null).getStatus());
        assertEquals(1, customers.callsTo("delete").size());
    }

    @Test
    public void theDaoRefusesACustomerCreateOrDeleteThatSkipsTheCheck() {
        SecurityContext.init(Fakes.superAdmin(1, 1));

        assertThrowsSecurity(() -> customerDAO.insertCustomer(newCustomer()));
        assertThrowsSecurity(() -> customerDAO.removeCustomerById(2));
        assertEquals(0, customers.callsTo("insert").size());
        assertEquals(0, customers.callsTo("delete").size());
        assertEquals(0, users.calls.size());
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
        // The account, the session format, and the super-admin's id
        assertEquals(java.util.Arrays.asList(admin, 1, 1), sessionWrites);
    }

    @Test
    public void aSessionSignedInAsAnotherAccountCannotSignInAsACustomersAdmin() throws Exception {
        SecurityContext.init(Fakes.superAdmin(1, 1));
        com.hmdm.testsupport.FakeSessions sessions = new com.hmdm.testsupport.FakeSessions();
        com.hmdm.testsupport.FakeSessions.Session inner = sessions.existing("signed-in-as-another");
        inner.attributes.put("impersonatedBy", 5);

        assertDenied("impersonate", resource.impersonateCustomer(2, sessions.request(), null));
        assertEquals(0, users.callsTo("findAll").size());
        assertEquals(inner, sessions.all.get(sessions.all.size() - 1));
        assertEquals(false, inner.invalidated);
    }

    @Test
    public void customerImpersonationStartsAFreshSession() throws Exception {
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
        FakeSessions sessions = new FakeSessions();
        FakeSessions.Session superAdmins = sessions.existing("the-super-admins");
        superAdmins.attributes.put("credentials", Fakes.superAdmin(1, 1));

        Response response = resource.impersonateCustomer(2, sessions.request(), null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertTrue(superAdmins.invalidated);
        assertTrue(!"the-super-admins".equals(sessions.current().id));
        assertEquals(admin, sessions.current().attributes.get("credentials"));
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
