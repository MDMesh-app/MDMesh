// The demo extension's configuration section: a label and a switch under `extensions.demo`, as a fork's would be.
import { ChangedChip, Checkbox, FieldRow, Input, type ConfigSectionProps } from '@mdmesh/console';

export interface DemoSettings {
  label: string;
  on: boolean;
}

export function DemoConfigSection({ value, stored, onChange, readOnly, errors, idBase }: ConfigSectionProps<DemoSettings>) {
  return (
    <>
      <FieldRow
        id={`${idBase}-label`}
        label="Demo label"
        adornment={value.label !== stored.label ? <ChangedChip /> : undefined}
        below={errors.length ? <span role="alert">{errors.join(' ')}</span> : undefined}
      >
        {({ labelId }) => (
          <Input
            aria-labelledby={labelId}
            disabled={readOnly}
            value={value.label}
            onChange={(e) => onChange({ ...value, label: e.target.value })}
          />
        )}
      </FieldRow>
      <FieldRow id={`${idBase}-on`} label="Demo switch">
        {() => (
          <Checkbox
            label="Demo on"
            disabled={readOnly}
            checked={value.on}
            onChange={(e) => onChange({ ...value, on: e.target.checked })}
          />
        )}
      </FieldRow>
    </>
  );
}
