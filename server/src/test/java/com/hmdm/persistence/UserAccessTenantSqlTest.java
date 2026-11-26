/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.persistence;

import com.hmdm.persistence.mapper.ApplicationMapper;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.testsupport.Fakes;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.Assert.assertTrue;

/**
 * Shared mode: an account's device group and configuration access is read and used only within its own customer. A
 * stored access row that names another customer's group or configuration shows no name and reaches nothing.
 */
public class UserAccessTenantSqlTest {

    private static String squash(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    @Test
    public void anAccountsGroupsAndConfigurationsAreItsOwnCustomers() {
        InputStream in = UserMapper.class.getResourceAsStream("UserMapper.xml");
        String xml = squash(new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A").next());
        String select = xml.substring(xml.indexOf("<sql id=\"userDataSelect\">"));
        select = select.substring(0, select.indexOf("</sql>"));
        assertTrue(select, select.contains(
                "LEFT JOIN groups ON userDeviceGroupsAccess.groupId = groups.id AND groups.customerId = users.customerId"));
        assertTrue(select, select.contains("LEFT JOIN configurations ON userConfigurationAccess.configurationId = "
                + "configurations.id AND configurations.customerId = users.customerId"));
    }

    @Test
    public void aLimitedUserUnlinksAnAppVersionFromTheirOwnCustomersConfigurationsOnly() {
        String sql = squash(Fakes.sqlOf(ApplicationMapper.class, "removeApplicationVersionConfigurationsForLimitedUser"));
        assertTrue(sql, sql.contains("WHERE userConfigurationAccess.userId=#{userId} "
                + "AND configurations.customerId=#{customerId}"));
    }
}
