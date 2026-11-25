/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.util;

import com.hmdm.persistence.domain.Customer;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;

/**
 * Locks {@link FileUtil#resolveFile} — the single source of truth for the served-files layout that
 * both {@code moveFile} (write target) and idempotent hosting ({@code exists()} check) rely on. If the
 * two ever computed different paths, a re-upload of the same content-addressed part would miss the
 * existence check and then throw FileExistsException — the "uploading a .xapk a second time fails" bug.
 */
public class FileUtilResolveTest {

    private static Customer customer(String filesDir) {
        Customer c = new Customer();
        c.setFilesDir(filesDir);
        return c;
    }

    @Test
    public void resolvesUnderTheCustomerDir() {
        assertEquals(new File("/srv/files/acme/pkg-abc.apk"),
                FileUtil.resolveFile(customer("acme"), "/srv/files", null, "pkg-abc.apk"));
    }

    @Test
    public void stripsLeadingSlashesFromTheName() {
        assertEquals(new File("/srv/files/acme/pkg-abc.apk"),
                FileUtil.resolveFile(customer("acme"), "/srv/files", null, "///pkg-abc.apk"));
    }

    @Test
    public void includesLocalPathWhenGiven() {
        assertEquals(new File("/srv/files/acme/sub/pkg-abc.apk"),
                FileUtil.resolveFile(customer("acme"), "/srv/files", "sub", "pkg-abc.apk"));
    }

    @Test
    public void isDeterministicForTheSameInputs() {
        // The invariant behind idempotent hosting: identical inputs → identical File, every call,
        // including when the customer's filesDir is null (moveFile treats it the same way).
        Customer c = customer(null);
        assertEquals(
                FileUtil.resolveFile(c, "/srv/files", null, "pkg-abc.apk"),
                FileUtil.resolveFile(c, "/srv/files", null, "pkg-abc.apk"));
    }
}
