// A device that runs its configuration's current revision, but whose agent doesn't apply a section the configuration
// sets (Connectivity on an agent before 0.4.0): the configuration panel says which, and offers no Retry apply, which
// would only answer "already applied".
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ConfigStatus } from '../api/configSync';
import { renderInConsole } from '../test/console';
import { ConfigPanel } from './DeviceDetailControl';

const REVISION = 'b'.repeat(64);

function panel(status: ConfigStatus) {
  renderInConsole(
    <ConfigPanel
      status={status}
      configName="Warehouse"
      isDeviceOwner
      androidSdkInt={34}
      busy={false}
      onRetry={() => {}}
      now={Date.now()}
    />,
  );
}

describe('the configuration panel when the agent needs an update', () => {
  const status: ConfigStatus = {
    configurationId: 3,
    currentRevision: REVISION,
    appliedRevision: REVISION,
    inSync: true,
    supported: true,
    agentUpdateNeeded: ['connectivity'],
  };

  it('names the sections and offers no Retry apply', () => {
    panel(status);
    expect(screen.getByText('Agent update needed')).toBeTruthy();
    expect(document.body.textContent).toContain('Warehouse sets Connectivity, which this device’s agent doesn’t apply.');
    expect(screen.queryByRole('button', { name: /Retry apply/ })).toBeNull();
  });

  it('is the plain in-sync panel without it', () => {
    panel({ ...status, agentUpdateNeeded: undefined });
    expect(screen.getByText('Configuration in sync')).toBeTruthy();
    expect(screen.queryByText('Agent update needed')).toBeNull();
  });
});
