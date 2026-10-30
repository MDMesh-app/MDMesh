import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { Badge } from './Badge';
import { ProgressBar, batteryTone } from './Bars';
import { DEVICE_KIND_LABEL, DeviceTile, type DeviceKind } from './DeviceGlyph';
import { StatusPill, StatusText, type StatusKind } from './Status';
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
   * - `row` (table): 40px tile, 14/600 name, wrapping sub-line "number · Type · model"
   * - `attention`: 38px tile, sub-line "number · Type"
   * - `card`: 44px tile, 15/600 name, both lines truncated
   */
  variant?: 'row' | 'attention' | 'card';
  className?: string;
}

/** Glyph tile + name + "KSK-0151 · Kiosk · Galaxy Tab A8". */
export function DeviceIdentity({ name, number, kind, model, dupCount, to, variant = 'row', className }: DeviceIdentityProps) {
  const tile = variant === 'card' ? 44 : variant === 'attention' ? 38 : 40;
  const type = DEVICE_KIND_LABEL[kind];
  // Table rows (02) space the parts 6px apart; the attention list (01) and cards (03) set them as text, " · ".
  const inline = variant !== 'row';
  const sep = <span aria-hidden="true">{inline ? ' · ' : '·'}</span>;
  const body = (
    <>
      <DeviceTile kind={kind} tile={tile} />
      <span className="v2-devid__text">
        <span className="v2-devid__name">{name}</span>
        <span className="v2-devid__sub">
          <span className="v2-devid__num">{number}</span>
          {sep}
          <span className={cx(variant !== 'attention' && 'v2-devid__type')}>{type}</span>
          {model && variant !== 'attention' ? (
            <>
              {sep}
              <span>{model}</span>
            </>
          ) : null}
          {dupCount && dupCount > 1 ? (
            <>
              {inline ? ' ' : null}
              <Badge tone="warn" size="xs">
                {dupCount}× hardware ID
              </Badge>
            </>
          ) : null}
        </span>
      </span>
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
  status: 'online' | 'attention' | 'offline';
  /** Why ("Kiosk running", "Battery low, not charging", "Last seen on Wi-Fi “WH-North”"). */
  reason?: ReactNode;
  /** `stack` (table: pill over a 12.5px reason) or `inline` (card: side by side, reason wraps to 2 lines). */
  layout?: 'stack' | 'inline';
  className?: string;
}

/** Presence pill with its reason line. */
export function PresenceStatus({ status, reason, layout = 'stack', className }: PresenceStatusProps) {
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
  /** `stack` (name over the sync line) or `row` (card strip: "Configuration  name  ● In sync"). */
  layout?: 'stack' | 'row';
  className?: string;
}

/** Configuration name with its sync line: 6px dot + 12.5px in the tone's ink. */
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
  return (
    <span className={cx('v2-cfgsync', 'v2-cfgsync--stack', className)}>
      <span>{label}</span>
      <StatusText status={sync}>{syncLabel}</StatusText>
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
