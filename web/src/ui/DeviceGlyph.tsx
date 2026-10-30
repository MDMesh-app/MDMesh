import type { ReactElement } from 'react';
import type { TelemetrySnapshot } from '../api/telemetry';
import { cx } from './cx';
import './DeviceGlyph.css';

export type DeviceKind = 'kiosk' | 'signage' | 'tablet' | 'phone' | 'scanner';

export const DEVICE_KIND_LABEL: Record<DeviceKind, string> = {
  kiosk: 'Kiosk',
  signage: 'Signage',
  tablet: 'Tablet',
  phone: 'Phone',
  scanner: 'Scanner',
};

export interface DeviceGlyphProps {
  kind: DeviceKind;
  /** Rendered size in px (boards: 21 in 38px tiles, 22 in 40px, 24 in 44px, 34 in 60px). */
  size?: number;
  /** Accessible name; omit when the type is written next to the glyph (decorative). */
  title?: string;
  className?: string;
}

/**
 * Outlined device drawing with a filled screen. Stroke is currentColor (ink); the body and screen
 * fills are tokens so the glyph works in dark mode (the board hard-codes #fff / #D6DAE1).
 */
export function DeviceGlyph({ kind, size = 22, title, className }: DeviceGlyphProps) {
  const a11y = title ? { role: 'img', 'aria-label': title } : { 'aria-hidden': true as const };
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
      focusable="false"
      className={cx('v2-glyph', className)}
      {...a11y}
    >
      {GLYPHS[kind]}
    </svg>
  );
}

const body = 'v2-glyph__body';
const screen = 'v2-glyph__screen';

const GLYPHS: Record<DeviceKind, ReactElement> = {
  kiosk: (
    <>
      <rect className={body} x="3.5" y="2" width="17" height="11" rx="1.5" />
      <rect className={screen} x="5" y="3.5" width="14" height="8" rx="0.6" stroke="none" />
      <path d="M12 13c0 2.5-1 3.5-1 6.5" />
      <rect className={body} x="6.5" y="19.5" width="9" height="2.5" rx="1.2" />
    </>
  ),
  signage: (
    <>
      <rect className={body} x="2" y="4" width="20" height="12.5" rx="1.5" />
      <rect className={screen} x="3.75" y="5.75" width="16.5" height="9" rx="0.5" stroke="none" />
      <path d="M12 16.5v3.5M8 20.5h8" />
    </>
  ),
  tablet: (
    <>
      <rect className={body} x="4" y="2.5" width="16" height="19" rx="2.5" />
      <rect className={screen} x="5.75" y="4.5" width="12.5" height="14" rx="0.8" stroke="none" />
      <path d="M11 20h2" />
    </>
  ),
  phone: (
    <>
      <rect className={body} x="7" y="2" width="10" height="20" rx="2.5" />
      <rect className={screen} x="8.5" y="4.5" width="7" height="14.5" rx="0.6" stroke="none" />
      <path d="M11 20.5h2" />
    </>
  ),
  scanner: (
    <>
      <rect className={body} x="9" y="2" width="6" height="3.5" rx="0.8" />
      <rect className={body} x="5.5" y="5.5" width="13" height="16.5" rx="3" />
      <rect className={screen} x="7.75" y="8" width="8.5" height="7" rx="0.6" stroke="none" />
      <path d="M9.5 18.5h5" />
    </>
  ),
};

/** Glyph in a bordered tile: 38 (Overview rows), 40 (Devices table), 44 (device cards), 60 (detail header). */
export function DeviceTile({
  kind,
  tile = 40,
  title,
  className,
}: {
  kind: DeviceKind;
  tile?: 38 | 40 | 44 | 60;
  title?: string;
  className?: string;
}) {
  const glyph = { 38: 21, 40: 22, 44: 24, 60: 34 }[tile];
  return (
    <span className={cx('v2-devtile', `v2-devtile--${tile}`, className)}>
      <DeviceGlyph kind={kind} size={glyph} title={title} />
    </span>
  );
}

/* ---------------------------------------------------------------------------------------------------------------
   Type derivation: a kiosk configuration → kiosk, else from the model and screen; an admin-set type would win.
   --------------------------------------------------------------------------------------------------------------- */

export interface DeviceKindInput {
  /** An admin-set type, once that exists. Wins over everything. */
  override?: DeviceKind | null;
  /** Kiosk mode in the device's configuration (DeviceView.kioskMode). */
  kioskMode?: boolean | null;
  /** Kiosk actually running (agent state.kioskActive). */
  kioskActive?: boolean | null;
  manufacturer?: string | null;
  model?: string | null;
  screenWidthPx?: number | null;
  screenHeightPx?: number | null;
  densityDpi?: number | null;
  /** Name / description: last-resort hint when there is no telemetry ("Front desk tablet"). */
  name?: string | null;
}

// Rugged handhelds with a scan engine: Zebra TC/MC/EC/WT, Honeywell CT/CK/EDA/ScanPal, Datalogic, Urovo,
// Newland, Point Mobile, Unitech, Chainway, plus anything that says "scanner".
const SCANNER_MAKERS = /\b(zebra|symbol|honeywell|datalogic|urovo|newland|point ?mobile|unitech|chainway|cipherlab|bluebird|m3 ?mobile)\b/i;
const SCANNER_MODELS = /\b(tc\d{2,}|mc\d{2,}|ec\d{2,}|wt\d{4}|ct\d{2}|ck\d{2}|eda\d{2}|scanpal|memor|skorpio|dt\d{2}|rt\d{2}|pm\d{2})\b|scanner/i;
// Boxes and panels that drive a screen rather than being held: TV boxes, signage players, commercial displays.
const SIGNAGE_HINTS = /\b(android ?tv|google ?tv|fire ?tv|aft[a-z]+|mi ?box|shield|bravia|chromecast|signage|player|display|tv box|stb)\b/i;

/** Smallest screen side in dp, or null without telemetry. */
export function smallestWidthDp(i: Pick<DeviceKindInput, 'screenWidthPx' | 'screenHeightPx' | 'densityDpi'>): number | null {
  const w = i.screenWidthPx ?? 0;
  const h = i.screenHeightPx ?? 0;
  const dpi = i.densityDpi ?? 0;
  if (w <= 0 || h <= 0 || dpi <= 0) return null;
  return Math.min(w, h) / (dpi / 160);
}

/**
 * Derive the device type for glyphs and the Type filter.
 * 1. admin override; 2. kiosk configuration or kiosk running → kiosk (signage when the hardware is a TV/signage
 * player); 3. scanner hardware → scanner; 4. signage hardware → signage; 5. smallest width ≥ 600dp → tablet,
 * else phone; 6. no telemetry: the name ("tab", "pad", "kiosk", "scanner", "signage", "board") or phone.
 */
export function deriveDeviceKind(i: DeviceKindInput): DeviceKind {
  if (i.override) return i.override;
  const hw = `${i.manufacturer ?? ''} ${i.model ?? ''}`.trim();
  const isSignageHw = hw !== '' && SIGNAGE_HINTS.test(hw);
  if (i.kioskMode || i.kioskActive) return isSignageHw ? 'signage' : 'kiosk';
  if (hw && (SCANNER_MAKERS.test(hw) || SCANNER_MODELS.test(hw))) return 'scanner';
  if (isSignageHw) return 'signage';
  const sw = smallestWidthDp(i);
  if (sw !== null) return sw >= 600 ? 'tablet' : 'phone';
  const n = (i.name ?? '').toLowerCase();
  if (/scanner|scan\b/.test(n)) return 'scanner';
  if (/signage|menu board|display|screen/.test(n)) return 'signage';
  if (/kiosk/.test(n)) return 'kiosk';
  if (/tab|pad|book|sm-[xt]/.test(n)) return 'tablet';
  return 'phone';
}

/** Pulls the hardware fields deriveDeviceKind needs out of an agent telemetry snapshot. */
export function kindInputFromTelemetry(t: TelemetrySnapshot | null | undefined): DeviceKindInput {
  const hw = (t?.hardware ?? {}) as Record<string, unknown>;
  const str = (v: unknown) => (typeof v === 'string' ? v : null);
  const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
  return {
    manufacturer: str(hw.manufacturer),
    model: str(hw.model),
    screenWidthPx: num(hw.screenWidthPx),
    screenHeightPx: num(hw.screenHeightPx),
    densityDpi: num(hw.densityDpi),
  };
}
