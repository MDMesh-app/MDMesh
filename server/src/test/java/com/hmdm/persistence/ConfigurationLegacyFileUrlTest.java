package com.hmdm.persistence;

import com.hmdm.event.Event;
import com.hmdm.event.EventService;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.ConfigurationFile;
import com.hmdm.persistence.mapper.ApplicationMapper;
import com.hmdm.persistence.mapper.ConfigurationMapper;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** A configuration whose files carry an odd legacy URL still saves and copies; the URL just gets no checksum. */
public class ConfigurationLegacyFileUrlTest {

    private static final int CUSTOMER = 1;
    private static final String LEGACY_URL = "ftp://legacy host/wall paper|1.png";

    private FakeMapper<ConfigurationMapper> configs;
    private ConfigurationDAO dao;

    @Before
    public void setUp() throws Exception {
        configs = new FakeMapper<>(ConfigurationMapper.class)
                .answer("getConfigurationById", a -> {
                    Configuration c = new Configuration();
                    c.setId((Integer) a[0]);
                    c.setCustomerId(CUSTOMER);
                    return c;
                });
        ConfigurationFileDAO files = new ConfigurationFileDAO(null, null, null) {
            @Override public List<ConfigurationFile> getConfigurationFiles(Integer id) { return new ArrayList<>(); }
        };
        EventService events = new EventService(null) {
            @Override public void fireEvent(Event event) { }
        };
        dao = new ConfigurationDAO(configs.mapper, new FakeMapper<>(ApplicationMapper.class).mapper, null, files,
                "https://mdm.example.com", Files.createTempDirectory("files").toString(), events);
        SecurityContext.init(Fakes.user(5, CUSTOMER, "configurations"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static Configuration withLegacyFile(Integer id) {
        ConfigurationFile f = new ConfigurationFile();
        f.setId(10);
        f.setExternalUrl(LEGACY_URL);
        Configuration c = new Configuration();
        c.setId(id);
        c.setName("legacy");
        c.setApplications(new ArrayList<>());
        c.setFiles(new ArrayList<>(Collections.singletonList(f)));
        return c;
    }

    @SuppressWarnings("unchecked")
    private List<ConfigurationFile> lastInsertedFiles() {
        List<Fakes.Call> calls = configs.callsTo("insertConfigurationFiles");
        return (List<ConfigurationFile>) calls.get(calls.size() - 1).args[1];
    }

    @Test
    public void savingKeepsTheLegacyUrlWithAnEmptyChecksum() {
        dao.updateConfiguration(withLegacyFile(3));
        assertEquals(LEGACY_URL, lastInsertedFiles().get(0).getExternalUrl());
        assertEquals("", lastInsertedFiles().get(0).getChecksum());
    }

    @Test
    public void copyingAConfigurationWithALegacyUrlWorks() {
        Configuration copy = withLegacyFile(3).newCopy();
        copy.setName("legacy copy");
        dao.insertConfiguration(copy);
        assertEquals(1, configs.callsTo("insertConfiguration").size());
        assertEquals(LEGACY_URL, lastInsertedFiles().get(0).getExternalUrl());
    }
}
