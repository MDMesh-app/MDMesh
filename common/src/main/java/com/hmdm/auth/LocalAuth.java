// Derived from Headwind MDM (Apache-2.0); modified by the MDMesh contributors.
package com.hmdm.auth;

import com.google.inject.Inject;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.domain.User;

import javax.inject.Named;
import javax.inject.Singleton;

@Singleton
public class LocalAuth implements HmdmAuthInterface {

    private UnsecureDAO userDAO;

    @Inject
    public LocalAuth(UnsecureDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Override
    public User findUser(String login) {
        return userDAO.findByLoginOrEmail(login);
    }

    @Override
    public boolean authenticate(User user, String password) {
        // No write on a failure: a database round trip only for accounts that exist would tell them apart by timing,
        // and nothing reads lastLoginFail since sign-in is limited by LoginThrottle. A legacy hash that matches is
        // stored as bcrypt there.
        return userDAO.checkPasswordUnsecure(user, password);
    }
}
