import { Card, CardHeader, useCurrentUser } from '@mdmesh/console';

export function DemoPage() {
  const user = useCurrentUser();
  return (
    <Card>
      <CardHeader title="Demo page" subtitle={`Signed in as ${user?.login ?? '—'}`} />
    </Card>
  );
}
