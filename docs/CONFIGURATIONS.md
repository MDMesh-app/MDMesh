# Configurations

<sub>[← README](../README.md) · [Deploy](../DEPLOY.md) · [Upgrading](UPGRADING.md) · [Operations](OPERATIONS.md) · [Users and roles](USER_MANAGEMENT.md) · **Configurations** · [Kiosk mode](KIOSK.md)</sub>

A configuration is the set of apps, kiosk rules and restrictions a group of devices follows. Each device uses one
configuration. When you change a configuration, every device that uses it applies the change and reports the result
for each setting.

## Create a configuration

1. Open **Configurations** and select **New configuration**.
2. If there are built-in templates, choose **Blank configuration**, or **Based on** a template to copy its settings
   and apps, then select **Create**. With no templates, a blank configuration opens directly.
3. Give it a **Name** (it must be unique) and optionally a **Description**.
4. Set the apps and settings (below), then select **Create configuration**.

The built-in templates (marked **Built-in**) are view only. Use **Duplicate to edit** to make an editable copy. Any
configuration can be copied with **Duplicate**; devices stay on the configuration they had. When the list has both
kinds, **All**, **Custom** (made on this server) and **Built-in** filter it.

**Delete** is refused while devices still use the configuration: move them to another one first.

## What a configuration contains

The editor has these sections: **Identity**, **Apps**, **Kiosk**, **Network**, **Security**, **Enrollment** and
**Legacy launcher settings**. A setting you change is marked **Changed** until you save.

### Apps

Select **Add apps** to pick apps from your app library (the **Uploaded** and **System** tabs). For each app, **On
devices** says what happens:

- **Install**: devices install it. Apps added to a configuration install at each device's next check-in.
- **Do not install**: the app stays listed but is not installed.
- **Remove**: the app is taken out of this configuration. It is **not** uninstalled from devices.

Under an app set to Install, **Permissions** lets you **Grant** or **Deny** each runtime permission the app asks for,
or leave it at **Default** (agent 0.5.0 or later, Device Owner). Each device applies what its Android version and the
installed app support. While an app with permission rules isn't installed yet, the device reports the
configuration as **Apply failed**.

The **Main app** and the **Show in kiosk** column (shown once Kiosk mode is on) are kiosk settings: see
[Kiosk mode](KIOSK.md).

### Kiosk

Locks devices to the apps you choose. See [Kiosk mode](KIOSK.md).

### Network and Security

Most of these settings have three states:

| State | Meaning |
|-------|---------|
| **Not managed** | MDMesh doesn't change the setting. |
| **Allow** / **On** | MDMesh allows it, or turns it on. |
| **Block** / **Off** | MDMesh blocks it, or turns it off. |

| Section | Settings |
|---------|----------|
| Network | **Wi-Fi settings**, **Wi-Fi radio** (agent 0.3.4), **Location services** (agent 0.5.0, Android 11 or later), **Bluetooth**, **Location reporting**, **Connectivity** (agent 0.4.0) |
| Security | **Camera**, **USB storage**, **Screenshots**, **Factory reset**, **Safe Mode** |

**Not managed doesn't undo a block.** Switching a setting from Block to Not managed leaves the device as it is,
blocked included. To lift a block, set the setting to **Allow**. The exceptions:

- **Wi-Fi radio** and **Location services**: Not managed lifts their lock.
- **Camera** (agent 0.5.0 or later): Not managed restores the camera after an MDMesh camera block.
- **Safe Mode** (agent 0.3.7 or later): Not managed blocks Safe Mode on a configuration with kiosk on and on any
  device in kiosk, and otherwise allows it, lifting an earlier block.

**Location reporting** (Do not track, Last known (passive), GPS (fresh fix)) sets what location MDMesh collects;
**Connectivity** (Battery-saver, Always-on, or **—** for not managed) sets how the device keeps its live connection
to the server. Each setting's help text in the console explains the details.

### Enrollment

**Setup Wi-Fi name (SSID)**, **Setup Wi-Fi password** and **Setup Wi-Fi security** are not sent to devices. The
**Enroll** page can put this Wi-Fi network in the enrollment QR code, so a new device joins it during setup. The
password is in the QR code in plain text.

Under **Setup screens**, a few settings Android applies while the device sets itself up go in the enrollment QR code:

- **Hidden network**: the setup Wi-Fi doesn't broadcast its name.
- **Skip intro screens**: hides Android's education screens (Android 8 and later). The setup wizard steps that come
  after enrollment, such as Google's terms, are up to the device maker and still show.
- **Language** and **Time zone**: set during setup. A value the list doesn't offer can be written in Extra QR
  parameters.
- **Enroll over mobile data**: lets setup download the agent on mobile data (Android 10 and later).

Hidden network, Skip intro screens, Language and Time zone are stored at the top of **Extra QR parameters**, one per
line, so a configuration still has one place for everything its QR code adds; the editor's Extra QR parameters field
shows only the other entries. It takes any other Android provisioning entry, for example:

```
"android.app.extra.PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT": true
```

Write them as JSON members, one or more per line (a trailing comma is fine), or as one JSON object. While they aren't
valid JSON, the field says so: they are saved as typed, and the Enroll page leaves them out (the settings above still
apply) until they are fixed. An entry typed here that has a setting of its own is pointed out; once that setting is
set, it wins. The **Enroll** page adds the entries to the code it shows for the configuration and sums them up under
**Setup**. Entries that name the agent, its download, its checksums or its enrollment extras are never replaced, and
the Wi-Fi chosen on the Enroll page wins over Wi-Fi entries here. Like the Wi-Fi password, they are in the QR code in
plain text.

### Legacy launcher settings

Older settings that only the Headwind launcher read (collapsed: select **Show settings**). The MDMesh agent doesn't
apply them; they stay stored with the configuration.

## Put devices on a configuration

- **New devices:** on the **Enroll** page, choose the **Configuration** before you share the QR code. The code is tied
  to that configuration ([enrolling devices](../DEPLOY.md#first-login-and-enrolling-devices)).
- **Enrolled devices:** on **Devices**, use **Change configuration** in a device's menu, or select several devices
  and choose **Change configuration**. Pick the configuration and select **Move**. The device gets the new
  configuration's settings and installs the apps it doesn't have yet.

On the Configurations page, a link shows how many devices have no configuration, and leads to them.

## How a configuration reaches devices

There is no separate Save: **Save and apply** saves the configuration and sends it to its devices.

1. The server wakes every device that uses the configuration. A device with a live connection checks in within
   seconds.
2. A device without a live connection (offline, or asleep) gets the change at its next regular check-in.
3. At check-in, the server compares the configuration with what the device last applied. If they differ, it sends
   the whole configuration once.
4. The device applies each setting and reports a result per setting: **Applied**, **Failed**, **Deferred** or **Not
   supported**.

The device keeps the last configuration it applied and applies it again after a restart or an agent update, even
without a network. Apps are installed separately from the settings, at check-in.

A configuration needs agent 0.3.0 or later. Some settings need a newer agent, as noted next to them. An older agent
reports most of them as not supported; it ignores **Connectivity** and the kiosk background image, and a
configuration with app permission rules shows as **Out of sync** on it
([agent compatibility](UPGRADING.md#agent-compatibility)).

### Sync status

Each device shows one of these:

| Status | Meaning |
|--------|---------|
| **In sync** | The device applied the current configuration. |
| **In sync (some settings deferred)** | Applied, but the device postponed some settings. It tries them again at the next configuration change, restart or agent update. |
| **Applying…** | The configuration is on its way, or being applied. |
| **Apply failed** | At least one setting failed. |
| **Out of sync** | The device has an older version of the configuration. It applies the current one at its next check-in. |
| **Not reported yet** | The device hasn't reported a configuration yet. |
| **Agent too old** | The agent is too old for configurations. Update it from **Settings → Agent rollout**. |
| **Out of date on the device** | The device asked for its configuration again, and the server re-sends it. This happens when the device turned Wi-Fi back on because it couldn't reach the server after **Wi-Fi radio** turned it off (Wi-Fi then stays on until the configuration changes), restored settings its storage damaged, went back to its settings after a factory reset that didn't start, or couldn't fully apply a configuration. The device page names the cause when the device reported it; the Wi-Fi case reports none. |
| **No configuration** | Nothing is assigned, so nothing is enforced. |

Where to look:

- **Overview**: **Configuration in sync** across the fleet, **By configuration**, and **Needs attention** for the devices
  that failed, are out of sync or have old agents.
- **Configurations**: each card shows how many of its devices are in sync, applying, on too old an agent or not
  reported yet. After a save the cards update for about two minutes.
- **The device page**, **Control** tab: the configuration's status, each setting's result, and the reason a setting
  failed or was deferred.

## When a configuration doesn't apply

- **Apply failed:** open the device's **Control** tab to see which setting failed and why, and fix the cause. If a
  setting fails, the device doesn't record the configuration as applied. On its own, the server sends the same
  configuration again an hour after the failure, so a device that can't apply it isn't sent it at every check-in, or
  right away when you save a change to the configuration.
- **Retry apply** sends the configuration again now, without waiting for that hour, and wakes the device to get it.
  A device without a live connection gets it at its next check-in. If it fails again, the hour starts over.
- **Sync now** only makes the device check in. That sends the configuration to a device that is out of sync, but
  within the hour after a failure it doesn't send it again: use Retry apply.
- **Not supported:** the device's agent or Android version can't apply that setting. Update the agent, or leave the
  setting Not managed for those devices.
- **Applying… for a long time:** the device is probably offline. It gets the configuration when it checks in.
- **An app didn't install:** use **Install configuration apps** on the device page to send every Install app again.

## Who can edit configurations

Roles decide who can open, create, copy and delete configurations, and who can move devices. An account can also be
limited to selected configurations in **Settings → Users & roles**
([users and roles](USER_MANAGEMENT.md)).
