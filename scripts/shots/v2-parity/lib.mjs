// Shared options for the parity tests. Each test serves a built SPA (the dist below) with the shots harness
// fixtures, drives one page with Playwright and exits 1 when a check fails.
//   --dist <dir>   build to serve (env V2_PARITY_DIST; default $TMPDIR/mdmesh-dist-v2 if it exists, else web/dist)
//   --out <dir>    where a test may write screenshots (env V2_PARITY_OUT; default a fresh temp dir)
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DIST } from '../harness.mjs';

const arg = (k) => {
  const i = process.argv.indexOf(k);
  return i > 0 && process.argv[i + 1] ? process.argv[i + 1] : undefined;
};
const fallback = path.join(process.env.TMPDIR || os.tmpdir(), 'mdmesh-dist-v2');

export const dist = arg('--dist') ?? process.env.V2_PARITY_DIST ?? (fs.existsSync(fallback) ? fallback : DIST);
export const outDir = arg('--out') ?? process.env.V2_PARITY_OUT ?? fs.mkdtempSync(path.join(process.env.TMPDIR || os.tmpdir(), 'v2-parity-'));
fs.mkdirSync(outDir, { recursive: true });
