-- Post-seed repairs for MDMesh, shared by BOTH installers (setup.sh Docker + install-native.sh).
-- Run on EVERY install/upgrade — not just after a fresh seed — so older deployments pick up these
-- fixes too. Idempotent, and a safe no-op on an empty database: every statement's WHERE clause
-- matches nothing until the seed/migrations have created rows. Requires the schema to exist
-- (run it after Liquibase has finished first boot).

-- QR/token enrollment creates the device row on demand — that needs the settings flag ON and a
-- default configuration (devices.configurationid is NOT NULL; the init seed sets neither, and
-- without them every /agent/v1/enroll fails). COALESCE keeps a previously chosen default config.
UPDATE settings SET createnewdevices=true, newdeviceconfigurationid=COALESCE(newdeviceconfigurationid, (SELECT MIN(id) FROM configurations));

-- Older seeds gave the three demo configurations the same QR keys on every install; replace any of those still in
-- place with a random key (Liquibase changeset 26.10.13-configurations-new-qrCodeKeys does the same at server start).
-- Matches nothing once they are gone, so re-running is a no-op. gen_random_uuid() needs PostgreSQL 13+.
DO $$
BEGIN
    IF current_setting('server_version_num')::int >= 130000 THEN
        EXECUTE $q$UPDATE configurations SET qrcodekey = replace(gen_random_uuid()::text, '-', '')
            WHERE qrcodekey IN ('6fb9c8dc81483173a0c0e9f8b2e46be1', '8e6ca072ddb926a1af61578dfa9fc334',
                                '350e0fb7fc3f4b7fe61cac75fd43818f')$q$;
    ELSE
        EXECUTE $q$UPDATE configurations SET qrcodekey = md5(random()::text || clock_timestamp()::text)
            WHERE qrcodekey IN ('6fb9c8dc81483173a0c0e9f8b2e46be1', '8e6ca072ddb926a1af61578dfa9fc334',
                                '350e0fb7fc3f4b7fe61cac75fd43818f')$q$;
    END IF;
END $$;

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
