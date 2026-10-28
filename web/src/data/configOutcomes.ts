// config.apply outcomes (the agent's per-key result in a command's `detail`): labels, classification and the one-line
// summary. Shared by the device page's configuration panel and the command history wording (deviceActivity.ts).
import { CONFIG_FIELDS } from './configFields';

const FIELD_LABEL = new Map(CONFIG_FIELDS.map((f) => [f.key, f.label]));

/** A server plugin's policy key, `x.<plugin id>.<key>` (DesiredConfigContributor), as its plugin and its key. */
const PLUGIN_KEY = /^x\.([a-z][a-z0-9-]*)\.([A-Za-z][A-Za-z0-9]*)$/;

/** Mixed-case words a key may hold, as a label spells them (a whole word only: not followed by a lowercase letter). */
const KNOWN_WORDS: Readonly<Record<string, string>> = { IPv4: 'IPv4', IPv6: 'IPv6', WiFi: 'Wi-Fi', '2FA': '2FA' };
const KNOWN_WORD = new RegExp(`(${Object.keys(KNOWN_WORDS).join('|')})(?![a-z])`);

/** camelCase in words, lowercase but for its acronyms. */
function humps(part: string): string[] {
  return part
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    .split(' ')
    .filter(Boolean)
    .map((w) => (/^[A-Z][A-Z0-9]+$/.test(w) ? w : w.toLowerCase()));
}

/**
 * A camelCase key in words, lowercase but for its acronyms and the mixed-case words it knows: `blockTethering` →
 * "block tethering", `enableUSB` → "enable USB", `USBMode` → "USB mode", `enableIPv6` → "enable IPv6", `allowWiFi` →
 * "allow Wi-Fi".
 */
function words(key: string): string {
  // split() with a capturing group puts each known word at an odd index.
  return key
    .split(KNOWN_WORD)
    .flatMap((part, i) => (i % 2 ? [KNOWN_WORDS[part]] : humps(part)))
    .join(' ');
}

/**
 * config.apply outcome key → label: `policies.usbStorage` → "USB storage", `kiosk` → "Kiosk mode", a server plugin's
 * `policies.x.netaccess.blockTethering` → "netaccess: block tethering".
 */
export function outcomeKeyLabel(key: string): string {
  const k = key.startsWith('policies.') ? key.slice('policies.'.length) : key;
  if (k === 'kiosk') return 'Kiosk mode';
  if (k === 'location') return 'Location reporting';
  if (k === 'apps' || k === 'applications') return 'Apps';
  const plugin = key.startsWith('policies.') ? PLUGIN_KEY.exec(k) : null;
  if (plugin) return `${plugin[1]}: ${words(plugin[2])}`;
  return FIELD_LABEL.get(k) ?? key;
}

export type OutcomeKind = 'applied' | 'failed' | 'unsupported' | 'deferred';

export interface Outcome {
  key: string;
  label: string;
  kind: OutcomeKind;
  /** The value column ("Applied", "Failed", "Not supported", "Deferred"). */
  text: string;
  /** The reason after "failed: " / "deferred: ". */
  reason?: string;
}

/** Classify one outcome string from `lastCommand.detail` (proto config-apply-result). */
export function classifyOutcome(key: string, raw: string): Outcome {
  const label = outcomeKeyLabel(key);
  const reasonOf = (p: string) => {
    const r = raw.slice(p.length).replace(/^[:\s]+/, '').trim();
    return r || undefined;
  };
  if (raw === 'applied') return { key, label, kind: 'applied', text: 'Applied' };
  if (raw === 'unsupported') return { key, label, kind: 'unsupported', text: 'Not supported' };
  if (raw.startsWith('deferred')) return { key, label, kind: 'deferred', text: 'Deferred', reason: reasonOf('deferred') };
  if (raw.startsWith('failed')) return { key, label, kind: 'failed', text: 'Failed', reason: reasonOf('failed') };
  return { key, label, kind: 'failed', text: raw };
}

/** "4 settings applied, 1 failed, 1 not supported on this device." */
export function outcomeSummary(list: Outcome[]): string {
  const n = (k: OutcomeKind) => list.filter((o) => o.kind === k).length;
  const parts: string[] = [];
  const applied = n('applied');
  parts.push(`${applied} setting${applied === 1 ? '' : 's'} applied`);
  if (n('failed')) parts.push(`${n('failed')} failed`);
  if (n('deferred')) parts.push(`${n('deferred')} deferred`);
  if (n('unsupported')) parts.push(`${n('unsupported')} not supported on this device`);
  return `${parts.join(', ')}.`;
}

