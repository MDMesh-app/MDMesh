package com.hmdm.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class CommandPayloadsTest {

    @Test public void reads_a_top_level_string_packageName() {
        assertEquals("com.a", CommandPayloads.packageNameOf("{\"packageName\":\"com.a\"}"));
        assertEquals("com.a", CommandPayloads.packageNameOf(
                "{\"url\":\"https://h/com.b.apk\",\"packageName\":\"com.a\",\"versionCode\":3}"));
    }

    @Test public void returns_the_value_verbatim_like_the_agent_sees_it() {
        assertEquals(" com.a ", CommandPayloads.packageNameOf("{\"packageName\":\" com.a \"}"));
    }

    @Test public void null_for_unreadable_payloads_and_never_throws() {
        assertNull(CommandPayloads.packageNameOf(null));
        assertNull(CommandPayloads.packageNameOf(""));
        assertNull(CommandPayloads.packageNameOf("   "));
        assertNull(CommandPayloads.packageNameOf("null"));
        assertNull(CommandPayloads.packageNameOf("not json com.a"));
        assertNull(CommandPayloads.packageNameOf("{broken com.a"));
        assertNull(CommandPayloads.packageNameOf("[\"com.a\"]"));
        assertNull(CommandPayloads.packageNameOf("\"com.a\""));
    }

    @Test public void null_when_packageName_is_missing_blank_or_not_a_string() {
        assertNull(CommandPayloads.packageNameOf("{}"));
        assertNull(CommandPayloads.packageNameOf("{\"pkg\":\"com.a\"}"));
        assertNull(CommandPayloads.packageNameOf("{\"packageName\":null}"));
        assertNull(CommandPayloads.packageNameOf("{\"packageName\":42}"));
        assertNull(CommandPayloads.packageNameOf("{\"packageName\":{\"x\":\"com.a\"}}"));
        assertNull(CommandPayloads.packageNameOf("{\"packageName\":\"   \"}"));
        assertNull(CommandPayloads.packageNameOf("{\"nested\":{\"packageName\":\"com.a\"}}"));
    }
}
