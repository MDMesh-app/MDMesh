// Rules for the staged agent rollout, shared by the Overview and Settings.
import type { ActiveRollout } from '../api/rollout';

/** A rollout that is still running (canary or fleet stage); done and cancelled ones are history. */
export const rolloutActive = (r: ActiveRollout | null | undefined): r is ActiveRollout =>
  !!r && (r.stage === 'canary' || r.stage === 'fleet');
