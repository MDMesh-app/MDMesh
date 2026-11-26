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
import com.hmdm.event.CustomerCreatedEvent;
import com.hmdm.event.DeviceInfoUpdatedEvent;
import com.hmdm.event.EventService;
import com.hmdm.persistence.domain.*;
import com.hmdm.persistence.mapper.*;
import com.hmdm.rest.json.DeviceCreateOptions;
import com.hmdm.rest.json.DeviceListHook;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.rest.json.PaginatedData;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.util.CryptoUtil;
import com.hmdm.util.PasswordResetTokens;
import com.hmdm.util.PasswordUtil;
import org.mybatis.guice.transactional.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import java.io.File;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>A DAO which does not perform any security checks when accessing/updating data. It is intended for processing
 * requests from anonymous clients (for example, devices).</p>
 *
 * @author isv
 */
@Singleton
public class UnsecureDAO {

    private static final Logger logger = LoggerFactory.getLogger(UnsecureDAO.class);

    private final DeviceMapper deviceMapper;
    private final UserMapper userMapper;
    private final ConfigurationMapper configurationMapper;
    private final CommonMapper settingsMapper;
    private final ApplicationMapper applicationMapper;
    private final UploadedFileMapper uploadedFileMapper;
    private final ApplicationDAO applicationDAO;
    private final ApplicationSettingDAO applicationSettingDAO;
    private final UserDAO userDAO;
    private final CommonDAO settingDAO;
    private final CustomerDAO customerDAO;
    private final ConfigurationFileMapper configurationFileMapper;
    private final CustomerMapper customerMapper;
    private final String defaultLauncherPackage;
    private final File filesDirectory;
    private final int orgAdminRoleId;
    private final EventService eventService;

    private static final int DEFAULT_CUSTOMER_ID = 1;

    /**
     * <p>Constructs new <code>UnsecureDAO</code> instance. This implementation does nothing.</p>
     */
    @Inject
    public UnsecureDAO(DeviceMapper deviceMapper,
                       UserMapper userMapper,
                       ConfigurationMapper configurationMapper,
                       CommonMapper settingsMapper,
                       ApplicationMapper applicationMapper,
                       UploadedFileMapper uploadedFileMapper,
                       ApplicationDAO applicationDAO,
                       ApplicationSettingDAO applicationSettingDAO,
                       UserDAO userDAO,
                       CommonDAO settingDAO,
                       CustomerDAO customerDAO,
                       ConfigurationFileMapper configurationFileMapper,
                       CustomerMapper customerMapper,
                       EventService eventService,
                       @Named("files.directory") String filesDirectory,
                       @Named("role.orgadmin.id") int orgAdminRoleId,
                       @Named("launcher.package") String defaultLauncherPackage) {
        this.deviceMapper = deviceMapper;
        this.userMapper = userMapper;
        this.configurationMapper = configurationMapper;
        this.settingsMapper = settingsMapper;
        this.applicationMapper = applicationMapper;
        this.uploadedFileMapper = uploadedFileMapper;
        this.applicationDAO = applicationDAO;
        this.applicationSettingDAO = applicationSettingDAO;
        this.userDAO = userDAO;
        this.settingDAO = settingDAO;
        this.customerDAO = customerDAO;
        this.configurationFileMapper = configurationFileMapper;
        this.customerMapper = customerMapper;
        this.eventService = eventService;
        this.filesDirectory = new File(filesDirectory);
        this.orgAdminRoleId = orgAdminRoleId;
        this.defaultLauncherPackage = defaultLauncherPackage;
    }

    public User findByLoginOrEmail(String login) {
        User user = userMapper.findByLogin(login);
        if (user == null) {
            user = userMapper.findByEmail(login);
        }
        return user;
    }

    public User findByLogin(String login) {
        return userMapper.findByLogin(login);
    }

    public User findByEmail(String email) {
        return userMapper.findByEmail(email);
    }

    /**
     * The user holding this password reset token, or null when no user does or the token has expired
     * ({@link PasswordResetTokens}). Every use of a reset token goes through here.
     */
    public User findByPasswordResetToken( String token ) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        User user = userMapper.findByPasswordResetToken(token);
        if (user != null && !PasswordResetTokens.isFresh(user, System.currentTimeMillis())) {
            logger.info("Refused an expired password reset token of user {}", user.getLogin());
            return null;
        }
        return user;
    }

    /**
     * Whether {@code password} (what a client sent: the MD5 of the password, in hex) is {@code user}'s password.
     * Every check of a user's password goes through here: the console and API sign-ins, and the current password a
     * signed-in user confirms. A stored hash in an older format (legacy SHA-1, or bcrypt below the current cost) that
     * just matched is replaced with a current bcrypt one. Only the hash changes, not the sessions, and only while
     * the row still holds the hash that was checked, so a password changed meanwhile is never overwritten. A failure
     * to store it is logged and the check still succeeds: the old hash keeps working.
     *
     * @param user the account, with its stored hash; null fails, at the cost of a check for a real account.
     */
    public boolean checkPasswordUnsecure(User user, String password) {
        String stored = user == null ? null : user.getPassword();
        if (!PasswordUtil.passwordMatch(password, stored)) {
            return false;
        }
        if (PasswordUtil.needsRehash(stored) && PasswordUtil.isMd5Hex(password)) {
            try {
                String upgraded = PasswordUtil.getHashFromMd5(password);
                if (userMapper.upgradePasswordHash(user.getId(), stored, upgraded) == 1) {
                    user.setPassword(upgraded);
                    logger.info("Stored the password of user {} as bcrypt", user.getLogin());
                }
            } catch (RuntimeException e) {
                logger.warn("Could not store the password of user {} as bcrypt", user.getLogin(), e);
            }
        }
        return true;
    }

    public List<User> findAllWithOldPassword() {
        return userMapper.findAllWithOldPassword();
    }

    public void updateUserUnsecure(User user) {
        if (user.getId() == null) {
            userMapper.insert(user);
        } else {
            userMapper.updateUserMainDetails(user);
        }
    }

    /**
     * Replaces a stored password hash with [newHash] only while the row still holds [oldHash], so a password set in
     * the meantime is never overwritten. Sessions are untouched: the password itself doesn't change.
     *
     * @return whether the row was updated
     */
    public boolean upgradePasswordHashUnsecure(Integer id, String oldHash, String newHash) {
        return userMapper.upgradePasswordHash(id, oldHash, newHash) == 1;
    }

    /**
     * Sets the user's password ({@code newPassword}, already hashed). It also gives the user a new authToken, so every
     * session and JWT issued before the change ends.
     */
    public void setUserNewPasswordUnsecure(User user ) {
        user.setAuthToken(PasswordUtil.generateToken());
        userMapper.setNewPassword(user);
    }

    /** Stores the user's authToken alone, for a user who has none yet (first sign-in). The password is untouched. */
    public void setAuthTokenUnsecure(User user) {
        userMapper.setAuthToken(user);
    }

    /** Stores the user's password reset token and its issue time. The password and the sessions are untouched. */
    public void setPasswordResetTokenUnsecure(User user) {
        userMapper.setPasswordResetToken(user);
    }

    public void setUserLoginFailTime(User user, long ts) {
        user.setLastLoginFail(ts);
        userMapper.setLoginFailTime(user);
    }

    public void resetUserLoginFailTime() {
        userMapper.resetLoginFailTime();
    }

    /** Any device by its number. The Headwind launcher's routes use {@link #findLegacyDevice} instead. */
    public Device getDeviceByNumber(String number) {
        return this.deviceMapper.getDeviceByNumber(number);
    }

    // =================================================================================================================
    // The Headwind launcher's device API (on only with legacy.device.api; LegacyDeviceApiFilter lists its routes).
    //
    // One rule for all of it: the launcher proves nothing but a device number, so the API never reaches what the
    // MDMesh agent's authenticated API owns. It never reaches an agent-enrolled device (one with a check-in secret),
    // however it is named, and never serves, or creates a device into, an agent-managed configuration: one such a
    // device uses, or one a live agent enrollment token would enroll into (DeviceMapper.isAgentConfiguration). Every launcher route finds its device with findLegacyDevice (or findLegacyDeviceById), creates one
    // only with createLegacyDevice and reads a configuration by QR key only with getLegacyConfigurationByQRCodeKey; a
    // refused request is logged here and answered with LegacyDevice#refusal, and the route writes nothing.
    // =================================================================================================================

    /**
     * The launcher device {@code identifier} names, resolved as far as {@code widest} (number, then old number, then
     * IMEI or serial). The first device found is the one: a refused device never lets the lookup go on to another
     * device, nor a route create one with its number.
     */
    public LegacyDevice findLegacyDevice(String identifier, LegacyDevice.Lookup widest) {
        LegacyDevice.Lookup match = LegacyDevice.Lookup.NUMBER;
        Device device = this.deviceMapper.getDeviceByNumber(identifier);
        if (device == null && widest.compareTo(LegacyDevice.Lookup.OLD_NUMBER) >= 0) {
            match = LegacyDevice.Lookup.OLD_NUMBER;
            device = this.deviceMapper.getDeviceByOldNumber(identifier);
        }
        if (device == null && widest.compareTo(LegacyDevice.Lookup.IMEI_OR_SERIAL) >= 0) {
            match = LegacyDevice.Lookup.IMEI_OR_SERIAL;
            device = this.deviceMapper.getDeviceByImeiOrSerial(identifier);
        }
        return device == null ? LegacyDevice.absent() : legacyDevice(device, match);
    }

    /** The launcher device with this id (a message's device, say), under the same rule as {@link #findLegacyDevice}. */
    public LegacyDevice findLegacyDeviceById(Integer id) {
        Device device = id == null ? null : this.deviceMapper.getDeviceById(id);
        return device == null ? LegacyDevice.absent() : legacyDevice(device, null);
    }

    /**
     * The configuration with this QR code key, for the launcher's provisioning QR code; null when there is none or it
     * is agent-managed.
     */
    public Configuration getLegacyConfigurationByQRCodeKey(String key) {
        Configuration configuration = this.configurationMapper.getConfigurationByQRCodeKey(key);
        if (configuration != null && isAgentConfiguration(configuration.getId())) {
            logAgentConfigurationRefused("QR code of", configuration.getId());
            return null;
        }
        return configuration;
    }

    private LegacyDevice legacyDevice(Device device, LegacyDevice.Lookup match) {
        if (this.deviceMapper.isAgentDevice(device.getId())) {
            logger.warn("Headwind launcher request for device {} refused: the MDMesh agent enrolled it",
                    device.getNumber());
            return LegacyDevice.agentDevice();
        }
        if (isAgentConfiguration(device.getConfigurationId())) {
            logAgentConfigurationRefused("device " + device.getNumber() + " in", device.getConfigurationId());
            return LegacyDevice.agentConfiguration();
        }
        return LegacyDevice.served(device, match);
    }

    private boolean isAgentConfiguration(Integer configurationId) {
        return configurationId != null
                && this.deviceMapper.isAgentConfiguration(configurationId, System.currentTimeMillis());
    }

    private static void logAgentConfigurationRefused(String what, Integer configurationId) {
        logger.warn("Headwind launcher request for {} configuration {} refused: MDMesh agent devices use that "
                + "configuration, or an agent enrollment code enrolls into it. Give launcher devices a configuration "
                + "no agent device or enrollment code uses.", what, configurationId);
    }

    public List<ApplicationSetting> getDeviceAppSettings(int deviceId) {
        final List<ApplicationSetting> appSettings
                = this.applicationSettingDAO.getApplicationSettingsByDeviceId(deviceId);
        return appSettings;
    }

    public void updateDeviceInfo(Integer id, String info, Long imeiUpdateTs, String publicIp) {
        this.deviceMapper.updateDeviceInfo(id, info, imeiUpdateTs, publicIp);
    }

    public void updateDeviceCustomProperties(Integer id, Device device) {
        this.deviceMapper.updateDeviceCustomProperties(id, device.getCustom1(), device.getCustom2(), device.getCustom3());
    }

    public void completeDeviceMigration(Integer id) {
        this.deviceMapper.clearOldNumber(id);
    }

    // This method should be called in a single-tenant mode only
    // and the device customer ID should be set
    @Transactional
    public void insertDevice(Device device) {
        this.deviceMapper.insertDevice(device);
        if (device.getGroups() != null && !device.getGroups().isEmpty()) {
            this.deviceMapper.insertDeviceGroups(
                    device.getId(), device.getGroups().stream().map(LookupItem::getId).collect(Collectors.toList())
            );
        }
    }

    @Transactional
    public List<Application> getPlainConfigurationApplications(Integer customerId, Integer id) {
        String tblName = "ca" + CryptoUtil.randomHexString(8);
        configurationMapper.createTempConfigAppTable(tblName, id);
        return this.configurationMapper.getPlainConfigurationApplications(customerId, tblName, id);
    }

    /** Cheap per-check-in variant: a single plain SELECT, no temp table (unlike {@link #getPlainConfigurationApplications}). */
    public List<Application> getPlainConfigurationAppsOptimized(Integer configurationId) {
        return this.configurationMapper.getPlainConfigurationAppsOptimized(configurationId);
    }

    /** The version ids the configuration marks Install: one plain select, cheap enough for every check-in. */
    public List<Integer> getConfigurationInstallVersionIds(Integer configurationId) {
        return this.configurationMapper.getInstallVersionIds(configurationId);
    }

    public Configuration getConfigurationById(Integer id) {
        return this.configurationMapper.getConfigurationById(id);
    }

    /**
     * The customer's configurations whose managed kiosk background image has these bytes: id, customer and the image
     * columns only (KioskCommandImages).
     */
    public List<Configuration> getManagedBackgroundImageConfigurations(int customerId, String sha256) {
        return this.configurationMapper.getManagedBackgroundImageConfigurations(customerId, sha256);
    }

    public List<ManagedAppPermission> getManagedAppPermissions(Integer configurationId) {
        return this.configurationMapper.getManagedAppPermissions(configurationId);
    }

    @Transactional
    public Configuration getConfigurationByIdWithAppSettings(Integer id) {
        final Configuration dbConfiguration = this.configurationMapper.getConfigurationById(id);
        if (dbConfiguration != null) {
            final List<ApplicationSetting> appSettings = this.applicationSettingDAO.getApplicationSettingsByConfigurationId(dbConfiguration.getId());
            dbConfiguration.setApplicationSettings(appSettings);
        }

        return dbConfiguration;
    }

    public Settings getSettings(int customerId) {
        return this.settingsMapper.getSettings(customerId);
    }

    public Settings getSingleCustomerSettings() {
        return this.settingsMapper.getSettings(DEFAULT_CUSTOMER_ID);
    }

    public List<Application> findByPackageIdAndVersion(Integer customerId, String pkg, String version) {
        return this.applicationMapper.findByPackageIdAndVersion(customerId, pkg, version);
    }

    /**
     * <p>Builds the lookup map from application package ID to application ID for specified packages and customer
     * account.</p>
     *
     * @param customerId an ID of a customer record.
     * @param appPackages a collection of application package IDs to build mapping for.
     * @return a mapping from application package ID to application ID.
     */
    public Map<String, Integer> buildPackageIdMapping(Integer customerId, Collection<String> appPackages) {
        if (appPackages == null || appPackages.isEmpty()) {
            return new HashMap<>();
        }

        List<LookupItem> ddd = this.applicationMapper.resolveAppsByPackageId(customerId, appPackages);
        return ddd.stream().collect(Collectors.toMap(LookupItem::getName, LookupItem::getId, (r1, r2) -> r1));
    }

    public void insertApplication(Application application) {
        final List<User> users = this.userMapper.findAll(application.getCustomerId());
        if (!users.isEmpty()) {
            final User user = users.stream().filter(u -> !u.getUserRole().isSuperAdmin()).findAny().orElse(users.get(0));
            logger.info("Using user account '{}' for setting up the security context when uploading application {} " +
                    "from mobile device", user.getLogin(), application);

            SecurityContext.init(user);
            try {
                this.applicationDAO.insertApplication(application);
            } finally {
                SecurityContext.release();
            }
        } else {
            throw new DAOException("No user accounts have been found mapped to requested customer account: #"
                    + application.getCustomerId());
        }
    }

    public Application findApplicationById(Integer appId) {
        Application app = this.applicationMapper.findById(appId);
        return app;
    }

    public ApplicationVersion findApplicationVersionById(Integer appId) {
        ApplicationVersion app = this.applicationMapper.findVersionById(appId);
        return app;
    }

    private static final Function<ApplicationSetting, String> appSettingMapKeyGenerator = (s) -> s.getApplicationPkg() + "," + s.getName();


    @Transactional
    public void saveDeviceApplicationSettings(Device dbDevice,
                                              List<ApplicationSetting> applicationSettings) {

        final Map<String, ApplicationSetting> dbDeviceAppSettingsMapping
                = this.applicationSettingDAO.getApplicationSettingsByDeviceId(dbDevice.getId())
                .stream()
                .collect(Collectors.toMap(appSettingMapKeyGenerator, s -> s, (r1, r2) -> r1));

        final Map<String, ApplicationSetting> appSettingsMapping
                = applicationSettings
                .stream()
                .filter(s -> s.getValue() != null && !s.getValue().trim().isEmpty())
                .collect(Collectors.toMap(appSettingMapKeyGenerator, s -> s, (r1, r2) -> r1));

        List<ApplicationSetting> mergedApplicationSettings = new ArrayList<>();

        dbDeviceAppSettingsMapping.values().forEach(dbSetting -> {
            final String dbSettingKey = appSettingMapKeyGenerator.apply(dbSetting);
            if (appSettingsMapping.containsKey(dbSettingKey)) {
                final ApplicationSetting appSetting = appSettingsMapping.get(dbSettingKey);
                if (appSetting.getLastUpdate() < dbSetting.getLastUpdate()) {
                    mergedApplicationSettings.add(dbSetting);
                } else {
                    mergedApplicationSettings.add(appSetting);
                }
            } else {
                mergedApplicationSettings.add(dbSetting);
            }
        });

        final Map<String, Application> appsMapping = this.applicationMapper.getAllApplications(dbDevice.getCustomerId())
                .stream()
                .collect(Collectors.toMap(Application::getPkg, a -> a, (r1, r2) -> r1));

        appSettingsMapping.values().forEach(appSetting -> {
            final String appSettingKey = appSettingMapKeyGenerator.apply(appSetting);
            if (!dbDeviceAppSettingsMapping.containsKey(appSettingKey)) {
                mergedApplicationSettings.add(appSetting);
            }
        });

        mergedApplicationSettings.forEach(appSetting -> {
            if (appSetting.getApplicationId() == null) {
                if (appsMapping.containsKey(appSetting.getApplicationPkg())) {
                    appSetting.setApplicationId(appsMapping.get(appSetting.getApplicationPkg()).getId());
                } else {
                    // TODO : Log a warning on unknown package ID
                }
            }
        });

        this.deviceMapper.deleteDeviceApplicationSettings(dbDevice.getId());
        if (!mergedApplicationSettings.isEmpty()) {
            final List<ApplicationSetting> validSettings = mergedApplicationSettings
                    .stream()
                    .filter(appSetting -> appSetting.getApplicationId() != null)
                    .collect(Collectors.toList());
            this.deviceMapper.insertDeviceApplicationSettings(dbDevice.getId(), validSettings);
        }
    }

    /**
     * <p>Saves the hash value for APK-file associated with the specified aplication version.</p>
     *
     * @param appVersionId an application version ID to save the hash value for APK file for.
     * @param hashValue a hash-value to be saved.
     */
    public void saveApkFileHash(Integer appVersionId, String hashValue) {
        this.applicationMapper.saveApkFileHash(appVersionId, hashValue);
    }

    /** The stored apkHash of a Library version (the customer's own, or a common app's) whose URL is {@code url}. */
    public String findApkHashByUrl(int customerId, String url) {
        return this.applicationMapper.findApkHashByUrl(customerId, url);
    }

    /**
     * To use in impersonated background services only
     */
    public List<Device> getAllGroupDevices(int groupId, int customerId) {
        return this.deviceMapper.getAllGroupDevices(groupId, customerId);
    }

    public List<Device> getAllConfigurationDevices(int configurationId, int customerId) {
        return this.deviceMapper.getAllConfigurationDevices(configurationId, customerId);
    }

    public List<Device> getAllCustomerDevices(int customerId) {
        return this.deviceMapper.getAllCustomerDevices(customerId);
    }

    /**
     * <p>Gets the device referenced by the specified ID.</p>
     *
     * @param id an ID of a device.
     * @return a device referenced by the specified ID or <code>null</code> if there is no such device found.
     */
    public Device getDeviceById(Integer id) {
        return this.deviceMapper.getDeviceById(id);
    }

    /**
     * <p>Gets the list of configuration files to be used on device.</p>
     *
     * @param device a device to get the configuration files for.
     * @return a list of configuration files to be used on device.
     */
    public List<ConfigurationFile> getConfigurationFiles(Device device) {
        return this.configurationFileMapper.getConfigurationFiles(device.getConfigurationId());
    }

//    /**
//     * <p>Gets the settings for the customer account mapped to specified device.</p>
//     *
//     * @param deviceId a device number identifying the device.
//     * @return the settings for related customer account.
//     */
//    public Settings getSettingsByDeviceId(String deviceId) {
//        return this.settingsMapper.getSettingsByDeviceId(deviceId);
//    }

    /**
     * <p>Tests if the current installation is single-customer</p>
     *
     * @return true if single-customer, false otherwise
     */
    public boolean isSingleCustomer() {
        return !customerMapper.isMultiTenant();
    }

    /**
     * <p>Whether the user may manage the whole deployment: apply, roll back or schedule updates (the updater asks
     * through GET /private/users/current). A super-admin may. On a single-customer install (every private-mode
     * install: its admin is the customer's org admin, and no super-admin exists) the org admin is the operator and may
     * too. Once the install has a second customer, only a super-admin may: a customer's admin must not roll back
     * every customer's data. Only the org admin role counts, not a role that merely holds "settings".</p>
     */
    public boolean mayManageDeployment(User user) {
        if (user == null || user.getUserRole() == null) {
            return false;
        }
        if (user.getUserRole().isSuperAdmin()) {
            return true;
        }
        Integer roleId = user.getUserRole().getId();
        return roleId != null && roleId == this.orgAdminRoleId && isSingleCustomer();
    }

    // This should only be called from the tasks, not from the web resource methods
    public List<Customer> getAllCustomersUnsecure() {
        return customerMapper.findAll();
    }

    public List<Customer> getFollowUpCustomersUnsecure() {
        return customerMapper.findFollowedUp();
    }

    public void updateCustomerUnsecure(Customer customer) {
        customerMapper.update(customer);
    }

    public Customer getCustomerByEmailUnsecure(String email) {
        return customerMapper.findCustomerByEmail(email);
    }

    public Customer getCustomerByNameUnsecure(String name) {
        return customerMapper.findCustomerByName(name);
    }

    public Customer getCustomerByIdUnsecure(Integer id) {
        return customerMapper.findCustomerById(id);
    }

    public Application getDefaultLauncher() {
        List<Application> apps = this.applicationMapper.findByPackageId(DEFAULT_CUSTOMER_ID, defaultLauncherPackage);
        if (apps.size() == 0) {
            return null;
        }
        return apps.get(0);
    }


    /**
     * Device creation for agent-v1 token enrollment. The device lands in the TOKEN's customer (not
     * the single-customer default), and — when the token binds a configuration — in that
     * configuration, skipping the legacy "create new devices" flag (an explicitly-bound config IS
     * the admin's intent, mirroring the createOptions overload below). Returns null only when no
     * config is bound AND the customer's settings disallow on-demand creation / name no default.
     */
    public Device createNewDeviceForToken(String deviceId, int customerId, Integer boundConfigurationId) {
        Settings settings = getSettings(customerId);
        Integer configurationId = boundConfigurationId;
        if (configurationId == null) {
            if (settings == null || !settings.isCreateNewDevices()) {
                logger.warn("Creating new devices disabled by settings (customer {})", customerId);
                return null;
            }
            configurationId = settings.getNewDeviceConfigurationId();
            if (configurationId == null) {
                logger.warn("No default configuration for new devices (customer {})", customerId);
                return null;
            }
        }
        if (!isCustomerConfiguration(configurationId, customerId)) {
            logger.warn("Configuration {} does not belong to customer {}, device not created", configurationId, customerId);
            return null;
        }

        Device newDevice = new Device();
        newDevice.setCustomerId(customerId);
        newDevice.setConfigurationId(configurationId);
        Integer groupId = settings == null ? null : settings.getNewDeviceGroupId();
        if (isCustomerGroup(groupId, customerId)) {
            List<LookupItem> groups = new LinkedList<>();
            groups.add(new LookupItem(groupId, ""));
            newDevice.setGroups(groups);
        }
        newDevice.setNumber(deviceId);
        newDevice.setLastUpdate(0L);
        insertDevice(newDevice);
        logger.info("New device {} added (customer {}, configuration {})", deviceId, customerId, configurationId);

        return getDeviceByNumber(deviceId);
    }

    /** A default group from the settings is applied only when it belongs to the device's customer. */
    private boolean isCustomerGroup(Integer groupId, int customerId) {
        if (groupId == null) {
            return false;
        }
        if (!this.deviceMapper.isCustomerGroup(groupId, customerId)) {
            logger.warn("Default group {} does not belong to customer {}, group ignored", groupId, customerId);
            return false;
        }
        return true;
    }

    private boolean isCustomerConfiguration(Integer configurationId, int customerId) {
        return configurationId != null && this.deviceMapper.isCustomerConfiguration(configurationId, customerId);
    }

    /**
     * A launcher device created on demand with {@code deviceId}, which {@link #findLegacyDevice} found
     * {@link LegacyDevice#isAbsent() absent}: in the single customer's default configuration for new devices, when its
     * settings create devices on demand and that configuration is not agent-managed. Never on a multi-tenant install.
     */
    public LegacyDevice createLegacyDevice(String deviceId) {
        if (!isSingleCustomer()) {
            logger.warn("Not allowed to create devices in the multi-tenant setup");
            return LegacyDevice.absent();
        }
        Settings settings = getSingleCustomerSettings();
        if (settings.isCreateNewDevices()) {
            Device newDevice = new Device();
            newDevice.setCustomerId(settings.getCustomerId());
            newDevice.setConfigurationId(settings.getNewDeviceConfigurationId());
            if (isAgentConfiguration(newDevice.getConfigurationId())) {
                logAgentConfigurationRefused("new device " + deviceId + " in", newDevice.getConfigurationId());
                return LegacyDevice.agentConfiguration();
            }
            Integer groupId = settings.getNewDeviceGroupId();
            if (isCustomerGroup(groupId, settings.getCustomerId())) {
                List<LookupItem> groups = new LinkedList<>();
                groups.add(new LookupItem(groupId, ""));
                newDevice.setGroups(groups);
            }
            newDevice.setNumber(deviceId);
            newDevice.setLastUpdate(0L);
            insertDevice(newDevice);
            logger.info("New device {} added", deviceId);

            return createdLegacyDevice(deviceId);
        } else {
            logger.warn("Creating new devices disabled by settings");
            return LegacyDevice.absent();
        }
    }

    private LegacyDevice createdLegacyDevice(String deviceId) {
        Device device = getDeviceByNumber(deviceId);
        return device == null ? LegacyDevice.absent() : LegacyDevice.served(device, LegacyDevice.Lookup.NUMBER);
    }

    /**
     * A launcher device created on demand with {@code deviceId}, which {@link #findLegacyDevice} found
     * {@link LegacyDevice#isAbsent() absent}: in the configuration whose QR code key the options name, else in the
     * customer's default configuration for new devices when its settings create devices on demand; never in an
     * agent-managed configuration, and within the customer's device limit.
     */
    @Transactional
    public LegacyDevice createLegacyDevice(String deviceId, DeviceCreateOptions createOptions) {
        int customerId = DEFAULT_CUSTOMER_ID;
        int deviceLimit = 0;
        if (!isSingleCustomer()) {
            if (createOptions.getCustomer() == null) {
                logger.warn("Customer is not set, device not created");
                return LegacyDevice.absent();
            }
            Customer customer = customerMapper.findCustomerByName(createOptions.getCustomer());
            if (customer != null) {
                customerId = customer.getId();
            } else {
                logger.warn("Failed to get a customer by name '" + createOptions.getCustomer() + "', device not created");
                return LegacyDevice.absent();
            }
            deviceLimit = customer.getDeviceLimit();
        }

        Settings settings = getSettings(customerId);
        Device newDevice = new Device();
        newDevice.setCustomerId(customerId);

        // If the configuration is specified, we want to create a new device, so don't check the legacy setting
        if (createOptions.getConfiguration() != null) {
            Configuration configuration = configurationMapper.getConfigurationByQRCodeKey(createOptions.getConfiguration());
            if (configuration == null) {
                logger.warn("Failed to get a configuration by key {}, device not created",
                        CryptoUtil.logFingerprint(createOptions.getConfiguration()));
                return LegacyDevice.absent();
            } else if (configuration.getCustomerId() != customerId) {
                logger.warn("Configuration " + configuration.getId() + " doesn't belong to customer " +
                        customerId + ", device not created");
                return LegacyDevice.absent();
            } else {
                newDevice.setConfigurationId(configuration.getId());
            }
        } else if (settings != null && settings.isCreateNewDevices()) {
            // Configuration not specified, we will add a device only if a legacy setting "Create new devices" is set
            if (settings.getNewDeviceConfigurationId() != null
                    && !isCustomerConfiguration(settings.getNewDeviceConfigurationId(), customerId)) {
                logger.warn("Default configuration of customer {} does not belong to it, device not created", customerId);
                return LegacyDevice.absent();
            }
            newDevice.setConfigurationId(settings.getNewDeviceConfigurationId());
        } else {
            logger.warn("Creating new devices disabled by settings, configuration ID not set");
            return LegacyDevice.absent();
        }
        if (isAgentConfiguration(newDevice.getConfigurationId())) {
            logAgentConfigurationRefused("new device " + deviceId + " in", newDevice.getConfigurationId());
            return LegacyDevice.agentConfiguration();
        }

        if (createOptions.getGroups() != null) {
            List<LookupItem> groups = new LinkedList<>();
            for (String s : createOptions.getGroups()) {
                int groupId = 0;
                try {
                    groupId = Integer.parseInt(s);
                } catch (Exception e) {
                    logger.warn("Group id must be integer: '" + s + "', group ignored");
                    continue;
                }
                Group group = deviceMapper.getGroupById(groupId);
                if (group == null) {
                    logger.warn("Failed to find group by id: " + s + ", group ignored");
                } else if (group.getCustomerId() != customerId) {
                    logger.warn("Group id " + s + " doesn't belong to customer " + customerId + ", group ignored");
                } else {
                    groups.add(new LookupItem(group.getId(), ""));
                }
            }
            if (groups.size() > 0) {
                newDevice.setGroups(groups);
            }
        } else if (settings != null) {
            Integer groupId = settings.getNewDeviceGroupId();
            if (isCustomerGroup(groupId, customerId)) {
                List<LookupItem> groups = new LinkedList<>();
                groups.add(new LookupItem(groupId, ""));
                newDevice.setGroups(groups);
            }
        }

        // The limit, counted under the per-customer lock every device-adding path shares (CustomerDeviceLimit), in
        // this method's transaction with the insert: concurrent adds can't together pass it.
        final int limitCustomerId = customerId;
        if (!CustomerDeviceLimit.roomForOne(deviceLimit, () -> deviceMapper.lockCustomerDevices(limitCustomerId), () -> {
            Long n = deviceMapper.countAllDevicesForCustomer(limitCustomerId);
            return n == null ? 0L : n;
        })) {
            logger.warn("New device " + deviceId + " not created by customer " + customerId + ": license limit");
            return LegacyDevice.absent();
        }
        newDevice.setNumber(deviceId);
        newDevice.setLastUpdate(0L);
        insertDevice(newDevice);
        logger.info("New device {} added", deviceId);

        return createdLegacyDevice(deviceId);
    }

    public void updateDeviceFastSearch(int fastSearchChars) {
        deviceMapper.updateFastSearch(fastSearchChars);
    }

    public UserRole findRoleByNameUnsecure(String name) {
        return userMapper.findUserRoleByName(name);
    }

    /** An uploaded file of any customer, by id; null when there is none. */
    public UploadedFile getUploadedFileById(Integer id) {
        return id == null ? null : uploadedFileMapper.findById(id);
    }

    public UploadedFile getUploadedFileByPath(Integer customerId, String path) {
        return uploadedFileMapper.findByPath(customerId, path);
    }

    public int insertUploadedFile(UploadedFile uploadedFile) {
        return uploadedFileMapper.insert(uploadedFile);
    }

    public void updateUploadedFile(UploadedFile uploadedFile) {
        uploadedFileMapper.update(uploadedFile);
    }

}
