package com.hmdm.persistence;

import com.hmdm.persistence.domain.Group;
import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A record update is authorized against the stored owner, never against the customerId sent with the record. */
public class PersistedOwnerUpdateTest {

    private FakeMapper<DeviceMapper> fake;
    private GroupDAO dao;

    @Before
    public void setUp() {
        fake = new FakeMapper<>(DeviceMapper.class).answer("getGroupById", a -> {
            Group g = new Group();
            g.setId((Integer) a[0]);
            g.setName("stored");
            g.setCustomerId((Integer) a[0] == 1 ? 1 : 2);   // group #1 is the caller's, any other is not
            return g;
        });
        dao = new GroupDAO(fake.mapper);
        SecurityContext.init(Fakes.user(5, 1, "settings"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static Group group(int id, int claimedCustomerId) {
        Group g = new Group();
        g.setId(id);
        g.setName("renamed");
        g.setCustomerId(claimedCustomerId);
        return g;
    }

    @Test
    public void anotherCustomersGroupIsNotRenamedWithAnotherCustomerId() {
        try {
            dao.updateGroup(group(2, 1));
            fail("expected a SecurityException");
        } catch (SecurityException expected) {
            // ok
        }
        assertTrue(fake.calls.toString(), fake.callsTo("updateGroup").isEmpty());
    }

    @Test
    public void anotherCustomersGroupDeviceCountIsNotDisclosed() {
        try {
            dao.countDevicesByGroupId(2);
            fail("expected a SecurityException");
        } catch (SecurityException expected) {
            // ok
        }
        assertTrue(fake.calls.toString(), fake.callsTo("countDevicesByGroupId").isEmpty());
    }

    @Test
    public void ownGroupIsRenamedWhateverCustomerIdIsSent() {
        dao.updateGroup(group(1, 0));
        assertEquals(1, fake.callsTo("updateGroup").size());
        assertEquals(1, ((Group) fake.callsTo("updateGroup").get(0).args[0]).getCustomerId());
    }
}
