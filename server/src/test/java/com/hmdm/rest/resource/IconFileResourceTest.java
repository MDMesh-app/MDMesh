/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UploadedFileDAO;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** Icon upload needs the settings permission and only decodes small images. */
public class IconFileResourceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class FakeCustomerDAO extends CustomerDAO {
        final Customer customer;
        FakeCustomerDAO(Customer customer) {
            super(null, null, null, null, null, null, null, "unused", 0, null);
            this.customer = customer;
        }
        @Override public Customer findById(int customerId) { return customer; }
    }

    private static final class FakeUploadedFileDAO extends UploadedFileDAO {
        final List<UploadedFile> inserted = new ArrayList<>();
        FakeUploadedFileDAO() { super(null, null, null, "unused"); }
        @Override public UploadedFile insert(UploadedFile file) { inserted.add(file); return file; }
    }

    private File acme;
    private FakeUploadedFileDAO files;
    private IconFileResource resource;

    @Before
    public void setUp() throws IOException {
        File filesDir = tmp.newFolder("files");
        acme = new File(filesDir, "acme");
        Customer customer = new Customer();
        customer.setId(1);
        customer.setFilesDir("acme");
        files = new FakeUploadedFileDAO();
        resource = new IconFileResource(files, new FakeCustomerDAO(customer), filesDir.getAbsolutePath());
        login(true);
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static void login(boolean superAdmin) {
        UserRole role = new UserRole();
        role.setSuperAdmin(superAdmin);
        role.setPermissions(new ArrayList<>());
        User user = new User();
        user.setCustomerId(1);
        user.setLogin(superAdmin ? "admin" : "viewer");
        user.setUserRole(role);
        SecurityContext.init(user);
    }

    private static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    /** A PNG whose header claims huge dimensions (a decoder would size its buffer from them). */
    private static byte[] pngClaiming(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream i = new DataOutputStream(ihdr);
        i.writeBytes("IHDR");
        i.writeInt(w);
        i.writeInt(h);
        i.write(new byte[]{8, 6, 0, 0, 0});
        byte[] chunk = ihdr.toByteArray();
        d.writeInt(chunk.length - 4);
        d.write(chunk);
        CRC32 crc = new CRC32();
        crc.update(chunk);
        d.writeInt((int) crc.getValue());
        return out.toByteArray();
    }

    private Response upload(byte[] bytes) throws Exception {
        return resource.uploadIconFile(new ByteArrayInputStream(bytes), null);
    }

    private static int count(File dir) {
        String[] names = dir.list();
        return names == null ? 0 : names.length;
    }

    @Test
    public void acceptsASmallSquareIcon() throws Exception {
        Response r = upload(png(64, 64));
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(1, files.inserted.size());
        assertEquals(1, count(acme));
    }

    @Test
    public void requiresTheSettingsPermission() throws Exception {
        login(false);
        Response r = upload(png(64, 64));
        assertEquals("error.permission.denied", r.getMessage());
        assertEquals(0, files.inserted.size());
        assertEquals(0, count(acme));
    }

    @Test
    public void refusesHugeDeclaredDimensionsBeforeDecoding() throws Exception {
        Response r = upload(pngClaiming(60_000, 60_000));
        assertNotEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals("error.icon.dimension.invalid", r.getMessage());
        assertEquals(0, files.inserted.size());
    }

    @Test
    public void refusesATruncatedImageAsInvalid() throws Exception {
        byte[] whole = png(64, 64);
        for (int length : new int[]{12, 20, 30}) {
            Response r = upload(java.util.Arrays.copyOf(whole, length));
            assertEquals("cut at " + length, "error.icon.format.invalid", r.getMessage());
        }
        Response headerOnly = upload(pngClaiming(64, 64));
        assertEquals("error.icon.format.invalid", headerOnly.getMessage());
        assertEquals(0, files.inserted.size());
    }

    @Test
    public void refusesAnOversizedUpload() throws Exception {
        byte[] big = new byte[(int) IconFileResource.MAX_ICON_BYTES + 1];
        byte[] header = png(64, 64);
        System.arraycopy(header, 0, big, 0, header.length);
        Response r = upload(big);
        assertEquals("error.icon.size.invalid", r.getMessage());
        assertEquals(0, files.inserted.size());
    }
}
