/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.service.EmailService;

import javax.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Who is told about a kiosk lockout on a device. A user is mailed when they
 * <ul>
 *   <li>belong to the device's customer: tenants never get mail about each other's devices, and the master customer's
 *       super-admin is not mailed about a tenant's device;</li>
 *   <li>have a single valid email address (a comma list is refused, so one user's field can't fan the mail out);</li>
 *   <li>can manage devices: a super-admin role, or a role with the {@code edit_devices} permission. View-only roles
 *       (such as Observer) are left out, because the mail asks the reader to act on the device;</li>
 *   <li>can see this device: a super-admin, a user with access to all devices, or a user limited to device groups
 *       of which the device is in at least one. This is the device list's rule
 *       ({@code DeviceMapper.USER_DEVICE_ACCESS}). Configuration access limits don't hide devices in the device
 *       list, so they are not applied here either.</li>
 * </ul>
 */
final class KioskAlertRecipients {

    private static final String MANAGE_DEVICES = "edit_devices";

    private KioskAlertRecipients() {
    }

    /** @return the addresses to mail, in the order of {@code users}, each once */
    static List<String> of(Device device, List<User> users) {
        List<String> to = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (users == null) {
            return to;
        }
        for (User user : users) {
            if (user == null || user.getCustomerId() != device.getCustomerId()
                    || !managesDevices(user) || !seesDevice(user, device)) {
                continue;
            }
            InternetAddress address = EmailService.singleAddress(user.getEmail());
            if (address != null && seen.add(address.getAddress().toLowerCase(Locale.ROOT))) {
                to.add(address.getAddress());
            }
        }
        return to;
    }

    private static boolean managesDevices(User user) {
        UserRole role = user.getUserRole();
        if (role == null) {
            return false;
        }
        return role.isSuperAdmin() || role.getPermissions() != null && role.getPermissions().stream()
                .anyMatch(p -> p != null && MANAGE_DEVICES.equalsIgnoreCase(p.getName()));
    }

    private static boolean seesDevice(User user, Device device) {
        if (user.getUserRole().isSuperAdmin() || user.isAllDevicesAvailable()) {
            return true;
        }
        Set<Integer> deviceGroups = groupIds(device.getGroups());
        return groupIds(user.getGroups()).stream().anyMatch(deviceGroups::contains);
    }

    private static Set<Integer> groupIds(List<LookupItem> groups) {
        Set<Integer> ids = new HashSet<>();
        if (groups != null) {
            for (LookupItem group : groups) {
                if (group != null) {
                    ids.add(group.getId());
                }
            }
        }
        return ids;
    }
}
