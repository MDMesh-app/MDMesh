/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.task;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.util.UntrustedText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runs once at startup. A private-mode (single-customer) install has one customer, id 1: the installers create no
 * other, and without a super-admin the console can't. Before 0.3.8 the self-signup API could add one. This logs a WARN
 * naming each customer above id 1, and where the docs say how to check and remove them. Until they are removed, the
 * install counts as multi-customer, so only a super-admin may update it, and it has none.
 *
 * <p>Nothing in the database records how a customer was created: the signupStatus and signupToken columns were never
 * written. The self-signup API set status customer.new and account type 0, so the lines show both. The files
 * directory is not logged (it keeps the customer's /files/ URLs unguessable).</p>
 */
public class ExtraCustomersCheck implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ExtraCustomersCheck.class);

    static final String DOCS = "docs/UPGRADING.md, v0.3.8, \"Customers you didn't create\"";

    private final UnsecureDAO unsecureDAO;
    private final String usageScenario;

    @Inject
    public ExtraCustomersCheck(UnsecureDAO unsecureDAO, @Named("usage.scenario") String usageScenario) {
        this.unsecureDAO = unsecureDAO;
        this.usageScenario = usageScenario;
    }

    @Override
    public void run() {
        if (!"private".equalsIgnoreCase(usageScenario)) {
            return;                 // shared mode has customers by design
        }
        try {
            for (String line : warnings(unsecureDAO.getAllCustomersUnsecure())) {
                log.warn(line);
            }
        } catch (Exception e) {
            log.error("Could not check this install for customers besides the default one", e);
        }
    }

    /** The WARN lines for a private-mode install with these customers: none when id 1 is the only one. */
    static List<String> warnings(List<Customer> customers) {
        List<Customer> extra = customers == null ? Collections.emptyList() : customers.stream()
                .filter(c -> c.getId() != null && c.getId() > 1)
                .sorted(Comparator.comparing(Customer::getId))
                .collect(Collectors.toList());
        if (extra.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<>();
        lines.add("SECURITY: this single-customer install has " + extra.size() + " customer account"
                + (extra.size() == 1 ? "" : "s") + " besides the default one (id 1), listed below. MDMesh doesn't "
                + "create them in private mode, but the self-signup API removed in 0.3.8 could. If you didn't add "
                + "them, check and remove them: " + DOCS + ". Until then only a super-admin may update this install, "
                + "and it has none.");
        for (Customer c : extra) {
            lines.add("SECURITY: customer id=" + c.getId()
                    + " name=" + quoted(c.getName())
                    + " email=" + quoted(c.getEmail())
                    + " created=" + time(c.getRegistrationTime(), "unknown")
                    + " lastSignIn=" + time(c.getLastLoginTime(), "never")
                    + " status=" + quoted(c.getCustomerStatus())
                    + " accountType=" + c.getAccountType()
                    + " (self-signup set status customer.new and account type 0)");
        }
        return lines;
    }

    /** Names and emails come from whoever created the customer, so they can't start a new log line. */
    private static String quoted(String value) {
        String s = UntrustedText.oneLine(value, 100);
        return s == null ? "none" : "'" + s + "'";
    }

    private static String time(Long millis, String none) {
        return millis == null || millis <= 0 ? none : Instant.ofEpochMilli(millis).toString();
    }
}
