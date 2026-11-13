// The device page's Telemetry tab: the System update row, from telemetry security.pendingSystemUpdateAt and
// pendingSystemUpdateSecurityPatch (agent 0.5.0, which leaves out a key it has no value for).
import { cleanup, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { TelemetrySnapshot } from '../api/telemetry';
import { fmtDateTime } from '../ui/format';
import { TelemetryTab } from './DeviceDetailTabs';

const AT = Date.UTC(2026, 10, 3, 9, 30);

/** The System update row's value, or null when the tab has no such row (the tab rendered alone, until the next). */
function updateRow(telemetry: TelemetrySnapshot, agentVersion?: string | null): string | null {
  cleanup();
  render(<TelemetryTab telemetry={telemetry} loaded agentVersion={agentVersion} />);
  const label = screen.queryAllByText('System update').find((el) => el.tagName === 'DT');
  return label ? (label.nextElementSibling?.textContent ?? '') : null;
}

describe('telemetry: System update', () => {
  it('shows a waiting update with the time the device learned of it, and whether it is a security patch', () => {
    const since = `Waiting since ${fmtDateTime(AT)}`;
    expect(updateRow({ security: { pendingSystemUpdateAt: AT, pendingSystemUpdateSecurityPatch: true } }, '0.5.0')).toBe(
      `${since} (security patch)`,
    );
  });

  it('says when it is not a security patch', () => {
    const since = `Waiting since ${fmtDateTime(AT)}`;
    expect(updateRow({ security: { pendingSystemUpdateAt: AT, pendingSystemUpdateSecurityPatch: false } }, '0.5.0')).toBe(
      `${since} (not a security patch)`,
    );
  });

  it('shows the time alone when the device sends no security-patch flag', () => {
    expect(updateRow({ security: { pendingSystemUpdateAt: AT } }, '0.5.1')).toBe(`Waiting since ${fmtDateTime(AT)}`);
  });

  it('says None reported for an agent that reports updates and sent none', () => {
    expect(updateRow({ security: { patchAgeDays: 12 } }, '0.5.0')).toBe('None reported');
    expect(updateRow({ security: { patchAgeDays: 12 } }, '1.0.0')).toBe('None reported');
  });

  it('has no row for an older or unknown agent, or without a security section, as for any key not sent', () => {
    expect(updateRow({ security: { patchAgeDays: 12 } }, '0.4.0')).toBeNull();
    expect(updateRow({ security: { patchAgeDays: 12 } }, null)).toBeNull();
    expect(updateRow({ hardware: { model: 'Pixel 8' } }, '0.5.0')).toBeNull();
  });

  it('shows a dash for a time that is not a date, and never lists the keys again under Other', () => {
    expect(updateRow({ security: { pendingSystemUpdateAt: 'soon', pendingSystemUpdateSecurityPatch: true } }, '0.5.0')).toBe('—');
    expect(screen.queryByText('pendingSystemUpdateAt')).toBeNull();
    expect(screen.queryByText('pendingSystemUpdateSecurityPatch')).toBeNull();
  });
});
