import { describe, expect, it } from 'vitest';
import { remoteViewSocketUrl } from './remoteView';

describe('remoteViewSocketUrl', () => {
  it('uses a same-origin secure viewer socket for an HTTPS console', () => {
    expect(remoteViewSocketUrl('a/b c', { protocol: 'https:', host: 'mdm.example:8443' } as Location))
      .toBe('wss://mdm.example:8443/agent/viewer/a%2Fb%20c');
  });

  it('uses ws only for an explicitly non-TLS development origin', () => {
    expect(remoteViewSocketUrl('session-1', { protocol: 'http:', host: '127.0.0.1:5173' } as Location))
      .toBe('ws://127.0.0.1:5173/agent/viewer/session-1');
  });
});
