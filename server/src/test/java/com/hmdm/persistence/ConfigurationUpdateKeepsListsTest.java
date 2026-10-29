package com.hmdm.persistence;

import com.hmdm.event.Event;
import com.hmdm.event.EventService;
import com.hmdm.persistence.domain.ApplicationSetting;
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

/**
 * Updating a configuration replaces its files and app settings only when the request carries those lists:
 * a list left out (null) keeps what is stored, and an empty list clears it.
 */
public class ConfigurationUpdateKeepsListsTest {

    private static final int CUSTOMER = 1;

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

    private static Configuration config(List<ConfigurationFile> files, List<ApplicationSetting> settings) {
        Configuration c = new Configuration();
        c.setId(3);
        c.setName("warehouse");
        c.setApplications(new ArrayList<>());
        c.setFiles(files);
        c.setApplicationSettings(settings);
        return c;
    }

    private static ConfigurationFile file() {
        ConfigurationFile f = new ConfigurationFile();
        f.setId(10);
        f.setFileId(7);
        return f;
    }

    private static ApplicationSetting setting() {
        ApplicationSetting s = new ApplicationSetting();
        s.setApplicationId(2);
        s.setName("server_url");
        s.setValue("https://ops.example.com");
        return s;
    }

    private int calls(String method) {
        return configs.callsTo(method).size();
    }

    @Test
    public void absentListsKeepTheStoredFilesAndSettings() {
        dao.updateConfiguration(config(null, null));
        assertEquals(1, calls("updateConfiguration"));
        assertEquals(0, calls("removeConfigurationFilesById"));
        assertEquals(0, calls("insertConfigurationFiles"));
        assertEquals(0, calls("removeConfigurationApplicationSettingsById"));
        assertEquals(0, calls("insertConfigurationApplicationSettings"));
    }

    @Test
    public void emptyListsClearThem() {
        dao.updateConfiguration(config(new ArrayList<>(), new ArrayList<>()));
        assertEquals(1, calls("removeConfigurationFilesById"));
        assertEquals(0, calls("insertConfigurationFiles"));
        assertEquals(1, calls("removeConfigurationApplicationSettingsById"));
        assertEquals(0, calls("insertConfigurationApplicationSettings"));
    }

    @Test
    public void sentListsReplaceThem() {
        dao.updateConfiguration(config(new ArrayList<>(Collections.singletonList(file())),
                new ArrayList<>(Collections.singletonList(setting()))));
        assertEquals(1, calls("removeConfigurationFilesById"));
        assertEquals(1, calls("insertConfigurationFiles"));
        assertEquals(1, calls("removeConfigurationApplicationSettingsById"));
        assertEquals(1, calls("insertConfigurationApplicationSettings"));
    }
}
