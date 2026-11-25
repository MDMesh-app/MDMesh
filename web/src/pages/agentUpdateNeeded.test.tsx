// A device that runs its configuration's current revision, but whose agent doesn't apply a section the configuration
// sets (Connectivity on an agent before 0.4.0): the configuration panel says which, and offers no Retry apply, which
// would only answer "already applied".
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ConfigStatus } from '../api/configSync';
import { renderInConsole } from '../test/console';
import { ConfigPanel } from './DeviceDetailControl';

const REVISION = 'b'.repeat(64);

function panel(status: ConfigStatus, isDeviceOwner = true) {
  renderInConsole(
    <ConfigPanel
      status={status}
      configName="Warehouse"
      isDeviceOwner={isDeviceOwner}
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
    // Where agent updates are, as on Overview and Devices.
    expect(screen.getByRole('link', { name: 'Settings → Agent rollout' }).getAttribute('href')).toMatch(/#agent-rollout$/);
  });

  it('adds that app permissions also need device owner when the agent isn’t', () => {
    panel({ ...status, agentUpdateNeeded: ['appPermissions'] }, false);
    expect(document.body.textContent).toContain('App permissions also need the agent to be device owner.');
  });

  it('is the plain in-sync panel without it', () => {
    panel({ ...status, agentUpdateNeeded: undefined });
    expect(screen.getByText('Configuration in sync')).toBeTruthy();
    expect(screen.queryByText('Agent update needed')).toBeNull();
  });
});

// A current agent that isn't device owner can't take app permission rules: the server names them apart from an agent
// update (no newer agent would apply them), and the panel says why the device stays out of sync, with no Retry apply.
describe('the configuration panel for an agent that isn’t device owner', () => {
  const status = {
    configurationId: 3,
    currentRevision: REVISION,
    appliedRevision: 'a'.repeat(64),
    inSync: false,
    supported: true,
  } as ConfigStatus;

  it('names the settings that need device owner and offers no retry', () => {
    panel({ ...status, needsDeviceOwner: ['appPermissions'] }, false);
    expect(screen.getByText('Device owner needed')).toBeTruthy();
    expect(document.body.textContent).toContain('Warehouse sets App permissions, which an agent applies only as device owner.');
    expect(screen.queryByRole('button', { name: /Retry apply/ })).toBeNull();
  });

  it('is the plain out-of-sync panel, with a retry, when no setting needs it', () => {
    panel(status, false);
    expect(screen.getByText('Configuration out of sync')).toBeTruthy();
    expect(screen.getByRole('button', { name: /Retry apply/ })).toBeTruthy();
  });

  it('keeps the retry after an apply that failed', () => {
    panel({ ...status, needsDeviceOwner: ['appPermissions'], lastCommand: { id: 1, status: 'failed' } }, false);
    expect(screen.getByRole('button', { name: /Retry apply/ })).toBeTruthy();
  });
});
