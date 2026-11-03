import type { ReactNode } from 'react';
import { cx } from './cx';
import './Status.css';

export type StatusTone = 'ok' | 'warn' | 'alert' | 'idle';
/** Shape coding keeps status readable without colour: never drop it. */
export type StatusShape = 'circle' | 'diamond' | 'square' | 'ring';

export type StatusKind =
  // device presence
  | 'online'
  | 'attention'
  | 'offline'
  // configuration sync (Devices sync column, device page outcomes)
  | 'inSync'
  | 'applying'
  | 'applyFailed'
  | 'agentTooOld'
  | 'notReported'
  | 'noConfiguration'
  | 'applied'
  | 'failed'
  | 'notSupported'
  // command lifecycle (recent commands)
  | 'done'
  | 'pending'
  | 'expired';

interface StatusMeta {
  tone: StatusTone;
  shape: StatusShape;
  label: string;
}

/** Tone, shape and default wording per status. Labels follow the boards; pass `label` to override. */
export const STATUS_META: Record<StatusKind, StatusMeta> = {
  online: { tone: 'ok', shape: 'circle', label: 'Online' },
  attention: { tone: 'warn', shape: 'diamond', label: 'Attention' },
  offline: { tone: 'idle', shape: 'ring', label: 'Offline' },
  inSync: { tone: 'ok', shape: 'circle', label: 'In sync' },
  applying: { tone: 'warn', shape: 'diamond', label: 'Applying…' },
  applyFailed: { tone: 'alert', shape: 'square', label: 'Apply failed' },
  agentTooOld: { tone: 'warn', shape: 'diamond', label: 'Agent too old' },
  notReported: { tone: 'idle', shape: 'ring', label: 'Not reported' },
  noConfiguration: { tone: 'idle', shape: 'ring', label: 'No configuration' },
  applied: { tone: 'ok', shape: 'circle', label: 'Applied' },
  failed: { tone: 'alert', shape: 'square', label: 'Failed' },
  notSupported: { tone: 'idle', shape: 'ring', label: 'Not supported' },
  done: { tone: 'ok', shape: 'circle', label: 'Done' },
  pending: { tone: 'idle', shape: 'ring', label: 'Waiting' },
  expired: { tone: 'warn', shape: 'diamond', label: 'Expired' },
};

/** Default shape for a tone (ok circle, warn diamond, alert square, idle ring). */
export const TONE_SHAPE: Record<StatusTone, StatusShape> = {
  ok: 'circle',
  warn: 'diamond',
  alert: 'square',
  idle: 'ring',
};

export interface StatusDotProps {
  tone: StatusTone;
  shape?: StatusShape;
  /** 7px (pills, reasons, legends, default) · 8px (fleet legend) · 6px (sync lines: always a circle on the boards). */
  size?: 6 | 7 | 8;
  className?: string;
}

/** The decorative status mark. Always pair it with text. */
export function StatusDot({ tone, shape = TONE_SHAPE[tone], size = 7, className }: StatusDotProps) {
  return (
    <span
      aria-hidden="true"
      className={cx('v2-sdot', `v2-sdot--${tone}`, `v2-sdot--${shape}`, `v2-sdot--s${size}`, className)}
    />
  );
}

export interface StatusPillProps {
  status: StatusKind;
  /** Override the default wording. */
  label?: ReactNode;
  /** Drop the shape (command-status pills "Done / Failed / Expired" draw none). */
  dot?: boolean;
  className?: string;
}

/** Soft pill with the shape mark: 2×10, r999, 12.5/500, soft fill + line + ink. */
export function StatusPill({ status, label, dot = true, className }: StatusPillProps) {
  const m = STATUS_META[status];
  return (
    <span className={cx('v2-spill', `v2-spill--${m.tone}`, !dot && 'v2-spill--nodot', className)}>
      {dot ? <StatusDot tone={m.tone} shape={m.shape} /> : null}
      {label ?? m.label}
    </span>
  );
}

export interface StatusTextProps {
  /** A known status, or give `tone` (+ optional `shape`) for free text such as attention reasons. */
  status?: StatusKind;
  tone?: StatusTone;
  shape?: StatusShape;
  /**
   * - `sync`: 6px circle + 12.5px text in the tone's ink (configuration sync line under a name)
   * - `reason`: 7px shape + 13.5px ink text (Overview "Needs attention" reasons)
   * - `meta`: 7px shape + 13px secondary text, label 600 in ink (rail header "◆ Attention · checked in 3m ago")
   */
  variant?: 'sync' | 'reason' | 'meta';
  children?: ReactNode;
  className?: string;
}

/** Inline status: mark + text coloured by tone (no pill). */
export function StatusText({ status, tone, shape, variant = 'sync', children, className }: StatusTextProps) {
  const m = status ? STATUS_META[status] : undefined;
  const t = tone ?? m?.tone ?? 'idle';
  const s = variant === 'sync' ? 'circle' : (shape ?? m?.shape ?? TONE_SHAPE[t]);
  return (
    <span className={cx('v2-stext', `v2-stext--${variant}`, `v2-stext--${t}`, className)}>
      <StatusDot tone={t} shape={s} size={variant === 'sync' ? 6 : 7} />
      {children ?? m?.label}
    </span>
  );
}
