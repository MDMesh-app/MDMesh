import { useCallback, useEffect, useRef, useState } from 'react';
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
  /** The search value the shown list answers (the latest request's, once its answer is in). */
  query: string;
  /** Loads the list for `value`; an answer that arrives after a newer request's is dropped. */
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
  pageSize = 1000,
): DevicesState {
  const [devices, setDevices] = useState<DeviceView[]>([]);
  const [total, setTotal] = useState(0);
  const [configurations, setConfigurations] = useState<
    Record<string, ConfigurationLookup>
  >({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState(initialValue);
  // Every request takes the next number; only the latest one's answer is applied, so a slow earlier search can
  // never overwrite a newer one (or a list loaded after unmount, which bumps it too).
  const seq = useRef(0);
  useEffect(() => () => void (seq.current += 1), []);

  const reload = useCallback(
    async (value = '') => {
      const mine = ++seq.current;
      setLoading(true);
      setError(null);
      try {
        const res = await searchDevices({ value, pageSize });
        if (mine !== seq.current) return;
        setDevices(res.devices?.items ?? []);
        setTotal(res.devices?.totalItemsCount ?? 0);
        setConfigurations(res.configurations ?? {});
        setQuery(value);
      } catch (err) {
        if (mine !== seq.current) return;
        setError(messageFor(err));
        setDevices([]);
        setTotal(0);
        setQuery(value);
      } finally {
        if (mine === seq.current) setLoading(false);
      }
    },
    [pageSize],
  );

  useEffect(() => {
    void reload(initialValue);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

  return { devices, total, configurations, loading, error, query, reload };
}
