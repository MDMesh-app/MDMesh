/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.task;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.mapper.CustomerMapper;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * At startup, a private-mode install with customers besides id 1 says so in the log: each one's id, name, email and
 * times, and where the docs say how to remove them. Never its files directory.
 */
public class ExtraCustomersCheckTest {

    private final List<String> warned = new ArrayList<>();
    private final AppenderSkeleton capture = new AppenderSkeleton() {
        @Override
        protected void append(LoggingEvent event) {
            if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
                warned.add(String.valueOf(event.getRenderedMessage()));
            }
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
    private final List<Customer> stored = new ArrayList<>();

    @Before
    public void setUp() {
        Logger logger = Logger.getLogger(ExtraCustomersCheck.class);
        previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addAppender(capture);
        stored.add(customer(1, "DEFAULT", null, null, ""));
    }

    @After
    public void tearDown() {
        Logger logger = Logger.getLogger(ExtraCustomersCheck.class);
        logger.removeAppender(capture);
        logger.setLevel(previous);
    }

    @Test
    public void aPrivateInstallWithOnlyTheDefaultCustomerLogsNothing() {
        check("private").run();

        assertTrue(warned.toString(), warned.isEmpty());
    }

    @Test
    public void aPrivateInstallWithAnotherCustomerNamesItAndPointsToTheDocs() {
        Customer signup = customer(7, "acme", "someone@example.com", 1760000000000L, "3f2c9a5e-1111-2222-3333-444455556666");
        signup.setCustomerStatus(Customer.CUSTOMER_NEW);
        signup.setAccountType(Customer.Demo);
        stored.add(signup);

        check("private").run();

        assertEquals(warned.toString(), 2, warned.size());
        String summary = warned.get(0);
        assertTrue(summary, summary.startsWith("SECURITY: this single-customer install has 1 customer account besides"));
        assertTrue(summary, summary.contains(ExtraCustomersCheck.DOCS));
        String line = warned.get(1);
        assertTrue(line, line.contains("customer id=7 name='acme' email='someone@example.com'"));
        assertTrue(line, line.contains("created=2025-10-09T08:53:20Z"));
        assertTrue(line, line.contains("lastSignIn=never"));
        assertTrue(line, line.contains("status='customer.new' accountType=0"));
        for (String w : warned) {
            assertFalse("the files directory is not logged: " + w, w.contains("3f2c9a5e"));
        }
    }

    @Test
    public void everyExtraCustomerGetsALineInIdOrder() {
        stored.add(customer(9, "b", null, null, "dir-b"));
        stored.add(customer(3, "a", null, null, "dir-a"));

        check("private").run();

        assertEquals(warned.toString(), 3, warned.size());
        assertTrue(warned.get(0), warned.get(0).contains("has 2 customer accounts besides"));
        assertTrue(warned.get(1), warned.get(1).contains("customer id=3 name='a' email=none created=unknown"));
        assertTrue(warned.get(2), warned.get(2).contains("customer id=9 name='b'"));
    }

    @Test
    public void aNameCannotStartAnotherLogLine() {
        stored.add(customer(4, "x\nSECURITY: all clear\r", "a@b\n", null, "d"));

        check("private").run();

        for (String w : warned) {
            assertFalse(w, w.contains("\n") || w.contains("\r"));
        }
        assertTrue(warned.get(1), warned.get(1).contains("name='x SECURITY: all clear'"));
    }

    @Test
    public void sharedModeHasCustomersByDesign() {
        stored.add(customer(2, "ADMIN", null, null, "ADMIN"));
        stored.add(customer(3, "tenant", null, null, "t"));

        check("shared").run();

        assertTrue(warned.toString(), warned.isEmpty());
    }

    private ExtraCustomersCheck check(String usageScenario) {
        Fakes.FakeMapper<CustomerMapper> customers = new Fakes.FakeMapper<>(CustomerMapper.class);
        customers.answer("findAll", a -> new ArrayList<>(stored));
        UnsecureDAO unsecureDAO = new UnsecureDAO(null, null, null, null, null, null, null, null, null, null,
                null, null, customers.mapper, null, ".", 2, "com.hmdm.launcher");
        return new ExtraCustomersCheck(unsecureDAO, usageScenario);
    }

    private static Customer customer(int id, String name, String email, Long created, String filesDir) {
        Customer c = new Customer();
        c.setId(id);
        c.setName(name);
        c.setEmail(email);
        c.setRegistrationTime(created);
        c.setFilesDir(filesDir);
        return c;
    }
}
