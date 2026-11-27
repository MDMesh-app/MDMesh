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

package com.hmdm.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;

import java.util.List;

public interface UserMapper {

    User findByLogin(@Param("login") String login);

    User findByEmail(@Param("email") String email);

    User findByPasswordResetToken(@Param("token") String token);

    User findById(@Param("userId") Integer userId);

    /**
     * Locks the account's row until the transaction ends: a change to it committed meanwhile is seen by the next read,
     * and one started after waits. An admin's edit of the account (UserDAO.updateUserMainDetails) updates the row
     * before its access, so it holds the same lock.
     */
    @Select("SELECT id FROM users WHERE id = #{id} FOR UPDATE")
    Integer lockById(@Param("id") Integer id);

    List<User> findAll(@Param("customerId") Integer customerId);

    User findFirstByRole(@Param("customerId") Integer customerId, @Param("roleId") Integer roleId);

    List<User> findAllByFilter(@Param("customerId") Integer customerId, @Param("value") String value);

    List<User> findAllWithOldPassword();

    @Insert({"INSERT INTO users (login, email, name, password, customerId, userRoleId, " +
            "allDevicesAvailable, allConfigAvailable, passwordReset, authToken, " +
            "passwordResetToken, passwordResetTokenTime, authData, twoFactorSecret, twoFactorAccepted) " +
            "VALUES (#{login}, #{email}, #{name}, #{password}, #{customerId}, #{userRole.id}, " +
            "#{allDevicesAvailable}, #{allConfigAvailable}, #{passwordReset}, #{authToken}, " +
            "#{passwordResetToken}, #{passwordResetTokenTime}, #{authData}, #{twoFactorSecret}, #{twoFactorAccepted})"})
    @SelectKey( statement = "SELECT currval('users_id_seq')", keyColumn = "id", keyProperty = "id", before = false, resultType = int.class )
    void insert(User user);

    /**
     * Never writes authData, twoFactorSecret or twoFactorAccepted: the client never sees them, and only the password
     * flows ({@link #updatePassword}) change them.
     */
    @Update({"UPDATE users " +
            "SET name = #{name}, login=#{login}, email=#{email}, userRoleId=#{userRole.id}, " +
            "allDevicesAvailable=#{allDevicesAvailable}, allConfigAvailable=#{allConfigAvailable}, " +
            "passwordReset=#{passwordReset} " +
            "WHERE id=#{id} AND customerId=#{customerId}"})
    void updateUserMainDetails(User user);

    /**
     * Writes an account's login, name and email, and nothing else: its role and access stay as they are in the row.
     * Call through UserDAO.updateUserIdentity, which locks the row first.
     */
    @Update({"UPDATE users SET login=#{login}, name=#{name}, email=#{email} " +
            "WHERE id=#{id} AND customerId=#{customerId}"})
    void updateIdentity(User user);

    /** Sets a password. Call through UserDAO.updatePassword, which also gives the user a new authToken. */
    @Update({"UPDATE users SET password=#{password}, passwordReset=#{passwordReset}, " +
            "authToken=#{authToken}, passwordResetToken=#{passwordResetToken}, " +
            "passwordResetTokenTime=#{passwordResetTokenTime}, " +
            "twoFactorSecret=#{twoFactorSecret}, twoFactorAccepted=#{twoFactorAccepted} " +
            "WHERE id=#{id} AND customerId=#{customerId}"})
    void updatePassword(User user);

    /**
     * Sets a password. Call through UnsecureDAO.setUserNewPasswordUnsecure or UserDAO.updatePasswordBySuperAdmin,
     * which also give the user a new authToken, so the sessions and JWTs issued for the old password end.
     */
    @Update({"UPDATE users SET password=#{newPassword}, passwordReset=#{passwordReset}, " +
            "authToken=#{authToken}, passwordResetToken=#{passwordResetToken}, " +
            "passwordResetTokenTime=#{passwordResetTokenTime} WHERE id=#{id}"})
    void setNewPassword(User user);

    /**
     * Replaces a stored password hash with the same password in the current format, at sign-in
     * (UnsecureDAO.checkPasswordUnsecure). Only the hash changes: sessions, JWTs and the reset state stay. Changes the
     * row only while it still holds {@code oldHash}, so a password changed in the meantime is never overwritten.
     *
     * @return the number of rows changed (0 or 1).
     */
    @Update({"UPDATE users SET password=#{newHash} WHERE id=#{id} AND password=#{oldHash}"})
    int upgradePasswordHash(@Param("id") Integer id, @Param("oldHash") String oldHash, @Param("newHash") String newHash);

    /** Stores the session/JWT binding token alone (a first sign-in, impersonation); the password is untouched. */
    @Update({"UPDATE users SET authToken=#{authToken} WHERE id=#{id}"})
    void setAuthToken(User user);

    /** Stores a password reset token and when it was issued; the password and the sessions are untouched. */
    @Update({"UPDATE users SET passwordResetToken=#{passwordResetToken}, " +
            "passwordResetTokenTime=#{passwordResetTokenTime} WHERE id=#{id}"})
    void setPasswordResetToken(User user);

    @Update({"UPDATE users SET lastLoginFail=#{lastLoginFail} WHERE id=#{id}"})
    void setLoginFailTime(User user);

    @Update({"UPDATE users SET lastLoginFail=0"})
    void resetLoginFailTime();

    @Delete({"DELETE FROM users WHERE id=#{id} AND userRoleId <> 1"})
    void deleteUser(User user);

    List<UserRole> findAllUserRoles(@Param("includeSuperAdmin") boolean inludeSuperAdmin);

    // Permissions not selected here
    @Select("SELECT * FROM userRoles WHERE name = #{name}")
    UserRole findUserRoleByName(@Param("name") String name);

    @Delete({"DELETE FROM userDeviceGroupsAccess " +
            "WHERE userId=#{id} " +
            "AND groupId IN (SELECT groups.id FROM groups WHERE groups.customerId=#{customerId})"})
    void removeDeviceGroupsAccessByUserId(@Param("customerId") int customerId, @Param("id") Integer userId);

    void insertUserDeviceGroupsAccess(@Param("id") Integer userId, @Param("groups") List<Integer> groups);

    @Delete({"DELETE FROM userConfigurationAccess " +
            "WHERE userId=#{id} " +
            "AND configurationId IN (SELECT configurations.id FROM configurations WHERE configurations.customerId=#{customerId})"})
    void removeConfigurationAccessByUserId(@Param("customerId") int customerId, @Param("id") Integer userId);

    void insertUserConfigurationAccess(@Param("id") Integer userId, @Param("configurations") List<Integer> configurations);

    /** Those of {@code ids} that are device groups of the customer. */
    @Select({"<script>SELECT id FROM groups WHERE customerId = #{customerId} AND id IN " +
            "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach></script>"})
    List<Integer> findCustomerGroupIds(@Param("customerId") int customerId, @Param("ids") List<Integer> ids);

    /** Those of {@code ids} that are configurations of the customer. */
    @Select({"<script>SELECT id FROM configurations WHERE customerId = #{customerId} AND id IN " +
            "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach></script>"})
    List<Integer> findCustomerConfigurationIds(@Param("customerId") int customerId, @Param("ids") List<Integer> ids);

    @Select("SELECT hintKey FROM userHints WHERE userId = #{id}")
    List<String> getShownHints(@Param("id") Integer userId);

    @Insert("INSERT INTO userHints (userId, hintKey) VALUES (#{userId}, #{hintKey})")
    int insertShownHint(@Param("userId") Integer userId, @Param("hintKey") String hintKey);

    @Delete("DELETE FROM userHints WHERE userId = #{id}")
    int clearHintsHistory(@Param("id") Integer userId);

    @Insert("INSERT INTO userHints (userId, hintKey) SELECT #{id}, hintKey FROM userHintTypes")
    int insertHintsHistoryAll(@Param("id") Integer userId);
}
