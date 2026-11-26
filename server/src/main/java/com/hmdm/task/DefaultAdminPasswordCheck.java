/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.task;

import com.google.inject.Inject;
import com.hmdm.persistence.CommonDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.util.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Warns in the server log while the account named {@code admin}, or {@code superadmin} (shared mode, which seeded it
 * with admin's password of the time), still has the password {@code admin}. The installer sets admin's own password,
 * but only when it seeds, after the server's first boot: until then the changelog's admin/admin row is there on every
 * fresh install, so the check waits for the seed (the settings table has rows only after it). To be run after the
 * startup migration of MD5 passwords.</p>
 */
public class DefaultAdminPasswordCheck implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAdminPasswordCheck.class);

    private static final String[] DEFAULT_LOGINS = {"admin", "superadmin"};
    /** MD5("admin"), as the clients send it. */
    private static final String DEFAULT_PASSWORD_MD5 = "21232F297A57A5A743894A0E4A801FC3";

    private final UnsecureDAO unsecureDAO;
    private final CommonDAO commonDAO;

    @Inject
    public DefaultAdminPasswordCheck(UnsecureDAO unsecureDAO, CommonDAO commonDAO) {
        this.unsecureDAO = unsecureDAO;
        this.commonDAO = commonDAO;
    }

    @Override
    public void run() {
        if (!commonDAO.isDatabaseInitialized()) {
            return;
        }
        for (String login : DEFAULT_LOGINS) {
            User account = unsecureDAO.findByLogin(login);
            // Compares only: this check never upgrades the stored hash
            if (account != null && PasswordUtil.passwordMatch(DEFAULT_PASSWORD_MD5, account.getPassword())) {
                logger.warn("The account '{}' still has the default password 'admin'. Sign in and change it now: "
                        + "anyone who can reach this server can sign in with it.", login);
            }
        }
    }
}
