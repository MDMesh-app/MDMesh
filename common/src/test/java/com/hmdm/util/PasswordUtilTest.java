package com.hmdm.util;

import org.junit.Test;

import java.lang.reflect.Field;
import java.security.SecureRandom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** SecureRandom provenance of token generators (ADR 0010 extends this file in a later release). */
public class PasswordUtilTest {

    @Test
    public void tokenGeneratorsUseSecureRandom() throws Exception {
        Field f = PasswordUtil.class.getDeclaredField("random");
        f.setAccessible(true);
        assertTrue(f.get(null) instanceof SecureRandom);
        Field c = CryptoUtil.class.getDeclaredField("RANDOM");
        c.setAccessible(true);
        assertTrue(c.get(null) instanceof SecureRandom);
    }
}
