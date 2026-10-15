package com.hmdm.persistence;

import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.persistence.mapper.ConfigurationFileMapper;
import com.hmdm.persistence.mapper.UploadedFileMapper;
import com.hmdm.rest.json.FileConfigurationLink;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.Call;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** File-to-configuration links are removed only within the caller's own file and configurations. */
public class UploadedFileDAOLinkTest {

    private static final int CUSTOMER = 1;
    private static final int OTHER_CUSTOMER = 2;
    private static final int OWN_FILE = 10;
    private static final int OTHER_FILE = 20;

    private FakeMapper<UploadedFileMapper> files;
    private FakeMapper<ConfigurationFileMapper> links;
    private UploadedFileDAO dao;

    @Before
    public void setUp() {
        files = new FakeMapper<>(UploadedFileMapper.class).answer("findById", a -> {
            int id = (Integer) a[0];
            UploadedFile f = new UploadedFile();
            f.setId(id);
            f.setCustomerId(id == OWN_FILE ? CUSTOMER : OTHER_CUSTOMER);
            return f;
        });
        links = new FakeMapper<>(ConfigurationFileMapper.class);
        dao = new UploadedFileDAO(files.mapper, links.mapper, null, "files");
        SecurityContext.init(Fakes.user(5, CUSTOMER, "edit_files"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static FileConfigurationLink unlink(int linkId, int fileId, int configurationId) {
        FileConfigurationLink l = new FileConfigurationLink();
        l.setId(linkId);
        l.setFileId(fileId);
        l.setConfigurationId(configurationId);
        l.setUpload(false);
        return l;
    }

    @Test
    public void unlinkIsBoundToTheOwnedFileAndTheCallersCustomer() {
        // Owned file + owned configuration, but a link id that may belong to anybody.
        dao.updateFileConfigurations(Fakes.list(unlink(99, OWN_FILE, 5)));

        assertTrue("an id-only delete must not be used: " + links.calls,
                links.callsTo("deleteConfigurationFile").isEmpty());
        List<Call> deletes = links.callsStartingWith("delete");
        assertEquals(links.calls.toString(), 1, deletes.size());
        List<Object> args = Arrays.asList(deletes.get(0).args);
        assertTrue("delete binds the link id: " + args, args.contains(99));
        assertTrue("delete binds the authorized file: " + args, args.contains(OWN_FILE));
        assertTrue("delete binds the caller's customer: " + args, args.contains(CUSTOMER));

        String where = Fakes.whereOf(Fakes.sqlOf(ConfigurationFileMapper.class, deletes.get(0).method));
        assertTrue(where, where.contains("fileid"));
        assertTrue(where, where.contains("customerid"));
    }

    @Test
    public void unlinkOfAnotherCustomersFileIsRejected() {
        try {
            dao.updateFileConfigurations(Fakes.list(unlink(99, OTHER_FILE, 5)));
            fail("expected a SecurityException");
        } catch (SecurityException expected) {
            // ok
        }
        assertTrue(links.calls.toString(), links.callsStartingWith("delete", "insert").isEmpty());
    }
}
