/** Location state the editor leaves on the list after a save: poll this configuration's sync summary until `until`. */
export interface ConfigWatch {
  id: number;
  until: number;
}

/** How long the list follows a saved configuration's devices (classic: two minutes). */
export const WATCH_MS = 120_000;
