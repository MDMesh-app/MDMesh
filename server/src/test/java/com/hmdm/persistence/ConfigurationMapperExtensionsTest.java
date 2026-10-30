package com.hmdm.persistence;

import com.hmdm.persistence.mapper.ConfigurationMapper;
import com.hmdm.testsupport.Fakes;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.Assert.assertTrue;

/**
 * Where configurations.extensions is read and written. Every save goes through the one update statement, so it is
 * where a save without extensions (an older client) keeps the stored value; every new configuration (a blank one, a
 * copy, a new customer's) goes through the one insert, which stores the empty object without them. Reads are
 * SELECT *, mapped by JsonObjectTypeHandler.
 */
public class ConfigurationMapperExtensionsTest {

    private static String squash(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    @Test
    public void theUpdateKeepsTheStoredExtensionsWhenTheSaveHasNone() {
        String sql = squash(Fakes.sqlOf(ConfigurationMapper.class, "updateConfiguration"));
        assertTrue(sql, sql.contains("extensions=COALESCE(CAST(#{extensions} AS jsonb), extensions) WHERE id=#{id}"));
    }

    @Test
    public void theInsertStoresTheEmptyObjectWhenTheConfigurationHasNone() {
        InputStream in = ConfigurationMapper.class.getResourceAsStream("ConfigurationMapper.xml");
        String xml = squash(new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A").next());
        String insert = xml.substring(xml.indexOf("<insert id=\"insertConfiguration\""), xml.indexOf("</insert>"));
        assertTrue(insert, insert.contains("kioskLockVolume, extensions)"));
        assertTrue(insert, insert.contains("#{kioskLockVolume}, COALESCE(CAST(#{extensions} AS jsonb), '{}'::jsonb))"));
    }
}
