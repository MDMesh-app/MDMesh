-- Post-seed repairs for MDMesh, shared by BOTH installers (setup.sh Docker + install-native.sh).
-- Run on EVERY install/upgrade — not just after a fresh seed — so older deployments pick up these
-- fixes too. Idempotent, and a safe no-op on an empty database: every statement's WHERE clause
-- matches nothing until the seed/migrations have created rows. Requires the schema to exist
-- (run it after Liquibase has finished first boot).

-- QR/token enrollment creates the device row on demand — that needs the settings flag ON and a
-- default configuration (devices.configurationid is NOT NULL; the init seed sets neither, and
-- without them every /agent/v1/enroll fails). COALESCE keeps a previously chosen default config.
UPDATE settings SET createnewdevices=true, newdeviceconfigurationid=COALESCE(newdeviceconfigurationid, (SELECT MIN(id) FROM configurations));

-- Remove the Headwind agent/launcher seed records. MDMesh's agent is com.mdmesh.agent and is
-- supplied through the signed release/update path, not this legacy application library. The old
-- launcher row pointed at a non-existent h-mdm.com artifact and must not be offered for install
-- or kiosk selection. Clear the old version references before deleting, because mainAppId and
-- contentAppId retain restrictive foreign keys on upgraded databases.
UPDATE configurations SET mainappid = NULL
WHERE mainappid IN (SELECT id FROM applicationversions WHERE applicationid IN
    (SELECT id FROM applications WHERE pkg IN ('com.hmdm.launcher','com.hmdm.pager','com.hmdm.phoneproxy','com.hmdm.emuilauncherrestarter')));
UPDATE configurations SET contentappid = NULL
WHERE contentappid IN (SELECT id FROM applicationversions WHERE applicationid IN
    (SELECT id FROM applications WHERE pkg IN ('com.hmdm.launcher','com.hmdm.pager','com.hmdm.phoneproxy','com.hmdm.emuilauncherrestarter')));
DELETE FROM configurationapplications WHERE applicationid IN (SELECT id FROM applications WHERE pkg IN ('com.hmdm.launcher','com.hmdm.pager','com.hmdm.phoneproxy','com.hmdm.emuilauncherrestarter'));
DELETE FROM applicationversions      WHERE applicationid IN (SELECT id FROM applications WHERE pkg IN ('com.hmdm.launcher','com.hmdm.pager','com.hmdm.phoneproxy','com.hmdm.emuilauncherrestarter'));
DELETE FROM applications             WHERE pkg IN ('com.hmdm.launcher','com.hmdm.pager','com.hmdm.phoneproxy','com.hmdm.emuilauncherrestarter');
