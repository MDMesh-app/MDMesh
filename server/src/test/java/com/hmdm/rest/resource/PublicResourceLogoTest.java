/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.rest.resource;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.ws.rs.core.Response;
import java.io.File;

import static org.junit.Assert.assertEquals;

/** /rest/public/logo: the logo rebranding.logo names, or 404; never another product's logo. */
public class PublicResourceLogoTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static PublicResource withLogo(String logo) {
        return new PublicResource("", logo, "", "", "", "");
    }

    @Test
    public void noRebrandingLogoIsANotFound() {
        assertEquals(404, withLogo("").getRebrandedLogo().getStatus());
    }

    @Test
    public void theRebrandingLogoIsServed() throws Exception {
        File logo = tmp.newFile("logo.png");
        Response response = withLogo(logo.getPath()).getRebrandedLogo();
        assertEquals(200, response.getStatus());
        assertEquals("image/png", response.getHeaderString("Content-Type"));
    }

    @Test
    public void aRebrandingLogoThatIsGoneIsANotFound() {
        assertEquals(404, withLogo(new File(tmp.getRoot(), "gone.png").getPath()).getRebrandedLogo().getStatus());
    }
}
