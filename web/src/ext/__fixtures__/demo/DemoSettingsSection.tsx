import { Card, CardHeader, type SettingsSectionProps } from '@mdmesh/console';

export function DemoSettingsSection({ anchorId, headingId }: SettingsSectionProps) {
  return (
    <Card id={anchorId} aria-labelledby={headingId}>
      <CardHeader title="Demo settings" titleId={headingId} subtitle={`Anchored at #${anchorId}`} />
    </Card>
  );
}
