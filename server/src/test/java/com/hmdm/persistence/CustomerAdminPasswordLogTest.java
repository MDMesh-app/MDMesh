/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import com.hmdm.event.Event;
import com.hmdm.event.EventService;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.Settings;
import com.hmdm.persistence.mapper.CustomerMapper;
import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Creating a customer hands its admin's first password to the super-admin, and never to the log; nor its files dir. */
public class CustomerAdminPasswordLogTest {

    private final List<String> logged = new ArrayList<>();
    private final AppenderSkeleton capture = new AppenderSkeleton() {
        @Override
        protected void append(LoggingEvent event) {
            logged.add(String.valueOf(event.getRenderedMessage()));
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    };
    private Level previous;

    @Before
    public void setUp() {
        Logger logger = Logger.getLogger(CustomerDAO.class);
        previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addAppender(capture);
    }

    @After
    public void tearDown() {
        Logger logger = Logger.getLogger(CustomerDAO.class);
        logger.removeAppender(capture);
        logger.setLevel(previous);
        SecurityContext.release();
    }

    @Test
    public void theNewAdminPasswordIsNotLogged() {
        Fakes.FakeMapper<CustomerMapper> customers = new Fakes.FakeMapper<>(CustomerMapper.class);
        customers.answer("insert", a -> {
            ((Customer) a[0]).setId(7);
            return null;
        });
        Fakes.FakeMapper<UserMapper> users = new Fakes.FakeMapper<>(UserMapper.class);
        Fakes.FakeMapper<DeviceMapper> devices = new Fakes.FakeMapper<>(DeviceMapper.class);
        devices.answer("insertDevice", a -> {
            ((com.hmdm.persistence.domain.Device) a[0]).setId(100);
            return 1;
        });
        CommonDAO settings = new CommonDAO(null, null, null) {
            @Override
            public Settings getSettings() {
                Settings s = new Settings();
                s.setPasswordLength(12);
                return s;
            }
        };
        EventService events = new EventService(null) {
            @Override
            public void fireEvent(Event event) {
            }
        };
        CustomerDAO dao = new CustomerDAO(customers.mapper, null, null, devices.mapper, new UserDAO(users.mapper, 2),
                settings, null, "target/customer-admin-password-log-test", 2, events);
        SecurityContext.init(Fakes.superAdmin(1, 1));
        Customer customer = new Customer();
        customer.setName("tenant");
        customer.setPrefix("tn");

        String credentials = dao.insertCustomer(customer);

        String password = credentials.substring(credentials.indexOf('/') + 1);
        assertTrue("a password was generated: " + credentials, password.length() >= 8);
        assertFalse("something was logged", logged.isEmpty());
        assertTrue(customer.getFilesDir() != null && !customer.getFilesDir().isEmpty());
        for (String line : logged) {
            assertFalse("the password is in the log: " + line, line.contains(password));
            // The files directory name keeps the customer's /files/ URLs unguessable.
            assertFalse("the files directory is in the log: " + line, line.contains(customer.getFilesDir()));
        }
    }

    @Test
    public void deletingACustomerNamesNoFilePath() throws Exception {
        java.io.File base = new java.io.File("target/customer-delete-log-test");
        java.io.File dir = new java.io.File(base, "0b7e2c1d-aaaa-bbbb-cccc-ddddeeeeffff");
        assertTrue(new java.io.File(dir, "app").mkdirs() || dir.isDirectory());
        java.nio.file.Files.write(new java.io.File(dir, "app/x.apk").toPath(), new byte[]{1});
        Customer stored = new Customer();
        stored.setId(7);
        stored.setName("tenant");
        stored.setFilesDir(dir.getName());
        Fakes.FakeMapper<CustomerMapper> customers = new Fakes.FakeMapper<>(CustomerMapper.class);
        customers.answer("findCustomerById", a -> stored);
        CustomerDAO dao = new CustomerDAO(customers.mapper, null, null, null, null, null, null, base.getPath(), 2, null);
        SecurityContext.init(Fakes.superAdmin(1, 1));
        java.io.PrintStream out = System.out;
        java.io.ByteArrayOutputStream printed = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(printed, true, "UTF-8"));
        try {
            dao.removeCustomerById(7);
        } finally {
            System.setOut(out);
        }

        assertFalse("the files directory is gone", dir.exists());
        assertFalse("nothing printed: " + printed, printed.toString("UTF-8").contains(dir.getName()));
        for (String line : logged) {
            assertFalse("the files directory is in the log: " + line, line.contains(dir.getName()));
        }
    }
}
