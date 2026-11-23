package com.hmdm.guice.module;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.mapper.CommonMapper;
import com.hmdm.persistence.mapper.CustomerMapper;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.PasswordUtil;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The startup task stores a bare-MD5 users row (the changelog's first admin) as bcrypt, but only while the row still
 * holds that MD5: an installer setting the admin's password while the task runs keeps its password.
 */
public class StartupPasswordTaskTest {

    private static final String SEED_MD5 = "21232f297a57a5a743894a0e4a801fc3";
    private static final String CHOSEN = "$2a$11$chosenByTheInstallerChosenByTheInstallerChosenByTheIn";

    private String row;
    private String rowAfterRead;
    private StartupTaskModule module;

    @Before
    public void setUp() {
        Fakes.FakeMapper<UserMapper> users = new Fakes.FakeMapper<>(UserMapper.class);
        users.answer("findAllWithOldPassword", a -> {
            User u = new User();
            u.setId(1);
            u.setPassword(row);
            if (rowAfterRead != null) {
                row = rowAfterRead; // the installer writes between the task's read and its write
            }
            return Collections.singletonList(u);
        });
        users.answer("upgradePasswordHash", a -> {
            if (!Objects.equals(row, a[1])) {
                return 0;
            }
            row = (String) a[2];
            return 1;
        });
        UnsecureDAO dao = new UnsecureDAO(null, users.mapper, null, new Fakes.FakeMapper<>(CommonMapper.class).mapper,
                null, null, null, null, null, null, null, null, new Fakes.FakeMapper<>(CustomerMapper.class).mapper, null,
                ".", 2, "com.hmdm.launcher");
        module = new StartupTaskModule(null, dao, null, null, null, null, null, null, 0, "", false, false);
    }

    @Test
    public void aSeededMd5IsStoredAsBcrypt() {
        row = SEED_MD5;
        module.new UpdatePasswordTask().run();
        assertTrue(row.startsWith("$2a$" + PasswordUtil.BCRYPT_COST + "$"));
        assertTrue(PasswordUtil.passwordMatch(SEED_MD5.toUpperCase(), row));
    }

    @Test
    public void aPasswordSetMeanwhileIsKept() {
        row = SEED_MD5;
        rowAfterRead = CHOSEN;
        module.new UpdatePasswordTask().run();
        assertEquals(CHOSEN, row);
    }
}
