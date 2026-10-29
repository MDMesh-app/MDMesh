// The notes vite.changelog.ts (the changelogNotes plugin vite.config.ts uses) bundles from CHANGELOG.md.

declare module 'virtual:mdmesh-changelog' {
  /** The versions (X.Y.Z) whose CHANGELOG.md section this build carries, newest first. */
  export const versions: readonly string[];
  /** A dev build whose CHANGELOG.md has something under Unreleased. */
  export const hasUnreleased: boolean;
  /** The sections themselves, a chunk of their own. */
  export function load(): Promise<typeof import('virtual:mdmesh-changelog/notes')>;
}

declare module 'virtual:mdmesh-changelog/notes' {
  /** A ChangelogSection (./changelog.ts); an ambient module can't import it by a relative path. */
  interface Section {
    version: string | null;
    date: string | null;
    body: string;
  }
  export const sections: Readonly<Record<string, Section>>;
  /** Dev builds only. */
  export const unreleased: Section | null;
}
