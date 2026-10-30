import type { Key, ReactNode } from 'react';
import { Link } from 'react-router';
import { StatusPill, StatusText, type StatusKind, type StatusShape, type StatusTone } from './Status';
import { cx } from './cx';
import './Table.css';
import './Lists.css';

/* ------------------------------------------------------------------------------------------------------------
   Attention list ("Needs attention"): device · reason · when · one action, as table rows.
   ------------------------------------------------------------------------------------------------------------ */

export interface AttentionItem {
  key: Key;
  /** Usually <DeviceIdentity variant="attention" …/>. */
  device: ReactNode;
  reason: ReactNode;
  tone: StatusTone;
  /** Mark shape; defaults by tone (alert square, warn diamond, idle ring). */
  shape?: StatusShape;
  /** "3m ago" (a <RelativeTime/>). */
  when: ReactNode;
  /** One sm Button ("Retry apply", "Show alert", "Update agent", "Open device"). */
  action?: ReactNode;
}

/** Rows: 12px vertical padding, 24px outer edges, #EEF0F3 rules; scrolls sideways under 600px. */
export function AttentionList({ items, 'aria-label': ariaLabel }: { items: ReadonlyArray<AttentionItem>; 'aria-label'?: string }) {
  return (
    <div className="v2-tablewrap">
      <table className="v2-attn" aria-label={ariaLabel}>
        <tbody>
          {items.map((it) => (
            <tr key={it.key}>
              <td className="v2-attn__device">{it.device}</td>
              <td className="v2-attn__reason">
                <StatusText variant="reason" tone={it.tone} shape={it.shape}>
                  {it.reason}
                </StatusText>
              </td>
              <td className="v2-attn__when">{it.when}</td>
              <td className="v2-attn__action">{it.action}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* ------------------------------------------------------------------------------------------------------------
   Activity feed ("Recent activity").
   ------------------------------------------------------------------------------------------------------------ */

export interface ActivityRowProps {
  /** 15px icon inside the 30px tinted tile. */
  icon: ReactNode;
  /** Tile tint: soft fill + ink of the tone (idle = surface-2 + secondary). */
  tone: StatusTone;
  /** Device number or name, 600. */
  who: ReactNode;
  whoTo?: string;
  /** "failed to apply its configuration" (text-2). */
  what: ReactNode;
  /** "USB storage needs device owner" (13px secondary). */
  detail?: ReactNode;
  when: ReactNode;
}

/** One feed row: 30px r9 tile, who/what/detail, time right-aligned; 10×24, top rule. */
export function ActivityRow({ icon, tone, who, whoTo, what, detail, when }: ActivityRowProps) {
  return (
    <li className="v2-activity__row">
      <span className={cx('v2-activity__icon', `is-${tone}`)} aria-hidden="true">
        {icon}
      </span>
      <span className="v2-activity__text">
        {whoTo ? (
          <Link to={whoTo} className="v2-activity__who">
            {who}
          </Link>
        ) : (
          <span className="v2-activity__who">{who}</span>
        )}{' '}
        <span className="v2-activity__what">{what}</span>
        {detail ? (
          <>
            {' '}
            <span className="v2-activity__detail">{detail}</span>
          </>
        ) : null}
      </span>
      <span className="v2-activity__when">{when}</span>
    </li>
  );
}

/** The feed's <ol> (6px bottom padding, as on the board). */
export function ActivityList({ children, 'aria-label': ariaLabel }: { children: ReactNode; 'aria-label'?: string }) {
  return (
    <ol className="v2-activity" aria-label={ariaLabel}>
      {children}
    </ol>
  );
}

/* ------------------------------------------------------------------------------------------------------------
   Recent commands: name + detail, status pill without a mark, time.
   ------------------------------------------------------------------------------------------------------------ */

export interface CommandItem {
  key: Key;
  /** "Apply configuration" (500). */
  label: ReactNode;
  /** "USB storage: permission denied" (13px secondary). */
  detail?: ReactNode;
  status: StatusKind;
  statusLabel?: ReactNode;
  when: ReactNode;
}

export function CommandList({ items, 'aria-label': ariaLabel }: { items: ReadonlyArray<CommandItem>; 'aria-label'?: string }) {
  return (
    <div className="v2-tablewrap">
      <table className="v2-cmds" aria-label={ariaLabel}>
        <tbody>
          {items.map((it) => (
            <tr key={it.key}>
              <td className="v2-cmds__label">
                <span className="v2-cmds__name">{it.label}</span>
                {it.detail ? (
                  <>
                    {' '}
                    <span className="v2-cmds__detail">{it.detail}</span>
                  </>
                ) : null}
              </td>
              <td className="v2-cmds__status">
                <StatusPill status={it.status} label={it.statusLabel} dot={false} />
              </td>
              <td className="v2-cmds__when">{it.when}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
