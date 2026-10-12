package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.Application;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The URL a configuration app is installed from: never a Headwind seed leftover. */
public class ConfigAppInstallerTest {

    private static Application app(String url) {
        Application a = new Application();
        a.setUrl(url);
        return a;
    }

    @Test
    public void skipsHeadwindSeedUrls() {
        assertNull(ConfigAppInstaller.firstUsableUrl(app("https://h-mdm.com/files/_HMDM_APK_")));
        assertNull(ConfigAppInstaller.firstUsableUrl(app("https://h-mdm.com/files/pager-1.02.apk")));
        assertNull(ConfigAppInstaller.firstUsableUrl(app("http://www.h-mdm.com/files/LauncherRestarter-1.04.apk")));
    }

    @Test
    public void keepsRealUrls() {
        assertEquals("https://mdm.example.com/files/app.apk",
                ConfigAppInstaller.firstUsableUrl(app(" https://mdm.example.com/files/app.apk ")));
        assertEquals("https://f-droid.org/repo/org.fdroid.fdroid_1.apk",
                ConfigAppInstaller.firstUsableUrl(app("https://f-droid.org/repo/org.fdroid.fdroid_1.apk")));
        assertNull(ConfigAppInstaller.firstUsableUrl(app("ftp://x/app.apk")));
    }
}
