// Second half of the dev gallery: the shared components added later.
import { useState, type ReactNode } from 'react';
import * as Icons from '../ui/icons';
import { QrPanel } from '../ui/QrPanel';
import {
  ActivityList,
  ActivityRow,
  AppTile,
  AttentionList,
  BatteryBar,
  BulkSelectionBar,
  Button,
  Card,
  CardHeader,
  Chip,
  ColourInput,
  CommandList,
  ConfigSync,
  CopyField,
  CountMeta,
  DeviceIdentity,
  Field,
  FieldRow,
  FileDropZone,
  FileReadPanel,
  FleetSummary,
  LinkTabs,
  Menu,
  NumberedSteps,
  PageErrorBoundary,
  Pagination,
  PasswordInput,
  PresenceStatus,
  RelativeTime,
  RolloutSteps,
  RuleList,
  ShowMore,
  StatCell,
  StatGrid,
  Stepper,
  Tabs,
  type CardPadding,
  type MenuEntry,
} from '../ui';

const now = Date.now();
const min = 60_000;

function Section({ id, title, note, children, padding }: { id: string; title: string; note?: ReactNode; children: ReactNode; padding?: CardPadding }) {
  return (
    <Card id={id} aria-labelledby={`${id}-h`} gap={16} padding={padding}>
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

function Boom(): ReactNode {
  throw new Error('Demo crash: this page threw while rendering.');
}

const MENU: MenuEntry[] = [
  { key: 'lock', label: 'Lock', icon: <Icons.IconLock size={15} />, onSelect: () => {} },
  { key: 'ring', label: 'Ring', icon: <Icons.IconRing size={15} />, onSelect: () => {} },
  { key: 'cfg', label: 'Change configuration…', icon: <Icons.IconSliders />, onSelect: () => {} },
  { key: 'off', label: 'Exit kiosk', icon: <Icons.IconExitKiosk size={15} />, onSelect: () => {}, disabled: true },
  'separator',
  { key: 'del', label: 'Delete…', icon: <Icons.IconTrash />, onSelect: () => {}, danger: true },
];

export function GalleryMore() {
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [selected, setSelected] = useState(2);
  const [pressed, setPressed] = useState(false);
  const [qr, setQr] = useState<'qr' | 'token'>('qr');
  const [colour, setColour] = useState<string | null>('#15181D');
  const [colour2, setColour2] = useState<string | null>(null);
  const [pw, setPw] = useState('harbour-lantern-42');
  const [pw2, setPw2] = useState('harbour-lantern-4');
  const [crash, setCrash] = useState(false);
  const [file, setFile] = useState<string | null>(null);

  return (
    <>
      <Section id="menu" title="Menu" note="“…” menus: Enter / Space / ArrowDown open, arrows and type-ahead move, Esc closes and returns focus.">
        <Row label="icon md / sm">
          <Menu label="More actions for KSK-0151" items={MENU} />
          <Menu label="More for Cafeteria Signage" iconSize="sm" items={MENU} />
        </Row>
        <Row label="button">
          <Menu label="More actions" trigger="button" items={MENU} />
          <Menu label="Actions" trigger="button" buttonText="Actions" buttonSize="md" items={MENU} align="start" />
        </Row>
      </Section>

      <Card padding="list" clip aria-labelledby="gal-pager-h">
        <CardHeader inset title="BulkSelectionBar, Pagination" titleId="gal-pager-h" actions={<CountMeta shown={6} total={14} to="/devices" />} />
        <BulkSelectionBar count={selected} total={248} onSelectAll={() => setSelected(248)} onClear={() => setSelected(0)}>
          <Button icon={<Icons.IconPlay />}>Run action</Button>
          <Button icon={<Icons.IconSliders />}>Change configuration</Button>
          <Button variant="danger" icon={<Icons.IconTrash />}>
            Delete
          </Button>
        </BulkSelectionBar>
        <div style={{ padding: '16px' }} className="gal-sub">
          (table rows)
        </div>
        <Pagination page={page} pageSize={size} total={248} onPageChange={setPage} pageSizeOptions={[10, 50, 100]} onPageSizeChange={setSize} noun="devices" />
      </Card>
      <BulkSelectionBar variant="standalone" count={2} onClear={() => {}}>
        <Button icon={<Icons.IconPlay />}>Run action</Button>
      </BulkSelectionBar>
      <Pagination variant="plain" page={1} pageSize={9} total={248} onPageChange={() => {}} suffix="· needs attention first" />
      <ShowMore onClick={() => {}} />

      <Section id="devcells" title="Device cells" note="DeviceIdentity, PresenceStatus, ConfigSync, BatteryBar.">
        <Row label="row">
          <DeviceIdentity to="/devices/101" name="Wayfinding, level 2" number="KSK-0151" kind="kiosk" model="Galaxy Tab A8" />
          <DeviceIdentity name="Front desk tablet" number="TAB-0220" kind="tablet" model="Lenovo Tab M10" dupCount={2} />
        </Row>
        <Row label="attention / card">
          <DeviceIdentity variant="attention" name="Warehouse scanner 4" number="TAB-0217" kind="scanner" />
          <div style={{ width: 300 }}>
            <DeviceIdentity variant="card" name="Lobby check-in, Building A with a long name" number="KSK-0142" kind="kiosk" model="Galaxy Tab A8" />
          </div>
        </Row>
        <Row label="presence">
          <PresenceStatus status="attention" reason="Battery low, not charging" />
          <PresenceStatus status="online" reason="Kiosk running" />
          <PresenceStatus status="offline" reason="Last seen on Wi-Fi “WH-North”" />
          <div style={{ width: 260 }}>
            <PresenceStatus layout="inline" status="attention" reason="Agent 0.2.9 out of date and more" />
          </div>
        </Row>
        <Row label="config">
          <ConfigSync name="Managed Launcher" to="/configurations/2" sync="applyFailed" />
          <ConfigSync name="Unassigned" sync="noConfiguration" />
          <div style={{ width: 360 }}>
            <ConfigSync layout="row" name="Background (Agent) Mode" sync="inSync" />
          </div>
        </Row>
        <Row label="battery">
          <BatteryBar percent={9} />
          <BatteryBar percent={31} charging />
          <BatteryBar percent={100} />
          <BatteryBar percent={null} />
          <BatteryBar layout="card" percent={97} />
          <BatteryBar layout="card" percent={9} />
        </Row>
      </Section>

      <Section id="chips" title="Chip, LinkTabs, Tabs sizes">
        <Row label="chips">
          <Chip tone="alert" to="/devices?filter=failed">
            1 config failed
          </Chip>
          <Chip to="/devices?filter=agent">9 agent out of date</Chip>
          <Chip onClick={() => setPressed((p) => !p)} pressed={pressed}>
            4 battery low
          </Chip>
          <Chip tone="outline" onClick={() => {}}>
            Outline
          </Chip>
        </Row>
        <Row label="LinkTabs tab">
          <LinkTabs
            aria-label="App source"
            items={[
              { key: 'lib', to: '/_gallery', label: 'Library', icon: <Icons.IconLibrary />, count: 8, active: true },
              { key: 'apk', to: '/_gallery?source=custom', label: 'Custom APK', icon: <Icons.IconUpload />, active: false },
              { key: 'fd', to: '/_gallery?source=fdroid', label: 'F-Droid', icon: <Icons.IconAndroid />, active: false },
              { key: 'play', to: '#', label: 'Play Store', disabled: true, badge: 'Later' },
            ]}
          />
        </Row>
        <Row label="LinkTabs lg">
          <LinkTabs
            size="lg"
            aria-label="View"
            items={[
              { key: 't', to: '/_gallery', label: 'Table', icon: <Icons.IconTable />, active: true },
              { key: 'c', to: '/_gallery?view=cards', label: 'Cards', icon: <Icons.IconCards />, active: false },
            ]}
          />
        </Row>
        <Row label="Tabs md">
          <Tabs
            size="md"
            aria-label="Method"
            value={qr}
            onChange={setQr}
            items={[
              { key: 'qr', label: 'QR code' },
              { key: 'token', label: 'Token' },
            ]}
          />
        </Row>
      </Section>

      <Card padding="list" clip aria-labelledby="gal-attn-h">
        <CardHeader inset title="AttentionList" titleId="gal-attn-h" actions={<CountMeta shown={3} total={14} to="/devices" />} />
        <AttentionList
          items={[
            {
              key: 1,
              device: <DeviceIdentity variant="attention" to="/devices/101" name="Wayfinding, level 2" number="KSK-0151" kind="kiosk" />,
              reason: 'Configuration failed to apply',
              tone: 'alert',
              when: <RelativeTime value={now - 3 * min} />,
              action: <Button size="sm">Retry apply</Button>,
            },
            {
              key: 2,
              device: <DeviceIdentity variant="attention" name="Courier, route 14" number="PHN-0032" kind="phone" />,
              reason: 'Agent 0.2.9 is out of date',
              tone: 'warn',
              when: <RelativeTime value={now - min} />,
              action: <Button size="sm">Update agent</Button>,
            },
            {
              key: 3,
              device: <DeviceIdentity variant="attention" name="Cafeteria menu board" number="KSK-0150" kind="signage" />,
              reason: 'Offline, never reported config',
              tone: 'idle',
              when: <RelativeTime value={now - 2 * 24 * 60 * min} />,
              action: <Button size="sm">Open device</Button>,
            },
          ]}
        />
      </Card>

      <Card padding="list" clip aria-labelledby="gal-act-h">
        <CardHeader inset title="ActivityList" titleId="gal-act-h" />
        <ActivityList aria-label="Recent activity">
          <ActivityRow icon={<Icons.IconAlert size={15} />} tone="alert" who="KSK-0151" whoTo="/devices/101" what="failed to apply its configuration" detail="USB storage needs device owner" when={<RelativeTime value={now - 3 * min} />} />
          <ActivityRow icon={<Icons.IconCheck size={15} />} tone="ok" who="KSK-0151" what="synced" detail="Requested by admin" when={<RelativeTime value={now - 3 * min} />} />
          <ActivityRow icon={<Icons.IconBattery />} tone="warn" who="TAB-0217" what="reported low battery" detail="9%" when={<RelativeTime value={now - 4 * min} />} />
          <ActivityRow icon={<Icons.IconNetwork />} tone="idle" who="PHN-0031" what="changed network" detail="Wi-Fi to mobile data" when={<RelativeTime value={now - 52 * min} />} />
        </ActivityList>
      </Card>

      <Card padding="list" clip aria-labelledby="gal-cmd-h">
        <CardHeader inset spacing={8} title="CommandList" titleId="gal-cmd-h" actions={<a href="#gal-cmd-h">All activity</a>} />
        <CommandList
          items={[
            { key: 1, label: 'Apply configuration', detail: 'USB storage: permission denied', status: 'failed', when: <RelativeTime value={now - 3 * min} /> },
            { key: 2, label: 'Sync now', detail: 'Sent by admin', status: 'done', when: <RelativeTime value={now - 3 * min} /> },
            { key: 3, label: 'Lock device', detail: 'Not delivered within 24h', status: 'expired', when: <RelativeTime style="long" value={now - 26 * 60 * min} /> },
          ]}
        />
      </Card>

      <FleetSummary
        total={248}
        legend={[
          { key: 'on', label: 'Online', count: 211, tone: 'ok', to: '/devices?status=online' },
          { key: 'att', label: 'Need attention', count: 14, tone: 'warn', to: '/devices?status=attention' },
          { key: 'off', label: 'Offline', count: 23, tone: 'idle', to: '/devices?status=offline' },
        ]}
        stats={
          <StatGrid>
            <StatCell label="Config in sync" value="226" of="/248" notes={[{ text: '1 failed', tone: 'alert' }, { text: '12 applying', tone: 'warn' }]} />
            <StatCell label="Agent 0.3.3" value="239" of="/248" notes={[{ text: '9 on older versions', tone: 'warn' }, { text: '1 rollout running' }]} />
            <StatCell label="Commands today" value="64" notes={[{ text: '3 failed', tone: 'alert' }, { text: '5 waiting for device' }]} />
          </StatGrid>
        }
      />

      <Section id="steps" title="Stepper, RolloutSteps, NumberedSteps, numbered header" padding="roomy">
        <Stepper steps={['File', 'Check details', 'Add']} current={1} />
        <RolloutSteps
          steps={[
            { key: 1, step: 'Step 1', name: 'Canary', state: 'In progress', stateTone: 'warn', active: true, done: 1, busy: 1, total: 2, note: 'PHN-0027 updated · PHN-0032 installing' },
            { key: 2, step: 'Step 2', name: 'Everyone else', state: 'Waiting', done: 0, total: 7, note: 'Starts when you promote the rollout.' },
          ]}
        />
        <Card padding="roomy" gap={16}>
          <CardHeader number={2} size={17} title="Enroll the device" actions={<span className="gal-sub">(QR / Token tabs)</span>} />
          <NumberedSteps
            steps={[
              'Factory-reset the device, or start with one fresh from the box.',
              'On the first welcome screen, tap the same spot six times.',
              'Scan this code when the camera opens.',
            ]}
          />
        </Card>
      </Section>

      <Section id="files" title="FileDropZone, FileReadPanel" padding="roomy">
        <FileDropZone accept=".apk,.apks,.xapk,.apkm,.zip" hint=".apk, .apks, .xapk, .apkm or .zip" onFile={(f) => setFile(f.name)} />
        <FileReadPanel
          name={file ?? 'route-planner-5.2.0.apks'}
          detail="Read successfully · split bundle with 3 parts · 41.0 MB"
          action={<Button size="md">Choose another file</Button>}
        />
        <FileReadPanel tone="alert" name="notes.pdf" detail="Not an Android app. Choose an .apk or a split bundle." />
      </Section>

      <Section id="colour" title="ColourInput, PasswordInput, RuleList, CopyField, AppTile, RelativeTime" padding="rows">
        <FieldRow label="Background colour" help="Behind the app icons.">
          {({ labelId }) => <ColourInput aria-labelledby={labelId} value={colour} onChange={setColour} />}
        </FieldRow>
        <FieldRow label="Text colour" help="Unset: the launcher default.">
          {({ labelId }) => <ColourInput aria-labelledby={labelId} value={colour2} onChange={setColour2} />}
        </FieldRow>
        <div className="gal-grid" style={{ padding: '13px 0', borderTop: '1px solid var(--line-soft)' }}>
          <Field label="New password">
            <PasswordInput value={pw} onChange={(e) => setPw(e.target.value)} autoComplete="new-password" aria-describedby="gal-rules" />
          </Field>
          <Field label="Type it again">
            <PasswordInput value={pw2} onChange={(e) => setPw2(e.target.value)} autoComplete="new-password" tone={pw2 && pw2 !== pw ? 'invalid' : 'default'} aria-describedby="gal-rules" />
          </Field>
          <RuleList
            id="gal-rules"
            rules={[
              { key: 'len', label: 'At least 8 characters', met: pw.length === 0 ? null : pw.length >= 8 },
              { key: 'match', label: 'Both passwords match', met: pw2.length === 0 ? null : pw === pw2 },
              { key: 'n', label: 'Not checked yet', met: null },
            ]}
          />
          <Field label="Enrollment token">
            <CopyField value="mdm_tok_7f3a9c21e4b84d0b9a6c" />
          </Field>
        </div>
        <Row label="AppTile">
          <AppTile name="Menu Board" size={32} />
          <AppTile name="Wayfinder" size={38} tone="ink" />
          <AppTile name="Organic Maps" size={38} />
          <AppTile name="Binary Eye" size={44} />
        </Row>
        <Row label="RelativeTime">
          <span className="gal-sub">
            <RelativeTime value={now - 20_000} seconds prefix="Updated " />
          </span>
          <RelativeTime value={now - 3 * min} />
          <RelativeTime value={now - 2 * 60 * min} style="long" prefix="Checked " />
          <RelativeTime value={now - 26 * 60 * min} style="long" />
          <RelativeTime value={null} />
        </Row>
      </Section>

      <Section id="qr" title="QrPanel" note="Imported directly (keeps qrcode out of other chunks). Full screen opens a viewport-sized modal.">
        <QrPanel value='{"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":"demo"}' label="Enrollment QR code for Field Phones" onNewCode={() => {}} />
      </Section>

      <Section id="paddings" title="Card paddings" note="default 20/24 · rows 20/24/8 · roomy 20/24/24 · compact 18/24 · snug 16/24/18 · rail 20/20/16 · facts 4/20/16 · side 18/20.">
        <div className="gal-cards">
          {(['default', 'rows', 'roomy', 'compact', 'snug', 'rail', 'facts', 'side'] as CardPadding[]).map((p) => (
            <Card key={p} padding={p} style={{ background: 'var(--app)' }}>
              <div style={{ background: 'var(--surface)', border: '1px dashed var(--border-strong)', padding: 6 }} className="gal-sub">
                {p}
              </div>
            </Card>
          ))}
        </div>
      </Section>

      <Section id="boundary" title="PageErrorBoundary" note="A crash inside a page replaces only the page; the shell keeps working.">
        <Row>
          <Button onClick={() => setCrash(true)}>Crash the demo</Button>
        </Row>
        <PageErrorBoundary key={String(crash)}>
          {crash ? <Boom /> : <span className="gal-sub">Healthy.</span>}
        </PageErrorBoundary>
      </Section>
    </>
  );
}

