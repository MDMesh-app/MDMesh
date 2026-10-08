package com.hmdm.util;

import com.hmdm.persistence.domain.Application;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class ConfigurationAppDeltaTest {
    private static Application app(int versionId, int action) {
        Application app = new Application();
        app.setUsedVersionId(versionId);
        app.setAction(action);
        return app;
    }

    @Test public void addedInstallVersionIsReturned() {
        assertEquals(Collections.singleton(20), ConfigurationAppDelta.addedInstallVersionIds(
                Collections.singletonList(app(10, 1)), Arrays.asList(app(10, 1), app(20, 1))));
    }

    @Test public void unchangedAndUnrelatedEditsDoNotReinstall() {
        assertEquals(Collections.emptySet(), ConfigurationAppDelta.addedInstallVersionIds(
                Collections.singletonList(app(10, 1)), Collections.singletonList(app(10, 1))));
    }

    @Test public void changingSelectedVersionQueuesOnlyTheNewVersion() {
        assertEquals(Collections.singleton(20), ConfigurationAppDelta.addedInstallVersionIds(
                Collections.singletonList(app(10, 1)), Collections.singletonList(app(20, 1))));
    }

    @Test public void removeOrHiddenEntriesDoNotQueueInstallation() {
        assertEquals(Collections.emptySet(), ConfigurationAppDelta.addedInstallVersionIds(
                Collections.singletonList(app(10, 1)), Arrays.asList(app(10, 2), app(20, 0))));
    }
}
