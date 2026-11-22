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
import javax.inject.Singleton;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.notification.PushService;
import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.*;
import com.hmdm.rest.json.view.devicelist.DeviceListView;
import com.hmdm.rest.json.view.devicelist.DeviceView;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Api(tags = {"Device"}, authorizations = {@Authorization("Bearer Token")})
@Singleton
@Path("/private/devices")
public class DeviceResource {

    private static final Logger log = LoggerFactory.getLogger(DeviceResource.class);

    /** A bulk configuration change names no configuration. */
    static final String BULK_CONFIGURATION_REQUIRED = "error.device.configuration.required";

    /** Bulk delete deleted some devices, not all: data {deleted, notDeleted}, device ids. */
    static final String BULK_DELETE_PARTIAL = "error.device.delete.partial";

    private DeviceDAO deviceDAO;
    private ConfigurationDAO configurationDAO;
    private PushService pushService;
    private ConfigurationFileDAO configurationFileDAO;
    private CommonDAO commonDAO;
    private UnsecureDAO unsecureDAO;
    private AgentWakeHub wakeHub;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public DeviceResource() {
    }

    @Inject
    public DeviceResource(DeviceDAO deviceDAO,
                          ConfigurationDAO configurationDAO,
                          PushService pushService,
                          ConfigurationFileDAO configurationFileDAO,
                          CommonDAO commonDAO,
                          UnsecureDAO unsecureDAO,
                          AgentWakeHub wakeHub) {
        this.deviceDAO = deviceDAO;
        this.configurationDAO = configurationDAO;
        this.pushService = pushService;
        this.configurationFileDAO = configurationFileDAO;
        this.commonDAO = commonDAO;
        this.unsecureDAO = unsecureDAO;
        this.wakeHub = wakeHub;
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Search devices",
            notes = "Search devices meeting the specified filter value",
            response = DeviceListView.class
    )
    @POST
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllDevices(DeviceSearchRequest request) {
        PaginatedData<Device> devices = this.deviceDAO.getAllDevices(request);
        Map<Integer, List<Application>> configIdToApplicationsMap = new HashMap<>();
        Map<Integer, List<ConfigurationFile>> configIdToFilesMap = new HashMap<>();
        Map<Integer, Configuration> configIdToConfigurationsMap = new HashMap<>();
        for (Device device : devices.getItems()) {
            final Integer deviceConfigurationId = device.getConfigurationId();

            Configuration dbConfig = null;
            if (!configIdToConfigurationsMap.containsKey(deviceConfigurationId)) {
                dbConfig = configurationDAO.getConfigurationById(deviceConfigurationId);
                if (dbConfig == null) {
                    log.error("Device " + device.getNumber() + ": configuration does not exist: " + deviceConfigurationId);
                    device.setConfigurationId(null);     // Will be filtered out when converting to DeviceView
                    continue;
                }
            }

            if (!configIdToApplicationsMap.containsKey(deviceConfigurationId)) {
                configIdToApplicationsMap.put(deviceConfigurationId, this.configurationDAO.getConfigurationApplications(deviceConfigurationId));
            }
            if (!configIdToFilesMap.containsKey(deviceConfigurationId)) {
                configIdToFilesMap.put(deviceConfigurationId, this.configurationFileDAO.getConfigurationFiles(deviceConfigurationId));
            }

            if (!configIdToConfigurationsMap.containsKey(deviceConfigurationId)) {
                // Here we keep only required properties
                Configuration configuration = new Configuration();
                configuration.setId(deviceConfigurationId);
                configuration.setName(device.getConfigName());
                configuration.setPermissive(dbConfig.getPermissive());
                if (dbConfig.getMainAppId() != null && dbConfig.getMainAppId() > 0 &&
                        dbConfig.getEventReceivingComponent() != null && dbConfig.getEventReceivingComponent().length() > 0) {
                    configuration.setQrCodeKey(dbConfig.getQrCodeKey());
                    configuration.setBaseUrl(this.configurationDAO.getBaseUrl());
                }
                configuration.setApplications(configIdToApplicationsMap.get(deviceConfigurationId));
                configuration.setFiles(configIdToFilesMap.get(deviceConfigurationId));

                configIdToConfigurationsMap.put(deviceConfigurationId, configuration);
            }

            device.setConfiguration(configIdToConfigurationsMap.get(deviceConfigurationId));
        }

        final List<DeviceView> deviceViews = devices.getItems().stream()
                .filter(d -> d.getConfigurationId() != null)
                .map(DeviceView::new)
                .collect(Collectors.toList());
        PaginatedData<DeviceView> devicesPage = new PaginatedData<>(deviceViews, devices.getTotalItemsCount());

        DeviceListView view = new DeviceListView(configIdToConfigurationsMap.values(), devicesPage);

        return Response.OK(view);

    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get the device info by number",
            notes = "Get the device info by number",
            response = DeviceListView.class
    )
    @GET
    @Path("/number/{number}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDevice(@PathParam("number") @ApiParam("Device number") String number) {
        try {
            Device device = this.deviceDAO.getAccessibleDeviceByNumber(number);
            if (device == null) {
                return Response.DEVICE_NOT_FOUND_ERROR();
            }
            DeviceView deviceView = new DeviceView(device);
            return Response.OK(deviceView);
        } catch (Exception e) {
            log.error("Cannot find device by number: " + number);
            return Response.DEVICE_NOT_FOUND_ERROR();
        }
    }

    // =================================================================================================================
    /**
     * <p>Gets the list of device ids/names matching the specified string filter for autocompletions.</p>
     *
     * @param filter a filter to be used for filtering the records.
     * @return a response with list of devices matching the specified filter.
     */
    @ApiOperation(value = "")
    @POST
    @Path("/autocomplete")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDevicesForAutocomplete(String filter) {
        try {
            List<DeviceLookupItem> devices = this.deviceDAO.findDevices(filter, 10);
            return Response.OK(devices);
        } catch (Exception e) {
            log.error("Failed to search the devices due to unexpected error. Filter: {}", filter, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Create or update device",
            notes = "Create a new device (if id is not provided) or update existing one otherwise."
    )
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateDevice(Device device) {
        try {
            final boolean canEditDevices = SecurityContext.get().hasPermission("edit_devices");

            if (!canEditDevices) {
                log.error("Unauthorized attempt to create or edit device",
                        SecurityException.onCustomerDataAccessViolation(device.getId(), "device"));
                return Response.PERMISSION_DENIED();
            }

            Device dbDevice;
            try {
                dbDevice = this.deviceDAO.getDeviceByNumber(device.getNumber());
            } catch (SecurityException e) {
                log.error("A different device with same number exists in other organization: {}", device.getNumber());
                return Response.DEVICE_EXISTS();
            }
            if (dbDevice != null && !dbDevice.getId().equals(device.getId())) {
                log.error("A different device with same number exists: {}", dbDevice);
                return Response.DEVICE_EXISTS();
            } else {
                dbDevice = this.deviceDAO.getDeviceById(device.getId());
                if (device.getId() != null) {
                    if (dbDevice != null) {
                        boolean notify = (dbDevice.getConfigurationId() != null &&
                                !dbDevice.getConfigurationId().equals(device.getConfigurationId())) ||
                                (dbDevice.getOldNumber() == null && device.getOldNumber() != null);
                        boolean configChanged = device.getConfigurationId() != null &&
                                !device.getConfigurationId().equals(dbDevice.getConfigurationId());
                        this.deviceDAO.updateDevice(device);
                        if (notify) {
                            this.pushService.notifyDeviceOnSettingUpdate(device.getId());
                        }
                        if (configChanged) {
                            // The MDMesh agent reconciles its new configuration on check-in: wake it so that happens
                            // now, not at the periodic check-in. updateDevice has committed by now.
                            this.wakeHub.wake(dbDevice.getNumber(), "commands");
                        }
                    }
                } else if (device.getIds() != null) {
                    // This is a bulk request to update configurations for selected devices: all or nothing
                    if (device.getConfigurationId() == null) {
                        return Response.ERROR(BULK_CONFIGURATION_REQUIRED);
                    }
                    // One transaction: every device moves, or none. The devices and the configuration are checked
                    // there, under the rows' locks: a refusal answers PERMISSION_DENIED below, nothing changed.
                    List<Device> changed = this.deviceDAO.updateDevicesConfiguration(device.getIds(),
                            device.getConfigurationId());
                    for (Device before : changed) {
                        this.pushService.notifyDeviceOnSettingUpdate(before.getId());
                        if (!Objects.equals(before.getConfigurationId(), device.getConfigurationId())) {
                            this.wakeHub.wake(before.getNumber(), "commands");
                        }
                    }
                } else {
                    Settings settings = new Settings();
                    if (!unsecureDAO.isSingleCustomer()) {
                        commonDAO.loadCustomerSettings(settings);
                    }
                    // Counted and inserted under the per-customer lock all device-adding paths share (agent enrollment
                    // too), so concurrent adds can't together pass the limit.
                    device.setLastUpdate(0L);
                    if (!this.deviceDAO.insertDeviceWithinLimit(device, settings.getDeviceLimit())) {
                        log.warn("New device {} not added by customer {} due to the license limit", device.getNumber(),
                                SecurityContext.get().getCurrentCustomerId().get());
                        return Response.ERROR();
                    }
                }

                return Response.OK();
            }
        } catch (SecurityException e) {
            // A device, configuration or group this user may not use: nothing was saved
            log.warn("Device save refused for user {}: {}; nothing changed",
                    SecurityContext.get().getCurrentUserName(), e.getMessage());
            return Response.PERMISSION_DENIED();
        } catch (DeviceDAO.AgentDeviceRenameException e) {
            log.warn("Device {} not renamed: {}", device.getId(), e.getMessage());
            return Response.ERROR(DeviceDAO.AgentDeviceRenameException.ERROR_KEY);
        } catch (Exception e) {
            log.error("Unexpected error when saving/creating device", e);
            return Response.ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Delete device",
            notes = "Delete an existing device"
    )
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeDevice(@PathParam("id") @ApiParam("Device ID") Integer id) {
        final boolean canEditDevices = SecurityContext.get().hasPermission("edit_devices");

        if (!(canEditDevices)) {
            log.error("Unauthorized attempt to delete device",
                    SecurityException.onCustomerDataAccessViolation(id, "device"));
            return Response.PERMISSION_DENIED();
        }

        try {
            this.deviceDAO.removeDeviceById(id);
        } catch (SecurityException e) {
            log.warn("Device {} not deleted: not accessible to user {}", id, SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        return Response.OK();
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Delete bulk devices",
            notes = "Delete multiple devices at once. Refused (PERMISSION_DENIED, nothing deleted) when the user can't "
                    + "reach one of them. Devices already gone count as deleted. When some can't be deleted, answers "
                    + "error.device.delete.partial with data {deleted, notDeleted}."
    )
    @POST
    @Path("/deleteBulk")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeBulkDevices(Device device) {
        final boolean canEditDevices = SecurityContext.get().hasPermission("edit_devices");

        if (!(canEditDevices)) {
            log.error("Unauthorized attempt to delete devices",
                    SecurityException.onCustomerDataAccessViolation(0, "device"));
            return Response.PERMISSION_DENIED();
        }

        if (device.getIds() != null) {
            // Device IDs are transferred in the "ids" parameter. The user must reach all of them, checked under the
            // rows' locks (else none is deleted); then each is deleted on its own, and any not deleted is reported
            // (DeviceDAO.removeDevicesByIds).
            DeviceDAO.BulkDeleteResult result;
            try {
                result = this.deviceDAO.removeDevicesByIds(device.getIds());
            } catch (SecurityException e) {
                log.warn("Bulk delete refused: not all devices are accessible to user {}; nothing deleted",
                        SecurityContext.get().getCurrentUserName());
                return Response.PERMISSION_DENIED();
            }
            if (!result.notDeleted.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("deleted", result.deleted);
                data.put("notDeleted", result.notDeleted);
                return Response.ERROR(BULK_DELETE_PARTIAL, data);
            }
        }
        return Response.OK();
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Set or clear device groups in bulk"
    )
    @POST
    @Path("/groupBulk")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateDeviceGroupBulk(DeviceGroupBulkRequest request) {
        final boolean canEditDevices = SecurityContext.get().hasPermission("edit_devices");

        if (!(canEditDevices)) {
            log.error("Unauthorized attempt to delete devices",
                    SecurityException.onCustomerDataAccessViolation(0, "device"));
            return Response.PERMISSION_DENIED();
        }

        if (request.getIds() != null) {
            // Device IDs are transferred in the "ids" parameter. One transaction: every device is regrouped, or none
            // (DeviceDAO checks the devices and the groups under the rows' locks). No need to notify devices: a group
            // doesn't affect a device.
            try {
                deviceDAO.updateDevicesGroups(request.getIds(), request.getGroups(), "set".equals(request.getAction()));
            } catch (SecurityException e) {
                log.warn("Bulk regroup refused: a device or group is not accessible to user {}; nothing changed",
                        SecurityContext.get().getCurrentUserName());
                return Response.PERMISSION_DENIED();
            } catch (RuntimeException e) {
                log.error("Bulk regroup failed; nothing changed", e);
                return Response.ERROR();
            }
        }
        return Response.OK();
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get device application settings",
            notes = "Get application settings set at device level"
    )
    @GET
    @Path("/{id}/applicationSettings")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDeviceApplicationSettings(@PathParam("id") @ApiParam("Device ID") Integer id) {
        try {
            final List<ApplicationSetting> deviceApplicationSettings = this.deviceDAO.getDeviceApplicationSettings(id);
            return Response.OK(deviceApplicationSettings);
        } catch (Exception e) {
            log.error("Failed to retrieve the application settings for device #{}", id, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Save device application settings",
            notes = "Save application settings set at device level"
    )
    @POST
    @Path("/{id}/applicationSettings")
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveDeviceApplicationSettings(@PathParam("id") @ApiParam("Device ID") Integer id,
                                                  List<ApplicationSetting> applicationSettings) {
        try {
            this.deviceDAO.saveDeviceApplicationSettings(id, applicationSettings);
            return Response.OK();
        } catch (Exception e) {
            log.error("Failed to save the application settings for device #{}", id, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Notify device on update",
            notes = "Sends a notification to device on application settings update",
            response = Void.class
    )
    @POST
    @Path("/{id}/applicationSettings/notify")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notifyDevicesOnUpdate(@PathParam("id") Integer id) {
        try {
            if (!SecurityContext.get().hasPermission("edit_devices")
                    || !this.deviceDAO.areAccessibleToUser(Collections.singletonList(id))) {
                log.error("Unauthorized attempt to notify device #{} by user {}", id,
                        SecurityContext.get().getCurrentUserName());
                return Response.PERMISSION_DENIED();
            }
            this.pushService.notifyDeviceOnApplicationSettingUpdate(id);
            return Response.OK();
        } catch (Exception e) {
            log.error("Failed to send notification on application settings update to device #{}", id, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Save device description",
            notes = "Updates existing device description"
    )
    @POST
    @Path("/{id}/description")
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveDeviceDescription(@PathParam("id") @ApiParam("Device ID") Integer deviceId,
                                          String newDeviceDescription) {
        try {
            final boolean canEditDeviceDescription = SecurityContext.get().hasPermission("edit_device_desc");

            if (!canEditDeviceDescription) {
                log.error("Unauthorized attempt to edit device description",
                        SecurityException.onCustomerDataAccessViolation(deviceId, "device"));
                return Response.PERMISSION_DENIED();
            }

            this.deviceDAO.updateDeviceDescription(deviceId, newDeviceDescription);
            return Response.OK();
        } catch (Exception e) {
            log.error("Failed to save the description for device #{}", deviceId, e);
            return Response.INTERNAL_ERROR();
        }
    }
}
