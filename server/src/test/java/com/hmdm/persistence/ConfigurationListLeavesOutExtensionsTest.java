package com.hmdm.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.mapper.ApplicationMapper;
import com.hmdm.persistence.mapper.ConfigurationMapper;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * The configuration lists (GET /search, /search/{value}, and the /list and /autocomplete lookups built from the same
 * DAO calls) leave out each row's extensions, up to 64 KB a row that no list reads; one configuration (GET /{id}, which
 * the editor, a template and a copy load) keeps them.
 */
public class ConfigurationListLeavesOutExtensionsTest {

    private static final int CUSTOMER = 1;

    private ConfigurationDAO dao;

    private static Configuration row(Integer id) {
        Configuration c = new Configuration();
        c.setId(id);
        c.setName("c" + id);
        c.setCustomerId(CUSTOMER);
        try {
            c.setExtensions((ObjectNode) new ObjectMapper().readTree("{\"netaccess\":{\"vpn\":true}}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    @Before
    public void setUp() throws Exception {
        FakeMapper<ConfigurationMapper> configs = new FakeMapper<>(ConfigurationMapper.class)
                .answer("getAllConfigurations", a -> Fakes.list(row(1), row(2)))
                .answer("getAllConfigurationsByValue", a -> Fakes.list(row(3)))
                .answer("getConfigurationById", a -> row((Integer) a[0]));
        dao = new ConfigurationDAO(configs.mapper, new FakeMapper<>(ApplicationMapper.class).mapper, null, null,
                "https://mdm.example.com", Files.createTempDirectory("files").toString(), null);
        SecurityContext.init(Fakes.user(5, CUSTOMER, "configurations"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void listRowsHaveNoExtensions() throws Exception {
        List<Configuration> all = dao.getAllConfigurations();
        assertEquals(2, all.size());
        for (Configuration c : all) assertNull(c.getExtensions());
        assertNull(dao.getAllConfigurationsByValue("c").get(0).getExtensions());
        // Nor the key: a list row's JSON doesn't say "extensions" at all.
        assertFalse(new ObjectMapper().writeValueAsString(all.get(0)).contains("extensions"));
    }

    @Test
    public void oneConfigurationKeepsThem() throws Exception {
        Configuration c = dao.getConfigurationById(4);
        assertEquals("{\"netaccess\":{\"vpn\":true}}", c.getExtensions().toString());
        assertEquals(true, new ObjectMapper().writeValueAsString(c).contains("\"extensions\":{\"netaccess\":{\"vpn\":true}}"));
    }
}
