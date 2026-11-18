// Managed app configuration ("application settings"): typed key-value pairs an installed app reads through Android's
// RestrictionsManager. A configuration holds them per installed app; a device can override a key or add its own, and
// the device row wins. The rows are the inherited Headwind application settings (docs/managed-app-configuration-
// design.md). Values can be secrets (tokens): nothing here logs a value, and no message quotes one.

import type { AppSetting, AppSettingType } from '../api/configurations';

export type { AppSetting, AppSettingType };

export const APP_SETTING_TYPES: ReadonlyArray<{ value: AppSettingType; label: string }> = [
  { value: 'STRING', label: 'Text' },
  { value: 'INTEGER', label: 'Number' },
  { value: 'BOOLEAN', label: 'On/off' },
];

/** The server's limits (the column width, the value size, the settings of one app). */
export const MAX_SETTING_NAME = 200;
export const MAX_SETTING_VALUE = 4096;
export const MAX_SETTINGS_PER_APP = 64;

const INT_MIN = -2147483648;
const INT_MAX = 2147483647;

/** The rows a configuration or device holds; anything else (absent, a legacy shape) reads as none. */
export function appSettingsOf(list: unknown): AppSetting[] {
  return Array.isArray(list) ? (list.filter((r) => !!r && typeof r === 'object') as AppSetting[]) : [];
}

/** A new row: a free key, Text, empty. Not readonly or variable (the Headwind flags the console does not edit). */
export function newAppSetting(applicationId: number): AppSetting {
  return { applicationId, name: '', type: 'STRING', value: '', readonly: false, variable: false, lastUpdate: Date.now() };
}

/** A row of another type keeps its value, but a switch to On/off starts from Off unless it already reads true or false. */
export function withType(row: AppSetting, type: AppSettingType): AppSetting {
  const value = type === 'BOOLEAN' && row.value !== 'true' && row.value !== 'false' ? 'false' : row.value;
  return { ...row, type, value };
}

/** Why one value does not fit its type, or null. Never quotes the value. */
export function settingValueProblem(type: AppSettingType, value: string): string | null {
  if (type !== 'STRING' && type !== 'INTEGER' && type !== 'BOOLEAN') return 'Choose a type.';
  if ((value ?? '').length > MAX_SETTING_VALUE) return `Use at most ${MAX_SETTING_VALUE} characters.`;
  if (type === 'BOOLEAN' && value !== 'true' && value !== 'false') return 'Choose On or Off.';
  if (type === 'INTEGER') {
    if (!/^-?\d+$/.test(value ?? '')) return 'Enter a whole number.';
    const n = Number(value);
    if (n < INT_MIN || n > INT_MAX) return `Enter a number from ${INT_MIN} to ${INT_MAX}.`;
  }
  return null;
}

export interface SettingProblem {
  /** The row's index in the list checked. */
  index: number;
  field: 'name' | 'value';
  message: string;
}

/**
 * The problems the server would refuse a save for, per row (the configuration's rows, or a device's): a blank, long
 * or repeated key within an app, a value that does not fit its type, or more than 64 settings for one app.
 */
export function appSettingsProblems(rows: readonly AppSetting[]): SettingProblem[] {
  const out: SettingProblem[] = [];
  const seen = new Map<number, Set<string>>();
  const perApp = new Map<number, number>();
  rows.forEach((row, index) => {
    const name = (row.name ?? '').trim();
    const names = seen.get(row.applicationId) ?? new Set<string>();
    seen.set(row.applicationId, names);
    const n = (perApp.get(row.applicationId) ?? 0) + 1;
    perApp.set(row.applicationId, n);
    if (!name) out.push({ index, field: 'name', message: 'Give the setting a key.' });
    else if (name.length > MAX_SETTING_NAME) out.push({ index, field: 'name', message: `Use at most ${MAX_SETTING_NAME} characters.` });
    else if (names.has(name)) out.push({ index, field: 'name', message: 'This app already has a setting with this key.' });
    else if (n > MAX_SETTINGS_PER_APP) out.push({ index, field: 'name', message: `An app takes at most ${MAX_SETTINGS_PER_APP} settings.` });
    names.add(name);
    const valueProblem = settingValueProblem(row.type, row.value);
    if (valueProblem) out.push({ index, field: 'value', message: valueProblem });
  });
  return out;
}

/**
 * Order-free, and blind to `lastUpdate` and row ids: setting a value back to what it was is no change, wherever the
 * row moved in the list.
 */
export function appSettingsSignature(list: unknown): string {
  return appSettingsOf(list)
    .map((r) => JSON.stringify([r.applicationId, r.name, r.type, r.value, !!r.readonly, !!r.variable, r.comment ?? null]))
    .sort()
    .join('\n');
}

/** The settings without those of one application; an absent list stays absent. */
export function dropAppSettings(list: unknown, applicationId: number): unknown {
  return Array.isArray(list) ? appSettingsOf(list).filter((r) => Number(r.applicationId) !== applicationId) : list;
}

/** A value as the console shows it read only: On/off for a boolean. */
export function settingValueText(type: AppSettingType, value: string): string {
  if (type === 'BOOLEAN') return value === 'true' ? 'On' : value === 'false' ? 'Off' : value;
  return value;
}
