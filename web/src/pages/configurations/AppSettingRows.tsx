// Rows of one app's managed configuration ("application settings"): key, type, value, remove, and Add setting. The
// configuration editor edits a configuration's rows with it (inside the app's row), the device page a device's
// overrides. Values can be secrets: they are only ever an input's value here.
import { useId, type ReactNode } from 'react';
import {
  APP_SETTING_TYPES,
  MAX_SETTINGS_PER_APP,
  MAX_SETTING_NAME,
  MAX_SETTING_VALUE,
  withType,
  type AppSetting,
  type AppSettingType,
  type SettingProblem,
} from '../../data/managedAppSettings';
import { Button, IconButton, IconClose, IconPlus, Input, Select } from '../../ui';
import './AppSettingRows.css';

export interface AppSettingEntry {
  /** The row's index in the whole list being edited (problems and edits refer to it). */
  index: number;
  row: AppSetting;
  /** A line under the row (the device page: "Overrides the configuration"). */
  note?: ReactNode;
}

export interface AppSettingRowsProps {
  appName: string;
  entries: AppSettingEntry[];
  /** Problems of the whole list, by index. */
  problems: readonly SettingProblem[];
  readOnly: boolean;
  onChange: (index: number, row: AppSetting) => void;
  onRemove: (index: number) => void;
  onAdd: () => void;
  addLabel?: string;
}

export function AppSettingRows({ appName, entries, problems, readOnly, onChange, onRemove, onAdd, addLabel = 'Add setting' }: AppSettingRowsProps) {
  const ids = useId();
  const full = entries.length >= MAX_SETTINGS_PER_APP;
  return (
    <div className="v2-appsettings">
      {entries.map(({ index, row, note }) => {
        const nameProblem = problems.find((p) => p.index === index && p.field === 'name')?.message;
        const valueProblem = problems.find((p) => p.index === index && p.field === 'value')?.message;
        const errorId = `${ids}-${index}-err`;
        const label = row.name.trim() || 'new setting';
        const edit = (patch: Partial<AppSetting>) => onChange(index, { ...row, ...patch, lastUpdate: Date.now() });
        return (
          <div key={index} className="v2-appsettings__row" role="group" aria-label={`Setting ${label}`}>
            <Input
              size="sm"
              mono
              className="v2-appsettings__key"
              aria-label="Key"
              placeholder="Key"
              value={row.name}
              maxLength={MAX_SETTING_NAME}
              disabled={readOnly}
              tone={nameProblem ? 'invalid' : 'default'}
              aria-describedby={nameProblem || valueProblem ? errorId : undefined}
              onChange={(e) => edit({ name: e.target.value })}
            />
            <Select
              size="sm"
              className="v2-appsettings__type"
              aria-label="Type"
              value={row.type}
              disabled={readOnly}
              onChange={(e) => onChange(index, { ...withType(row, e.target.value as AppSettingType), lastUpdate: Date.now() })}
            >
              {APP_SETTING_TYPES.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </Select>
            {row.type === 'BOOLEAN' ? (
              <Select
                size="sm"
                className="v2-appsettings__value"
                aria-label="Value"
                value={row.value === 'true' ? 'true' : 'false'}
                disabled={readOnly}
                onChange={(e) => edit({ value: e.target.value })}
              >
                <option value="true">On</option>
                <option value="false">Off</option>
              </Select>
            ) : (
              <Input
                size="sm"
                className="v2-appsettings__value"
                aria-label="Value"
                placeholder="Value"
                autoComplete="off"
                spellCheck={false}
                inputMode={row.type === 'INTEGER' ? 'numeric' : undefined}
                value={row.value}
                maxLength={MAX_SETTING_VALUE}
                disabled={readOnly}
                tone={valueProblem ? 'invalid' : 'default'}
                aria-describedby={nameProblem || valueProblem ? errorId : undefined}
                onChange={(e) => edit({ value: e.target.value })}
              />
            )}
            {readOnly ? (
              <span />
            ) : (
              <IconButton size="xs" label={`Remove setting ${label}`} onClick={() => onRemove(index)}>
                <IconClose />
              </IconButton>
            )}
            {nameProblem || valueProblem ? (
              <p id={errorId} className="v2-appsettings__error">
                {[nameProblem, valueProblem].filter(Boolean).join(' ')}
              </p>
            ) : null}
            {note ? <p className="v2-appsettings__note">{note}</p> : null}
          </div>
        );
      })}
      {readOnly ? null : (
        <div>
          <Button size="sm" icon={<IconPlus />} onClick={onAdd} disabled={full}>
            {addLabel}
          </Button>
          {full ? <span className="v2-appsettings__full"> {appName} has {MAX_SETTINGS_PER_APP} settings, the most an app takes.</span> : null}
        </div>
      )}
    </div>
  );
}
