/** The LAN POC accepts only a direct browser-to-device host candidate pair. */
export interface IcePath {
  localType: string;
  remoteType: string;
}

type CandidatePair = RTCStats & {
  selected?: boolean;
  nominated?: boolean;
  state?: string;
  localCandidateId?: string;
  remoteCandidateId?: string;
};

type Candidate = RTCStats & { candidateType?: string };

export function selectedIcePath(report: RTCStatsReport): IcePath | null {
  const entries = [...report.values()];
  const pair = entries.find((entry) => {
    const value = entry as CandidatePair;
    return value.type === 'candidate-pair'
      && (value.selected === true || (value.nominated === true && value.state === 'succeeded'));
  }) as CandidatePair | undefined;
  if (!pair?.localCandidateId || !pair.remoteCandidateId) return null;
  const local = report.get(pair.localCandidateId) as Candidate | undefined;
  const remote = report.get(pair.remoteCandidateId) as Candidate | undefined;
  if (!local?.candidateType || !remote?.candidateType) return null;
  return { localType: local.candidateType, remoteType: remote.candidateType };
}

export function isDirectLanPath(path: IcePath | null): boolean {
  return path?.localType === 'host' && path.remoteType === 'host';
}

export function icePathLabel(path: IcePath | null): string {
  return path ? `${path.localType} ↔ ${path.remoteType}` : 'waiting for selected pair';
}
