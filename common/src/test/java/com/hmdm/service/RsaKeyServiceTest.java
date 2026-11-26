/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.service;

import org.apache.commons.io.FileUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.Assert.assertEquals;

/** The block size a sign-in's ciphertext must have is the key's, kept from the key last read and new with a new key. */
public class RsaKeyServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void theBlockSizeIsTheKeysAndFollowsAReloadedKey() throws Exception {
        File dir = tmp.newFolder();
        RsaKeyService rsa = new RsaKeyService(dir.getAbsolutePath());

        assertEquals("a generated key is 1024 bits", 128, rsa.blockBytes());

        // Another key in its place: the size changes when the key is next read (every decrypt reads it)
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        FileUtils.writeByteArrayToFile(new File(dir, "private.key"), kp.getPrivate().getEncoded());
        FileUtils.writeByteArrayToFile(new File(dir, "public.key"), kp.getPublic().getEncoded());
        assertEquals("kept until the key is read again", 128, rsa.blockBytes());
        rsa.getPrivateKey();
        assertEquals(256, rsa.blockBytes());
    }

    @Test
    public void noKeyHasNoBlockSize() {
        assertEquals(0, new RsaKeyService("").blockBytes());
    }
}
