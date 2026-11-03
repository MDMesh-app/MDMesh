import { useState, type CSSProperties, type Key, type ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import { useTheme, type ThemePreference } from '../ui/theme';
import * as Icons from '../ui/icons';
import {
  ALLOW_BLOCK_SEGMENTS,
  Badge,
  Breadcrumb,
  BulkSelectionBar,
  Button,
  ButtonLink,
  Callout,
  Card,
  CardHeader,
  ChangedChip,
  Checkbox,
  DEVICE_KIND_LABEL,
  DeviceGlyph,
  DeviceTile,
  EmptyState,
  Eyebrow,
  Field,
  FieldRow,
  IconButton,
  Input,
  KeyValueGroup,
  KeyValueList,
  LabelledSelect,
  MetaGrid,
  Menu,
  Modal,
  Notice,
  PageHeader,
  ProgressBar,
  SearchInput,
  SectionLayout,
  SectionNav,
  Segmented,
  Select,
  StackedBar,
  StatusDot,
  StatusPill,
  StatusText,
  StickySaveBar,
  TabPanel,
  Table,
  Tabs,
  Textarea,
  Toggle,
  TriState,
  TwoState,
  batteryTone,
  deriveDeviceKind,
  useToast,
  type ButtonSize,
  type ButtonVariant,
  type BadgeSize,
  type BadgeTone,
  type Column,
  type DeviceKind,
  type DeviceKindInput,
  type SortDir,
  type StatusKind,
} from '../ui';
import { GalleryMore } from './GalleryMore';
import './GalleryPage.css';

/* ------------------------------------------------------------------------------------------------------------- */

function Section({ id, title, note, children }: { id: string; title: string; note?: ReactNode; children: ReactNode }) {
  return (
    <Card id={id} aria-labelledby={`${id}-h`} gap={16}>
      <CardHeader title={title} titleId={`${id}-h`} subtitle={note} />
      {children}
    </Card>
  );
}

function Row({ label, children }: { label?: string; children: ReactNode }) {
  return (
    <div className="gal-row">
      {label ? <span className="gal-label">{label}</span> : null}
      <div className="gal-items">{children}</div>
    </div>
  );
}

const SWATCHES = [
  'app', 'surface', 'surface-2', 'surface-sunken', 'row-selected', 'seg-selected',
  'border', 'border-strong', 'line-soft', 'input-border',
  'ink', 'text', 'text-2', 'secondary', 'muted', 'faint',
  'online', 'ok-ink', 'ok-soft', 'ok-line', 'ok-wash',
  'warn', 'warn-ink', 'warn-soft', 'warn-line',
  'alert', 'alert-ink', 'alert-soft', 'alert-line',
  'offline', 'idle-ink', 'idle-soft', 'bar-idle', 'disabled-bg',
];

const TYPE: Array<[string, string, CSSProperties]> = [
  ['display 40 Mono 500', '248', { fontFamily: 'var(--font-mono)', fontSize: 'var(--fs-display)', fontWeight: 500, letterSpacing: 'var(--ls-display)', lineHeight: 1 }],
  ['h1 28/600', 'Overview', { fontSize: 'var(--fs-h1)', fontWeight: 600, letterSpacing: 'var(--ls-h1)', lineHeight: 'var(--lh-h1)' }],
  ['stat 24 Mono 500', '226/248', { fontFamily: 'var(--font-mono)', fontSize: 'var(--fs-stat)', fontWeight: 500, lineHeight: 1.1 }],
  ['title 17/600', 'Choose what the device gets', { fontSize: 'var(--fs-title)', fontWeight: 600 }],
  ['h2 16/600', 'Needs attention', { fontSize: 'var(--fs-h2)', fontWeight: 600 }],
  ['lead 15 text-2', 'Most of the fleet is healthy.', { fontSize: 'var(--fs-lead)', color: 'var(--text-2)' }],
  ['body 14', 'Courier and site phones. Location reporting on.', {}],
  ['control 13.5', 'Kiosk settings change on 2 devices.', { fontSize: 'var(--fs-control)' }],
  ['sm 13 secondary', 'Only needed if the device can’t reach the server.', { fontSize: 'var(--fs-sm)', color: 'var(--secondary)' }],
  ['meta 12.5', '117 in sync · 9 agent too old', { fontSize: 'var(--fs-meta)', color: 'var(--warn-ink)' }],
  ['small 12 mono', 'com.northfork.menuboard', { fontSize: 'var(--fs-small)', fontFamily: 'var(--font-mono)', color: 'var(--secondary)' }],
];

const VARIANTS: ButtonVariant[] = ['primary', 'secondary', 'danger', 'subtle', 'ghost'];
const SIZES: ButtonSize[] = ['xs', 'sm', 'md', 'lg', 'xl', 'hero'];
const BADGE_TONES: BadgeTone[] = ['outline', 'neutral', 'count', 'ok', 'warn', 'alert', 'idle', 'ink', 'quiet'];
const BADGE_SIZES: BadgeSize[] = ['xs', 'sm', 'md', 'lg'];
const STATUSES = [
  'online', 'attention', 'offline', 'inSync', 'applying', 'applyFailed', 'agentTooOld', 'notReported',
  'noConfiguration', 'applied', 'failed', 'notSupported', 'done', 'pending', 'expired',
] as const satisfies ReadonlyArray<StatusKind>;
const KINDS: DeviceKind[] = ['kiosk', 'signage', 'tablet', 'phone', 'scanner'];

interface DemoDevice {
  id: number;
  number: string;
  name: string;
  kind: DeviceKind;
  model: string;
  status: 'online' | 'attention' | 'offline';
  reason: string;
  config: string;
  sync: StatusKind;
  android: string;
  battery: number | null;
  seen: string;
}

const DEVICES: DemoDevice[] = [
  { id: 1, number: 'KSK-0151', name: 'Wayfinding, level 2', kind: 'kiosk', model: 'Galaxy Tab A8', status: 'attention', reason: 'Config apply failed', config: 'Managed Launcher', sync: 'applyFailed', android: '13', battery: 100, seen: '3m ago' },
  { id: 2, number: 'TAB-0217', name: 'Warehouse scanner 4', kind: 'scanner', model: 'Zebra TC52', status: 'attention', reason: 'Battery low, not charging', config: 'Background (Agent) Mode', sync: 'inSync', android: '11', battery: 9, seen: '4m ago' },
  { id: 3, number: 'PHN-0031', name: 'Courier, route 12', kind: 'phone', model: 'Pixel 7a', status: 'online', reason: 'On mobile data', config: 'Field Phones', sync: 'applying', android: '14', battery: 63, seen: '1m ago' },
  { id: 4, number: 'TAB-0220', name: 'Front desk tablet', kind: 'tablet', model: 'Lenovo Tab M10', status: 'online', reason: 'On Wi-Fi', config: 'Unassigned', sync: 'noConfiguration', android: '13', battery: 31, seen: '6m ago' },
  { id: 5, number: 'KSK-0150', name: 'Cafeteria menu board', kind: 'signage', model: 'Xiaomi Redmi Pad', status: 'offline', reason: 'Never reported its config', config: 'MIUI (Xiaomi Redmi)', sync: 'notReported', android: '12', battery: null, seen: '2d ago' },
];

const DERIVE_EXAMPLES: Array<[string, DeviceKindInput]> = [
  ['kiosk configuration', { kioskMode: true, model: 'SM-X200', screenWidthPx: 1920, screenHeightPx: 1200, densityDpi: 240 }],
  ['kiosk on a TV box', { kioskActive: true, manufacturer: 'Xiaomi', model: 'MiBOX4' }],
  ['Zebra handheld', { manufacturer: 'Zebra Technologies', model: 'TC52' }],
  ['sw 800dp', { model: 'Lenovo TB-X606F', screenWidthPx: 1200, screenHeightPx: 1920, densityDpi: 240 }],
  ['sw 411dp', { model: 'Pixel 7a', screenWidthPx: 1080, screenHeightPx: 2400, densityDpi: 420 }],
  ['no telemetry, name', { name: 'Front desk tablet' }],
  ['admin override', { override: 'signage', model: 'Pixel 7a' }],
];

/* ------------------------------------------------------------------------------------------------------------- */

/** Dev-only component gallery (/_gallery). `?open=modal` opens the modal for screenshots. */
export default function GalleryPage() {
  const { preference, setPreference } = useTheme();
  const toast = useToast();
  const [params] = useSearchParams();

  const [seg, setSeg] = useState<string>('all');
  const [view, setView] = useState<'table' | 'cards'>('table');
  const [act, setAct] = useState<'all' | 'commands' | 'events'>('all');
  const [iconSize, setIconSize] = useState<'s' | 'm' | 'l' | undefined>(undefined);
  const [tri, setTri] = useState<boolean | null>(null);
  const [tri2, setTri2] = useState<boolean | null>(false);
  const [flag, setFlag] = useState<boolean | null>(true);
  const [exit, setExit] = useState<boolean | null>(null);
  const [on, setOn] = useState(true);
  const [off, setOff] = useState(false);
  const [tab, setTab] = useState<'control' | 'telemetry' | 'activity' | 'location'>('control');
  const [q, setQ] = useState('');
  const [checked, setChecked] = useState(true);
  const [selected, setSelected] = useState<ReadonlySet<Key>>(new Set([1, 2]));
  const [sort, setSort] = useState<{ key: string; dir: SortDir }>({ key: 'seen', dir: 'descending' });
  const [modal, setModal] = useState(params.get('open') === 'modal');
  const [count, setCount] = useState(3);

  const columns: Column<DemoDevice>[] = [
    {
      key: 'device',
      header: 'Device',
      cell: (d) => (
        <span className="gal-devcell">
          <DeviceTile kind={d.kind} tile={40} />
          <span className="gal-devcell__text">
            <span className="gal-devcell__name">{d.name}</span>
            <span className="gal-devcell__sub">
              <span className="v2-mono">{d.number}</span> · <strong>{DEVICE_KIND_LABEL[d.kind]}</strong> · {d.model}
            </span>
          </span>
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      cell: (d) => (
        <span className="gal-stack">
          <StatusPill status={d.status} />
          <span className="gal-sub">{d.reason}</span>
        </span>
      ),
    },
    {
      key: 'config',
      header: 'Configuration',
      cell: (d) => (
        <span className="gal-stack gal-stack--2">
          <span>{d.config}</span>
          <StatusText status={d.sync} />
        </span>
      ),
    },
    { key: 'android', header: 'Android', mono: true, cell: (d) => d.android },
    {
      key: 'battery',
      header: 'Battery',
      cell: (d) => (
        <span className="gal-battery">
          <ProgressBar size="mini" value={d.battery ?? 0} tone={batteryTone(d.battery ?? 100)} />
          <span className="v2-num" style={{ fontSize: 12.5, color: (d.battery ?? 100) < 15 ? 'var(--alert-ink)' : 'var(--text-2)' }}>
            {d.battery === null ? '—' : `${d.battery}%`}
          </span>
        </span>
      ),
    },
    { key: 'seen', header: 'Last seen', sortable: true, cell: (d) => <span style={{ color: 'var(--text-2)' }}>{d.seen}</span> },
    {
      key: 'actions',
      header: 'Actions',
      headerHidden: true,
      shrink: true,
      cell: (d) => (
        <span className="gal-actions">
          <IconButton label={`Sync ${d.number} now`}>
            <Icons.IconSync />
          </IconButton>
          <Menu
            label={`More actions for ${d.number}`}
            items={[
              { key: 'lock', label: 'Lock', onSelect: () => {} },
              { key: 'ring', label: 'Ring', onSelect: () => {} },
              'separator',
              { key: 'del', label: 'Delete…', danger: true, onSelect: () => {} },
            ]}
          />
        </span>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Component gallery"
        subtitle="Every component in every state. Development builds only."
        actions={
          <Segmented<ThemePreference>
            aria-label="Theme"
            value={preference}
            onChange={setPreference}
            options={[
              { value: 'light', label: 'Light' },
              { value: 'dark', label: 'Dark' },
              { value: 'system', label: 'Match system' },
            ]}
          />
        }
      />

      <Section id="tokens" title="Colour and type" note="Tokens from web/src/tokens.css; the dark values are derived from the light ones.">
        <div className="gal-swatches">
          {SWATCHES.map((s) => (
            <div key={s} className="gal-swatch">
              <span className="gal-swatch__chip" style={{ background: `var(--${s})` }} />
              <span className="gal-swatch__name">--{s}</span>
            </div>
          ))}
        </div>
        <div className="gal-type">
          {TYPE.map(([label, sample, style]) => (
            <div key={label} className="gal-row">
              <span className="gal-label">{label}</span>
              <span style={style}>{sample}</span>
            </div>
          ))}
        </div>
      </Section>

      <Section id="buttons" title="Button" note="Variants × sizes (xs sm md lg xl hero), with and without icons, disabled.">
        {VARIANTS.map((v) => (
          <Row key={v} label={v}>
            {SIZES.map((s) => (
              <Button key={s} variant={v} size={s}>
                {s}
              </Button>
            ))}
            <Button variant={v} icon={v === 'primary' ? <Icons.IconPlus /> : <Icons.IconSliders />}>
              With icon
            </Button>
            <Button variant={v} disabled>
              Disabled
            </Button>
          </Row>
        ))}
        <Row label="links / strong">
          <ButtonLink to="/enroll" variant="primary" size="hero" icon={<Icons.IconPlus />}>
            Enroll device
          </ButtonLink>
          <Button strong size="md">
            Roll out agent 0.3.3 to 9
          </Button>
          <Button variant="danger" size="lg" icon={<Icons.IconTrash size={14} />}>
            Delete…
          </Button>
          <Button variant="subtle" size="lg" icon={<Icons.IconRing />}>
            Ring
          </Button>
        </Row>
        <div style={{ maxWidth: 336 }}>
          <Button variant="primary" size="auth">
            Sign in
          </Button>
        </div>
      </Section>

      <Section id="iconbuttons" title="IconButton" note="sm 30 r8 · md 32 r9 · lg 34 r9; strong border; disabled.">
        <Row>
          <IconButton size="sm" label="Remove">
            <Icons.IconClose />
          </IconButton>
          <IconButton size="md" label="Sync now">
            <Icons.IconSync />
          </IconButton>
          <IconButton size="lg" label="Switch to dark mode">
            <Icons.IconMoon />
          </IconButton>
          <IconButton size="lg" label="Sign out">
            <Icons.IconSignOut />
          </IconButton>
          <IconButton size="md" tone="strong" label="Show password">
            <Icons.IconEye />
          </IconButton>
          <IconButton size="md" label="More" disabled>
            <Icons.IconMore />
          </IconButton>
        </Row>
      </Section>

      <Section id="segmented" title="Segmented, TriState, TwoState" note="radiogroup with roving tabindex; arrow keys select.">
        <Row label="sm">
          <Segmented
            size="sm"
            aria-label="Filter activity"
            value={act}
            onChange={setAct}
            options={[
              { value: 'all', label: 'All' },
              { value: 'commands', label: 'Commands' },
              { value: 'events', label: 'Device events' },
            ]}
          />
        </Row>
        <Row label="lg + counts">
          <Segmented
            size="lg"
            aria-label="Status"
            value={seg}
            onChange={setSeg}
            options={[
              { value: 'all', label: 'All', count: 248 },
              { value: 'online', label: 'Online', count: 211 },
              { value: 'attention', label: 'Attention', count: 14 },
              { value: 'offline', label: 'Offline', count: 23 },
            ]}
          />
          <Segmented
            size="lg"
            aria-label="View"
            value={view}
            onChange={setView}
            options={[
              { value: 'table', label: 'Table', icon: <Icons.IconTable /> },
              { value: 'cards', label: 'Cards', icon: <Icons.IconCards /> },
            ]}
          />
        </Row>
        <Row label="md, nothing set">
          <Segmented
            aria-label="Icon size"
            value={iconSize}
            onChange={setIconSize}
            options={[
              { value: 's', label: 'Small' },
              { value: 'm', label: 'Medium' },
              { value: 'l', label: 'Large' },
            ]}
          />
          <Segmented
            aria-label="Disabled example"
            disabled
            value="a"
            onChange={() => {}}
            options={[
              { value: 'a', label: 'Always-on' },
              { value: 'b', label: 'Battery-saver' },
            ]}
          />
        </Row>
        <Row label="TriState">
          <TriState aria-label="Wi-Fi settings" value={tri} onChange={setTri} />
          <TriState aria-label="Screenshots" value={tri2} onChange={setTri2} segments={ALLOW_BLOCK_SEGMENTS} />
          <TriState
            aria-label="Wi-Fi radio"
            value={null}
            onChange={() => {}}
            segments={[
              { value: null, label: 'Not managed' },
              { value: true, label: 'On' },
              { value: false, label: 'Off' },
            ]}
          />
        </Row>
        <Row label="TwoState">
          <TwoState aria-label="Home button" value={flag} onChange={setFlag} />
          <TwoState aria-label="Kiosk exit" value={exit} onChange={setExit} labels={['Visible button', 'Hidden gesture']} falseFirst />
          <TwoState aria-label="System info" value={true} onChange={() => {}} labels={['Show', 'Hide']} />
        </Row>
      </Section>

      <Section id="toggle" title="Toggle" note="role=switch; the off track carries a 1px ring (contrast).">
        <Row>
          <Toggle aria-label="Kiosk mode" checked={on} onChange={setOn} />
          <Toggle aria-label="Install updates automatically" checked={off} onChange={setOff} />
          <Toggle aria-label="Join Wi-Fi" checked={on} onChange={setOn} showState={false} />
          <Toggle aria-label="Join Wi-Fi off" checked={off} onChange={setOff} showState={false} />
          <Toggle aria-label="Disabled" checked={false} onChange={() => {}} disabled />
          <Toggle aria-label="Disabled on" checked onChange={() => {}} disabled />
        </Row>
      </Section>

      <Section id="tabs" title="Tabs" note="role=tablist; arrows move and activate.">
        <Tabs
          aria-label="Device sections"
          idBase="gal-tabs"
          value={tab}
          onChange={setTab}
          items={[
            { key: 'control', label: 'Control', icon: <Icons.IconControl /> },
            { key: 'telemetry', label: 'Telemetry', icon: <Icons.IconPulse /> },
            { key: 'activity', label: 'Activity', icon: <Icons.IconHistory />, count: 12 },
            { key: 'location', label: 'Location', icon: <Icons.IconPin /> },
          ]}
        />
        <TabPanel idBase="gal-tabs" tabKey={tab}>
          <span className="gal-sub">Panel for “{tab}”.</span>
        </TabPanel>
      </Section>

      <Section id="inputs" title="Inputs" note="Text inputs rest on the 3:1 --input-border; read-only on the page fill.">
        <div className="gal-grid">
          <Field label="Name">
            <Input defaultValue="Cafeteria Signage" />
          </Field>
          <Field label="Display name" help="What admins see in MDMesh.">
            <Input defaultValue="Route Planner" />
          </Field>
          <Field label="Package name" help="Read from the file.">
            <Input readOnly mono defaultValue="com.northfork.routes" />
          </Field>
          <Field label="SHA-256 checksum" help="✓ Worked out from the uploaded file." helpTone="ok">
            <Input readOnly mono defaultValue="9f2c7a41e0b3…4d18c6e2" />
          </Field>
          <Field label="Type it again" error="The passwords don’t match.">
            <Input type="password" size="lg" defaultValue="harbour-lantern-4" />
          </Field>
          <Field label="Exit PIN">
            <Input type="password" size="sm" mono tone="warn" defaultValue="12345678" />
          </Field>
          <Field label="Description">
            <Textarea defaultValue="Menu boards pinned to Menu Board. Status bar and buttons hidden." />
          </Field>
          <Field label="Network name" size="sm">
            <Input defaultValue="Lobby-Staff" />
          </Field>
          <Field label="Disabled">
            <Input disabled defaultValue="Not editable" />
          </Field>
        </div>
        <Row label="Select xs sm md lg">
          <Select size="xs" aria-label="xs" defaultValue="Install">
            <option>Install</option>
            <option>Remove</option>
            <option>Do not install</option>
          </Select>
          <Select size="sm" aria-label="sm">
            <option>Menu Board 1.8.2</option>
          </Select>
          <Select size="md" aria-label="md">
            <option>Field Phones</option>
          </Select>
          <Select size="lg" aria-label="lg">
            <option>Field Phones (default)</option>
          </Select>
        </Row>
        <Row label="Toolbar">
          <SearchInput className="gal-search" aria-label="Search devices" placeholder="Search name, number, serial, model" value={q} onChange={setQ} />
          <LabelledSelect label="Type">
            <option>All</option>
            <option>Kiosk</option>
          </LabelledSelect>
          <LabelledSelect label="Configuration">
            <option>All</option>
          </LabelledSelect>
        </Row>
        <Row label="Checkbox">
          <Checkbox aria-label="Unchecked" />
          <Checkbox aria-label="Checked" checked={checked} onChange={(e) => setChecked(e.target.checked)} />
          <Checkbox aria-label="Indeterminate" indeterminate />
          <Checkbox aria-label="Card size" size={17} defaultChecked />
          <Checkbox label="Shown" defaultChecked />
          <Checkbox label="Not installed" disabled />
        </Row>
      </Section>

      <Section id="rows" title="FieldRow" note="Editor and Settings setting rows, with Changed chip, eyebrow and a warning below.">
        <div>
          <FieldRow label="Kiosk mode" help="When off, the settings below are ignored and the device uses its normal launcher.">
            {({ labelId }) => <Toggle aria-labelledby={labelId} checked={on} onChange={setOn} />}
          </FieldRow>
          <div className="gal-eyebrowrow">
            <Eyebrow help="Only applies in kiosk mode">Buttons and system screens</Eyebrow>
          </div>
          <FieldRow label="Home button" help="The Android home button or gesture." adornment={<ChangedChip />}>
            {({ labelId }) => <TwoState aria-labelledby={labelId} value={flag} onChange={setFlag} />}
          </FieldRow>
          <FieldRow label="Wi-Fi settings" help="Block locks the Wi-Fi settings so users cannot change networks.">
            {({ labelId }) => <TriState aria-labelledby={labelId} value={tri} onChange={setTri} />}
          </FieldRow>
          <FieldRow
            label="Exit PIN"
            help="Staff enter this on the device to leave kiosk mode."
            below={
              <Callout tone="warn" icon={<Icons.IconWarning size={16} />}>
                This is still the well-known default PIN. Anyone who knows it can leave kiosk mode.
              </Callout>
            }
          >
            <Input type="password" size="sm" mono tone="warn" defaultValue="12345678" aria-label="Exit PIN" />
          </FieldRow>
          <FieldRow label="Icon size" help="How large app icons appear." disabled>
            {({ labelId }) => (
              <Segmented
                aria-labelledby={labelId}
                value={iconSize}
                onChange={setIconSize}
                options={[
                  { value: 's', label: 'Small' },
                  { value: 'm', label: 'Medium' },
                  { value: 'l', label: 'Large' },
                ]}
              />
            )}
          </FieldRow>
        </div>
      </Section>

      <Section id="badges" title="Badge" note="Tones × sizes.">
        {BADGE_SIZES.map((s) => (
          <Row key={s} label={s}>
            {BADGE_TONES.map((t) => (
              <Badge key={t} tone={t} size={s}>
                {t}
              </Badge>
            ))}
          </Row>
        ))}
        <Row label="in use">
          <Badge tone="neutral" icon={<Icons.IconLockSmall />}>
            Built-in
          </Badge>
          <Badge tone="outline" size="md">
            Kiosk
          </Badge>
          <Badge tone="ok" size="md" icon={<Icons.IconCheck size={12} strokeWidth={2} />}>
            Signature verified
          </Badge>
          <Badge tone="warn" size="md">
            Missing
          </Badge>
          <Badge tone="warn" size="xs">
            2× hardware ID
          </Badge>
          <Badge tone="quiet" size="xs">
            Later
          </Badge>
          <Badge tone="neutral" size="md" mono square>
            .apks
          </Badge>
          <Badge tone="ok" size="lg">
            ✓ File
          </Badge>
          <Badge tone="ink" size="lg">
            2 Check details
          </Badge>
          <Badge tone="alert" size="lg">
            1 config failed
          </Badge>
        </Row>
      </Section>

      <Section id="status" title="StatusPill, StatusText, StatusDot" note="Shape-coded: circle ok, diamond attention, square failed, hollow ring offline.">
        <Row label="pill">
          {STATUSES.map((s) => (
            <StatusPill key={s} status={s} />
          ))}
        </Row>
        <Row label="pill, no dot">
          <StatusPill status="failed" dot={false} />
          <StatusPill status="done" dot={false} />
          <StatusPill status="expired" dot={false} />
        </Row>
        <Row label="sync">
          {(['inSync', 'applying', 'applyFailed', 'agentTooOld', 'notReported', 'noConfiguration'] as const).map((s) => (
            <StatusText key={s} status={s} />
          ))}
        </Row>
        <Row label="reason">
          <StatusText variant="reason" tone="alert">Configuration failed to apply</StatusText>
          <StatusText variant="reason" tone="warn">Battery 9%, not charging</StatusText>
          <StatusText variant="reason" tone="idle">Offline, never reported config</StatusText>
        </Row>
        <Row label="meta">
          <StatusText variant="meta" status="attention" />
          <StatusText variant="meta" status="online" />
        </Row>
        <Row label="dots">
          {(['ok', 'warn', 'alert', 'idle'] as const).map((t) =>
            ([6, 7, 8] as const).map((s) => <StatusDot key={`${t}${s}`} tone={t} size={s} />),
          )}
        </Row>
      </Section>

      <Section id="cards" title="Card, Notice, Callout">
        <div className="gal-cards">
          <Card>
            <CardHeader title="Server" actions={<a href="#cards">Manage</a>} />
            <p className="gal-sub" style={{ marginTop: 12 }}>Default card: 20×24, r16, flat.</p>
          </Card>
          <Card tone="alert">
            <CardHeader title="Configuration didn’t fully apply" subtitle="Alert tone" />
          </Card>
          <Card tone="dashed">
            <CardHeader title="Headwind launcher only" subtitle="Dashed tone" actions={<Button size="lg">Show settings</Button>} />
          </Card>
          <Card tone="selected">
            <CardHeader title="Selected card" subtitle="1.5px ink outline" />
          </Card>
        </div>
        <Notice
          aria-label="Needs attention"
          icon={<Icons.IconWarning />}
          action={
            <Button strong size="md">
              Roll out agent 0.3.3 to 9
            </Button>
          }
        >
          <strong>14 need attention</strong>
          <span className="gal-items">
            <Badge tone="alert" size="lg">1 config failed</Badge>
            <Badge tone="warn" size="lg">9 agent out of date</Badge>
          </span>
        </Notice>
        <Notice tone="alert" icon={<Icons.IconAlert />} aria-label="Alert notice">
          <span>
            <strong>Menu Board has no SHA-256 checksum.</strong> Add the checksum before deploying it again.
          </span>
        </Notice>
        <div className="gal-grid">
          <Callout tone="info" role="status" icon={<Icons.IconClock size={16} />}>
            Your session expired. Sign in again to pick up where you left off.
          </Callout>
          <Callout tone="warn" icon={<Icons.IconWarning size={16} />}>
            The Wi-Fi password is written into the QR code.
          </Callout>
          <Callout tone="alert" icon={<Icons.IconAlert size={16} />}>
            The server rejected the change.
          </Callout>
          <Callout tone="ok" icon={<Icons.IconCheck size={16} />}>
            Read successfully · split bundle with 3 parts · 41.0 MB
          </Callout>
        </div>
      </Section>

      <Card padding="list" clip aria-labelledby="gal-table-h">
        <CardHeader inset title="Table" titleId="gal-table-h" subtitle="Selection, sortable header, glyph tiles, pills, battery bars." />
        <BulkSelectionBar count={selected.size} total={DEVICES.length} onSelectAll={() => setSelected(new Set(DEVICES.map((d) => d.id)))} onClear={() => setSelected(new Set())}>
          <Button icon={<Icons.IconPlay />}>Run action</Button>
          <Button variant="danger" icon={<Icons.IconTrash />}>
            Delete
          </Button>
        </BulkSelectionBar>
        <Table
          aria-label="Devices"
          columns={columns}
          rows={DEVICES}
          rowKey={(d) => d.id}
          minWidth={960}
          selection={{
            selected,
            rowLabel: (d) => `Select ${d.number}`,
            onToggle: (k) =>
              setSelected((s) => {
                const n = new Set(s);
                if (n.has(k)) n.delete(k);
                else n.add(k);
                return n;
              }),
            onToggleAll: (all) => setSelected(all ? new Set(DEVICES.map((d) => d.id)) : new Set()),
          }}
          sort={{
            ...sort,
            onSort: (key) => setSort((s) => ({ key, dir: s.key === key && s.dir === 'descending' ? 'ascending' : 'descending' })),
          }}
        />
      </Card>

      <Card padding="list" clip aria-label="Empty table">
        <Table aria-label="Empty" columns={columns.slice(0, 3)} rows={[]} rowKey={(d) => d.id} empty="No devices match these filters." />
      </Card>

      <Section id="secnav" title="SectionNav" note="Scroll-spy by default; here controlled. Real #anchors, aria-current=location.">
        <div className="gal-secnav">
          <SectionLayout
            nav={
              <SectionNav
                aria-label="Sections"
                activeId="kiosk"
                items={[
                  { id: 'g-identity', label: 'Identity' },
                  { id: 'g-apps', label: 'Apps', meta: '3' },
                  { id: 'kiosk', label: 'Kiosk', meta: '2 changed' },
                  { id: 'g-updates', label: 'Updates', meta: '1 ready', metaTone: 'ok' },
                  { id: 'g-rollout', label: 'Agent rollout', meta: 'Running', metaTone: 'warn' },
                ]}
              />
            }
          >
            <Card>
              <CardHeader title="Kiosk" subtitle="Lock the screen to the apps marked “Show in kiosk”." />
            </Card>
          </SectionLayout>
        </div>
      </Section>

      <Section id="savebar" title="StickySaveBar" note="Sticky 16px from the bottom of the content column.">
        <div className="gal-savebar">
          <StickySaveBar
            count={count}
            message="Kiosk settings change on 2 devices. Their launcher restarts on the next sync."
            onDiscard={() => setCount(0)}
            onSave={() => setCount(0)}
          />
          {count === 0 ? (
            <Button size="sm" onClick={() => setCount(3)}>
              Show the bar again
            </Button>
          ) : null}
        </div>
      </Section>

      <Section id="kv" title="KeyValueList, MetaGrid">
        <div className="gal-cards">
          <Card padding="rail">
            <KeyValueGroup
              title="Status"
              items={[
                { label: 'Battery', value: '100% · charging' },
                { label: 'Kiosk', value: 'Running · Wayfinder 2.4.1' },
              ]}
            />
            <KeyValueGroup
              title="Network"
              last
              items={[
                { label: 'Local IP', value: '10.20.4.51', mono: true },
                { label: 'Public IP', value: '203.0.113.24', mono: true },
              ]}
            >
              <a href="#kv" style={{ paddingTop: 12, fontSize: 13, fontWeight: 600 }}>
                Open location in Maps ↗
              </a>
            </KeyValueGroup>
          </Card>
          <Card>
            <CardHeader title="Account" />
            <KeyValueList
              variant="rows"
              items={[
                { label: 'Name', value: 'Fleet Admin' },
                { label: 'Login', value: 'admin', mono: true },
                { label: 'Role', value: 'Super admin' },
              ]}
            />
          </Card>
          <Card gap={14}>
            <KeyValueList
              variant="facts"
              items={[
                { label: 'Gives', value: <strong style={{ fontWeight: 500 }}>Field Phones</strong> },
                { label: 'Use', value: 'One device, then it stops working' },
                { label: 'Expires', value: '14 Oct 2026, 16:20 (in 24 hours)' },
              ]}
            />
            <MetaGrid
              boxed
              columns="minmax(0, 1.6fr) repeat(2, minmax(0, 1fr))"
              cells={[
                { label: 'Main app', value: 'Menu Board 1.8.2' },
                { label: 'Apps', value: '1', mono: true },
                { label: 'Devices', value: '2', mono: true },
              ]}
            />
          </Card>
          <Card padding="none" clip>
            <div style={{ padding: 16 }} className="gal-sub">
              Device card footer strip
            </div>
            <MetaGrid
              cells={[
                { label: 'Android', value: '13', mono: true },
                {
                  label: 'Battery',
                  value: (
                    <span className="gal-battery">
                      <span className="v2-num">97%</span>
                      <ProgressBar size="micro" value={97} tone="neutral" />
                    </span>
                  ),
                },
                { label: 'Last seen', value: '2m ago' },
              ]}
            />
          </Card>
        </div>
      </Section>

      <Section id="bars" title="StackedBar, ProgressBar">
        <StackedBar
          variant="fleet"
          label="211 online, 14 need attention, 23 offline"
          segments={[
            { value: 211, tone: 'ok' },
            { value: 14, tone: 'warn' },
            { value: 23, tone: 'idle' },
          ]}
        />
        <StackedBar
          segments={[
            { value: 39, tone: 'ok' },
            { value: 1, tone: 'warn' },
            { value: 1, tone: 'alert' },
          ]}
          label="39 in sync, 1 applying, 1 failed"
        />
        <StackedBar track segments={[{ value: 1, tone: 'ok' }, { value: 0, tone: 'warn' }, { value: 2, tone: 'pending' }]} label="1 in sync, 2 not reported" />
        <ProgressBar value={96.4} label="Agent 0.3.3 on 239 of 248" />
        <ProgressBar value={3.2} tone="warn" label="Agent 0.2.9 on 8 of 248" />
        <Row label="battery">
          {[9, 22, 63, 100].map((b) => (
            <span key={b} className="gal-battery">
              <ProgressBar size="mini" value={b} tone={batteryTone(b)} />
              <span className="v2-num" style={{ fontSize: 12.5 }}>
                {b}%
              </span>
            </span>
          ))}
        </Row>
      </Section>

      <Section id="empty" title="EmptyState">
        <EmptyState
          icon={<Icons.IconDevices />}
          title="No devices yet"
          action={
            <ButtonLink to="/enroll" variant="primary" size="lg" icon={<Icons.IconPlus />}>
              Enroll device
            </ButtonLink>
          }
        >
          Enroll a device with a QR code or a token. It shows up here after its first check-in.
        </EmptyState>
      </Section>

      <Section id="overlays" title="Modal, Toast" note="Escape closes the modal; focus is trapped and restored.">
        <Row>
          <Button onClick={() => setModal(true)}>Open modal</Button>
          <Button onClick={() => toast.push('ok', 'Command sent', 'KSK-0151 will sync on its next check-in.')}>Toast: ok</Button>
          <Button onClick={() => toast.push('err', 'Couldn’t save', 'The server rejected the change.')}>Toast: error</Button>
        </Row>
        <Modal
          open={modal}
          onClose={() => setModal(false)}
          title="Change configuration"
          description="2 devices will switch on their next sync."
          footer={
            <>
              <Button size="xl" onClick={() => setModal(false)}>
                Cancel
              </Button>
              <Button size="xl" variant="primary" onClick={() => setModal(false)}>
                Change configuration
              </Button>
            </>
          }
        >
          <Field label="Configuration">
            <Select>
              <option>Field Phones</option>
              <option>Managed Launcher</option>
            </Select>
          </Field>
          <Callout tone="warn" icon={<Icons.IconWarning size={16} />}>
            Kiosk settings change on these devices. Their launcher restarts.
          </Callout>
        </Modal>
      </Section>

      <Section id="glyphs" title="DeviceGlyph" note="Five types, tiles 38/40/44/60, and the type derivation.">
        <Row label="22px">
          {KINDS.map((k) => (
            <span key={k} className="gal-glyph">
              <DeviceGlyph kind={k} title={DEVICE_KIND_LABEL[k]} />
              <span className="gal-sub">{k}</span>
            </span>
          ))}
        </Row>
        <Row label="tiles">
          {([38, 40, 44, 60] as const).map((t) => (
            <DeviceTile key={t} kind={KINDS[t % 5]} tile={t} />
          ))}
        </Row>
        <ul className="gal-derive">
          {DERIVE_EXAMPLES.map(([label, input]) => {
            const k = deriveDeviceKind(input);
            return (
              <li key={label}>
                <DeviceGlyph kind={k} size={18} />
                <span>{label}</span>
                <span className="gal-sub">→ {k}</span>
              </li>
            );
          })}
        </ul>
      </Section>

      <Section id="header" title="Breadcrumb">
        <Breadcrumb items={[{ label: 'Devices', to: '/devices' }, { label: 'KSK-0151', mono: true }]} />
        <Breadcrumb items={[{ label: 'Configurations', to: '/configurations' }, { label: 'Cafeteria Signage' }]} />
      </Section>

      <GalleryMore />

      <Section id="icons" title="Icons" note="web/src/ui/icons.tsx (paths from the boards).">
        <div className="gal-icons">
          {Object.entries(Icons)
            .filter(([name]) => name.startsWith('Icon'))
            .map(([name, Icon]) => {
              const C = Icon as (p: { size?: number }) => ReactNode;
              return (
                <span key={name} className="gal-icon">
                  <C />
                  <span>{name.replace(/^Icon/, '')}</span>
                </span>
              );
            })}
        </div>
      </Section>
    </>
  );
}
