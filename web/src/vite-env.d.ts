/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE?: string;
  /** '1' includes the component gallery (/_gallery) in a production build (compare harness). */
  readonly VITE_GALLERY?: string;
  /** Comma-separated extension ids to leave out of the build's registry (ext/load.ts). */
  readonly VITE_EXTENSIONS_DISABLED?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
