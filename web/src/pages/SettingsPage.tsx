import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import type { AuthUser } from '../api/auth';
import { API_BASE } from '../api/client';
import { listConfigurations, type ConfigurationSummary } from '../api/configurations';
import type { UpdateStatus } from '../api/updates';
import { useTheme, type ThemePreference } from '../ui/theme';
import { paths } from '../routes';
import {
  Button,
  Card,
  CardHeader,
  KeyValueList,
  PageHeader,
  SectionLayout,
  SectionNav,
  Segmented,
  Select,
  LinkButton,
} from '../ui';
import { ChangePasswordModal } from './settings/ChangePasswordModal';
import { applyRunning, useSupervisor } from '../data/useSupervisor';
import { CORE_ACTION_ACCESS } from '../ext/core/access';
import { CORE_SETTINGS, CoreSettingsProvider } from '../ext/core/settings';
import { useSettingsSections, type SectionMeta } from '../ext/hosts/SettingsSections';
import { usePermission } from '../ext/permissions';
import { rolloutActive, useActiveRollout, useConnection, type Connection } from './settings/hooks';
import { RolloutSection } from './settings/RolloutSection';
import { UpdatesSection } from './settings/UpdatesSection';
import { useWhatsNew } from '../whatsnew/WhatsNew';
import './SettingsPage.css';

/** Section ids, also the anchors other pages link to (ext/core/settings.tsx). */
const S = CORE_SETTINGS;

/** Per-browser default enrollment configuration (read by Enroll). The server keeps no default. */
const DEFAULT_CONFIG_KEY = 'mdmesh-default-config';
/** Build-time console version (Docker images set it; source and dev builds don't). */
const CONSOLE_VERSION = (import.meta.env.VITE_APP_VERSION as string | undefined) || null;

const THEME_OPTIONS: ReadonlyArray<{ value: ThemePreference; label: string }> = [
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
  { value: 'system', label: 'Match system' },
];

function roleLabel(u: AuthUser | null): string {
  if (!u) return '—';
  if (u.superAdmin || u.userRole?.superAdmin) return 'Super admin';
  if (u.userRole?.id === 2) return 'Admin';
  if (u.userRole?.id === 3) return 'User';
  // A custom role keeps its name when it is plain text; the seeded names are Russian (Headwind's seed).
  const name = u.userRole?.name?.trim();
  if (name && /^[\x20-\x7E]+$/.test(name)) return name;
  return u.userRole ? 'Custom role' : '—';
}

function readDefaultConfig(): string {
  try {
    return localStorage.getItem(DEFAULT_CONFIG_KEY) ?? '';
  } catch {
    return '';
  }
}

/** The address admins use: the API base when it is absolute (split deployments), else this page's origin. */
function serverAddress(): string {
  if (/^https?:\/\//i.test(API_BASE)) {
    try {
      return new URL(API_BASE).origin;
    } catch {
      return API_BASE;
    }
  }
  return window.location.origin;
}

/** "0.3.3" when server and console agree; both when they differ (a console reload pending, or a dev build). */
function versionLine(s: UpdateStatus | null): string | null {
  const server = s?.current || null;
  if (server && CONSOLE_VERSION && CONSOLE_VERSION !== server) return `server ${server} · console ${CONSOLE_VERSION}`;
  return server ?? (CONSOLE_VERSION ? `console ${CONSOLE_VERSION}` : null);
}

function ConnectionValue({ conn, onRetry }: { conn: Connection; onRetry: () => void }) {
  if (conn.state === 'checking') {
    return (
      <span className="v2-settings-conn">
        <span className="v2-settings-conn__dot is-idle" aria-hidden="true" />
        Checking…
      </span>
    );
  }
  if (conn.state === 'down') {
    return (
      <span className="v2-settings-conn">
        <span className="v2-settings-conn__dot is-alert" aria-hidden="true" />
        Unreachable
        <LinkButton onClick={onRetry}>Try again</LinkButton>
      </span>
    );
  }
  return (
    <span className="v2-settings-conn">
      <span className="v2-settings-conn__dot is-ok" aria-hidden="true" />
      Connected · {conn.ms} ms
    </span>
  );
}

/** Settings. Replaces the classic pages/SettingsPage.tsx + components/RolloutPanel.tsx. */
export function SettingsPage() {
  const { user, signOut } = useAuth();
  const { preference, setPreference } = useTheme();
  const sup = useSupervisor(5 * 60 * 1000);
  const ro = useActiveRollout();
  const { conn, recheck } = useConnection();
  const [pwOpen, setPwOpen] = useState(false);
  const [configs, setConfigs] = useState<ConfigurationSummary[] | null>(null);
  const [configsErr, setConfigsErr] = useState(false);
  const [defaultConfig, setDefaultConfig] = useState<string>(readDefaultConfig);
  // Enrolling writes to devices (ext/core/access.ts).
  const canEnroll = usePermission(CORE_ACTION_ACCESS.editDevices);

  useEffect(() => {
    let on = true;
    listConfigurations()
      .then((list) => {
        if (on) setConfigs([...list].sort((a, b) => a.name.localeCompare(b.name)));
      })
      .catch(() => {
        if (!on) return;
        setConfigs([]);
        setConfigsErr(true);
      });
    return () => {
      on = false;
    };
  }, []);

  const chooseDefault = (v: string) => {
    setDefaultConfig(v);
    try {
      if (v) localStorage.setItem(DEFAULT_CONFIG_KEY, v);
      else localStorage.removeItem(DEFAULT_CONFIG_KEY);
    } catch {
      /* storage unavailable: the choice lasts for this visit */
    }
  };

  const s = sup.status;
  const apply = s?.apply;
  const updMeta: SectionMeta = applyRunning(s)
    ? { meta: 'Updating', metaTone: 'warn' }
    : apply && (apply.phase === 'failed' || apply.phase === 'rolled_back')
      ? { meta: 'Failed', metaTone: 'alert' }
      : s?.updateAvailable
        ? { meta: '1 ready', metaTone: 'ok' }
        : {};
  // Core's sections and extensions' come from the registry (ext/core/settings.tsx); the page builds core's bodies.
  const { nav, sections } = useSettingsSections({
    [S.updates]: updMeta,
    [S.agentRollout]: rolloutActive(ro.rollout) ? { meta: 'Running', metaTone: 'warn' } : {},
  });
  const sideNav = <SectionNav items={nav} aria-label="Settings sections" />;

  // A stored default whose configuration is gone shows as "No default".
  const known = configs === null || configs.some((c) => String(c.id) === defaultConfig);
  const version = versionLine(s);
  // What's new again, beside the version, when this console has notes for it.
  const whatsNew = useWhatsNew();
  const versionValue = whatsNew?.target ? (
    <span className="v2-settings-ver">
      {version ? <span className="v2-mono">{version}</span> : null}
      <LinkButton onClick={whatsNew.open}>What’s new</LinkButton>
    </span>
  ) : (
    version
  );

  // Core's section bodies, which the contributions in ext/core/settings.tsx pick out.
  const core = {
    account: (
      <Card id={S.account} aria-labelledby={`${S.account}-title`} padding="rows" className="v2-settings-card">
        <CardHeader
          title="Account"
          titleId={`${S.account}-title`}
          spacing={10}
          actions={
            <>
              <Button onClick={() => setPwOpen(true)} disabled={!user}>
                Change password
              </Button>
              <Button onClick={() => void signOut()}>Sign out</Button>
            </>
          }
        />
        <KeyValueList
          variant="rows"
          items={[
            { key: 'name', label: 'Name', value: user?.name || user?.login || '—' },
            { key: 'login', label: 'Login', value: user?.login || '—', mono: !!user?.login },
            { key: 'email', label: 'Email', value: user?.email || '—', mono: !!user?.email },
            { key: 'role', label: 'Role', value: roleLabel(user) },
          ]}
        />
      </Card>
    ),
    server: (
      <Card id={S.server} aria-labelledby={`${S.server}-title`} padding="rows" className="v2-settings-card">
        <CardHeader title="Server" titleId={`${S.server}-title`} spacing={10} />
        <KeyValueList
          variant="rows"
          items={[
            { key: 'conn', label: 'Connection', value: <ConnectionValue conn={conn} onRetry={recheck} /> },
            { key: 'addr', label: 'Address', value: serverAddress(), mono: true },
            ...(versionValue ? [{ key: 'ver', label: 'Server and console', value: versionValue, mono: !whatsNew?.target }] : []),
            ...(s
              ? [
                  {
                    key: 'how',
                    label: 'Installed with',
                    value:
                      s.applySupported === false
                        ? 'Source or native installer, updated from the shell'
                        : 'Docker, can update itself',
                  },
                ]
              : []),
          ]}
        />
      </Card>
    ),
    updates: (
      <UpdatesSection id={S.updates} sup={sup} rolloutHref={`#${S.agentRollout}`} />
    ),
    agentRollout: (
      <RolloutSection id={S.agentRollout} ro={ro} status={s} statusLoaded={sup.loaded} />
    ),
    enrollment: (
      <Card id={S.enrollment} aria-labelledby={`${S.enrollment}-title`} gap={12} className="v2-settings-card">
        <CardHeader
          title="Enrollment"
          titleId={`${S.enrollment}-title`}
          actions={canEnroll ? <Link to={paths.enroll}>Enroll a device</Link> : undefined}
        />
        <div className="v2-settings-pref v2-settings-pref--wide">
          <div className="v2-settings-pref__text">
            <span id="v2-settings-default-config" className="v2-settings-pref__label">
              Default configuration
            </span>
            <span id="v2-settings-default-config-help" className="v2-settings-pref__help">
              {configsErr
                ? 'Could not load the configurations. Reload the page to try again.'
                : configs && configs.length === 0
                  ? 'There are no configurations yet. Once you create one, it can be the default here.'
                  : 'Pre-selected when you open Enroll. Saved in this browser only, so other admins keep their own.'}
            </span>
          </div>
          <Select
            className="v2-settings-pref__select"
            aria-labelledby="v2-settings-default-config"
            aria-describedby="v2-settings-default-config-help"
            value={known ? defaultConfig : ''}
            onChange={(e) => chooseDefault(e.target.value)}
            disabled={configs === null}
          >
            <option value="">No default</option>
            {(configs ?? []).map((c) => (
              <option key={c.id} value={String(c.id)}>
                {c.name}
              </option>
            ))}
          </Select>
        </div>
      </Card>
    ),
    appearance: (
      <Card id={S.appearance} aria-labelledby={`${S.appearance}-title`} padding="rows" className="v2-settings-card">
        <CardHeader title="Appearance" titleId={`${S.appearance}-title`} spacing={10} />
        <div className="v2-settings-pref v2-settings-pref--row">
          <div className="v2-settings-pref__text">
            <span id="v2-settings-theme" className="v2-settings-pref__label">
              Theme
            </span>
            <span id="v2-settings-theme-help" className="v2-settings-pref__help">
              Match system follows your computer&rsquo;s light or dark setting.
            </span>
          </div>
          <Segmented
            className="v2-settings-pref__control"
            aria-labelledby="v2-settings-theme"
            aria-describedby="v2-settings-theme-help"
            options={THEME_OPTIONS}
            value={preference}
            onChange={(v) => setPreference(v)}
          />
        </div>
      </Card>

    ),
  };

  return (
    <>
      <PageHeader title="Settings" subtitle="Your account, the server, updates and console preferences." />
      <CoreSettingsProvider parts={core}>
        <SectionLayout contentMax={880} contentBasis={560} nav={sideNav}>
          {sections}
        </SectionLayout>
      </CoreSettingsProvider>

      <ChangePasswordModal open={pwOpen} user={user} onClose={() => setPwOpen(false)} />
    </>
  );
}
