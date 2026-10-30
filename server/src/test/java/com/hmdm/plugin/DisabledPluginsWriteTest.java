package com.hmdm.plugin;

import com.hmdm.plugin.persistence.DisabledPluginRows;
import com.hmdm.plugin.persistence.PluginDAO;
import com.hmdm.plugin.persistence.domain.DisabledPlugin;
import com.hmdm.plugin.persistence.domain.Plugin;
import com.hmdm.plugin.persistence.mapper.PluginMapper;
import com.hmdm.plugin.service.PluginStatusCache;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import com.mdmesh.fixture.FixturePluginConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.guice.transactional.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Every write of a customer's disabled plugins (pluginsDisabled) goes through PluginDAO, which drops that customer's
 * cached state once the write is done, so PluginStatusCache reads it again on the next ask instead of keeping what it
 * read before. The fake mapper below stands in for the table, and a fake transaction stands in for mybatis-guice's
 * {@code @Transactional}: the outermost one keeps a copy of the table and puts it back when the call throws.
 */
public class DisabledPluginsWriteTest {

    private static final String FIXTURE = FixturePluginConfiguration.ID;
    private static final int FIXTURE_ROW = 7;
    private static final int MASTER = 1;
    private static final int NEW_CUSTOMER = 5;

    /** The pluginsDisabled table. */
    private final List<DisabledPlugin> rows = new ArrayList<>();
    private FakeMapper<PluginMapper> mapper;
    private PluginStatusCache cache;
    private PluginDAO dao;
    /** The outermost transactions' ends and the cache's invalidations, in order. */
    private final List<String> events = new ArrayList<>();
    private int depth;
    /** Runs inside the cache's read of a customer's rows, after the rows are read: a write landing mid-read. */
    private Runnable duringRead = () -> { };

    private static DisabledPlugin disabled(int customerId, int pluginId) {
        DisabledPlugin d = new DisabledPlugin();
        d.setCustomerId(customerId);
        d.setPluginId(pluginId);
        return d;
    }

    private List<DisabledPlugin> rowsOf(int customerId) {
        return rows.stream().filter(r -> r.getCustomerId() == customerId).collect(Collectors.toList());
    }

    @BeforeClass
    public static void scan() {
        PluginList.init(PluginDiscoveryTest.context());
    }

    @Before
    public void setUp() {
        Plugin fixture = new Plugin();
        fixture.setId(FIXTURE_ROW);
        fixture.setIdentifier(FIXTURE);
        mapper = new FakeMapper<>(PluginMapper.class)
                .answer("findRegisteredPlugins", a -> Fakes.list(fixture))
                .answer("getDisabledPluginsForAllCustomers", a -> new ArrayList<>(rows))
                .answer("getDisabledPluginsForCustomer", a -> {
                    List<DisabledPlugin> read = rowsOf((Integer) a[0]);
                    duringRead.run();
                    return read;
                })
                .answer("cleanUpDisabledPlugins", a -> {
                    rows.removeIf(r -> r.getCustomerId() == (Integer) a[0]);
                    return 0;
                })
                .answer("insertDisabledPlugin", a -> {
                    for (Integer id : (Integer[]) a[0]) {
                        rows.add(disabled((Integer) a[1], id));
                    }
                    return 0;
                });
        // The master account turned the fixture plugin off; a new customer gets that copied.
        rows.add(disabled(MASTER, FIXTURE_ROW));
        cache = new PluginStatusCache(mapper.mapper) {
            @Override public void invalidate(int customerId) {
                events.add("invalidate " + customerId);
                super.invalidate(customerId);
            }
        };
        DisabledPluginRows writer = new DisabledPluginRows(mapper.mapper) {
            @Override public void replace(int customerId, Integer[] pluginIds) {
                inTransactionIf(DisabledPluginRows.class, "replace", () -> {
                    super.replace(customerId, pluginIds);
                    return null;
                }, int.class, Integer[].class);
            }

            @Override public void copyFromMaster(int customerId) {
                inTransactionIf(DisabledPluginRows.class, "copyFromMaster", () -> {
                    super.copyFromMaster(customerId);
                    return null;
                }, int.class);
            }
        };
        dao = new PluginDAO(mapper.mapper, cache, writer) {
            @Override public void saveDisabledPlugins(Integer[] pluginIds) {
                inTransactionIf(PluginDAO.class, "saveDisabledPlugins", () -> {
                    super.saveDisabledPlugins(pluginIds);
                    return null;
                }, Integer[].class);
            }

            @Override public void copyDisabledPluginsFromMaster(int customerId) {
                inTransactionIf(PluginDAO.class, "copyDisabledPluginsFromMaster", () -> {
                    super.copyDisabledPluginsFromMaster(customerId);
                    return null;
                }, int.class);
            }
        };
    }

    /** Runs the call as mybatis-guice would: in a transaction when the production method is {@code @Transactional}. */
    private <T> T inTransactionIf(Class<?> type, String method, Supplier<T> call, Class<?>... params) {
        try {
            if (!type.getMethod(method, params).isAnnotationPresent(Transactional.class)) {
                return call.get();
            }
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        if (depth++ > 0) {
            try {
                return call.get();
            } finally {
                depth--;
            }
        }
        List<DisabledPlugin> before = new ArrayList<>(rows);
        try {
            T result = call.get();
            events.add("commit");
            return result;
        } catch (RuntimeException e) {
            rows.clear();
            rows.addAll(before);
            events.add("rollback");
            throw e;
        } finally {
            depth--;
        }
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    /**
     * Failure scenario: the copy from master runs in a background task (CustomerCreatedEventListener) after the
     * customer exists. A check-in or the new admin's first page asks about the customer before it lands, so the cache
     * keeps an empty set, and the plugin master turned off stays on for that customer until a restart.
     */
    @Test
    public void aNewCustomerAskedAboutBeforeItsCopyLandsGetsMastersPluginsOffOnceItDoes() {
        assertFalse("nothing is copied yet", cache.isPluginDisabled(FIXTURE, NEW_CUSTOMER));
        dao.copyDisabledPluginsFromMaster(NEW_CUSTOMER);
        assertTrue(cache.isPluginDisabled(FIXTURE, NEW_CUSTOMER));
    }

    /**
     * Failure scenario: the cache reads a customer's rows just before a write commits, and stores them just after
     * that write dropped the entry. The read from before the write would then stay until a restart.
     */
    @Test
    public void aReadThatAWriteOvertakesIsNotKept() {
        duringRead = () -> {
            duringRead = () -> { };
            rows.add(disabled(NEW_CUSTOMER, FIXTURE_ROW));
            cache.invalidate(NEW_CUSTOMER);
        };
        assertFalse("this ask read the rows from before the write", cache.isPluginDisabled(FIXTURE, NEW_CUSTOMER));
        assertTrue("the next ask reads them again", cache.isPluginDisabled(FIXTURE, NEW_CUSTOMER));
    }

    @Test
    public void savingACustomersListCountsAtOnceAndIsReadFromTheTable() {
        SecurityContext.init(Fakes.user(9, NEW_CUSTOMER, "plugins_customer_access_management"));
        assertFalse(cache.isPluginDisabled(FIXTURE, NEW_CUSTOMER));
        dao.saveDisabledPlugins(new Integer[]{FIXTURE_ROW});
        assertTrue(cache.isPluginDisabled(FIXTURE, NEW_CUSTOMER));
        dao.saveDisabledPlugins(new Integer[0]);
        assertFalse(cache.isPluginDisabled(FIXTURE, NEW_CUSTOMER));
    }

    /**
     * Failure scenario: the entry is dropped inside the write's transaction, before the commit (as with
     * {@code @Transactional} on PluginDAO itself). A read between the two gets the old rows and keeps them until a
     * restart.
     */
    @Test
    public void theCachedStateIsDroppedOnlyAfterTheWriteCommits() {
        SecurityContext.init(Fakes.user(9, NEW_CUSTOMER, "plugins_customer_access_management"));
        dao.saveDisabledPlugins(new Integer[]{FIXTURE_ROW});
        dao.copyDisabledPluginsFromMaster(6);
        assertEquals(Arrays.asList("commit", "invalidate 5", "commit", "invalidate 6"), events);
    }

    /**
     * Failure scenario: the write fails half way (the clean-up ran, the insert threw), the transaction rolls back, and
     * the cache keeps a state the table never had. Dropping the entry after the rollback means the next ask reads the
     * table as it was: still off.
     */
    @Test
    public void aFailedSaveRollsBackAndStillDropsTheCachedState() {
        SecurityContext.init(Fakes.user(9, NEW_CUSTOMER, "plugins_customer_access_management"));
        // The cache holds an empty set while the table has the customer's row.
        assertFalse(cache.isPluginDisabled(FIXTURE, NEW_CUSTOMER));
        rows.add(disabled(NEW_CUSTOMER, FIXTURE_ROW));
        mapper.answer("insertDisabledPlugin", a -> {
            throw new IllegalStateException("insert failed");
        });
        try {
            dao.saveDisabledPlugins(new Integer[]{FIXTURE_ROW});
            fail("the insert threw");
        } catch (IllegalStateException expected) {
            // as arranged
        }
        assertEquals(Arrays.asList("rollback", "invalidate 5"), events);
        assertTrue("the rolled-back row counts, not the cached empty set", cache.isPluginDisabled(FIXTURE, NEW_CUSTOMER));
    }

    /**
     * Failure scenario: a POST of JSON null to /plugin/main/private/disabled cleared the customer's rows and
     * committed, so every plugin turned on for it. A malformed save must fail and keep the rows.
     */
    @Test
    public void savingNullIsRefusedAndKeepsTheRows() {
        SecurityContext.init(Fakes.user(9, MASTER, "plugins_customer_access_management"));
        try {
            dao.saveDisabledPlugins(null);
            fail("null is not a list");
        } catch (IllegalArgumentException expected) {
            // as intended
        }
        assertEquals(0, mapper.callsTo("cleanUpDisabledPlugins").size());
        assertEquals(1, rowsOf(MASTER).size());
        assertTrue(cache.isPluginDisabled(FIXTURE, MASTER));
    }

    /** An INSERT with no VALUES is invalid SQL, so an empty list writes nothing rather than failing the save. */
    @Test
    public void savingAnEmptyListOnlyClearsTheCustomersRows() {
        SecurityContext.init(Fakes.user(9, MASTER, "plugins_customer_access_management"));
        dao.saveDisabledPlugins(new Integer[0]);
        assertEquals(1, mapper.callsTo("cleanUpDisabledPlugins").size());
        assertEquals(0, mapper.callsTo("insertDisabledPlugin").size());
        assertTrue(rowsOf(MASTER).isEmpty());
    }

    /**
     * Failure scenario: master has no plugin turned off, so the copy built "INSERT ... VALUES" with nothing after it,
     * and the background task failed on every customer creation.
     */
    @Test
    public void copyingAnEmptyMasterListWritesNothing() {
        rows.clear();
        dao.copyDisabledPluginsFromMaster(NEW_CUSTOMER);
        assertEquals(0, mapper.callsTo("insertDisabledPlugin").size());
        assertFalse(cache.isPluginDisabled(FIXTURE, NEW_CUSTOMER));
    }
}
