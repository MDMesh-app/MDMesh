# Kiosk mode

<sub>[← README](../README.md) · [Deploy](../DEPLOY.md) · [Upgrading](UPGRADING.md) · [Operations](OPERATIONS.md) · [Users and roles](USER_MANAGEMENT.md) · [Configurations](CONFIGURATIONS.md) · **Kiosk mode**</sub>

Kiosk mode locks a device to the apps you choose. People on the device can't open other apps, and the device comes
back to the kiosk after a restart. You turn it on in a [configuration](CONFIGURATIONS.md), so every device that uses
the configuration gets the same kiosk.

## Before you start

- The device must be enrolled as **Device Owner** (the QR enrollment does this: see
  [enrolling devices](../DEPLOY.md#first-login-and-enrolling-devices)). Without Device Owner the device reports kiosk
  as not supported.
- Any Android version MDMesh supports (7.0 or later) can run a kiosk. The **Buttons and system screens** settings
  below are applied on Android 9 and later only, except **Lock volume buttons (kiosk)**, which works on every version.
- Some settings need a newer agent. The console names the version next to each one. Roll out a new agent from
  **Settings → Agent rollout** ([Upgrading](UPGRADING.md#agent-compatibility)).

## Set up a kiosk

1. Open **Configurations** and choose a configuration, or create one with **New configuration**. A built-in
   template is view only: use **Duplicate to edit** first.
2. In **Kiosk**, turn on **Kiosk mode**.
3. In **Apps**, set each app the kiosk should show to **Install**, and tick **Show in kiosk**. Only apps set to
   Install can be shown.
4. Optionally pick a **Main app**. It is pinned full screen when it is the only app shown in kiosk, and comes first in
   a grid.
5. Set **Kiosk exit PIN** and **Kiosk exit** (see [Leaving kiosk mode](#leaving-kiosk-mode)).
6. Select **Save and apply** (**Create configuration** for a new one). If devices already use the configuration,
   the console asks you to confirm the kiosk change: select **Save and apply** again.

Devices apply the change at their next check-in, usually within seconds
([how configurations are applied](CONFIGURATIONS.md#how-a-configuration-reaches-devices)).

### One app or a grid of apps

You don't choose the kind of kiosk directly. It follows from the apps:

- **One app.** When a main app is set and it is the only app shown in kiosk, the device pins that app full screen.
- **Launcher grid.** Otherwise the device shows the **MDMesh Kiosk** launcher: a grid of the apps shown in kiosk,
  with the main app first.

If the pinned app isn't installed yet, the device shows the grid with a notice and opens the app as soon as it is
installed. Apps in the grid that aren't installed are left out.

## Kiosk settings

### Buttons and system screens

| Setting | Options | What it does |
|---------|---------|--------------|
| **Home button** | Allow / Block | The Android home button or gesture. |
| **Recents button** | Allow / Block | The app switcher. Needs Home button on. |
| **Notifications** | Allow / Block | Pulling down the notification shade. Needs Home button on. |
| **System info** | Show / Hide | The status bar (time, battery, signal). |
| **Lock screen** | Allow / Block | Whether the lock screen can appear in kiosk. |
| **Lock hardware buttons** | On / Off | Blocks the power menu. |
| **Lock volume buttons (kiosk)** | On / Off | Blocks volume changes (agent 0.3.4 or later). Android mutes the device while volume is locked, so **Ring** is silent on it. The lock is lifted when kiosk ends. |

A kiosk also blocks Safe Mode, which could otherwise bypass it, unless the configuration's **Safe Mode** setting
is **Allow** (agent 0.3.7 or later).

### Launcher look

**Background color**, **Text color** and **Icon size** (Small, Medium, Large) style the launcher grid. The background
is always solid. If the text color has less than 4.5:1 contrast with the background, devices on agent 0.5.0 or later
use a readable color instead.

**Background image** (agent 0.5.0 or later) puts a PNG, JPEG or WebP photo behind the launcher, with **Fill** or **Fit**
placement. It can be up to 10 MB and 16 megapixels. Devices download it over HTTPS only and keep the background color
if anything goes wrong.

## What the device user sees

- **One-app kiosk:** the app, full screen. If it closes or crashes, the kiosk opens it again. If it closes more than
  3 times within a minute, the device stays in kiosk and shows **App closed** with an **Open again** button, and the
  device's **Activity** in the console shows **Kiosk app keeps closing**.
- **Launcher grid:** the **MDMesh Kiosk** grid. Closing an app returns to the grid.
- **Exit:** a visible **Exit kiosk** button at the bottom, or nothing visible (see below).

### While a device is in kiosk

With agent 0.6.0 or later, while a device is in kiosk mode (from its configuration or a custom kiosk):

- **App permissions:** the kiosk's apps get the permissions they ask for (camera, microphone, location and the
  rest) without Android asking the person at the device. An app permission rule set to **Deny** in the configuration
  still wins. On Android 12 or later devices set up without letting the admin grant sensor permissions, the agent
  leaves requests for camera, microphone, location, physical activity and body sensors to Android, and denies the
  kiosk's apps the background ones (location and body sensors) they don't already have, unless an app permission rule
  covers them.
- **Kiosk apps are protected:** they can't be stopped, have their storage cleared, or be uninstalled from the
  device. An **Uninstall** from the console of an app that is still in the kiosk is refused: take the app out of the
  kiosk first.
- **Factory reset** can't be started from the device's settings, whatever the configuration's **Factory reset**
  says. The console's **Wipe** still works.
- **Installs from unknown sources** are blocked on the device. Installs from the console still work.

When kiosk mode ends, the device answers permission requests as it did before kiosk. A device set up with the QR code
on Android 12 or later grants them without asking, as during kiosk. A device that asked before kiosk (set up with adb,
or on Android 11) asks again, and the permissions granted in kiosk stay granted but can be revoked in Android settings.
The apps can be managed again, and **Factory reset** follows the configuration again. A device already in kiosk when
its agent updates to 0.6.0 gets all of this at once. An agent update while in kiosk briefly takes the device out of
lock task; from 0.6.0 it returns to the kiosk by itself within seconds.

## Leaving kiosk mode

### On the device, with the exit PIN

**Kiosk exit** sets how staff reach the PIN prompt:

- **Visible button:** an **Exit kiosk** button on the launcher.
- **Hidden gesture:** 7 quick taps (within 3 seconds) in the top-right corner of the launcher.

Both are on the MDMesh kiosk screen, not inside the kiosk's apps. In a one-app kiosk the pinned app covers that
screen, so the exit shows only when the screen does, for example on the **App closed** screen. Plan to end a one-app
kiosk from the console. The prompt asks for the **Exit PIN**. The right PIN ends kiosk on that device.

Choose a PIN only your staff know: 6 to 12 digits. With agent 0.5.0 or later the device asks for it on a number pad.
The console marks a configuration whose PIN is still the old well-known default with **Default PIN**, and one without
a PIN with **No PIN**.

**PINs from before 0.5.0.** Earlier versions took any text as the PIN. Such a PIN keeps working on devices (they show
the full keyboard for it), and a configuration that has one still saves as long as the PIN stays as it is. The console
marks it **Old PIN**: set a new one of 6 to 12 digits. A new configuration, a changed PIN and a custom kiosk's PIN must
follow the rule.

**No PIN.** With agent 0.3.6 or later, a kiosk without an exit PIN has no exit on the device at all: only the console
can end it. Older agents let anyone leave a kiosk that has no PIN.

### Wrong PINs lock the exit

With agent 0.5.0 or later, 5 wrong PINs in a row lock the exit for 1 minute. While it is locked, no PIN is checked,
not even the right one. After each lockout staff get 3 more tries; the third wrong one locks the exit again for longer:
5 minutes, then 15 minutes, then 1 hour. So it takes 14 wrong PINs, and 21 minutes of waiting, to reach the hour. From
then on every wrong PIN locks the exit for another hour: one guess an hour, so a 6-digit PIN would take decades to
guess. The right PIN resets the
count, and so does a new PIN (a configuration change or a custom kiosk). Restarting the device starts the current
lockout over, and changing the device's clock doesn't shorten it. The device stays in kiosk throughout.

Agents 0.3.6 to 0.4.x lock after 3 wrong PINs, then for longer after each further wrong PIN (30 seconds, 1 minute, 5
minutes, 15 minutes, then 1 hour). A device updated to 0.5.0 during a lockout keeps it, at least as long.

Each lockout shows as **Exit PIN locked** in the device's **Activity**. If the server can send email, administrators
who can edit the device also get an email, at most one per device per hour
([kiosk alerts](OPERATIONS.md#server)).

To let staff try again now, select **Clear PIN lockout** on the device page (**Control → Remote actions → Kiosk &
apps**; agent 0.5.0 or later). It ends the lockout and gives back the first 5 tries; the device stays in kiosk. It
needs permission to edit devices and is in the audit log. Sent while the device is offline, it runs at the device's
next check-in. To let someone out
without the PIN, end kiosk from the console.

### From the console

On a device page, open **Control → Remote actions → Kiosk & apps**:

- **Clear PIN lockout** lets staff try the exit PIN again now, after too many wrong PINs
  ([Wrong PINs lock the exit](#wrong-pins-lock-the-exit)).
- **Exit kiosk** ends kiosk on the device and restores its normal home screen. The device must be online to receive
  it. Staff can still go back in with **Return to kiosk** on the device (agent 0.5.0 or later); to prevent that,
  turn kiosk off in the configuration.
- **Return to configured kiosk** puts the device back into the kiosk its configuration defines.
- **Custom kiosk** locks the device to apps you pick now, as a one-time kiosk the configuration doesn't define. Its
  **Exit mode** can also be **Remote only**: no exit on the device. A custom kiosk always allows the home button and
  notifications.

To act on many devices, select them on **Devices** and choose **Run action**: the **Kiosk** group has Return to
configured kiosk and Custom kiosk, and **Exit kiosk** is under **Disruptive**.

To take devices out of kiosk for good, turn **Kiosk mode** off in their configuration and save. Turning it off ends
whatever kiosk is running on devices whose configuration had kiosk on, a custom kiosk included. On devices whose
configuration never had kiosk on, it changes nothing: end a custom kiosk there with Exit kiosk.

### Going back into kiosk on the device

With agent 0.5.0 or later, after the kiosk ends (with the exit PIN or from the console), the **MDMesh** app on the
device offers **Return to kiosk** while the device's configuration has kiosk on. It works offline. A kiosk with no
exit on the device asks once more before it locks, because only the console can unlock it.

## Offline devices

A kiosk keeps running without a network. The exit PIN, the lockout and Return to kiosk work offline, and events are
reported at the next check-in. A kiosk without an on-device exit stays locked until the device is back online and an
admin ends it from the console.

## Troubleshooting

| What you see | What to check |
|---|---|
| Kiosk doesn't start | The device page's configuration panel: the device must be in sync and Device Owner. The device's agent must support configuration sync (**Agent too old** means it doesn't). |
| **Return to configured kiosk** is missing or unavailable | The hint under the **Kiosk & apps** buttons says why: for example no configuration, kiosk off in the configuration, no app set to Install and shown in kiosk, or the device is already in that kiosk. |
| Grid instead of the main app | The main app isn't installed yet, or more than one app is shown in kiosk. Use **Install configuration apps** on the device page. |
| Home or notifications still available | Check **Home button** and **Notifications**. A custom kiosk always allows them. |
| **App closed** on the device | The app closed more than 3 times in a minute. Fix the app, then tap **Open again**. |
| Staff locked out by wrong PINs | Select **Clear PIN lockout** on the device page (agent 0.5.0 or later), wait for the lockout to end, or select **Exit kiosk**. |
| **Old PIN** on a configuration | Its exit PIN was saved before PINs had to be 6 to 12 digits. Devices keep using it; set a new PIN and save. |
| No lockout email | The server needs `SMTP_HOST` ([configuration reference](OPERATIONS.md#server)); the recipient needs an email address, permission to edit devices, and access to the device. |
