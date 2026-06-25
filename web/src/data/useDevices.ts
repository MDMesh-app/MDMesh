import { useCallback, useEffect, useState } from 'react';
import {
  searchDevices,
  type DeviceView,
  type ConfigurationLookup,
} from '../api/devices';
import { ApiError } from '../api/client';

export interface DevicesState {
  devices: DeviceView[];
  total: number;
  configurations: Record<string, ConfigurationLookup>;
  loading: boolean;
  error: string | null;
  reload: (value?: string) => Promise<void>;
}

function messageFor(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.httpStatus === 401 || err.httpStatus === 403)
      return 'Your session has expired. Sign in again.';
    if (err.httpStatus === 0) return 'Cannot reach the server.';
  }
  return 'Failed to load devices.';
}

export function useDevices(
  initialValue = '',
  pageSize = 50,
): DevicesState {
  const [devices, setDevices] = useState<DeviceView[]>([]);
  const [total, setTotal] = useState(0);
  const [configurations, setConfigurations] = useState<
    Record<string, ConfigurationLookup>
  >({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(
    async (value = '') => {
      setLoading(true);
      setError(null);
      try {
        const res = await searchDevices({ value, pageSize });
        setDevices(res.devices?.items ?? []);
        setTotal(res.devices?.totalItemsCount ?? 0);
        setConfigurations(res.configurations ?? {});
      } catch (err) {
        setError(messageFor(err));
        setDevices([]);
        setTotal(0);
      } finally {
        setLoading(false);
      }
    },
    [pageSize],
  );

  useEffect(() => {
    void reload(initialValue);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

  return { devices, total, configurations, loading, error, reload };
}
