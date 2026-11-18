import { useState } from 'react';
import { cx } from './cx';
import './AppTile.css';

export interface AppTileProps {
  /** App name; the tile shows its first letter. */
  name: string;
  /**
   * - `32` chip radius, 13px (tables: library, configuration apps)
   * - `38` control radius, 14px
   * - `44` control radius, 16px (F-Droid cards)
   */
  size?: 32 | 38 | 44;
  /** @deprecated Every tile is neutral (surface-2, text-2) now; `ink` renders the same. Kept so callers compile. */
  tone?: 'neutral' | 'ink';
  /** Real icon URL; falls back to the letter if it fails to load. */
  src?: string;
  className?: string;
}

/** Letter avatar for an app. Decorative: put the app name in text next to it. */
export function AppTile({ name, size = 38, tone: _tone, src, className }: AppTileProps) {
  const [broken, setBroken] = useState(false);
  const letter = (name.trim().charAt(0) || '?').toUpperCase();
  return (
    <span aria-hidden="true" className={cx('v2-apptile', `v2-apptile--${size}`, className)}>
      {src && !broken ? <img src={src} alt="" loading="lazy" onError={() => setBroken(true)} /> : letter}
    </span>
  );
}
