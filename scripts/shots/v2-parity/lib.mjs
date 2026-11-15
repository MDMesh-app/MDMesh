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

// ---- Radix widgets: the console's selects and dialogs ----
/** Any open dialog: Radix Dialog / AlertDialog render divs with role dialog / alertdialog (no native <dialog>). */
export const DIALOG = '[role="dialog"], [role="alertdialog"]';

/**
 * Picks an option in a Select (web/src/ui/Input, Radix): opens its trigger (role combobox), then presses the option,
 * as a pointer user would. `select` is the trigger Locator, or its accessible name looked up (exactly) in `scope`
 * (a Page or a Locator). `option` is the option's visible name (string = exact, or RegExp) or `{ value }` (options
 * and the trigger carry their value as data-value).
 */
export async function pickOption(scope, select, option) {
  const trigger = typeof select === 'string' ? scope.getByRole('combobox', { name: select, exact: true }) : select;
  const page = typeof trigger.page === 'function' ? trigger.page() : scope;
  await trigger.click();
  const box = page.getByRole('listbox');
  const target = typeof option === 'object' && !(option instanceof RegExp)
    ? box.locator(`[role="option"][data-value="${String(option.value)}"]`)
    : box.getByRole('option', { name: option, exact: typeof option === 'string' });
  await target.click();
  await box.waitFor({ state: 'detached', timeout: 2000 }).catch(() => {});
}

/** The value a Select (Radix) holds: its trigger's data-value. */
export const pickedValue = (trigger) => trigger.getAttribute('data-value');

/**
 * The option names a Select (Radix) offers: opens its listbox, reads them, closes it with Escape (the value is
 * unchanged). `select` is the trigger Locator, or its accessible name looked up (exactly) in `scope`.
 */
export async function optionNames(scope, select) {
  const trigger = typeof select === 'string' ? scope.getByRole('combobox', { name: select, exact: true }) : select;
  const page = typeof trigger.page === 'function' ? trigger.page() : scope;
  await trigger.click();
  const box = page.getByRole('listbox');
  await box.waitFor();
  const names = await box.getByRole('option').allInnerTexts();
  await page.keyboard.press('Escape');
  await box.waitFor({ state: 'detached', timeout: 2000 }).catch(() => {});
  return names.map((n) => n.trim());
}
