import { describe, expect, it } from 'vitest';
import { remoteViewStateText } from './RemoteViewDialog';

describe('remote-view status text', () => {
  it('makes a device denial clear even when the server has not supplied an end reason', () => {
    expect(remoteViewStateText({
      id: 'session', state: 'denied', consentExpiresAt: 0, expiresAt: 0, endedAt: 1,
    }, 'waiting')).toBe('The person holding the device did not approve screen sharing.');
  });

  it('keeps a terminal server reason when one exists', () => {
    expect(remoteViewStateText({
      id: 'session', state: 'stopped', consentExpiresAt: 0, expiresAt: 0, endedAt: 1, endReason: 'Stopped by requester',
    }, 'ended')).toBe('Stopped by requester');
  });
});
