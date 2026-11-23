// Fill / Fit and the focal point picker: keyboard, pointer, reset, and off in Fit; free of axe violations.
import { fireEvent, render, screen } from '@testing-library/react';
import axe from 'axe-core';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import type { FitMode, FocalPoint } from '../../data/kioskScrim';
import { KioskPlacement } from './KioskPlacement';

function Harness({ initialFit = 'fill' as FitMode, onChange }: { initialFit?: FitMode; onChange?: (f: FocalPoint, m: FitMode) => void }) {
  const [fit, setFit] = useState<FitMode>(initialFit);
  const [focal, setFocal] = useState<FocalPoint>({ x: 0.5, y: 0.5 });
  return (
    <div className="v2">
      <KioskPlacement
        src="/x.jpg"
        width={4000}
        height={3000}
        fit={fit}
        focal={focal}
        onFit={(m) => {
          setFit(m);
          onChange?.(focal, m);
        }}
        onFocal={(f) => {
          setFocal(f);
          onChange?.(f, fit);
        }}
      />
    </div>
  );
}

const picker = () => screen.getByRole('slider', { name: 'Focal point' });

describe('KioskPlacement', () => {
  it('moves the focal point with the arrow keys, Shift for bigger steps, Home to the centre', async () => {
    render(<Harness />);
    fireEvent.keyDown(picker(), { key: 'ArrowRight' });
    expect(picker().getAttribute('aria-valuetext')).toBe('51% across, 50% down');
    fireEvent.keyDown(picker(), { key: 'ArrowDown', shiftKey: true });
    expect(picker().getAttribute('aria-valuetext')).toBe('51% across, 60% down');
    for (let i = 0; i < 20; i++) fireEvent.keyDown(picker(), { key: 'ArrowLeft', shiftKey: true });
    expect(picker().getAttribute('aria-valuetext')).toBe('0% across, 60% down');
    fireEvent.keyDown(picker(), { key: 'Home' });
    expect(picker().getAttribute('aria-valuetext')).toBe('50% across, 50% down');
    const res = await axe.run(document.body, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
      rules: { 'color-contrast': { enabled: false } },
    });
    expect(res.violations.map((v) => v.id)).toEqual([]);
  });

  it('sets it where the image is clicked, and Centre resets it', () => {
    const seen: FocalPoint[] = [];
    render(<Harness onChange={(f) => seen.push(f)} />);
    const area = picker();
    area.getBoundingClientRect = () => ({ left: 100, top: 50, width: 200, height: 150, right: 300, bottom: 200, x: 100, y: 50, toJSON: () => ({}) });
    fireEvent.pointerDown(area, { clientX: 150, clientY: 80, pointerId: 1 });
    expect(seen[seen.length - 1]).toEqual({ x: 0.25, y: 0.2 });
    fireEvent.pointerMove(area, { clientX: 400, clientY: 300, pointerId: 1 });
    expect(seen[seen.length - 1]).toEqual({ x: 1, y: 1 });
    fireEvent.pointerUp(area, { pointerId: 1 });
    fireEvent.pointerMove(area, { clientX: 150, clientY: 80, pointerId: 1 });
    expect(seen[seen.length - 1]).toEqual({ x: 1, y: 1 });
    // A cancelled touch ends the drag: a later hover doesn't move the point.
    fireEvent.pointerDown(area, { clientX: 150, clientY: 80, pointerId: 2 });
    fireEvent.pointerCancel(area, { pointerId: 2 });
    fireEvent.pointerMove(area, { clientX: 300, clientY: 200, pointerId: 2 });
    expect(seen[seen.length - 1]).toEqual({ x: 0.25, y: 0.2 });
    fireEvent.click(screen.getByRole('button', { name: 'Centre' }));
    expect(seen[seen.length - 1]).toEqual({ x: 0.5, y: 0.5 });
  });

  it('switches to Fit, where the focal point is not used', () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole('radio', { name: 'Fit' }));
    expect(picker().getAttribute('aria-disabled')).toBe('true');
    expect(picker().getAttribute('tabindex')).toBe('-1');
    fireEvent.keyDown(picker(), { key: 'ArrowRight' });
    expect(picker().getAttribute('aria-valuetext')).toBe('50% across, 50% down');
    expect(screen.getByText(/focal point is not used/)).toBeTruthy();
  });
});
