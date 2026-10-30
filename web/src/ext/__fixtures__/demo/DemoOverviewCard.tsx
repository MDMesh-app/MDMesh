import { Card, CardHeader, StatCell } from '@mdmesh/console';

export function DemoOverviewCard() {
  return (
    <Card aria-label="Demo card">
      <CardHeader title="Demo card" />
    </Card>
  );
}

export function DemoStat() {
  return <StatCell label="Demo stat" value="42" />;
}
