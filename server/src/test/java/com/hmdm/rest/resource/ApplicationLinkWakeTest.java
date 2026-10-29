package com.hmdm.rest.resource;

import com.hmdm.event.ConfigurationUpdatedEvent;
import com.hmdm.event.Event;
import com.hmdm.event.EventService;
import com.hmdm.notification.PushService;
import com.hmdm.persistence.ApplicationDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.LinkConfigurationsToAppRequest;
import com.hmdm.rest.json.LinkConfigurationsToAppVersionRequest;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * Linking apps to configurations from the Apps page tells the changed configurations' devices, like a configuration
 * save does: a {@link ConfigurationUpdatedEvent} per configuration, which wakes MDMesh agents (AgentConfigUpdatedListener,
 * after its commit delay). Nothing is sent when nothing changed.
 */
public class ApplicationLinkWakeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<String> log = new ArrayList<>();
    private Set<Integer> changed = Collections.emptySet();
    private ApplicationResource resource;

    @Before
    public void setUp() {
        ApplicationDAO dao = new ApplicationDAO(null, null, "files", "https://mdm.example.com", "", null) {
            @Override public Set<Integer> updateApplicationConfigurations(LinkConfigurationsToAppRequest request) {
                log.add("saved");
                return changed;
            }
            @Override public Set<Integer> updateApplicationVersionConfigurations(
                    LinkConfigurationsToAppVersionRequest request, User user) {
                log.add("saved");
                return changed;
            }
        };
        EventService events = new EventService(null) {
            @Override public void fireEvent(Event event) {
                log.add("updated " + ((ConfigurationUpdatedEvent) event).getConfigurationId());
            }
        };
        PushService legacyOff = new PushService(null, null, null, null, false);
        resource = new ApplicationResource(dao, null, legacyOff, events, tmp.getRoot().getAbsolutePath());
        SecurityContext.init(Fakes.user(1, 1, "edit_applications", "edit_application_versions"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static LinkConfigurationsToAppRequest appRequest() {
        LinkConfigurationsToAppRequest r = new LinkConfigurationsToAppRequest();
        r.setApplicationId(100);
        r.setConfigurations(new ArrayList<>());
        return r;
    }

    private static LinkConfigurationsToAppVersionRequest versionRequest() {
        LinkConfigurationsToAppVersionRequest r = new LinkConfigurationsToAppVersionRequest();
        r.setApplicationVersionId(1000);
        r.setConfigurations(new ArrayList<>());
        return r;
    }

    @Test
    public void appLinksTellEveryChangedConfigurationAfterTheSave() {
        changed = new LinkedHashSet<>(Arrays.asList(5, 6));
        assertEquals(Response.ResponseStatus.OK, resource.updateApplicationConfigurations(appRequest()).getStatus());
        assertEquals(Arrays.asList("saved", "updated 5", "updated 6"), log);
    }

    @Test
    public void versionLinksTellEveryChangedConfigurationAfterTheSave() {
        changed = Collections.singleton(5);
        assertEquals(Response.ResponseStatus.OK,
                resource.updateApplicationVersionConfigurations(versionRequest()).getStatus());
        assertEquals(Arrays.asList("saved", "updated 5"), log);
    }

    @Test
    public void nothingChangedTellsNobody() {
        resource.updateApplicationConfigurations(appRequest());
        resource.updateApplicationVersionConfigurations(versionRequest());
        assertEquals(Arrays.asList("saved", "saved"), log);
    }
}
