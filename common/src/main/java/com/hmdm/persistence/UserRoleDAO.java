/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 * Modified by the MDMesh contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.persistence.domain.UserRolePermission;
import com.hmdm.persistence.mapper.UserRoleMapper;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;

import javax.inject.Named;
import org.mybatis.guice.transactional.Transactional;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class UserRoleDAO {
    private final UnsecureDAO unsecureDAO;
    private final UserDAO userDAO;
    private final UserRoleMapper mapper;
    private final int orgAdminRoleId;

    @Inject
    public UserRoleDAO(UnsecureDAO unsecureDAO,
                       UserDAO userDAO,
                       UserRoleMapper mapper,
                       @Named("role.orgadmin.id") int orgAdminRoleId) {
        this.unsecureDAO = unsecureDAO;
        this.userDAO = userDAO;
        this.mapper = mapper;
        this.orgAdminRoleId = orgAdminRoleId;
    }

    public boolean hasAccess() {
        return SecurityContext.get().getCurrentUser().map(u -> {
            if (unsecureDAO.isSingleCustomer()) {
                if (!u.getUserRole().isSuperAdmin() && !userDAO.isOrgAdmin(u)) {
                    return false;
                }
            } else if (!u.getUserRole().isSuperAdmin()) {
                return false;
            }
            return true;
        }).orElse(false);
    }

    void checkAccess() {
        if (!hasAccess()) {
            throw new IllegalArgumentException("Operation not allowed");
        }
    }

    public List<UserRolePermission> getPermissionsList() {
        checkAccess();
        return mapper.getPermissionsList();
    }

    public List<UserRole> findAll() {
        checkAccess();
        List<UserRole> roles = mapper.findAll();
        roles.removeIf(role -> (role.getId() == orgAdminRoleId));   // Admin cannot edit admin permissions!
        return roles;
    }

    public UserRole findByName(String name) {
        checkAccess();
        return mapper.findByName(name);
    }

    public UserRole findById(Integer id) {
        checkAccess();
        return mapper.findById(id);
    }

    private void checkEditable(Integer id) {
        UserRole role = id == null ? null : mapper.findById(id);
        if (role == null || role.isSuperAdmin() || id == orgAdminRoleId) {
            throw new IllegalArgumentException("error.role.protected");
        }
    }

    private void validate(UserRole role) {
        if (role == null || role.getName() == null || role.getName().trim().isEmpty() ||
                role.getName().trim().length() > 50) {
            throw new IllegalArgumentException("error.role.name");
        }
        role.setName(role.getName().trim());
        if (role.isSuperAdmin()) {
            throw new IllegalArgumentException("error.role.protected");
        }
        Set<Integer> allowed = mapper.getPermissionsList().stream()
                .map(UserRolePermission::getId).collect(Collectors.toSet());
        Set<Integer> selected = new HashSet<>();
        if (role.getPermissions() != null) {
            for (UserRolePermission permission : role.getPermissions()) {
                if (permission == null || !allowed.contains(permission.getId()) ||
                        !selected.add(permission.getId())) {
                    throw new IllegalArgumentException("error.role.permission");
                }
            }
        }
        UserRole existing = mapper.findByName(role.getName());
        if (existing != null && !existing.getId().equals(role.getId())) {
            throw new IllegalArgumentException("error.duplicate.role");
        }
    }

    /**
     * <p>Throws a {@link SecurityException} unless the current user may store {@code userRole} with the permissions
     * it lists. A super-admin may. Anyone else (an org admin of a single-customer install) edits no super-admin role
     * and not the admin role (theirs), and adds only permissions their own role holds (read from the database), never
     * a super-admin one. Permissions the stored role already has may be kept.</p>
     */
    void checkGrantable(UserRole userRole) {
        if (SecurityContext.get().isSuperAdmin()) {
            return;
        }
        UserRole stored = null;
        if (userRole.getId() != null) {
            stored = mapper.findById(userRole.getId());
            if (stored == null || stored.isSuperAdmin() || userRole.getId() == orgAdminRoleId) {
                throw SecurityException.onAdminDataAccessViolation("edit user role " + userRole.getId());
            }
        }
        if (userRole.getPermissions() == null || userRole.getPermissions().isEmpty()) {
            return;
        }
        // What the role already has may be kept, so saving a role never fails over a permission it was given before
        Set<Integer> kept = stored == null || stored.getPermissions() == null ? Collections.emptySet()
                : stored.getPermissions().stream().map(UserRolePermission::getId).collect(Collectors.toSet());
        Set<Integer> held = SecurityContext.get().getCurrentUser()
                .map(u -> u.getUserRole() == null ? null : mapper.findById(u.getUserRole().getId()))
                .map(r -> r.getPermissions() == null ? Collections.<UserRolePermission>emptyList() : r.getPermissions())
                .orElse(Collections.emptyList())
                .stream()
                .filter(p -> !p.isSuperAdmin())
                .map(UserRolePermission::getId)
                .collect(Collectors.toSet());
        for (UserRolePermission p : userRole.getPermissions()) {
            if (p == null || !(held.contains(p.getId()) || kept.contains(p.getId()))) {
                throw SecurityException.onAdminDataAccessViolation("grant permission " + (p == null ? null : p.getId()));
            }
        }
    }

    @Transactional
    public void insert(UserRole userRole) {
        checkAccess();
        validate(userRole);
        checkGrantable(userRole);
        mapper.insert(userRole);
        savePermissions(userRole);
    }

    private void savePermissions(UserRole role) {
        if (role.getPermissions() != null && !role.getPermissions().isEmpty()) {
            mapper.insertPermissions(role.getId(),
                    role.getPermissions().stream().map(UserRolePermission::getId).collect(Collectors.toList()));
        }
    }

    @Transactional
    public void update(UserRole userRole) {
        checkAccess();
        checkEditable(userRole == null ? null : userRole.getId());
        validate(userRole);
        checkGrantable(userRole);
        mapper.update(userRole);
        mapper.deletePermissions(userRole.getId());
        savePermissions(userRole);
    }

    @Transactional
    public void delete(int id) {
        checkAccess();
        checkEditable(id);
        if (mapper.countUsers(id) > 0) {
            throw new IllegalArgumentException("error.role.in.use");
        }
        mapper.deletePermissions(id);
        mapper.delete(id);
    }
}
