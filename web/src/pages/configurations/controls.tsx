import { useState, type ReactNode } from 'react';
import type { Application } from '../../api/applications';
import type { ConfigApp } from '../../api/configurations';
import { DEFAULT_SEGMENTS, flagOffValue, type FieldDef } from '../../data/configFields';
import { appNameForVersionId, versionIdForApp } from '../../data/configurationRules';
import {
  Button,
  ColourInput,
  IconEye,
  IconEyeOff,
  Input,
  Segmented,
  Select,
  Textarea,
  Toggle,
  TriState,
  TwoState,
  type InputTone,
} from '../../ui';

export interface ControlProps {
  def: FieldDef;
  value: unknown;
  /** The value as loaded: a `flag` switched off restores a stored null, an emptied text field its stored null. */
  stored: unknown;
  onChange: (v: unknown) => void;
  disabled?: boolean;
  labelId: string;
  helpId?: string;
  /** For `app` fields: the choices and the configuration's assigned apps (to resolve version ids). */
  apps?: Application[];
  assigned?: ConfigApp[];
  tone?: InputTone;
}

/**
 * Presentation of `flag` fields (configFields: nullable Boolean where null ≡ false). Each keeps the classic semantics;
 * only the control differs. `toggle` = a switch, On = true.
 */
const FLAG_UI: Record<string, { labels: [string, string]; falseFirst?: boolean } | 'toggle'> = {
  kioskExit: { labels: ['Visible button', 'Hidden gesture'], falseFirst: true },
  kioskHome: { labels: ['Allow', 'Block'] },
  kioskRecents: { labels: ['Allow', 'Block'] },
  kioskNotifications: { labels: ['Allow', 'Block'] },
  kioskKeyguard: { labels: ['Allow', 'Block'] },
  kioskSystemInfo: { labels: ['Show', 'Hide'] },
  kioskLockButtons: 'toggle',
  kioskLockVolume: 'toggle',
};

/** Enums shown as a segmented control in the main editor; others are selects. */
const SEGMENTED_ENUMS: ReadonlySet<string> = new Set(['iconSize', 'requestUpdates']);

/** Text-like change: emptying a field whose stored value was null keeps null, so it doesn't count as a change. */
const textValue = (v: string, stored: unknown) => (v === '' && stored == null ? stored : v);

/** The control for one configuration field, by its configFields type. */
export function FieldControl({ def, value, stored, onChange, disabled, labelId, helpId, apps = [], assigned = [], tone }: ControlProps) {
  const aria = { 'aria-labelledby': labelId, 'aria-describedby': helpId };
  switch (def.type) {
    case 'switch':
      return <Toggle {...aria} checked={value === true} disabled={disabled} onChange={(v) => onChange(v)} />;
    case 'flag': {
      // null and false mean the same on the device; switching off never turns a stored null into false.
      const set = (on: boolean) => onChange(on ? true : flagOffValue(stored));
      const ui = FLAG_UI[def.key] ?? (def.flagLabels ? { labels: [def.flagLabels[1], def.flagLabels[0]], falseFirst: true } : 'toggle');
      if (ui === 'toggle') return <Toggle {...aria} checked={value === true} disabled={disabled} onChange={set} />;
      return (
        <TwoState
          {...aria}
          value={value === true}
          onChange={set}
          labels={ui.labels}
          falseFirst={ui.falseFirst}
          disabled={disabled}
        />
      );
    }
    case 'tri':
      return (
        <TriState
          {...aria}
          segments={def.segments ?? DEFAULT_SEGMENTS}
          value={(value as boolean | null | undefined) ?? null}
          onChange={onChange}
          disabled={disabled}
        />
      );
    case 'enum': {
      const numeric = typeof def.options?.[0]?.value === 'number';
      if (SEGMENTED_ENUMS.has(def.key)) {
        // null selects nothing: never coerce an unset value to an option.
        return (
          <Segmented<string | number>
            {...aria}
            options={(def.options ?? []).map((o) => ({ value: o.value, label: o.label }))}
            value={value == null ? undefined : (value as string | number)}
            onChange={onChange}
            disabled={disabled}
          />
        );
      }
      return (
        <Select
          {...aria}
          className="v2-cfged-ctl"
          value={value == null ? '' : String(value)}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value === '' ? null : numeric ? Number(e.target.value) : e.target.value)}
        >
          <option value="">—</option>
          {def.options?.map((o) => (
            <option key={String(o.value)} value={String(o.value)}>
              {o.label}
            </option>
          ))}
        </Select>
      );
    }
    case 'app':
      return <AppSelect {...aria} value={value} apps={apps} assigned={assigned} disabled={disabled} onChange={onChange} />;
    case 'int':
      return (
        <Input
          {...aria}
          className="v2-cfged-ctl v2-cfged-ctl--num"
          type="number"
          size="sm"
          min={def.min}
          max={def.max}
          value={value == null ? '' : String(value)}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
        />
      );
    case 'textarea':
      return (
        <Textarea
          {...aria}
          className="v2-cfged-ctl v2-cfged-ctl--wide"
          rows={2}
          value={value == null ? '' : String(value)}
          disabled={disabled}
          onChange={(e) => onChange(textValue(e.target.value, stored))}
        />
      );
    case 'time':
      return (
        <Input
          {...aria}
          className="v2-cfged-ctl"
          type="time"
          size="sm"
          value={value == null ? '' : String(value)}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value || null)}
        />
      );
    case 'color':
      // Unset = the kiosk launcher's own default: "Default", with Reset back to it.
      return (
        <ColourInput
          aria-label={def.label}
          value={value ? String(value) : null}
          disabled={disabled}
          onChange={(v) => onChange(v)}
        />
      );
    case 'password':
      return (
        <SecretInput
          labelId={labelId}
          helpId={helpId}
          value={value}
          disabled={disabled}
          tone={tone}
          pin={def.key === 'password'}
          onChange={(v) => onChange(textValue(v, stored))}
        />
      );
    default:
      return (
        <Input
          {...aria}
          className="v2-cfged-ctl v2-cfged-ctl--wide"
          size="sm"
          value={value == null ? '' : String(value)}
          disabled={disabled}
          onChange={(e) => onChange(textValue(e.target.value, stored))}
        />
      );
  }
}

/** applicationVersions.id selector (mainAppId, contentAppId); unknown ids stay selectable as "<name> (version #id)". */
export function AppSelect({
  value,
  apps,
  assigned,
  disabled,
  onChange,
  ...aria
}: {
  value: unknown;
  apps: Application[];
  assigned: ConfigApp[];
  disabled?: boolean;
  onChange: (v: unknown) => void;
  'aria-labelledby'?: string;
  'aria-describedby'?: string;
}) {
  const options = apps
    .map((a) => ({ a, vid: versionIdForApp(a, assigned) }))
    .filter((o): o is { a: Application; vid: number } => o.vid != null);
  const known = value == null || options.some((o) => o.vid === value);
  const label = (a: Application) => {
    const v = assigned.find((x) => x.id === a.id)?.version ?? a.version;
    return v ? `${a.name} ${v}` : a.name;
  };
  return (
    <Select
      {...aria}
      className="v2-cfged-appselect"
      value={value == null ? '' : String(value)}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
    >
      <option value="">— none —</option>
      {!known ? (
        <option value={String(value)}>
          {appNameForVersionId(apps, value as number, assigned)} (version #{String(value)})
        </option>
      ) : null}
      {options.map(({ a, vid }) => (
        <option key={a.id} value={vid}>
          {label(a)}
        </option>
      ))}
    </Select>
  );
}

/** Password field with a Show / Hide button (exit PIN). The kiosk PIN must stay readable for admins. */
function SecretInput({
  labelId,
  helpId,
  value,
  disabled,
  tone,
  pin,
  onChange,
}: {
  labelId: string;
  helpId?: string;
  value: unknown;
  disabled?: boolean;
  tone?: InputTone;
  /** Mono, letter-spaced (the exit PIN). */
  pin?: boolean;
  onChange: (v: string) => void;
}) {
  const [shown, setShown] = useState(false);
  return (
    <span className="v2-cfged-secret">
      <Input
        aria-labelledby={labelId}
        aria-describedby={helpId}
        className={pin ? 'v2-cfged-secret__input v2-cfged-secret__input--pin' : 'v2-cfged-secret__input'}
        type={shown ? 'text' : 'password'}
        size="sm"
        mono={pin}
        tone={tone}
        value={value == null ? '' : String(value)}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        autoComplete="new-password"
        spellCheck={false}
      />
      <Button
        size="md"
        icon={shown ? <IconEyeOff /> : <IconEye />}
        aria-pressed={shown}
        onClick={() => setShown((v) => !v)}
        className="v2-cfged-secret__btn"
      >
        {shown ? 'Hide' : 'Show'}
      </Button>
    </span>
  );
}

/** Long help (Wi-Fi radio): help over `limit` characters shows its first sentences, with More / Less. */
export function LongHelp({ text, limit = 400 }: { text: string; limit?: number }): ReactNode {
  const [open, setOpen] = useState(false);
  if (text.length <= limit) return text;
  // Cut at the last sentence end within the first 200 characters (at least 80 in).
  const head = text.slice(0, 200);
  const cut = Math.max(head.lastIndexOf('. '), 80);
  return (
    <>
      {open ? text : `${text.slice(0, cut + 1)} `}
      {open ? ' ' : null}
      <button type="button" className="v2-cfged-more" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        {open ? 'Less' : 'More'}
      </button>
    </>
  );
}
