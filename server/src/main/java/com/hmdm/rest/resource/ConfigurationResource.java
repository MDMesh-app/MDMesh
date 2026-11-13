/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
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

package com.hmdm.rest.resource;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.POST;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.QueryParam;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.extension.ConfigurationExtensions;
import com.hmdm.extension.ExtensionError;
import com.hmdm.notification.PushService;
import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.rest.json.UpgradeConfigurationApplicationRequest;
import com.hmdm.rest.resource.support.ExtensionPoints;
import com.hmdm.rest.resource.support.KioskBackgroundImageAssets;
import com.hmdm.rest.resource.support.KioskBackgroundImageInfo;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.DesiredConfigBuilder;
import com.hmdm.util.FileUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import com.hmdm.rest.json.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Api(tags = {"Configuration"}, authorizations = {@Authorization("Bearer Token")})
@Singleton
@Path("/private/configurations")
public class ConfigurationResource {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationResource.class);

    private ConfigurationDAO configurationDAO;
    private ApplicationDAO applicationDAO;
    private PushService pushService;
    private CustomerDAO customerDAO;
    private UserDAO userDAO;
    private String baseUrl;
    private ExtensionPoints extensionPoints;
    private KioskBackgroundImageAssets backgroundImages;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public ConfigurationResource() {
    }

    @Inject
    public ConfigurationResource(ConfigurationDAO configurationDAO,
                                 ApplicationDAO applicationDAO,
                                 PushService pushService,
                                 CustomerDAO customerDAO,
                                 UserDAO userDAO,
                                 @Named("base.url") String baseUrl,
                                 ExtensionPoints extensionPoints,
                                 KioskBackgroundImageAssets backgroundImages,
                                 com.hmdm.persistence.ConfigurationFileDAO configurationFileDAO,
                                 com.hmdm.persistence.IconDAO iconDAO) {
        this(configurationDAO, applicationDAO, pushService, customerDAO, userDAO, baseUrl, extensionPoints, backgroundImages);
        this.configurationFileDAO = configurationFileDAO;
        this.iconDAO = iconDAO;
    }

    /** Tests: no configuration-file or icon users to check before a gallery delete. */
    public ConfigurationResource(ConfigurationDAO configurationDAO,
                                 ApplicationDAO applicationDAO,
                                 PushService pushService,
                                 CustomerDAO customerDAO,
                                 UserDAO userDAO,
                                 String baseUrl,
                                 ExtensionPoints extensionPoints,
                                 KioskBackgroundImageAssets backgroundImages) {
        this.configurationDAO = configurationDAO;
        this.applicationDAO = applicationDAO;
        this.pushService = pushService;
        this.customerDAO = customerDAO;
        this.userDAO = userDAO;
        this.baseUrl = baseUrl;
        this.extensionPoints = extensionPoints;
        this.backgroundImages = backgroundImages;
    }

    /** Compatibility constructor for focused resource tests which do not exercise managed images. */
    public ConfigurationResource(ConfigurationDAO configurationDAO, ApplicationDAO applicationDAO,
                                 PushService pushService, CustomerDAO customerDAO, UserDAO userDAO,
                                 String baseUrl, ExtensionPoints extensionPoints) {
        this(configurationDAO, applicationDAO, pushService, customerDAO, userDAO, baseUrl, extensionPoints, null);
    }
    // =================================================================================================================
    @ApiOperation(
            value = "Get configurations",
            notes = "Gets the list of available configurations. A row has no files, applicationSettings, " +
                    "applicationUsageParameters or extensions: GET /{id} returns them",
            response = Configuration.class,
            responseContainer = "List"
    )
    @GET
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllConfigurations() {
        if (!SecurityContext.get().hasPermission("configurations")) {
            log.error("Unauthorized attempt to access configurations");
            return Response.PERMISSION_DENIED();
        }
        List<Configuration> configurations = this.configurationDAO.getAllConfigurations();
        configurations.forEach(c -> c.setBaseUrl(this.configurationDAO.getBaseUrl()));
        return Response.OK(configurations);
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get configuration names",
            notes = "Gets the list of available configuration names",
            response = LookupItem.class,
            responseContainer = "List"
    )
    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllConfigurationNames() {
        // This list is available to users with all permissions
        List<LookupItem> items = this.configurationDAO.getAllConfigurations()
                .stream()
                .map(configuration -> new LookupItem(configuration.getId(), configuration.getName()))
                .collect(Collectors.toList());
        return Response.OK(items);
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Search configurations",
            notes = "Searches configurations meeting the specified filter value. A row has no files, " +
                    "applicationSettings, applicationUsageParameters or extensions: GET /{id} returns them",
            response = Configuration.class,
            responseContainer = "List"
    )
    @GET
    @Path("/search/{value}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response searchConfigurations(@PathParam("value") String value) {
        if (!SecurityContext.get().hasPermission("configurations")) {
            log.error("Unauthorized attempt to access configurations");
            return Response.PERMISSION_DENIED();
        }
        List<Configuration> configurations = this.configurationDAO.getAllConfigurationsByValue(value);
        configurations.forEach(c -> c.setBaseUrl(this.configurationDAO.getBaseUrl()));
        return Response.OK(configurations);
    }


    // =================================================================================================================
    /**
     * <p>Gets the list of configuration id/names matching the specified filter for autocompletions.</p>
     *
     * @param filter a filter to be used for filtering the records.
     * @return a response with list of configurations matching the specified filter.
     */
    @ApiOperation(value = "Get configurations for autocompletions")
    @POST
    @Path("/autocomplete")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfigurations(String filter) {
        try {
            List<LookupItem> groups = this.configurationDAO.getAllConfigurationsByValue(filter)
                    .stream()
                    .map(configuration -> new LookupItem(configuration.getId(), configuration.getName()))
                    .collect(Collectors.toList());
            return Response.OK(groups);
        } catch (Exception e) {
            log.error("Failed to search the configurations due to unexpected error. Filter: {}", filter, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Create or update configuration",
            notes = "Creates a new configuration (if id is not provided) or update existing one otherwise. " +
                    "An update replaces the configuration: every column, and the applications list, are written " +
                    "as sent, so a column left out becomes null and a missing applications list removes every app. " +
                    "Only files, applicationSettings, extensions and managedAppPermissions left out (null) are kept; " +
                    "an empty list (or object) clears them. managedAppPermissions ({applicationId, permission, state}, " +
                    "state grant or deny; absent = Default) keeps only the rules for apps the save installs; a " +
                    "malformed, duplicated or excess rule is refused with HTTP 400. extensions are checked once the user may save (com.hmdm.extension.ConfigurationExtensions), " +
                    "the plugins' validators on the namespaces the save changes: " +
                    "a refusal is an ERROR whose message is the first problem's key and whose data lists them all. " +
                    "applicationUsageParameters are upserted and never removed. " +
                    "An explicit \"applications\": null and a connectivityMode other than adaptive, alwaysOn or " +
                    "null are refused with HTTP 400. So, on a save that changes systemUpdateType, systemUpdateFrom " +
                    "or systemUpdateTo (or creates the configuration), are a systemUpdateType other than 0 to 3 and " +
                    "a Scheduled (2) one without systemUpdateFrom and systemUpdateTo as two different HH:MM times. " +
                    "Kiosk background image: backgroundImageAction is keep (the default), replace or remove. replace " +
                    "takes backgroundImageFileId, an uploaded file (PNG, JPEG or WebP, at most 10 MB and 16 megapixels), " +
                    "and the server sets backgroundImageUrl and backgroundImageSha256 from it; remove clears all three. " +
                    "With keep, an update keeps the stored image while backgroundImageUrl is left out or unchanged; " +
                    "another backgroundImageUrl is stored as the legacy launcher wallpaper and drops the managed image. " +
                    "A new configuration with a backgroundImageFileId gets that image. A refused image is HTTP 400 " +
                    "with an ERROR whose message says why. backgroundImageFit and backgroundImageFocalX/Y left out " +
                    "keep the stored ones, except that a replace without a focal point centres it. " +
                    "locationServicesAction is keep (the default), on, off, or notManaged; " +
                    "ordinary saves preserve the stored Location-services policy."
    )
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateConfiguration(Configuration configuration) {
        if (!SecurityContext.get().hasPermission("configurations")) {
            log.error("Unauthorized attempt to update the configuration " +
                    (configuration == null ? null : configuration.getId()) +
                    ", user " + SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        if (configuration == null || configuration.getApplications() == null) {
            // Saving needs the applications list (an empty one for none); null used to fail with a 500.
            throw new BadRequestException("applications must be a list");
        }
        if (configuration.getConnectivityMode() != null
                && DesiredConfigBuilder.connectivityMode(configuration.getConnectivityMode()) == null) {
            // An unknown mode would be stored and sent as not managed; a long one used to fail with a 500.
            throw new BadRequestException("connectivityMode must be adaptive, alwaysOn or null");
        }
        normalizeManagedAppPermissions(configuration);
        // Devices apply it now (config.apply systemUpdate, 1.11): a window they can't use would be sent as Default.
        String systemUpdateError = systemUpdateError(configuration);
        validateBackgroundImageFit(configuration);
        // A cleaned copy of the background image this save hosted: taken back if the save then fails.
        KioskBackgroundImageAssets.Applied imageApplied = null;
        // The file the save chose, held from the image check until the save is written or fails.
        KioskBackgroundImageAssets.Applied imageHeld = null;
        try {
            Configuration dbConfiguration = this.configurationDAO.getConfigurationByName(configuration.getName());
            final Integer id = configuration.getId();
            if (dbConfiguration != null && !dbConfiguration.getId().equals(id)) {
                return Response.DUPLICATE_ENTITY("error.duplicate.configuration");
            } else {
                if (id == null) {
                    if (!SecurityContext.get().hasPermission("add_config")) {
                        log.error("Unauthorized attempt to create the configuration " + configuration.getId() +
                                "by user " + SecurityContext.get().getCurrentUserName());
                        return Response.PERMISSION_DENIED();
                    }
                    if (systemUpdateError != null) throw new BadRequestException(systemUpdateError);
                    imageApplied = canonicalizeBackgroundImage(configuration, null);
                    imageHeld = imageApplied;
                    applyLocationServicesUpdate(configuration, null);
                    Response refused = refuseExtensions(configuration, null);
                    if (refused != null) {
                        undo(imageApplied);
                        return refused;
                    }
                    configuration.setDisableLocation(false);        // Not used but shouldn't be NULL
                    this.configurationDAO.insertConfiguration(configuration);
                    // Saved: the copy is the configuration's now, whatever fails after this.
                    imageApplied = null;
                    User user = SecurityContext.get().getCurrentUser().get();
                    if (!user.isAllConfigAvailable()) {
                        // User should get permissions to edit a configuration he created
                        user.getConfigurations().add(new LookupItem(configuration.getId(), null));
                        userDAO.updateUserMainDetails(user);
                    }
                } else {
                    if (!configurationDAO.hasConfigurationAccess(id)) {
                        log.error("Unauthorized attempt to update the configuration " + configuration.getId() +
                                "by user " + SecurityContext.get().getCurrentUserName());
                        return Response.PERMISSION_DENIED();
                    }
                    Configuration stored = configurationDAO.getConfigurationById(id);
                    // Like the plugins' validators, only what the save changes: a row saved before this check
                    // can still get an unrelated edit.
                    if (systemUpdateError != null && systemUpdateChanges(configuration, stored)) {
                        throw new BadRequestException(systemUpdateError);
                    }
                    imageApplied = canonicalizeBackgroundImage(configuration, stored);
                    imageHeld = imageApplied;
                    applyLocationServicesUpdate(configuration, stored);
                    keepBackgroundImageFit(configuration, stored);
                    Response refused = refuseExtensions(configuration, storedExtensions(id, configuration));
                    if (refused != null) {
                        undo(imageApplied);
                        return refused;
                    }
                    log.info("Configuration " + configuration.getName() + " updated by user "  + SecurityContext.get().getCurrentUserName());
                    this.configurationDAO.updateConfiguration(configuration);
                    imageApplied = null;
                    this.pushService.notifyDevicesOnUpdate(configuration.getId());
                }
                configuration = getConfiguration(configuration.getId());

                return Response.OK(configuration);
            }
        } catch (BadRequestException e) {
            undo(imageApplied);
            throw e;
        } catch (Exception e) {
            undo(imageApplied);
            log.error("Unexpected error when saving the configuration", e);
            e.printStackTrace();
            return Response.INTERNAL_ERROR();
        } finally {
            // Written (the configuration references the file) or failed: the save no longer holds it.
            if (imageHeld != null) imageHeld.release();
        }
    }

    /** The longest permission name a rule may carry (Android's are under 60 characters). */
    static final int MAX_PERMISSION_LENGTH = 128;
    /** Rules per app: more than Android has runtime permissions, so only a broken or hostile client reaches it. */
    static final int MAX_RULES_PER_APP = 64;
    private static final java.util.regex.Pattern PERMISSION_NAME =
            java.util.regex.Pattern.compile("android\\.permission\\.[A-Z][A-Z0-9_]*");

    /**
     * Checks a save's managedAppPermissions and keeps only the rules for apps the save installs. A rule for an app
     * the save leaves out or doesn't install is dropped, not refused: another path (the Applications page, a new
     * version) can change a configuration's apps under the editor, and the console still holds that app's rules, so
     * refusing would make the configuration unsaveable. Null (an older console or API client) keeps what is stored.
     *
     * @throws BadRequestException for a malformed rule: a state other than grant or deny, a permission name that is
     *                             not android.permission.*, too long, duplicated, or more than
     *                             {@link #MAX_RULES_PER_APP} for one app
     */
    static void normalizeManagedAppPermissions(Configuration configuration) {
        List<ManagedAppPermission> rules = configuration.getManagedAppPermissions();
        if (rules == null) return;
        Set<Integer> eligibleApps = configuration.getApplications().stream()
                .filter(app -> app != null && app.getId() != null && app.getAction() == DesiredConfigBuilder.ACTION_INSTALL)
                .map(Application::getId).collect(Collectors.toSet());
        Set<String> seen = new HashSet<String>();
        java.util.Map<Integer, Integer> perApp = new java.util.HashMap<Integer, Integer>();
        List<ManagedAppPermission> kept = new java.util.ArrayList<ManagedAppPermission>();
        for (ManagedAppPermission rule : rules) {
            if (rule == null || rule.getApplicationId() == null || rule.getPermission() == null
                    || rule.getPermission().length() > MAX_PERMISSION_LENGTH
                    || !PERMISSION_NAME.matcher(rule.getPermission()).matches()
                    || !("grant".equals(rule.getState()) || "deny".equals(rule.getState()))
                    || !seen.add(rule.getApplicationId() + " " + rule.getPermission())
                    || perApp.merge(rule.getApplicationId(), 1, Integer::sum) > MAX_RULES_PER_APP) {
                throw new BadRequestException("managedAppPermissions must be unique android.permission.* names set to "
                        + "grant or deny, at most " + MAX_RULES_PER_APP + " per app");
            }
            if (eligibleApps.contains(rule.getApplicationId())) kept.add(rule);
        }
        configuration.setManagedAppPermissions(kept);
    }

    private void undo(KioskBackgroundImageAssets.Applied applied) {
        if (applied == null) return;
        try {
            applied.undo(fileId -> configurationDAO.isBackgroundImageFileUsed(fileId));
        } catch (RuntimeException e) {
            log.warn("Could not remove the background image copy of a failed save", e);
        }
    }

    private KioskBackgroundImageAssets.Applied canonicalizeBackgroundImage(Configuration configuration, Configuration stored) {
        if (backgroundImages == null) return null;
        try {
            return backgroundImages.apply(configuration, stored);
        } catch (IllegalArgumentException e) {
            // The usual ERROR envelope, so the console can show the message.
            throw new BadRequestException(javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Response.ERROR(e.getMessage()))
                    .build());
        }
    }

    /** fit is fill, fit or null; a focal coordinate is null or a number from 0 to 1 (NaN and infinities refused). */
    private static void validateBackgroundImageFit(Configuration c) {
        String fit = c.getBackgroundImageFit();
        if (fit != null && !"fill".equals(fit) && !"fit".equals(fit)) {
            throw badRequest("backgroundImageFit must be fill, fit or null");
        }
        for (Double v : new Double[]{c.getBackgroundImageFocalX(), c.getBackgroundImageFocalY()}) {
            if (v != null && !(v >= 0 && v <= 1)) {
                throw badRequest("backgroundImageFocalX and backgroundImageFocalY must be from 0 to 1");
            }
        }
    }

    /**
     * What a client doesn't send of fit and the focal point (an older console or API client) keeps its stored value,
     * except the focal point on a {@code replace}: it belongs to the old image, so another image starts at the centre
     * unless the client sends one (as the console does). Fill or Fit stays as chosen.
     */
    private static void keepBackgroundImageFit(Configuration c, Configuration stored) {
        if (stored == null) return;
        if (c.getBackgroundImageFit() == null) c.setBackgroundImageFit(stored.getBackgroundImageFit());
        if ("replace".equals(c.getBackgroundImageAction())) return;
        if (c.getBackgroundImageFocalX() == null) c.setBackgroundImageFocalX(stored.getBackgroundImageFocalX());
        if (c.getBackgroundImageFocalY() == null) c.setBackgroundImageFocalY(stored.getBackgroundImageFocalY());
    }

    private static BadRequestException badRequest(String message) {
        return new BadRequestException(javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Response.ERROR(message))
                .build());
    }

    private static void applyLocationServicesUpdate(Configuration configuration, Configuration stored) {
        String action = configuration.getLocationServicesAction();
        if (action == null || "keep".equals(action)) {
            // Keep protects a stored policy from a client that doesn't know the field. A new configuration has none
            // stored, so it gets the value it was sent with (an API client, or a console copy without the action).
            if (stored != null) configuration.setLocationServices(stored.getLocationServices());
            return;
        }
        if ("on".equals(action)) {
            configuration.setLocationServices(true);
            return;
        }
        if ("off".equals(action)) {
            configuration.setLocationServices(false);
            return;
        }
        if ("notManaged".equals(action)) {
            configuration.setLocationServices(null);
            return;
        }
        throw new BadRequestException("locationServicesAction must be keep, on, off, or notManaged");
    }

    /**
     * What an update's extensions replace, for the validators to check only the namespaces it changes; read only when
     * the save carries extensions.
     */
    private ObjectNode storedExtensions(Integer id, Configuration configuration) {
        if (configuration.getExtensions() == null) return null;
        Configuration stored = configurationDAO.getConfigurationById(id);
        return stored == null ? null : stored.getExtensions();
    }

    /**
     * The ERROR for a save whose extensions may not be stored (ConfigurationExtensions.check), or null. Run once the
     * user may save, so plugins' validators only see the input of a user allowed to make it. Only the plugins on for
     * the user's customer check it (ExtensionPoints).
     */
    private Response refuseExtensions(Configuration configuration, ObjectNode stored) {
        if (configuration.getExtensions() == null) return null;
        List<ExtensionError> problems = ConfigurationExtensions.check(configuration.getExtensions(), stored,
                extensionPoints.validators(SecurityContext.get().getCurrentCustomerId().orElse(null)));
        if (problems.isEmpty()) return null;
        // The keys only: a refused namespace is the user's text, which must not reach the log as it is.
        log.info((configuration.getId() == null ? "A new configuration" : "Configuration " + configuration.getId())
                + " not saved: " + problems.stream().map(ExtensionError::getKey).collect(Collectors.joining(", ")));
        return Response.ERROR(problems.get(0).getKey(), problems);
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Upgrade configuration application",
            notes = "Upgrades the application used by configuration to most recent version",
            response = Configuration.class
    )
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/application/upgrade")
    public Response upgradeConfiguration(UpgradeConfigurationApplicationRequest request) {
        if (!SecurityContext.get().hasPermission("configurations") ||
                !configurationDAO.hasConfigurationAccess(request.getConfigurationId())) {
            log.error("Unauthorized attempt to upgrade the configuration " + request.getConfigurationId());
            return Response.PERMISSION_DENIED();
        }
        try {
            this.configurationDAO.upgradeConfigurationApplication(request.getConfigurationId(), request.getApplicationId());
            final Configuration configuration = this.getConfiguration(request.getConfigurationId());
            return Response.OK(configuration);
        } catch (Exception e) {
            log.error("Failed to upgrade application #{} for configuration #{} to latest version due to unexpected error",
                    request.getConfigurationId(), request.getApplicationId(), e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Copy configuration",
            notes = "Creates a new copy of configuration referenced by the id and names it with provided name."
    )
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/copy")
    public Response copyConfiguration(Configuration configuration) {
        if (!SecurityContext.get().hasPermission("copy_config") ||
                !configurationDAO.hasConfigurationAccess(configuration.getId())) {
            log.error("Unauthorized attempt to copy the configuration " + configuration.getId());
            return Response.PERMISSION_DENIED();
        }
        Configuration dbConfiguration = this.configurationDAO.getConfigurationByName(configuration.getName());
        if (dbConfiguration != null) {
            return Response.DUPLICATE_ENTITY("error.duplicate.configuration");
        } else {
            dbConfiguration = this.getConfiguration(configuration.getId());
            List<Application> configurationApplications = this.configurationDAO.getPlainConfigurationApplications(configuration.getId());
            Configuration copy = dbConfiguration.newCopy();
            copy.setName(configuration.getName());
            copy.setDescription(configuration.getDescription());
            copy.setApplications(configurationApplications);
            copy.setBaseUrl(this.configurationDAO.getBaseUrl());
            // The source's background image is held until the copy is written: a delete meanwhile can't leave the copy
            // pointing at nothing (refused when it is already gone).
            KioskBackgroundImageAssets.Applied held = null;
            if (backgroundImages != null && copy.getBackgroundImageFileId() != null) {
                try {
                    held = backgroundImages.holdExisting(copy.getBackgroundImageFileId(), true);
                } catch (IllegalArgumentException e) {
                    return Response.ERROR(KioskBackgroundImageAssets.COPY_SOURCE_DELETED);
                }
            }
            try {
                this.configurationDAO.insertConfiguration(copy);
            } finally {
                if (held != null) held.release();
            }
            User user = SecurityContext.get().getCurrentUser().get();
            if (!user.isAllConfigAvailable()) {
                // User should get permissions to edit a configuration he created
                user.getConfigurations().add(new LookupItem(copy.getId(), null));
                userDAO.updateUserMainDetails(user);
            }
            return Response.OK();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Delete configuration",
            notes = "Deletes a configuration referenced by the specified ID."
    )
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeConfiguration(@PathParam("id") @ApiParam("Configuration ID") Integer id) {
        if (!SecurityContext.get().hasPermission("copy_config") ||
                !configurationDAO.hasConfigurationAccess(id)) {
            log.error("Unauthorized attempt to delete the configuration " + id);
            return Response.PERMISSION_DENIED();
        }
        try {
            this.configurationDAO.removeConfigurationById(id);
            return Response.OK();
        } catch (ConfigurationReferenceExistsException e) {
            log.error("Failed to delete configuration #{}", id, e);
            return Response.CONFIGURATION_DEVICE_REFERENCE_EXISTS();
        } catch (Exception e) {
            log.error("Failed to delete configuration #{}", id, e);
            return Response.INTERNAL_ERROR();
        }
    }

    @ApiOperation(value = "", hidden = true)
    @GET
    @Path("/applications")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllApplications() {
        return Response.OK(this.applicationDAO.getAllApplications());
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get configuration applications",
            notes = "Gets the list of all applications in context of usage by the requested configuration",
            response = Application.class,
            responseContainer = "List"
    )
    @GET
    @Path("/applications/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfigurationApplications(@PathParam("id") @ApiParam("Configuration ID") Integer id) {
        if (!SecurityContext.get().hasPermission("configurations") ||
                !configurationDAO.hasConfigurationAccess(id)) {
            log.error("Unauthorized attempt to access configuration applications");
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(this.applicationDAO.populateRequestedPermissions(this.configurationDAO.getConfigurationApplications(id)));
    }

    /** What else may use an uploaded file (configuration files, icons), checked before a gallery delete. */
    private com.hmdm.persistence.ConfigurationFileDAO configurationFileDAO;
    private com.hmdm.persistence.IconDAO iconDAO;

    // =================================================================================================================
    @ApiOperation(
            value = "Kiosk background images",
            notes = "The customer's kiosk background images, once per content (SHA-256), newest first, with the "
                    + "configurations using each (the ones the user may see by name, all of them counted). Paged. "
                    + "Needs the configurations permission (as editing a background does), not Files access: a user "
                    + "who edits configurations sees every kiosk image of the customer.",
            response = KioskBackgroundImageAssets.GalleryPage.class
    )
    @GET
    @Path("/backgroundImages")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getBackgroundImages(@QueryParam("page") @DefaultValue("0") int page,
                                        @QueryParam("size") @DefaultValue("20") int size) {
        if (!SecurityContext.get().hasPermission("configurations") || backgroundImages == null) {
            return Response.PERMISSION_DENIED();
        }
        Map<Integer, List<KioskBackgroundImageAssets.Use>> uses = new HashMap<>();
        Set<Integer> hidden = new HashSet<>();
        // Which of them the user may open: one query, not an access check per configuration.
        Set<Integer> accessible = new HashSet<>();
        for (Configuration c : configurationDAO.getAccessibleBackgroundImageUses()) accessible.add(c.getId());
        for (Configuration c : configurationDAO.getBackgroundImageUses()) {
            KioskBackgroundImageAssets.Use u = new KioskBackgroundImageAssets.Use();
            u.setId(c.getId());
            u.setName(c.getName());
            uses.computeIfAbsent(c.getBackgroundImageFileId(), k -> new ArrayList<>()).add(u);
            if (!accessible.contains(c.getId())) hidden.add(c.getId());
        }
        return Response.OK(backgroundImages.gallery(uses, hidden, Math.max(0, page), Math.max(1, Math.min(100, size))));
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Delete a kiosk background image",
            notes = "Deletes an uploaded kiosk background image of the customer that no configuration (or configuration "
                    + "file or icon) uses. Refused (ERROR with a message) while anything uses it."
    )
    @POST
    @Path("/backgroundImages/{fileId}/delete")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteBackgroundImage(@PathParam("fileId") Integer fileId) {
        if (!SecurityContext.get().hasPermission("configurations") || !SecurityContext.get().hasPermission("edit_files")
                || backgroundImages == null || fileId == null) {
            return Response.PERMISSION_DENIED();
        }
        try {
            backgroundImages.deleteUnused(fileId, id -> configurationDAO.isBackgroundImageFileUsed(id)
                    || (configurationFileDAO != null && configurationFileDAO.isFileUsed(id))
                    || (iconDAO != null && iconDAO.isFileUsed(id)));
            return Response.OK();
        } catch (IllegalArgumentException e) {
            return Response.ERROR(e.getMessage());
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Describe a kiosk background image",
            notes = "An uploaded image as devices get it once a configuration is saved with it: name, format, displayed "
                    + "width and height, size and SHA-256 of the file without metadata, upload time, current URL. "
                    + "Refuses (ERROR with a message) a file a save would refuse. Another account's file is described "
                    + "only when a configuration of this account that the user may open uses it (one copied from the "
                    + "main account's template); it comes back with fromAnotherAccount true, and a save can't choose it. "
                    + "Any other account's file gets the same ERROR as a file that doesn't exist.",
            response = KioskBackgroundImageInfo.class
    )
    @GET
    @Path("/backgroundImage/{fileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getBackgroundImage(@PathParam("fileId") Integer fileId) {
        if (!SecurityContext.get().hasPermission("configurations") || backgroundImages == null || fileId == null) {
            log.error("Unauthorized attempt to describe the background image file " + fileId);
            return Response.PERMISSION_DENIED();
        }
        try {
            // Another account's file only when a configuration of this account the user may open uses it (one copied
            // from the main account's template): its editor then shows the image's facts, not an alert.
            return Response.OK(backgroundImages.describe(fileId, id -> configurationDAO.getAccessibleBackgroundImageUses()
                    .stream().anyMatch(c -> Integer.valueOf(id).equals(c.getBackgroundImageFileId()))));
        } catch (IllegalArgumentException e) {
            return Response.ERROR(e.getMessage());
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get configuration",
            notes = "Gets the details for configuration referenced by the specified ID",
            response = Configuration.class
    )
    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfigurationById(@PathParam("id") Integer id) {
        if (!SecurityContext.get().hasPermission("configurations") ||
                !configurationDAO.hasConfigurationAccess(id)) {
            log.error("Unauthorized attempt to access the configuration " + id);
            return Response.PERMISSION_DENIED();
        }

        Configuration configurationById = getConfiguration(id);

        return Response.OK(configurationById);
    }

    /**
     * <p>Gets the configuration referenced by the specified ID from DB.</p>
     *
     * @param id an ID of a configuration to get data for.
     *
     * @return a configuration referenced by the specified ID or <code>null</code> if there is no such configuration.
     */
    private Configuration getConfiguration(Integer id) {

        Configuration configuration = this.configurationDAO.getConfigurationByIdFull(id);
        if (configuration != null) {
            configuration.setBaseUrl(this.configurationDAO.getBaseUrl());
            final List<ConfigurationFile> files = configuration.getFiles();
            if (files != null && !files.isEmpty()) {
                final Customer customer = this.customerDAO.findById(configuration.getCustomerId());

                files.forEach(file -> {
                    if (file.getExternalUrl() != null) {
                        file.setUrl(file.getExternalUrl());
                    } else if (file.getFilePath() != null) {
                        final String url = FileUtil.createFileUrl(this.baseUrl, customer.getFilesDir(), file.getFilePath());
                        file.setUrl(url);
                    }
                });
            }

        }
        return configuration;
    }

    /** Why the configuration's system update setting can't be saved, or null when it can. */
    static String systemUpdateError(Configuration configuration) {
        int type = configuration.getSystemUpdateType();
        if (type < DesiredConfigBuilder.SYSTEM_UPDATE_DEFAULT || type > DesiredConfigBuilder.SYSTEM_UPDATE_POSTPONED) {
            return "systemUpdateType must be 0 (Default), 1 (Immediately), 2 (Scheduled) or 3 (Postponed)";
        }
        if (type == DesiredConfigBuilder.SYSTEM_UPDATE_SCHEDULED && DesiredConfigBuilder.systemUpdate(type,
                configuration.getSystemUpdateFrom(), configuration.getSystemUpdateTo()) == null) {
            return "a Scheduled systemUpdateType needs systemUpdateFrom and systemUpdateTo as two different HH:MM times";
        }
        return null;
    }

    /** Whether the save changes the stored system update setting. A blank time and null are the same. */
    static boolean systemUpdateChanges(Configuration configuration, Configuration stored) {
        return stored == null
                || configuration.getSystemUpdateType() != stored.getSystemUpdateType()
                || !timeOf(configuration.getSystemUpdateFrom()).equals(timeOf(stored.getSystemUpdateFrom()))
                || !timeOf(configuration.getSystemUpdateTo()).equals(timeOf(stored.getSystemUpdateTo()));
    }

    private static String timeOf(String time) {
        return time == null ? "" : time.trim();
    }
}
