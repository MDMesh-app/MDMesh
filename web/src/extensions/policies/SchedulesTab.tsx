// The Schedules tab of a policy: open an app on its phones at set times, like an alarm (agent command app.launch,
// protocol 1.13). Runs on the server (the Policies plugin). It reaches every phone of the policy, the policy's phones
// in one group, or phones picked by hand; only phones whose agent can open apps get it. Nothing locks the app: the user
// leaves it when they want. A phone offline at the time still gets it if it comes online within the late window.
import { useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  Callout,
  Card,
  CardHeader,
  Checkbox,
  EmptyState,
  Field,
  IconClock,
  IconInfo,
  IconPlus,
  IconTrash,
  Input,
  Menu,
  Modal,
  SearchInput,
  Segmented,
  Select,
  Table,
  Toggle,
  useToast,
  type Column,
} from '@mdmesh/console';
import { deleteSchedule, listSchedules, saveSchedule, type Schedule, type ScheduleInput, type ScheduleTarget } from './api';
import type { FleetDevice } from './fleet';
import type { DeviceGroup } from './model';
import { dateTimeText, errorText, plural } from './shared';

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const ALL_DAYS = 127;
const LATE = [5, 15, 30, 60, 120, 240];
const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

/** The days as words: "Every day", "Mon–Fri", "Mon, Wed". */
export function daysText(days: number): string {
  if (days === ALL_DAYS) return 'Every day';
  if (days === 31) return 'Mon–Fri';
  if (days === 96) return 'Sat, Sun';
  return DAYS.filter((_, i) => days & (1 << i)).join(', ') || 'No day';
}

/** A late window as words: "15 minutes", "2 hours". */
export function lateText(minutes: number): string {
  return minutes % 60 === 0 ? plural(minutes / 60, 'hour') : plural(minutes, 'minute');
}

function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

/** Who a schedule reaches, in words. */
export function targetText(s: Pick<Schedule, 'target' | 'groupId' | 'devices'>, groups: readonly DeviceGroup[]): string {
  if (s.target === 'policy') return 'Every phone of the policy';
  if (s.target === 'phones') return plural(s.devices.length, 'picked phone');
  const g = groups.find((x) => x.id === s.groupId);
  return g ? `Group: ${g.name}` : 'Its group was deleted';
}

export function SchedulesTab({
  policyId,
  allowed,
  phones,
  groups,
  readOnly,
  canEdit,
  available,
}: {
  policyId: number;
  /** The policy's allowed apps, package → name: the apps a schedule can open. */
  allowed: ReadonlyMap<string, string>;
  /** The phones on this policy. */
  phones: readonly FleetDevice[];
  /** Every group (those given this policy are offered first). */
  groups: readonly DeviceGroup[];
  readOnly: boolean;
  canEdit: boolean;
  /** The Policies server plugin answers. */
  available: boolean;
}) {
  const toast = useToast();
  const [rows, setRows] = useState<Schedule[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Schedule | 'new' | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!available) return;
    let on = true;
    listSchedules(policyId)
      .then((r) => on && setRows(r))
      .catch((e: unknown) => on && setError(errorText(e, 'Could not read the schedules.')));
    return () => {
      on = false;
    };
  }, [policyId, available, attempt]);

  if (!available) {
    return (
      <Callout tone="info" icon={<IconInfo size={16} />}>
        Schedules need the Policies server plugin (plugins/fork/policies) on the server.
      </Callout>
    );
  }

  const editable = !readOnly && canEdit;
  const canOpen = phones.filter((d) => d.canLaunch).length;

  async function toggle(s: Schedule, enabled: boolean) {
    try {
      const { id, configurationId: _c, lastSlot: _l, nextRun: _n, ...rest } = s;
      await saveSchedule(policyId, { ...rest, enabled }, id);
      setAttempt((n) => n + 1);
    } catch (e) {
      toast.push('err', 'Could not change the schedule', errorText(e, 'Try again.'));
    }
  }

  async function remove(s: Schedule) {
    try {
      await deleteSchedule(policyId, s.id);
      toast.push('ok', 'Schedule deleted', `${s.appName || s.pkg}: no more runs.`);
      setAttempt((n) => n + 1);
    } catch (e) {
      toast.push('err', 'Could not delete the schedule', errorText(e, 'Try again.'));
    }
  }

  const columns: Column<Schedule>[] = [
    {
      key: 'app',
      header: 'App',
      cell: (s) => (
        <span className="x-policies-cell">
          <span className="x-policies-strong">{s.appName || s.pkg}</span>
          <span className="x-policies-help x-policies-mono">{s.pkg}</span>
        </span>
      ),
    },
    { key: 'who', header: 'Opens on', cell: (s) => targetText(s, groups) },
    {
      key: 'when',
      header: 'When',
      cell: (s) => (
        <span className="x-policies-cell">
          <span>{s.times.join(', ')}</span>
          <span className="x-policies-help">
            {daysText(s.days)} · up to {lateText(s.lateMinutes)} late
          </span>
        </span>
      ),
    },
    {
      key: 'next',
      header: 'Next run',
      cell: (s) =>
        !s.enabled ? (
          <Badge tone="neutral" size="sm">
            Off
          </Badge>
        ) : s.nextRun ? (
          <span>{dateTimeText(s.nextRun)}</span>
        ) : (
          <span className="x-policies-muted">—</span>
        ),
    },
    ...(editable
      ? [
          {
            key: 'on',
            header: 'On',
            shrink: true,
            cell: (s: Schedule) => <Toggle aria-label={`Schedule ${s.appName || s.pkg} on`} checked={s.enabled} onChange={(v) => void toggle(s, v)} />,
          },
          {
            key: 'menu',
            header: 'Actions',
            headerHidden: true,
            shrink: true,
            cell: (s: Schedule) => (
              <Menu
                label={`More for ${s.appName || s.pkg}`}
                iconSize="sm"
                items={[
                  { key: 'edit', label: 'Edit…', textValue: 'Edit', onSelect: () => setEditing(s) },
                  { key: 'delete', label: 'Delete', icon: <IconTrash />, danger: true, onSelect: () => void remove(s) },
                ]}
              />
            ),
          },
        ]
      : []),
  ];

  return (
    <>
      <Card padding="none" clip>
        <div className="x-policies-cardhead">
          <CardHeader
            title="Schedules"
            subtitle={`Open an app on this policy's phones at set times, like an alarm. Nothing locks it: the user leaves it when they want. ${plural(canOpen, 'phone')} of ${phones.length} can open apps now; the others are skipped until their agent can.`}
            level={3}
            actions={
              editable ? (
                <Button size="sm" icon={<IconPlus />} disabled={allowed.size === 0} onClick={() => setEditing('new')}>
                  Add schedule
                </Button>
              ) : undefined
            }
          />
        </div>
        {error ? <Callout tone="alert">{error}</Callout> : null}
        <Table<Schedule>
          aria-label="Schedules"
          columns={columns}
          rows={rows ?? []}
          rowKey={(s) => s.id}
          edge={20}
          empty={
            <EmptyState icon={<IconClock />} title={rows ? 'No schedule yet' : 'Loading the schedules…'} bare>
              {rows && allowed.size === 0 ? 'Allow some apps first: a schedule opens one of them.' : null}
            </EmptyState>
          }
        />
      </Card>
      {editing ? (
        <ScheduleModal
          policyId={policyId}
          allowed={allowed}
          phones={phones}
          groups={groups}
          schedule={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(s) => {
            setEditing(null);
            toast.push('ok', 'Schedule saved', s.enabled && s.nextRun ? `Next run ${dateTimeText(s.nextRun)}.` : 'It is off.');
            setAttempt((n) => n + 1);
          }}
        />
      ) : null}
    </>
  );
}

function ScheduleModal({
  policyId,
  allowed,
  phones,
  groups,
  schedule,
  onClose,
  onSaved,
}: {
  policyId: number;
  allowed: ReadonlyMap<string, string>;
  phones: readonly FleetDevice[];
  groups: readonly DeviceGroup[];
  schedule: Schedule | null;
  onClose: () => void;
  onSaved: (s: Schedule) => void;
}) {
  const apps = useMemo(() => [...allowed.entries()].sort((a, b) => a[1].localeCompare(b[1])), [allowed]);
  // Groups given this policy first: their phones are the ones a group schedule normally means.
  const groupList = useMemo(
    () => [...groups].sort((a, b) => Number(b.policyId === policyId) - Number(a.policyId === policyId) || a.name.localeCompare(b.name)),
    [groups, policyId],
  );
  const [pkg, setPkg] = useState(schedule?.pkg ?? '');
  const [target, setTarget] = useState<ScheduleTarget>(schedule?.target ?? 'policy');
  const [groupId, setGroupId] = useState<string>(schedule?.groupId != null ? String(schedule.groupId) : groupList[0] ? String(groupList[0].id) : '');
  const [picked, setPicked] = useState<number[]>(schedule?.devices ?? []);
  const [q, setQ] = useState('');
  const [days, setDays] = useState(schedule?.days ?? ALL_DAYS);
  const [times, setTimes] = useState<string[]>(schedule?.times ?? []);
  const [time, setTime] = useState('09:00');
  const [late, setLate] = useState(schedule?.lateMinutes ?? 15);
  const [enabled, setEnabled] = useState(schedule?.enabled ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reached = target === 'policy' ? phones : target === 'phones' ? phones.filter((d) => picked.includes(d.device.id)) : phones.filter((d) => String(d.group?.id) === groupId);
  const whoOk = target === 'policy' || (target === 'group' && !!groupId) || (target === 'phones' && picked.length > 0);
  const valid = !!pkg && whoOk && days > 0 && times.length > 0;
  const needle = q.trim().toLowerCase();
  const shown = phones.filter((d) => !needle || (d.device.description ?? '').toLowerCase().includes(needle) || d.device.number.toLowerCase().includes(needle));

  function addTime() {
    if (!TIME.test(time) || times.includes(time)) return;
    setTimes([...times, time].sort());
  }

  async function save() {
    setBusy(true);
    setError(null);
    try {
      const input: ScheduleInput = {
        pkg,
        appName: allowed.get(pkg) ?? schedule?.appName ?? pkg,
        target,
        groupId: target === 'group' ? Number(groupId) : null,
        devices: target === 'phones' ? picked : [],
        days,
        times,
        timeZone: schedule?.timeZone ?? browserZone(),
        lateMinutes: late,
        enabled,
      };
      onSaved(await saveSchedule(policyId, input, schedule?.id));
    } catch (e) {
      setError(errorText(e, 'Could not save the schedule.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      size="md"
      title={schedule ? 'Edit schedule' : 'Add schedule'}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !valid} onClick={() => void save()}>
            {busy ? 'Saving…' : 'Save'}
          </Button>
        </>
      }
    >
      <div className="x-policies-stack">
        <Field label="App">
          <Select value={pkg} onChange={(e) => setPkg(e.target.value)}>
            <option value="">Choose an allowed app…</option>
            {apps.map(([p, name]) => (
              <option key={p} value={p}>
                {name} ({p})
              </option>
            ))}
            {schedule && !allowed.has(schedule.pkg) ? <option value={schedule.pkg}>{schedule.appName || schedule.pkg} (not allowed any more)</option> : null}
          </Select>
        </Field>
        <Field label="Opens on">
          <Segmented<ScheduleTarget>
            aria-label="Opens on"
            value={target}
            onChange={setTarget}
            options={[
              { value: 'policy', label: 'Whole policy' },
              { value: 'group', label: 'A group', disabled: groupList.length === 0 },
              { value: 'phones', label: 'Picked phones' },
            ]}
          />
        </Field>
        {target === 'group' ? (
          <Field label="Group">
            <Select value={groupId} onChange={(e) => setGroupId(e.target.value)}>
              {groupList.map((g) => (
                <option key={g.id} value={String(g.id)}>
                  {g.name}
                  {g.policyId === policyId ? '' : ' (another policy)'}
                </option>
              ))}
            </Select>
          </Field>
        ) : null}
        {target === 'phones' ? (
          <fieldset className="x-policies-fieldset">
            <legend>Phones</legend>
            <SearchInput value={q} onChange={setQ} placeholder="Search this policy's phones" aria-label="Search phones" />
            <div className="x-policies-picker" role="group" aria-label="Phones">
              {shown.length ? (
                shown.map((d) => (
                  <Checkbox
                    key={d.device.id}
                    checked={picked.includes(d.device.id)}
                    onChange={(e) => setPicked((prev) => (e.target.checked ? [...prev, d.device.id] : prev.filter((x) => x !== d.device.id)))}
                    label={
                      <>
                        <span className="x-policies-strong">{d.device.description || d.device.number}</span>{' '}
                        <span className="x-policies-muted">{d.canLaunch ? '' : '· its agent can’t open apps yet'}</span>
                      </>
                    }
                  />
                ))
              ) : (
                <p className="x-policies-help">{phones.length ? 'No phone matches.' : 'No phone uses this policy yet.'}</p>
              )}
            </div>
          </fieldset>
        ) : null}
        <p className="x-policies-help">
          Reaches {plural(reached.length, 'phone')} now; {plural(reached.filter((d) => d.canLaunch).length, 'phone')} of them can open apps.
          {target === 'group' ? ' Phones that join the group later are included.' : ''}
        </p>
        <Field label="Days">
          <span className="x-policies-inline">
            {DAYS.map((d, i) => (
              <Checkbox key={d} label={d} checked={(days & (1 << i)) !== 0} onChange={(e) => setDays(e.target.checked ? days | (1 << i) : days & ~(1 << i))} />
            ))}
          </span>
        </Field>
        <Field label="Times" error={times.length === 0 ? 'Add at least one time' : undefined}>
          <span className="x-policies-inline">
            {times.map((t) => (
              <Button key={t} size="xs" icon={<IconTrash size={12} />} onClick={() => setTimes(times.filter((x) => x !== t))} aria-label={`Remove ${t}`}>
                {t}
              </Button>
            ))}
            <Input type="time" value={time} onChange={(e) => setTime(e.target.value)} aria-label="Time to add" />
            <Button size="sm" icon={<IconPlus />} disabled={!TIME.test(time) || times.includes(time)} onClick={addTime}>
              Add time
            </Button>
          </span>
        </Field>
        <Field label="A phone offline at the time">
          <Select value={late} onChange={(e) => setLate(Number(e.target.value))}>
            {LATE.map((m) => (
              <option key={m} value={m}>
                Still opens it if it comes online within {lateText(m)}
              </option>
            ))}
          </Select>
        </Field>
        <Checkbox checked={enabled} onChange={(e) => setEnabled(e.target.checked)} label="On" />
        <p className="x-policies-help">Times are in {schedule?.timeZone ?? browserZone()}.</p>
        {error ? <p className="x-policies-error">{error}</p> : null}
      </div>
    </Modal>
  );
}
