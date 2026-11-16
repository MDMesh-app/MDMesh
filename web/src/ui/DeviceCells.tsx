import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { Badge } from './Badge';
import { ProgressBar, batteryTone } from './Bars';
import { DEVICE_KIND_LABEL, DeviceGlyph, DeviceTile, type DeviceKind } from './DeviceGlyph';
import { STATUS_META, StatusDot, StatusPill, StatusText, type StatusKind } from './Status';
import { Tooltip } from './Tooltip';
import { cx } from './cx';
import './DeviceCells.css';

export interface DeviceIdentityProps {
  name: ReactNode;
  /** Device number (mono). */
  number: string;
  kind: DeviceKind;
  model?: ReactNode;
  /** Same hardware id enrolled N times (> 1 shows the "N× hardware ID" chip). */
  dupCount?: number;
  /** Link target (device detail). Without it the block is plain text. */
  to?: string;
  /**
   * - `row` (tables) and `attention` (Overview list): one line, a 16px glyph, the name, the number after it
   * - `card`: glyph, name over "number · Type · model", both truncated
   */
  variant?: 'row' | 'attention' | 'card';
  className?: string;
}

/** Device glyph + name + number (the type is the glyph's tooltip and accessible name in rows). */
export function DeviceIdentity({ name, number, kind, model, dupCount, to, variant = 'row', className }: DeviceIdentityProps) {
  const type = DEVICE_KIND_LABEL[kind];
  const dup =
    dupCount && dupCount > 1 ? (
      <Badge tone="warn" size="xs">
        {dupCount}× hardware ID
      </Badge>
    ) : null;
  const body =
    variant === 'card' ? (
      <>
        <DeviceTile kind={kind} tile={40} />
        <span className="v2-devid__text">
          <span className="v2-devid__name">{name}</span>
          <span className="v2-devid__sub">
            <span className="v2-devid__num">{number}</span>
            <span aria-hidden="true"> · </span>
            {type}
            {model ? (
              <>
                <span aria-hidden="true"> · </span>
                {model}
              </>
            ) : null}
            {dup ? <> {dup}</> : null}
          </span>
        </span>
      </>
    ) : (
      <>
        <Tooltip content={type}>
          <span className="v2-devid__glyph">
            <DeviceGlyph kind={kind} size={16} />
            <span className="v2-visually-hidden">{type}: </span>
          </span>
        </Tooltip>
        <span className="v2-devid__name">{name}</span>
        <span className="v2-devid__num">{number}</span>
        {dup}
      </>
    );
  const cls = cx('v2-devid', `v2-devid--${variant}`, className);
  return to ? (
    <Link to={to} className={cls}>
      {body}
    </Link>
  ) : (
    <span className={cls}>{body}</span>
  );
}

export interface PresenceStatusProps {
  status: 'online' | 'attention' | 'offline' | 'wiped';
  /** Why ("Kiosk running", "Battery low, not charging", "Last seen on Wi-Fi “WH-North”", "Wiped 2h ago"). */
  reason?: ReactNode;
  /** `stack` (tables: one line, mark + label, the reason after it in secondary) or `inline` (cards: pill + reason). */
  layout?: 'stack' | 'inline';
  className?: string;
}

/** Presence with its reason. */
export function PresenceStatus({ status, reason, layout = 'stack', className }: PresenceStatusProps) {
  if (layout === 'stack') {
    return (
      <span className={cx('v2-presence', 'v2-presence--line', className)}>
        <StatusText variant="reason" status={status} />
        {reason ? <span className="v2-presence__reason">{reason}</span> : null}
      </span>
    );
  }
  return (
    <span className={cx('v2-presence', `v2-presence--${layout}`, className)}>
      <StatusPill status={status} />
      {reason ? <span className="v2-presence__reason">{reason}</span> : null}
    </span>
  );
}

export interface ConfigSyncProps {
  /** Configuration name ("Unassigned" when none). */
  name: ReactNode;
  /** Link to the configuration. */
  to?: string;
  sync: StatusKind;
  /** Override the sync wording. */
  syncLabel?: ReactNode;
  /** `stack` (tables: one line, the name then the sync state) or `row` (card strip: "Configuration  name  ● In sync"). */
  layout?: 'stack' | 'row';
  className?: string;
}

/** Configuration name with its sync state: in tables a quiet mark when in sync, the words when it is not. */
export function ConfigSync({ name, to, sync, syncLabel, layout = 'stack', className }: ConfigSyncProps) {
  const label = to ? <Link to={to}>{name}</Link> : name;
  if (layout === 'row') {
    return (
      <div className={cx('v2-cfgsync', 'v2-cfgsync--row', className)}>
        <span className="v2-cfgsync__key">Configuration</span>
        <span className="v2-cfgsync__name">{label}</span>
        <StatusText status={sync}>{syncLabel}</StatusText>
      </div>
    );
  }
  const quiet = STATUS_META[sync].tone === 'ok' || sync === 'notReported';
  const words = syncLabel ?? STATUS_META[sync].label;
  return (
    <span className={cx('v2-cfgsync', 'v2-cfgsync--line', className)}>
      <span className="v2-cfgsync__name">{label}</span>
      {quiet ? (
        <Tooltip content={words}>
          <span className="v2-cfgsync__mark">
            <StatusDot tone={STATUS_META[sync].tone} shape="circle" size={6} />
            <span className="v2-visually-hidden">{words}</span>
          </span>
        </Tooltip>
      ) : (
        <StatusText status={sync}>{syncLabel}</StatusText>
      )}
    </span>
  );
}

export interface BatteryBarProps {
  /** 0-100, or null when unknown ("—"). */
  percent: number | null | undefined;
  charging?: boolean;
  /** `table`: 40×6 bar then mono %; `card`: mono % then a ≤48×4 bar. */
  layout?: 'table' | 'card';
  className?: string;
}

/** Battery level: bar in the tone rule (< 15 alert, < 40 warn, else grey) and a mono percentage. */
export function BatteryBar({ percent, charging, layout = 'table', className }: BatteryBarProps) {
  const known = typeof percent === 'number' && Number.isFinite(percent);
  const pct = known ? Math.round(percent as number) : 0;
  const low = known && pct < 15;
  const text = (
    <span className={cx('v2-battery__pct', low && 'is-low')}>
      {known ? `${pct}%` : '—'}
      {charging ? <span className="v2-visually-hidden"> (charging)</span> : null}
    </span>
  );
  const bar = <ProgressBar size={layout === 'card' ? 'micro' : 'mini'} value={pct} tone={batteryTone(known ? pct : 100)} />;
  return (
    <span className={cx('v2-battery', `v2-battery--${layout}`, className)}>
      {layout === 'card' ? (
        <>
          {text}
          {bar}
        </>
      ) : (
        <>
          {bar}
          {text}
        </>
      )}
    </span>
  );
}
