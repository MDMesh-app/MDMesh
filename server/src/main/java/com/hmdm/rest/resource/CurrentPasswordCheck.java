/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.Response;
import com.hmdm.security.ConsoleSession;
import com.hmdm.security.LoginThrottle;
import com.hmdm.security.SecurityContext;

import javax.servlet.http.HttpServletRequest;

/**
 * Asks the caller for their current password before they create an account, delete one, or change another account's
 * password or email; creating or deleting a customer creates or deletes accounts too. A password that passes is
 * recorded on the request's {@link SecurityContext}, which is what UserDAO (and CustomerDAO, through it) requires before
 * any of these writes, so a route that forgets this check is refused there.
 */
final class CurrentPasswordCheck {

    /** The answer in a session signed in as another account: these changes need the account's own sign-in. */
    static final String IMPERSONATED = "error.password.impersonated";

    private final UserDAO userDAO;
    private final UnsecureDAO unsecureDAO;
    private final LoginThrottle loginThrottle;

    CurrentPasswordCheck(UserDAO userDAO, UnsecureDAO unsecureDAO, LoginThrottle loginThrottle) {
        this.userDAO = userDAO;
        this.unsecureDAO = unsecureDAO;
        this.loginThrottle = loginThrottle;
    }

    /**
     * Null when {@code password} is the current password of {@code currentUser}; else the error to answer. The check
     * counts against the same limits as sign-in (LoginThrottle): a session is no way to guess its password without a
     * limit either, and failures here lock that account like failed sign-ins do. A session signed in as another
     * account ({@link ConsoleSession#isImpersonated}) is refused outright, before anything is counted: whoever runs it
     * doesn't know that account's password, and guessing would lock the account's owner out.
     */
    Response refused(User currentUser, String password, HttpServletRequest req) {
        if (ConsoleSession.isImpersonated(req)) {
            return Response.ERROR(IMPERSONATED);
        }
        if (password == null || password.isEmpty() || currentUser == null) {
            return Response.ERROR("error.password.wrong");
        }
        User dbUser = userDAO.findByLoginOrEmail(currentUser.getLogin());
        if (dbUser == null || !dbUser.getId().equals(currentUser.getId())) {
            return Response.ERROR("error.password.wrong");
        }
        LoginThrottle.Verdict verdict = loginThrottle.begin(req, dbUser, dbUser.getLogin());
        if (!verdict.allowed()) {
            return Response.ERROR(AuthResource.TOO_MANY_ATTEMPTS);
        }
        if (!unsecureDAO.checkPasswordUnsecure(dbUser, password)) {   // upgrades a legacy hash that matches
            return Response.ERROR("error.password.wrong");
        }
        loginThrottle.succeeded(verdict.attempt());
        SecurityContext.get().confirmCurrentPassword();
        return null;
    }
}
