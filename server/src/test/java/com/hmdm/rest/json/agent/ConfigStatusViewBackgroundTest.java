package com.hmdm.rest.json.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** The device page shows which background image the device's configuration specifies, from the document it is sent. */
public class ConfigStatusViewBackgroundTest {

    @Test
    public void theImageComesFromTheDocumentsKioskTheme() {
        DesiredKioskBackgroundImage image = new DesiredKioskBackgroundImage();
        image.setUrl("https://mdm.example/files/acme/kiosk-0123456789ab-beach.jpg");
        image.setSha256("ab");
        DesiredKioskTheme theme = new DesiredKioskTheme();
        theme.setBackgroundImage(image);
        DesiredKiosk kiosk = new DesiredKiosk();
        kiosk.setTheme(theme);
        DesiredConfig doc = new DesiredConfig();
        doc.setKiosk(kiosk);

        ConfigStatusView v = new ConfigStatusView();
        v.setKioskBackgroundImageFrom(doc);
        assertSame(image, v.getKioskBackgroundImage());
    }

    @Test
    public void noKioskNoThemeOrNoImageIsNone() {
        ConfigStatusView v = new ConfigStatusView();
        v.setKioskBackgroundImageFrom(null);
        assertNull(v.getKioskBackgroundImage());
        DesiredConfig doc = new DesiredConfig();
        v.setKioskBackgroundImageFrom(doc);
        assertNull(v.getKioskBackgroundImage());
        doc.setKiosk(new DesiredKiosk());
        v.setKioskBackgroundImageFrom(doc);
        assertNull(v.getKioskBackgroundImage());
        doc.getKiosk().setTheme(new DesiredKioskTheme());
        v.setKioskBackgroundImageFrom(doc);
        assertNull(v.getKioskBackgroundImage());
        assertEquals(false, v.isInSync());
    }
}
