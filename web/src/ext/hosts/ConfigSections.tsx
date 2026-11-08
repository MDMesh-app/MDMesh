// The configuration editor as a host: core's sections and the extensions' through one registry, ordered together,
// gated by `requires`, each in its own boundary. Core's bodies are the editor's own cards (ext/core/config.tsx). An
// extension's section gets a card like core's and its namespace of `extensions`, and joins the editor's changed count,
// leave guard, validation, Discard and single save through what useConfigSections returns.
import {
  createElement,
  useCallback,
  useEffect,
  useRef,
  useState,
  type ComponentType,
  type Dispatch,
  type ReactNode,
  type SetStateAction,
} from 'react';
import type { Configuration, ExtensionError } from '../../api/configurations';
import { Callout, Card, CardHeader } from '../../ui/Card';
import { IconWarning } from '../../ui/icons';
import type { SectionNavItem } from '../../ui/SectionNav';
import { useToast } from '../../ui/toast';
import { CORE_ACTION_ACCESS } from '../core/access';
import { ExtensionBoundary } from '../ExtensionBoundary';
import { deepFreeze } from '../freeze';
import { usePermission } from '../permissions';
import { sectionAnchor, sectionNamespace, type SlotEntry } from '../registry';
import { safeCall } from '../safeCall';
import type { ConfigSectionContribution, ConfigSectionProps } from '../types';
import { useContributions } from '../useContributions';

type Entry = SlotEntry<ConfigSectionContribution<unknown>>;

/** A core section's nav meta ("2 changed", "Default PIN"), which the editor works out from its own data. */
export type ConfigSectionMeta = Pick<SectionNavItem, 'meta' | 'metaTone'>;

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  const proto = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}

function sortedKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortedKeys);
  if (typeof value !== 'object' || value === null) return value;
  const json = (value as { toJSON?: () => unknown }).toJSON;
  if (typeof json === 'function') return sortedKeys(json.call(value));
  const object = value as Record<string, unknown>;
  return Object.fromEntries(Object.keys(object).sort().map((k) => [k, sortedKeys(object[k])]));
}

/**
 * A section value as JSON with its keys sorted, to compare values whatever their keys' order (the server's jsonb keeps
 * none); null when it is not a plain JSON object `extensions` can hold (an array, a cycle, a BigInt).
 */
export function canonicalJson(value: unknown): string | null {
  if (!isPlainObject(value)) return null;
  try {
    return JSON.stringify(sortedKeys(value));
  } catch {
    return null;
  }
}

/** One extension section as the editor stands: its values, and whether its namespace is saved. */
interface SectionState {
  entry: Entry;
  namespace: string;
  anchor: string;
  stored: unknown;
  value: unknown;
  /** The stored value is not a JSON object: the body is not shown, and the namespace keeps what is stored. */
  unreadable: boolean;
  /** The value differs from the stored one and is saved with the configuration. */
  dirty: boolean;
  /** Its changes, for the save bar and the nav. */
  count: number;
}

// A value onChange gave that `extensions` cannot hold is logged once per section, and never saved.
const reportedUnsaveable = new WeakSet<object>();

/** A changed section's count: its `changes()`, at least 1, as its value is saved either way. */
function changesCount(entry: Entry, stored: unknown, value: unknown): number {
  const { changes } = entry.contribution;
  if (!changes) return 1;
  const n = safeCall(changes, [stored, value], 1, `${entry.key}.changes`, entry.contribution);
  return typeof n === 'number' && Number.isFinite(n) ? Math.max(1, Math.round(n)) : 1;
}

function sectionState(entry: Entry, namespace: string, baseline: Configuration, draft: Configuration, crashed: boolean): SectionState {
  const anchor = sectionAnchor(entry.extension, entry.contribution.id);
  // Frozen (it came from JSON, and nothing in core changes it): a body that changes `value` or `stored` in place
  // throws, into its callout, instead of changing what the editor compares against and Discard goes back to.
  // `storage.defaults` is frozen at registration (registry.ts).
  const loaded = deepFreeze(baseline.extensions?.[namespace]);
  const edited = draft.extensions?.[namespace];
  const stored = loaded === undefined ? entry.contribution.storage.defaults : loaded;
  const value = edited === undefined ? stored : edited;
  const storedJson = canonicalJson(stored);
  const valueJson = canonicalJson(value);
  if (valueJson === null && edited !== loaded && !reportedUnsaveable.has(entry.contribution)) {
    reportedUnsaveable.add(entry.contribution);
    console.error(`[ext:${entry.key}] onChange gave a value that is not a plain JSON object; it is not saved`, value);
  }
  const unreadable = storedJson === null;
  // A crashed body's value is not trusted: its namespace keeps what is stored, and the rest still saves.
  const dirty = !unreadable && !crashed && valueJson !== null && valueJson !== storedJson;
  return { entry, namespace, anchor, stored, value, unreadable, dirty, count: dirty ? changesCount(entry, stored, value) : 0 };
}

const problems = (n: number) => (n === 1 ? '1 problem' : `${n} problems`);

export interface ConfigSectionsHost {
  /** The section nav: core's entries with the editor's meta, extensions' with their changes or problems. */
  nav: SectionNavItem[];
  /** Every section the role may see, in order. Render it inside CoreConfigProvider. */
  sections: ReactNode;
  /** The extension sections' changes, for the save bar and the leave guard. */
  count: number;
  /** The changed namespaces and their values: what the save puts on top of the stored `extensions`. */
  changed: Readonly<Record<string, unknown>>;
  /**
   * Runs `validate` of each changed section and shows its problems in it. Returns the first failing section (to
   * point the user at) and the number of problems, or null when there are none.
   */
  validate(): { title: string; message: string; anchor: string; problems: number } | null;
  /** Shows the server's refusal of a save in the sections whose namespaces it names. */
  showServerErrors(errors: readonly ExtensionError[]): void;
  /** Discard: forget the problems shown (the editor resets the draft itself). */
  reset(): void;
}

/**
 * The editor's sections. `draft` and `baseline` are the editor's configuration as edited and as loaded; an extension
 * section's onChange writes `draft.extensions[namespace]` through `setDraft`.
 */
export function useConfigSections({
  draft,
  baseline,
  setDraft,
  readOnly,
  coreMeta,
  idBase,
}: {
  draft: Configuration;
  baseline: Configuration;
  setDraft: Dispatch<SetStateAction<Configuration>>;
  /** Built-in template, or a role that may not save configurations. */
  readOnly: boolean;
  /** Keyed by core section id. */
  coreMeta: Readonly<Record<string, ConfigSectionMeta>>;
  idBase: string;
}): ConfigSectionsHost {
  const entries = useContributions('config.section') as readonly Entry[];
  const toast = useToast();
  // Duplicate (PUT /configurations/copy) needs copy_config, so only a role with it is told to use it.
  const canCopy = usePermission(CORE_ACTION_ACCESS.copyConfig);
  const [errors, setErrors] = useState<Readonly<Record<string, readonly string[]>>>({});
  const [crashed, setCrashed] = useState<ReadonlySet<string>>(() => new Set());

  const onCrash = useCallback((key: string, isCrashed: boolean) => {
    setCrashed((prev) => {
      if (prev.has(key) === isCrashed) return prev;
      const next = new Set(prev);
      if (isCrashed) next.add(key);
      else next.delete(key);
      return next;
    });
  }, []);

  const onChange = useCallback(
    (namespace: string, next: unknown) => {
      // `value` stays frozen after an edit too: the body's own object becomes the draft, and the next render's
      // `value`. Only a plain object, which is all `extensions` holds; a typed array in it stays as it is (deepFreeze),
      // and the save turns it into an object anyway.
      if (isPlainObject(next)) {
        try {
          deepFreeze(next);
        } catch {
          /* not plain JSON all the way down */
        }
      }
      setDraft((d) => ({ ...d, extensions: { ...d.extensions, [namespace]: next } }));
      setErrors((prev) => {
        if (!(namespace in prev)) return prev;
        const rest = { ...prev };
        delete rest[namespace];
        return rest;
      });
    },
    [setDraft],
  );

  const states: SectionState[] = [];
  for (const entry of entries) {
    const namespace = sectionNamespace(entry);
    if (namespace !== null) states.push(sectionState(entry, namespace, baseline, draft, crashed.has(entry.key)));
  }
  const stateOf = (entry: Entry) => states.find((s) => s.entry === entry);
  const saved = readOnly ? [] : states.filter((s) => s.dirty);

  // A role or plugin refresh can hide a section with unsaved changes. Saving them would write data the user can no
  // longer see or undo (and a plugin turned off no longer checks), so its edit is dropped from the draft, and said.
  const pending = useRef<ReadonlyMap<string, SectionState>>(new Map());
  /** The crashed sections' namespaces as of the last render, by section key. */
  const crashedNamespaces = useRef<ReadonlyMap<string, string>>(new Map());
  // After every render: cheap, and it must see each render's sections.
  useEffect(() => {
    const shown = new Set(entries.map((e) => e.key));
    const drop = (namespace: string) =>
      setDraft((d) => {
        const extensions = { ...d.extensions };
        if (baseline.extensions && namespace in baseline.extensions) extensions[namespace] = baseline.extensions[namespace];
        else delete extensions[namespace];
        return { ...d, extensions };
      });
    // A crashed section that is hidden loses its boundary, and its edit leaves the draft as its callout said ("Changes
    // made here aren't saved"), with no second message. Shown again, it renders afresh from the stored value.
    const gone = [...crashed].filter((key) => !shown.has(key));
    if (gone.length) {
      setCrashed((prev) => {
        const next = new Set(prev);
        for (const key of gone) next.delete(key);
        return next;
      });
      for (const key of gone) {
        const namespace = crashedNamespaces.current.get(key);
        if (namespace !== undefined) drop(namespace);
      }
    }
    for (const [key, { namespace, entry }] of pending.current) {
      if (shown.has(key)) continue;
      toast.push('err', 'Changes dropped', `“${entry.contribution.title}” is no longer available to you, so its changes won’t be saved.`);
      drop(namespace);
    }
    pending.current = new Map(saved.map((s) => [s.entry.key, s]));
    crashedNamespaces.current = new Map(states.filter((s) => crashed.has(s.entry.key)).map((s) => [s.entry.key, s.namespace]));
  });

  const nav = entries.map((entry): SectionNavItem => {
    const anchor = sectionAnchor(entry.extension, entry.contribution.id);
    const state = stateOf(entry);
    if (!state) return { id: anchor, label: entry.contribution.title, ...coreMeta[entry.contribution.id] };
    const shown = errors[state.namespace]?.length ?? 0;
    const count = readOnly ? 0 : state.count;
    return {
      id: anchor,
      label: entry.contribution.title,
      meta: shown ? problems(shown) : count ? `${count} changed` : '',
      metaTone: shown ? 'alert' : 'default',
    };
  });

  const sections = entries.map((entry) => {
    const state = stateOf(entry);
    const anchor = sectionAnchor(entry.extension, entry.contribution.id);
    const headingId = `${idBase}-${anchor}-h`;
    const card = (body: ReactNode, subtitle?: string) => (
      <Card key={entry.key} as="section" id={anchor} padding="rows" aria-labelledby={headingId}>
        <CardHeader titleId={headingId} title={entry.contribution.title} subtitle={subtitle} spacing={12} />
        {body}
      </Card>
    );
    if (!state) {
      // Core's body is its whole card; a crash keeps the anchor and the heading, with the callout inside.
      return (
        <ExtensionBoundary key={entry.key} entry={entry} frame={(callout) => card(callout)}>
          {/* Core's bodies read the editor's cards from CoreConfigProvider, so they take no props. */}
          {createElement(entry.contribution.component as unknown as ComponentType)}
        </ExtensionBoundary>
      );
    }
    const { subtitle } = entry.contribution;
    if (state.unreadable) {
      // An update sends them back as stored, which the server accepts unchecked; a new configuration has every
      // namespace checked, so a template's unreadable one stops it (the server's Duplicate copies it as it is).
      return card(
        <Callout tone="alert" role="alert" icon={<IconWarning size={16} />}>
          {baseline.id == null
            ? `The template’s “${entry.contribution.title}” settings can’t be read here, and the server won’t create a configuration with them. ${
                canCopy ? 'Duplicate the template instead.' : 'Someone whose role may duplicate configurations can duplicate the template instead.'
              }`
            : `The saved “${entry.contribution.title}” settings can’t be read here, so saving keeps them as they are.`}
        </Callout>,
        subtitle,
      );
    }
    const props: ConfigSectionProps<unknown> = {
      value: state.value,
      stored: state.stored,
      onChange: (next) => onChange(state.namespace, next),
      readOnly,
      configuration: draft,
      errors: errors[state.namespace] ?? [],
      idBase: `${idBase}-${anchor}`,
    };
    return card(
      <ExtensionBoundary
        entry={entry}
        crashNote="Changes made here aren’t saved."
        onCrash={(isCrashed) => onCrash(entry.key, isCrashed)}
      >
        {createElement(entry.contribution.component, props)}
      </ExtensionBoundary>,
      subtitle,
    );
  });

  const changed: Record<string, unknown> = {};
  for (const s of saved) changed[s.namespace] = s.value;

  return {
    nav,
    sections,
    count: saved.reduce((n, s) => n + s.count, 0),
    changed,
    validate() {
      const found: Record<string, readonly string[]> = {};
      let total = 0;
      let first: { title: string; message: string; anchor: string } | null = null;
      for (const s of saved) {
        const { validate } = s.entry.contribution;
        if (!validate) continue;
        // One that throws counts as no problems: the server still checks the data.
        const result = safeCall(validate, [s.value, draft], [], `${s.entry.key}.validate`, s.entry.contribution);
        const messages = Array.isArray(result) ? result.filter((m): m is string => typeof m === 'string' && m.trim() !== '') : [];
        if (!messages.length) continue;
        found[s.namespace] = messages;
        total += messages.length;
        first ??= { title: s.entry.contribution.title, message: messages[0], anchor: s.anchor };
      }
      setErrors(found);
      return first ? { ...first, problems: total } : null;
    },
    showServerErrors(refused) {
      const found: Record<string, string[]> = {};
      for (const e of refused) {
        if (e.namespace !== null && states.some((s) => s.namespace === e.namespace)) {
          (found[e.namespace] ??= []).push(e.message);
        }
      }
      setErrors(found);
    },
    reset() {
      setErrors({});
    },
  };
}
