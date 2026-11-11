// How the kiosk background image fills the screen: Fill (cropped around a focal point) or Fit (whole, letterboxed),
// and the focal point itself, set by clicking or dragging on the image or with the arrow keys.
import { useId, useRef, type KeyboardEvent, type PointerEvent } from 'react';
import { DEFAULT_FOCAL, type FitMode, type FocalPoint } from '../../data/kioskScrim';
import { Button, Segmented } from '../../ui';
import './KioskPlacement.css';

const clamp01 = (v: number) => Math.min(1, Math.max(0, v));
/** Two decimals: what the configuration stores and the agent gets. */
const round2 = (v: number) => Math.round(v * 100) / 100;
const pct = (v: number) => `${Math.round(v * 100)}%`;

export function KioskPlacement({
  src,
  width,
  height,
  fit,
  focal,
  onFit,
  onFocal,
  disabled,
}: {
  src: string;
  width: number;
  height: number;
  fit: FitMode;
  focal: FocalPoint;
  onFit: (fit: FitMode) => void;
  onFocal: (focal: FocalPoint) => void;
  disabled?: boolean;
}) {
  const ids = useId();
  const area = useRef<HTMLDivElement>(null);
  const dragging = useRef(false);
  const focalOff = disabled || fit === 'fit';

  const fromPointer = (e: PointerEvent<HTMLDivElement>) => {
    const r = area.current!.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return;
    onFocal({ x: round2(clamp01((e.clientX - r.left) / r.width)), y: round2(clamp01((e.clientY - r.top) / r.height)) });
  };

  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const step = e.shiftKey ? 0.1 : 0.01;
    const move: Record<string, [number, number]> = {
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, -step],
      ArrowDown: [0, step],
    };
    if (e.key === 'Home') {
      e.preventDefault();
      onFocal(DEFAULT_FOCAL);
      return;
    }
    const d = move[e.key];
    if (!d) return;
    e.preventDefault();
    onFocal({ x: round2(clamp01(focal.x + d[0])), y: round2(clamp01(focal.y + d[1])) });
  };

  const valueText = `${pct(focal.x)} across, ${pct(focal.y)} down`;
  // The picker is at most 280 px wide and 200 px tall, in the image's proportions.
  const scale = Math.min(280 / width, 200 / height);

  return (
    <div className="v2-kbp">
      <div className="v2-kbp__row">
        <span id={`${ids}-fit`} className="v2-kbp__label">
          Fit
        </span>
        <Segmented
          aria-labelledby={`${ids}-fit`}
          size="sm"
          disabled={disabled}
          options={[
            { value: 'fill', label: 'Fill' },
            { value: 'fit', label: 'Fit' },
          ]}
          value={fit}
          onChange={(v) => onFit(v as FitMode)}
        />
        <span className="v2-kbp__hint">
          {fit === 'fit' ? 'The whole image, with bars of the background color.' : 'Fills the screen; the edges may be cropped.'}
        </span>
      </div>
      <div className="v2-kbp__row v2-kbp__row--top">
        <span id={`${ids}-focal`} className="v2-kbp__label">
          Focal point
        </span>
        <div className="v2-kbp__focal">
          <div
            ref={area}
            className={`v2-kbp__area${focalOff ? ' is-off' : ''}`}
            style={{ width: Math.round(width * scale), height: Math.round(height * scale) }}
            role="slider"
            tabIndex={focalOff ? -1 : 0}
            aria-labelledby={`${ids}-focal`}
            aria-describedby={`${ids}-focal-help`}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(focal.x * 100)}
            aria-valuetext={valueText}
            aria-disabled={focalOff}
            onKeyDown={focalOff ? undefined : onKey}
            onPointerDown={
              focalOff
                ? undefined
                : (e) => {
                    dragging.current = true;
                    e.currentTarget.setPointerCapture?.(e.pointerId);
                    fromPointer(e);
                  }
            }
            onPointerMove={focalOff ? undefined : (e) => dragging.current && fromPointer(e)}
            onPointerUp={() => {
              dragging.current = false;
            }}
          >
            <img src={src} alt="" draggable={false} />
            <span className="v2-kbp__marker" style={{ left: `${focal.x * 100}%`, top: `${focal.y * 100}%` }} aria-hidden="true" />
          </div>
          <p id={`${ids}-focal-help`} className="v2-kbp__hint">
            {fit === 'fit'
              ? 'Fit shows the whole image, so the focal point is not used.'
              : `${valueText}. Click or drag on the image, or use the arrow keys (Shift for bigger steps); what is around it stays in view when the image is cropped.`}
          </p>
          <div>
            <Button size="sm" onClick={() => onFocal(DEFAULT_FOCAL)} disabled={focalOff || (focal.x === 0.5 && focal.y === 0.5)}>
              Centre
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
