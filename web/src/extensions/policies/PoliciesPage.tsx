// /policies: the policy console. Three views, as an endpoint-security console has them: Policies (the policy list
// beside the selected policy's settings), Groups (which brands get which policy) and Status (every phone).
// The view, the policy and its tab are in the address (?view=, ?policy=, ?tab=), so a link opens them.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Badge,
  Button,
  Callout,
  Card,
  EmptyState,
  Eyebrow,
  IconLock,
  IconLockSmall,
  IconPlus,
  IconSliders,
  IconSync,
  IconWarning,
  LinkTabs,
  PageHeader,
  SearchInput,
  useNavigate,
  useParams,
  usePermission,
  useSearchParams,
} from '@mdmesh/console';
import type { PolicyRow } from './api';
import { StatusView } from './StatusView';
import { useFleet, type Fleet, type FleetDevice } from './fleet';
import { NewPolicyModal } from './NewPolicy';
import { GroupsTab } from './GroupsTab';
import { JobBar } from './JobBar';
import { AutomationView } from './AutomationView';
import { matchingPreset, PRESETS, protectionSummary } from './model';
import { PolicyDetail, POLICY_TABS, type PolicyTab } from './PolicyDetail';
import { useAutoRemove } from './removal';
import { ClientActions } from './ClientActions';
import { plural } from './shared';
import './policies.css';

type View = 'policies' | 'groups' | 'status' | 'automation';

export function PoliciesPage() {
  const [params, setParams] = useSearchParams();
  const fleet = useFleet();
  useAutoRemove(fleet);
  const { policies, devices, progress } = fleet;
  const v = params.get('view');
  const view: View = v === 'groups' || v === 'status' || v === 'automation' ? v : 'policies';
  const tab: PolicyTab = POLICY_TABS.includes(params.get('tab') as PolicyTab) ? (params.get('tab') as PolicyTab) : 'overview';
  const canAct = usePermission({ permission: 'edit_devices' });
  const [acting, setActing] = useState<{ phones: FleetDevice[]; scope: string } | null>(null);
  const dirty = useRef(false);
  const onDirty = useCallback((d: boolean) => {
    dirty.current = d;
  }, []);

  /** The policy shown: the one asked for, else the first of your own, else the first. */
  const selectedId = useMemo(() => {
    const asked = Number(params.get('policy'));
    if (policies?.some((p) => p.id === asked)) return asked;
    return policies?.find((p) => !p.builtIn)?.id ?? policies?.[0]?.id ?? null;
  }, [params, policies]);

  const go = (next: Record<string, string | null>) => {
    const p = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) {
      if (v == null) p.delete(k);
      else p.set(k, v);
    }
    setParams(p, { replace: true });
  };
  const select = (id: number) => {
    if (id === selectedId) return;
    if (dirty.current && !window.confirm('This policy has unsaved changes. Leave without saving them?')) return;
    go({ policy: String(id) });
  };

  const summary = useMemo(() => {
    const list = devices ?? [];
    const compliant = list.filter((d) => d.compliance.verdict === 'compliant').length;
    const found = list.reduce((n, d) => n + (d.violations?.length ?? 0), 0);
    const ungrouped = list.filter((d) => !d.group).length;
    return { total: list.length, compliant, found, ungrouped };
  }, [devices]);

  const checking = progress.total > 0 && progress.done < progress.total;
  const subtitle = !devices
    ? 'Loading…'
    : [
        `${summary.compliant} of ${summary.total} phones compliant`,
        summary.found ? `${plural(summary.found, 'extra app')} found` : null,
        summary.ungrouped ? `${summary.ungrouped} in no group` : null,
        checking ? `checking ${progress.done} of ${progress.total}…` : null,
      ]
        .filter(Boolean)
        .join(' · ') + '.';

  const href = (v: View) => {
    const p = new URLSearchParams(params);
    if (v === 'policies') p.delete('view');
    else p.set('view', v);
    const q = p.toString();
    return `/policies${q ? `?${q}` : ''}`;
  };

  return (
    <>
      <PageHeader
        title="Policies"
        subtitle={subtitle}
        actions={
          <>
            <Button icon={<IconSync />} onClick={fleet.reload}>
              Refresh
            </Button>
            {canAct ? (
              <Button variant="primary" icon={<IconSliders />} disabled={!devices?.length} onClick={() => setActing({ phones: devices ?? [], scope: 'All phones' })}>
                Client actions
              </Button>
            ) : null}
          </>
        }
      />
      {acting ? <ClientActions phones={acting.phones} scope={acting.scope} fleet={fleet} onClose={() => setActing(null)} /> : null}

      {fleet.error ? (
        <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
          <span className="x-policies-inline">
            {fleet.error}
            <Button size="sm" onClick={fleet.reload}>
              Retry
            </Button>
          </span>
        </Callout>
      ) : null}

      <JobBar fleet={fleet} />

      <LinkTabs
        aria-label="Policies views"
        items={[
          { key: 'policies', to: href('policies'), label: 'Policies', count: policies?.length, active: view === 'policies' },
          { key: 'groups', to: href('groups'), label: 'Groups', count: fleet.groups.length, active: view === 'groups' },
          { key: 'status', to: href('status'), label: 'Status', count: devices?.length, active: view === 'status' },
          ...(fleet.source === 'plugin' || view === 'automation'
            ? [{ key: 'automation', to: href('automation'), label: 'Automation', active: view === 'automation' }]
            : []),
        ]}
      />

      {view === 'policies' ? (
        <div className="x-policies-cols">
          <PolicyRail fleet={fleet} selectedId={selectedId} onSelect={select} onCreated={(id) => go({ policy: String(id), tab: null })} />
          {selectedId != null ? (
            <PolicyDetail
              key={selectedId}
              id={selectedId}
              fleet={fleet}
              tab={tab}
              onTab={(t) => go({ tab: t === 'overview' ? null : t })}
              onDirty={onDirty}
              onManageGroups={() => go({ view: 'groups', tab: null })}
              onActions={(phones, scope) => setActing({ phones, scope })}
            />
          ) : (
            <Card className="x-policies-main">
              <EmptyState icon={<IconLock />} title={policies ? 'No policies yet' : 'Loading policies…'}>
                {policies ? 'Create a configuration: it shows here as a policy.' : null}
              </EmptyState>
            </Card>
          )}
        </div>
      ) : null}
      {view === 'groups' ? <GroupsTab fleet={fleet} onActions={(phones, scope) => setActing({ phones, scope })} /> : null}
      {view === 'status' ? <StatusView fleet={fleet} /> : null}
      {view === 'automation' ? <AutomationView fleet={fleet} /> : null}
    </>
  );
}

/** The policy list: the rail beside the selected policy. */
function PolicyRail({
  fleet,
  selectedId,
  onSelect,
  onCreated,
}: {
  fleet: Fleet;
  selectedId: number | null;
  onSelect: (id: number) => void;
  onCreated: (id: number) => void;
}) {
  const policies = fleet.policies;
  const devices = fleet.devices ?? [];
  const canAdd = usePermission({ permission: 'add_config' });
  const [creating, setCreating] = useState(false);
  const [q, setQ] = useState('');
  const shown = (policies ?? []).filter((p) => !q.trim() || p.name.toLowerCase().includes(q.trim().toLowerCase()));
  const own = shown.filter((p) => !p.builtIn);
  const builtIn = shown.filter((p) => p.builtIn);

  const item = (p: PolicyRow) => {
    const preset = matchingPreset(p.config);
    const managed = protectionSummary(p.config).managed;
    const phones = devices.filter((d) => d.device.configurationId === p.id);
    const attention = phones.filter((d) => d.compliance.verdict === 'attention').length;
    const selected = p.id === selectedId;
    return (
      <li key={p.id}>
        <button
          type="button"
          className={`x-policies-railitem${selected ? ' is-selected' : ''}`}
          aria-current={selected ? 'true' : undefined}
          onClick={() => onSelect(p.id)}
        >
          <span className="x-policies-railitem__name">
            {p.name}
            {p.builtIn ? <IconLockSmall aria-label="Built-in" /> : null}
          </span>
          <span className="x-policies-railitem__meta">
            {plural(phones.length, 'phone')} · {preset ? PRESETS[preset].label : managed ? 'Custom' : 'No rules'}
          </span>
          {attention ? (
            <Badge tone="warn" size="sm" className="x-policies-railitem__badge">
              {attention}
            </Badge>
          ) : null}
        </button>
      </li>
    );
  };

  return (
    <Card as="aside" padding="rail" className="x-policies-rail" aria-label="Policy list">
      <SearchInput value={q} onChange={setQ} placeholder="Search policies" aria-label="Search policies" />
      {own.length ? (
        <div className="x-policies-railgroup">
          <Eyebrow as="h3">Your policies</Eyebrow>
          <ul className="x-policies-raillist">{own.map(item)}</ul>
        </div>
      ) : null}
      {builtIn.length ? (
        <div className="x-policies-railgroup">
          <Eyebrow as="h3">Built-in</Eyebrow>
          <ul className="x-policies-raillist">{builtIn.map(item)}</ul>
        </div>
      ) : null}
      {policies && !shown.length ? <p className="x-policies-help">No policy matches.</p> : null}
      {!policies ? <p className="x-policies-help">Loading…</p> : null}
      {canAdd ? (
        <Button icon={<IconPlus />} block disabled={!policies?.length} onClick={() => setCreating(true)}>
          New policy
        </Button>
      ) : null}
      {creating ? (
        <NewPolicyModal
          fleet={fleet}
          onClose={() => setCreating(false)}
          onCreated={(id) => {
            setCreating(false);
            onCreated(id);
          }}
        />
      ) : null}
    </Card>
  );
}

/** /policies/:id: an older link to one policy. Opens it in the console. */
export function PolicyLinkPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  useEffect(() => {
    navigate(`/policies?policy=${encodeURIComponent(id ?? '')}`, { replace: true });
  }, [id, navigate]);
  return null;
}
