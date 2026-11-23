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
import javax.inject.Named;

import com.google.inject.Singleton;
import org.mybatis.guice.transactional.Transactional;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.util.PasswordResetTokens;
import com.hmdm.util.PasswordUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Singleton
public class UserDAO extends AbstractDAO<User> {
    private final UserMapper mapper;
    private final int orgAdminRoleId;

    @Inject
    public UserDAO(UserMapper mapper, @Named("role.orgadmin.id") int orgAdminRoleId) {
        this.mapper = mapper;
        this.orgAdminRoleId = orgAdminRoleId;
    }

    public User findByLoginOrEmail( String login ) {
        return getSingleRecord(() -> {
            User user = mapper.findByLogin(login);
            if (user == null) {
                user = mapper.findByEmail(login);
            }
            return user;
        }, SecurityException::onUserAccessViolation);
    }

    public User getUserDetails( int id ) {
        return getSingleRecord(() -> mapper.findById( id ), SecurityException::onUserAccessViolation);
    }

    /**
     * <p>Gets the persisted user account which the current user is allowed to administer: an account of the same
     * customer, or of any customer for a super-admin. Only a super-admin may administer a super-admin account, and
     * anyone else only an account whose device and configuration access their own covers ({@link #covers}).</p>
     *
     * @param id an ID of the user account.
     * @return the persisted user account or <code>null</code> if there is no such account.
     * @throws SecurityException if the current user is not allowed to administer the account.
     */
    public User getManagedUser(int id) {
        User target = mapper.findById(id);
        if (target == null) {
            return null;
        }
        if (!manageableAccounts().test(target)) {
            throw SecurityException.onUserAccessViolation(target);
        }
        return target;
    }

    /**
     * The rule of {@link #getManagedUser}, to apply to a list of accounts: the caller is read from the database, not
     * the session, and only once.
     */
    public Predicate<User> manageableAccounts() {
        User current = SecurityContext.get().getCurrentUser().orElse(null);
        if (current == null) {
            return target -> false;
        }
        if (current.isSuperAdmin()) {
            return target -> true;
        }
        User caller = mapper.findById(current.getId());
        return target -> caller != null && administers(caller, target);
    }

    /** An administrator who is no super-admin administers an account of their customer that their access covers. */
    private static boolean administers(User caller, User target) {
        return caller.getCustomerId() == target.getCustomerId() && !target.isSuperAdmin() && covers(caller, target);
    }

    /**
     * <p>Whether a session in which account {@code impersonatorId} signed in as {@code target} may go on. Checked on
     * every request (AuthFilter), not only when it started: the account that signed in must still exist with the
     * authToken it had then ({@code token}; a password change or a new sign-in limit gives a new one), must still be
     * a super-admin or an org admin, and an org admin must still administer {@code target} as it is now
     * ({@link #manageableAccounts}): a limited admin's session does not gain what the account was given later.</p>
     *
     * <p>Reads the impersonator unscoped: a super-admin's account is in another customer than the session's user.</p>
     */
    public boolean mayStillImpersonate(Integer impersonatorId, String token, User target) {
        if (impersonatorId == null || token == null || token.isEmpty() || target == null) {
            return false;
        }
        User by = mapper.findById(impersonatorId);
        if (by == null || by.isPasswordReset() || by.getAuthToken() == null
                || !com.hmdm.util.CryptoUtil.constantTimeEquals(token, by.getAuthToken())) {
            return false;
        }
        if (by.getUserRole() != null && by.getUserRole().isSuperAdmin()) {
            return true;
        }
        return by.getUserRole() != null && isOrgAdmin(by) && administers(by, target);
    }

    /**
     * The account as it is now, its row locked until the transaction ends ({@link UserMapper#lockById}), so a change
     * another admin made after the resource's check (getManagedUser) is what the write checks against.
     */
    private User lockAndRead(int id) {
        this.mapper.lockById(id);
        return this.mapper.findById(id);
    }

    /**
     * Throws a {@link SecurityException} unless {@code stored} is the current user's own account, or one they may
     * administer ({@link #manageableAccounts}). Every write of another account checks this again, inside its
     * transaction: the resource checked before, and the account may have been widened since.
     */
    private void requireStillManageable(User stored) {
        if (stored == null) {
            return;
        }
        boolean own = SecurityContext.get().getCurrentUser().filter(u -> u.getId().equals(stored.getId())).isPresent();
        if (!own && !manageableAccounts().test(stored)) {
            throw SecurityException.onUserAccessViolation(stored);
        }
    }

    /**
     * Whether {@code caller} reaches every device and configuration {@code target} reaches. Administering an account
     * (editing, a new password, its role, deleting it, signing in as it) needs this.
     */
    private static boolean covers(User caller, User target) {
        return covers(caller.isAllDevicesAvailable(), caller.getGroups(),
                      target.isAllDevicesAvailable(), target.getGroups())
                && covers(caller.isAllConfigAvailable(), caller.getConfigurations(),
                          target.isAllConfigAvailable(), target.getConfigurations());
    }

    private static boolean covers(boolean callerHasAll, List<LookupItem> callerItems,
                                  boolean targetHasAll, List<LookupItem> targetItems) {
        if (callerHasAll) {
            return true;
        }
        return !targetHasAll && ids(callerItems).containsAll(ids(targetItems));
    }

    /**
     * <p>Checks if the current user may grant the specified role: one of the roles returned by
     * {@link #findAllUserRoles()}, so the super-admin roles are granted by a super-admin only. The role is looked up by
     * ID, whatever else the request says about it.</p>
     */
    public boolean canGrantRole(UserRole role) {
        return role != null && role.getId() != null &&
                findAllUserRoles().stream().anyMatch(r -> role.getId().equals(r.getId()));
    }


    /**
     * Sets the user's password ({@code password}, already hashed). It also gives the user a new authToken, so every
     * session and JWT issued before the change ends; the caller reads the new one from {@code user}.
     */
    @Transactional
    public void updatePassword(User user ) {
        requireCurrentPassword(user);
        if (user.getId() != null) {
            requireStillManageable(lockAndRead(user.getId()));
        }
        user.setAuthToken(PasswordUtil.generateToken());
        updateRecord(user, this.mapper::updatePassword, SecurityException::onUserAccessViolation);
    }

    /**
     * Sets a user's password ({@code newPassword}, already hashed) on a super-admin's request. Only the id and the
     * password come from the request: the user gets a new authToken, and the reset flag and token are set here.
     */
    @Transactional
    public void updatePasswordBySuperAdmin(User user ) {
        if (SecurityContext.get().isSuperAdmin()) {
            requireCurrentPassword(user);
            user.setAuthToken(PasswordUtil.generateToken());
            if (user.isPasswordReset()) {
                PasswordResetTokens.issue(user, System.currentTimeMillis());
            } else {
                PasswordResetTokens.clear(user);
            }
            this.mapper.setNewPassword(user);
        } else {
            throw new IllegalArgumentException("Super-admin is allowed only");
        }
    }

    /**
     * Creates the account, with access no wider than the current user's own ({@link #checkGrantableAccess}). Creating an
     * account takes the current user's password ({@link #requireConfirmedPassword}).
     */
    @Transactional
    public void insert(User user) {
        requireConfirmedPassword("create user " + user.getLogin());
        checkGrantableAccess(user, null);
        this.mapper.insert(user);
        if (!user.isAllDevicesAvailable()) {
            List<LookupItem> groups = user.getGroups();
            if (groups != null && !groups.isEmpty()) {
                this.mapper.insertUserDeviceGroupsAccess(user.getId(), groups.stream().map(LookupItem::getId).collect(Collectors.toList()));
            }
        }
        if (!user.isAllConfigAvailable() && user.getConfigurations() != null && !user.getConfigurations().isEmpty()) {
            this.mapper.insertUserConfigurationAccess(user.getId(),
                    user.getConfigurations().stream().map(LookupItem::getId).collect(Collectors.toList()));
        }
    }

    public List<User> findAllUsers() {
        return getList(this.mapper::findAll);
    }

    public List<User> findAllUsers(String value) {
        return getList(customerId -> this.mapper.findAllByFilter(customerId, value));
    }


    /**
     * Whether writing {@code requested} over {@code stored} changes an account's email. Blank means no address, and
     * clearing the address is a change too.
     */
    public static boolean isEmailChange(String stored, String requested) {
        return !trimmed(stored).equals(trimmed(requested));
    }

    private static String trimmed(String email) {
        return email == null ? "" : email.trim();
    }

    /**
     * Throws a {@link SecurityException} unless this request gave the current user's password
     * ({@link SecurityContext#isCurrentPasswordConfirmed}, set by the resource's check). Every signed-in create or delete
     * of an account passes here, whichever route it came by: {@link #insert}, {@link #deleteUser} and the customer
     * create and delete in CustomerDAO.
     */
    public void requireConfirmedPassword(String what) {
        if (!SecurityContext.get().isCurrentPasswordConfirmed()) {
            throw SecurityException.onAdminDataAccessViolation(what + " without the current password");
        }
    }

    /**
     * Throws a {@link SecurityException} unless {@code account} is the current user's own (/details and /current ask
     * for the password themselves) or this request gave the current user's password. Every signed-in write of another
     * account's email or password passes here, whichever route it came by.
     */
    private void requireCurrentPassword(User account) {
        SecurityContext context = SecurityContext.get();
        boolean own = context.getCurrentUser().filter(u -> u.getId().equals(account.getId())).isPresent();
        if (!own && !context.isCurrentPasswordConfirmed()) {
            throw SecurityException.onUserAccessViolation(account);
        }
    }

    /**
     * Changes an account's name, its email when {@code email} is not null, and its login when {@code login} is not null (/details, a customer's admin
     * account), and nothing else. The row is locked and read again first: the role and access in it are kept as they
     * are now, whatever the caller read before, so a change made meanwhile (a demotion) is never undone. Changing
     * another account's email takes the current user's password ({@link #requireCurrentPassword}).
     *
     * @return the account as stored.
     * @throws SecurityException if the account is gone or beyond the current user now.
     */
    @Transactional
    public User updateUserIdentity(int id, String login, String name, String email) {
        User stored = lockAndRead(id);
        if (stored == null) {
            throw SecurityException.onAdminDataAccessViolation("update user " + id);
        }
        requireStillManageable(stored);
        if (email == null) {
            email = stored.getEmail();
        } else if (isEmailChange(stored.getEmail(), email)) {
            requireCurrentPassword(stored);
        }
        if (login != null) {
            stored.setLogin(login);
        }
        stored.setName(name);
        stored.setEmail(email);
        updateRecord(stored, this.mapper::updateIdentity, SecurityException::onUserAccessViolation);
        return stored;
    }

    /**
     * Stores the user's name, login, email, role and reset flag, and its device and configuration access
     * ({@link #checkGrantableAccess}: never beyond the current user's own). Changing another account's email takes the
     * current user's password ({@link #requireCurrentPassword}).
     */
    @Transactional
    public void updateUserMainDetails(User user) {
        User stored = user.getId() == null ? null : lockAndRead(user.getId());
        requireStillManageable(stored);
        if (stored != null && isEmailChange(stored.getEmail(), user.getEmail())) {
            requireCurrentPassword(user);
        }
        checkGrantableAccess(user, stored);
        updateRecord(user, u -> {
            this.mapper.updateUserMainDetails(u);
            this.mapper.removeDeviceGroupsAccessByUserId(u.getCustomerId(), u.getId());
            if (!u.isAllDevicesAvailable()) {
                List<LookupItem> groups = u.getGroups();
                if (groups != null && !groups.isEmpty()) {
                    this.mapper.insertUserDeviceGroupsAccess(u.getId(), groups.stream().map(LookupItem::getId).collect(Collectors.toList()));
                }
            }
            this.mapper.removeConfigurationAccessByUserId(u.getCustomerId(), u.getId());
            if (!u.isAllConfigAvailable()) {
                List<LookupItem> configs = u.getConfigurations();
                if (configs != null && !configs.isEmpty()) {
                    this.mapper.insertUserConfigurationAccess(u.getId(), configs.stream().map(LookupItem::getId).collect(Collectors.toList()));
                }
            }
        }, SecurityException::onUserAccessViolation);
    }

    /**
     * <p>Throws a {@link SecurityException} unless the current user may give {@code user} the device and configuration
     * access it asks for. Every write of a user's access (create, edit) comes through here. A super-admin may grant any
     * access. Anyone else grants only access they have themselves, read from the database rather than the session:</p>
     * <ul>
     *     <li>all devices (all configurations) only when they have all devices (all configurations);</li>
     *     <li>a device group (configuration) only when it is one of theirs, or any of their customer's when they have
     *     all of them.</li>
     * </ul>
     * <p>Access the account already has ({@code stored}, null for a new account) may be kept, or narrowed, whatever
     * the current user's own: an administrator limited to some groups can still edit an account that reaches more,
     * only not widen it.</p>
     */
    void checkGrantableAccess(User user, User stored) {
        if (SecurityContext.get().isSuperAdmin()) {
            return;
        }
        User current = SecurityContext.get().getCurrentUser().orElseThrow(SecurityException::onAnonymousAccess);
        User caller = this.mapper.findById(current.getId());
        if (caller == null || (stored != null && stored.getCustomerId() != caller.getCustomerId())) {
            throw SecurityException.onAdminDataAccessViolation("set the access of user " + user.getLogin());
        }
        int customerId = caller.getCustomerId();
        boolean devicesOk = grantable(user.isAllDevicesAvailable(), user.getGroups(),
                caller.isAllDevicesAvailable(), caller.getGroups(),
                stored != null && stored.isAllDevicesAvailable(), stored == null ? null : stored.getGroups(),
                ids -> this.mapper.findCustomerGroupIds(customerId, ids));
        boolean configsOk = grantable(user.isAllConfigAvailable(), user.getConfigurations(),
                caller.isAllConfigAvailable(), caller.getConfigurations(),
                stored != null && stored.isAllConfigAvailable(), stored == null ? null : stored.getConfigurations(),
                ids -> this.mapper.findCustomerConfigurationIds(customerId, ids));
        if (!devicesOk || !configsOk) {
            // Access beyond the caller's own
            throw SecurityException.onAdminDataAccessViolation("grant device or configuration access to user "
                    + user.getLogin());
        }
    }

    private static boolean grantable(boolean wantsAll, List<LookupItem> wanted,
                                     boolean callerHasAll, List<LookupItem> callerItems,
                                     boolean storedHasAll, List<LookupItem> storedItems,
                                     Function<List<Integer>, List<Integer>> ofTheCustomer) {
        if (wantsAll) {
            return callerHasAll || storedHasAll;
        }
        Set<Integer> rest = ids(wanted);
        rest.removeAll(ids(callerItems));
        rest.removeAll(ids(storedItems));
        if (!rest.isEmpty() && (callerHasAll || storedHasAll)) {
            rest.removeAll(ofTheCustomer.apply(new ArrayList<>(rest)));
        }
        return rest.isEmpty();
    }

    private static Set<Integer> ids(List<LookupItem> items) {
        Set<Integer> ids = new HashSet<>();
        if (items != null) {
            for (LookupItem item : items) {
                ids.add(item.getId());
            }
        }
        return ids;
    }

    /**
     * Gives the current user access to a device group they just created, when they are limited to some groups, so
     * they can see and edit it. The one row is added; nothing else about the user is written.
     */
    public void grantOwnAccessToNewGroup(int groupId) {
        SecurityContext.get().getCurrentUser().filter(u -> !u.isAllDevicesAvailable()).ifPresent(u -> {
            this.mapper.insertUserDeviceGroupsAccess(u.getId(), Collections.singletonList(groupId));
            if (u.getGroups() != null) {
                u.getGroups().add(new LookupItem(groupId, null));
            }
        });
    }

    /** As {@link #grantOwnAccessToNewGroup}, for a configuration the current user just created. */
    public void grantOwnAccessToNewConfiguration(int configurationId) {
        SecurityContext.get().getCurrentUser().filter(u -> !u.isAllConfigAvailable()).ifPresent(u -> {
            this.mapper.insertUserConfigurationAccess(u.getId(), Collections.singletonList(configurationId));
            if (u.getConfigurations() != null) {
                u.getConfigurations().add(new LookupItem(configurationId, null));
            }
        });
    }

    /** Deletes another account. It takes the current user's password ({@link #requireConfirmedPassword}). */
    @Transactional
    public void deleteUser(int id) {
        SecurityContext.get().getCurrentUser().ifPresent(current -> {
            if (current.getId() == id) {
                throw new IllegalArgumentException("Can't remove self");
            } else {
                requireConfirmedPassword("delete user " + id);
                requireStillManageable(lockAndRead(id));
                updateById(id, mapper::findById, this.mapper::deleteUser, SecurityException::onUserAccessViolation);
            }
        });
    }

    // Plain fields only, no permissions, groups and configurations
    public User findOrgAdminLite(Integer id) {
        return mapper.findFirstByRole(id, this.orgAdminRoleId);
    }

    public User findOrgAdmin(Integer id) {
        return mapper.findAll(id).stream().filter(u -> u.getUserRole().getId() == this.orgAdminRoleId).findFirst().orElse(null);
    }

    public boolean isOrgAdmin(User user) {
        return user.getUserRole().getId() == this.orgAdminRoleId;
    }

    public List<UserRole> findAllUserRoles() {
        return SecurityContext.get().getCurrentUser()
                .map(u -> mapper.findAllUserRoles(u.getUserRole().isSuperAdmin()))
                .orElse(new ArrayList<>());
    }

    public List<User> findAllCustomerUsers(int customerId) {
        if (SecurityContext.get().isSuperAdmin()) {
            return this.mapper.findAll(customerId);
        } else {
            throw new IllegalArgumentException("Super-admin is allowed only");
        }
    }

    /**
     * <p>Gets the list of identifiers for hints already show to current user.</p>
     *
     * @return a list of shown hint identifiers.
     */
    public List<String> getShownHints() {
        return SecurityContext.get().getCurrentUser()
                .map(user -> this.mapper.getShownHints(user.getId()))
                .orElseThrow(SecurityException::onAnonymousAccess);
    }

    /**
     * <p>Marks specified hint as shown to current user.</p>
     *
     * @param hintKey an identifier of the hint.
     */
    public void onHintShown(String hintKey) {
        SecurityContext.get().getCurrentUser()
                .map(user -> this.mapper.insertShownHint(user.getId(), hintKey))
                .orElseThrow(SecurityException::onAnonymousAccess);
    }

    /**
     * <p>Clears the list of identifiers for hints already show to current user.</p>
     */
    public void enableHints() {
        SecurityContext.get().getCurrentUser()
                .map(user -> this.mapper.clearHintsHistory(user.getId()))
                .orElseThrow(SecurityException::onAnonymousAccess);
    }

    /**
     * <p>Clears the list of identifiers for hints already show to current user.</p>
     */
    @Transactional
    public void disableHints() {
        SecurityContext.get().getCurrentUser()
                .map(user -> {
                    this.mapper.clearHintsHistory(user.getId());
                    this.mapper.insertHintsHistoryAll(user.getId());
                    return Optional.empty();
                })
                .orElseThrow(SecurityException::onAnonymousAccess);
    }
}
