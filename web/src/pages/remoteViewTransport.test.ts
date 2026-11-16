import { describe, expect, it } from 'vitest';
import { icePathLabel, isDirectLanPath, selectedIcePath } from './remoteViewTransport';

function report(entries: Array<[string, Record<string, unknown>]>): RTCStatsReport {
  return new Map(entries) as unknown as RTCStatsReport;
}

describe('remote-view LAN transport', () => {
  it('finds and accepts a selected host-to-host path', () => {
    const path = selectedIcePath(report([
      ['pair', { type: 'candidate-pair', selected: true, localCandidateId: 'local', remoteCandidateId: 'remote' }],
      ['local', { type: 'local-candidate', candidateType: 'host' }],
      ['remote', { type: 'remote-candidate', candidateType: 'host' }],
    ]));
    expect(path).toEqual({ localType: 'host', remoteType: 'host' });
    expect(isDirectLanPath(path)).toBe(true);
    expect(icePathLabel(path)).toBe('host ↔ host');
  });

  it('rejects a selected non-host path', () => {
    const path = selectedIcePath(report([
      ['pair', { type: 'candidate-pair', nominated: true, state: 'succeeded', localCandidateId: 'local', remoteCandidateId: 'remote' }],
      ['local', { type: 'local-candidate', candidateType: 'host' }],
      ['remote', { type: 'remote-candidate', candidateType: 'srflx' }],
    ]));
    expect(isDirectLanPath(path)).toBe(false);
    expect(icePathLabel(path)).toBe('host ↔ srflx');
  });

  it('does not claim a path before selected-pair statistics exist', () => {
    const path = selectedIcePath(report([['pair', { type: 'candidate-pair', state: 'succeeded' }]]));
    expect(path).toBeNull();
    expect(icePathLabel(path)).toBe('waiting for selected pair');
  });
});
