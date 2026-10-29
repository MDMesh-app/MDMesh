package com.hmdm.persistence;

import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.ApplicationVersion;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.mapper.ApplicationMapper;
import com.hmdm.rest.json.ApplicationConfigurationLink;
import com.hmdm.rest.json.ApplicationVersionConfigurationLink;
import com.hmdm.rest.json.LinkConfigurationsToAppRequest;
import com.hmdm.rest.json.LinkConfigurationsToAppVersionRequest;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.Call;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Linking apps to configurations from the Apps page reports the configurations that actually changed, so their
 * devices can be told (like a configuration save), and leaves kiosk mode as the configuration says.
 */
public class ApplicationLinkChangesTest {

    private static final int CUSTOMER = 1;
    private static final int APP = 100, VERSION = 1000;
    private static final int CONFIG = 5, CONFIG_2 = 6;

    private FakeMapper<ApplicationMapper> fake;
    private ApplicationDAO dao;
    /** What the link-state query returns: the first call sees the state before the change, later ones after it. */
    private List<LookupItem> before, after;

    private static LookupItem state(int configurationId, String links) {
        LookupItem i = new LookupItem();
        i.setId(configurationId);
        i.setName(links);
        return i;
    }

    @Before
    public void setUp() {
        before = Arrays.asList(state(CONFIG, "[null,null,true,null]"), state(CONFIG_2, "[null,null,false,null]"));
        after = before;
        fake = new FakeMapper<>(ApplicationMapper.class)
                .answer("findById", a -> {
                    Application app = new Application();
                    app.setId(APP);
                    app.setPkg("com.example");
                    app.setCustomerId(CUSTOMER);
                    return (Integer) a[0] == APP ? app : null;
                })
                .answer("findVersionById", a -> {
                    ApplicationVersion v = new ApplicationVersion();
                    v.setId(VERSION);
                    v.setApplicationId(APP);
                    return (Integer) a[0] == VERSION ? v : null;
                })
                .answer("getCustomerConfigurationIds", a -> Fakes.list(CONFIG, CONFIG_2))
                .answer("getConfigurationLinkStates",
                        a -> fake.callsTo("getConfigurationLinkStates").size() == 1 ? before : after);
        dao = new ApplicationDAO(fake.mapper, null, "files", "https://mdm.example.com", "", null);
        SecurityContext.init(Fakes.user(5, CUSTOMER, "edit_applications", "edit_application_versions"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static LinkConfigurationsToAppRequest appLinks(ApplicationConfigurationLink... links) {
        LinkConfigurationsToAppRequest r = new LinkConfigurationsToAppRequest();
        r.setApplicationId(APP);
        r.setConfigurations(new ArrayList<>(Arrays.asList(links)));
        return r;
    }

    private static ApplicationConfigurationLink link(Integer id, int configurationId, int action) {
        ApplicationConfigurationLink l = new ApplicationConfigurationLink();
        l.setId(id);
        l.setConfigurationId(configurationId);
        l.setApplicationId(APP);
        l.setAction(action);
        l.setShowIcon(true);
        l.setNotify(true);
        return l;
    }

    private static LinkConfigurationsToAppVersionRequest versionLinks(int... configurationIds) {
        LinkConfigurationsToAppVersionRequest r = new LinkConfigurationsToAppVersionRequest();
        r.setApplicationVersionId(VERSION);
        List<ApplicationVersionConfigurationLink> links = new ArrayList<>();
        for (int id : configurationIds) {
            ApplicationVersionConfigurationLink l = new ApplicationVersionConfigurationLink();
            l.setConfigurationId(id);
            l.setAction(1);
            l.setShowIcon(true);
            links.add(l);
        }
        r.setConfigurations(links);
        return r;
    }

    /** The link state is read before the first change and after the last one, for the app's package. */
    private void assertStateReadAroundTheChanges() {
        List<Call> calls = fake.calls;
        List<Call> reads = fake.callsTo("getConfigurationLinkStates");
        assertEquals(calls.toString(), 2, reads.size());
        assertTrue(calls.toString(), fake.callsStartingWith("update", "delete", "insert", "remove", "uninstall", "recheck")
                .stream().allMatch(c -> calls.indexOf(c) > calls.indexOf(reads.get(0))
                        && calls.indexOf(c) < calls.indexOf(reads.get(1))));
        for (Call c : reads) assertEquals(Arrays.asList(CUSTOMER, APP), Arrays.asList(c.args));
    }

    @Test
    public void linkingAnAppReportsTheConfigurationItChanged() {
        after = Arrays.asList(state(CONFIG, "[null,null,true,[{\"action\":1}]]"), before.get(1));
        assertEquals(Collections.singleton(CONFIG),
                dao.updateApplicationConfigurations(appLinks(link(null, CONFIG, 1), link(null, CONFIG_2, 0))));
        assertStateReadAroundTheChanges();
    }

    @Test
    public void resavingTheSameLinksReportsNothing() {
        // The Apps page sends every configuration's row, changed or not.
        assertEquals(Collections.<Integer>emptySet(),
                dao.updateApplicationConfigurations(appLinks(link(7, CONFIG, 1), link(null, CONFIG_2, 0))));
    }

    @Test
    public void aConfigurationThatAppearsOrDisappearsCountsAsChanged() {
        after = Collections.singletonList(before.get(0));
        assertEquals(Collections.singleton(CONFIG_2), dao.updateApplicationConfigurations(appLinks(link(8, CONFIG_2, 0))));
    }

    @Test
    public void versionLinksReportTheConfigurationsTheyChanged() {
        after = Arrays.asList(state(CONFIG, "[null,null,true,[{\"action\":1}]]"),
                state(CONFIG_2, "[null,null,false,[{\"action\":1}]]"));
        User user = SecurityContext.get().getCurrentUser().get();
        assertEquals(new HashSet<>(Arrays.asList(CONFIG, CONFIG_2)),
                dao.updateApplicationVersionConfigurations(versionLinks(CONFIG, CONFIG_2), user));
        assertStateReadAroundTheChanges();
    }

    @Test
    public void linkEditsLeaveKioskModeAsTheConfigurationSays() {
        // Kiosk mode belongs to the configuration (its main app), not to the legacy content app: no link edit writes it.
        for (java.lang.reflect.Method m : ApplicationMapper.class.getMethods()) {
            String sql = Fakes.sqlOf(ApplicationMapper.class, m.getName()).replaceAll("\\s+", " ").toLowerCase();
            assertTrue(m.getName() + ": " + sql, !sql.contains("set kioskmode"));
        }
    }
}
