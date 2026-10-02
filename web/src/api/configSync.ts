import { apiClient } from './client';

export interface ConfigStatus {
  configurationId?: number | null;
  currentRevision?: string | null;
  appliedRevision?: string | null;
  appliedAt?: number | null;
  inSync: boolean;
  supported: boolean;
  lastCommand?: { id: number; status: string; detail?: string | null; createdAt?: number; completedAt?: number } | null;
}

export interface ConfigSyncSummary {
  configurationId: number; total: number; inSync: number; outOfSync: number; unsupported: number; neverSeen: number;
}

/** Shape of `lastCommand.detail` for config.apply (proto/payloads/config-apply-result.schema.json). */
export interface ConfigOutcomes { revision?: string; outcomes: Record<string, string> }

export function getConfigStatus(deviceId: number | string): Promise<ConfigStatus | null> {
  return apiClient.get<ConfigStatus | null>(`/private/agent/v1/devices/${deviceId}/configStatus`);
}

export function getSyncSummary(): Promise<ConfigSyncSummary[]> {
  return apiClient.get<ConfigSyncSummary[]>('/private/agent/v1/configurations/syncSummary');
}

export function parseOutcomes(detail: string | null | undefined): ConfigOutcomes | null {
  if (!detail) return null;
  try {
    const v = JSON.parse(detail) as Partial<ConfigOutcomes>;
    if (!v || typeof v !== 'object' || !v.outcomes || typeof v.outcomes !== 'object' || Array.isArray(v.outcomes)) return null;
    if (!Object.values(v.outcomes).every((o) => typeof o === 'string')) return null;
    return { revision: v.revision, outcomes: v.outcomes };
  } catch { return null; }
}

/** One-line verdict for badges. Pure so it can be unit-tested once Vitest lands (Phase 6.1). */
export function summarizeStatus(s: ConfigStatus | null): { tone: 'ok' | 'warn' | 'alert' | 'idle'; label: string } {
  if (!s || s.configurationId == null) return { tone: 'idle', label: 'No configuration' };
  if (!s.supported) return { tone: 'warn', label: 'Agent too old' };
  const outcomes = parseOutcomes(s.lastCommand?.detail);
  if (outcomes && outcomes.revision === s.currentRevision && Object.values(outcomes.outcomes).some((v) => v.startsWith('failed'))) {
    return { tone: 'alert', label: 'Apply failed' };
  }
  if (outcomes && outcomes.revision === s.currentRevision && Object.values(outcomes.outcomes).includes('unsupported')) {
    return { tone: 'warn', label: 'Partially supported' };
  }
  if (s.inSync) return { tone: 'ok', label: 'In sync' };
  const st = s.lastCommand?.status;
  if (st === 'pending' || st === 'delivered') return { tone: 'warn', label: 'Applying…' };
  if (st === 'failed') return { tone: 'alert', label: 'Apply failed' };
  if (!s.appliedRevision) return { tone: 'idle', label: 'Not reported yet' };
  return { tone: 'warn', label: 'Out of sync' };
}
