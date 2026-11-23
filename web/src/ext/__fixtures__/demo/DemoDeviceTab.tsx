import { useState } from 'react';
import { Button, Card, CardHeader, KeyValueList, useDevice, type DeviceCommand } from '@mdmesh/console';

const RING: DeviceCommand = {
  label: 'Demo ring',
  request: { type: 'device.ring', requiresCapability: 'device.ring' },
};

export function DemoDeviceTab() {
  const { device, telemetry, telemetryLoaded, state, configuration, configStatus, refresh, sendCommand } = useDevice();
  const model = telemetry?.hardware?.model;
  const [queued, setQueued] = useState<boolean | null>(null);
  return (
    <Card>
      <CardHeader title="Demo tab" subtitle={`Device ${device.number}`} />
      <KeyValueList
        items={[
          { key: 'model', label: 'Model', value: typeof model === 'string' ? model : telemetryLoaded ? 'none' : 'loading' },
          { key: 'agent', label: 'Agent', value: state?.agentVersion ?? '—' },
          { key: 'cfg', label: 'Configuration', value: configuration?.name ?? '—' },
          { key: 'sync', label: 'In sync', value: configStatus ? String(configStatus.inSync) : '—' },
        ]}
      />
      <Button onClick={refresh}>Refresh now</Button>
      <Button onClick={() => void sendCommand(RING).then(setQueued)}>Ring from demo</Button>
      {queued !== null ? <p>{queued ? 'Ring queued' : 'Ring not queued'}</p> : null}
    </Card>
  );
}
