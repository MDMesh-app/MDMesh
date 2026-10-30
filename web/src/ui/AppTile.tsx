import { useState } from 'react';
import { cx } from './cx';
import './AppTile.css';

export interface AppTileProps {
  /** App name; the tile shows its first letter. */
  name: string;
  /**
   * - `32` r9, 13px (configuration apps table)
   * - `38` r10, 15px (library)
   * - `44` r12, 16px (F-Droid cards)
   */
  size?: 32 | 38 | 44;
  /** `ink` for apps you uploaded or host, `neutral` (surface-2) for catalogue apps. */
  tone?: 'neutral' | 'ink';
  /** Real icon URL; falls back to the letter if it fails to load. */
  src?: string;
  className?: string;
}

/** Letter avatar for an app. Decorative: put the app name in text next to it. */
export function AppTile({ name, size = 38, tone = 'neutral', src, className }: AppTileProps) {
  const [broken, setBroken] = useState(false);
  const letter = (name.trim().charAt(0) || '?').toUpperCase();
  return (
    <span aria-hidden="true" className={cx('v2-apptile', `v2-apptile--${size}`, `is-${tone}`, className)}>
      {src && !broken ? <img src={src} alt="" loading="lazy" onError={() => setBroken(true)} /> : letter}
    </span>
  );
}
