import { renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { CommandHistoryItem, QueueCommandRequest } from '../api/commands';
import { useAppScan } from './DeviceDetailApps';
import { stubApi } from '../test/console';

const saved = (id: number, createdAt: number): CommandHistoryItem => ({
  id,
  type: 'apps.scan',
  status: 'done',
  createdAt,
  completedAt: createdAt + 10,
  detail: JSON.stringify({ apps: [], includeAll: true, appActions: true }),
});
const done = (type: string): CommandHistoryItem => ({ id: 10, type, status: 'done', completedAt: 30 });
function queue() {
  return vi.fn(async (_label: string, build: () => QueueCommandRequest, onQueued?: (id: number | string | undefined) => void) => {
    build();
    onQueued?.(42);
    return true;
  });
}

describe('app inventory automatic refresh', () => {
  it.each(['app.install', 'app.uninstall', 'app.setHidden'])('queues one full scan after successful %s', async (type) => {
    stubApi();
    const send = queue();
    const hook = renderHook(({ history }) => useAppScan('WH-1', history, send), { initialProps: { history: [saved(1, 10)] } });
    expect(send).not.toHaveBeenCalled();
    hook.rerender({ history: [done(type), saved(1, 10)] });
    await waitFor(() => expect(send).toHaveBeenCalledTimes(1));
    expect(send.mock.calls[0][1]()).toEqual({ type: 'apps.scan', payload: JSON.stringify({ includeAll: true }) });
    hook.rerender({ history: [saved(42, 40), done(type), saved(1, 10)] });
    expect(send).toHaveBeenCalledTimes(1);
  });
  it('does not scan on pending/failed operations or mutate for an observer', () => {
    stubApi();
    const send = queue();
    const history = [{ ...done('app.install'), status: 'failed' }, saved(1, 10)];
    const hook = renderHook(({ canEdit }) => useAppScan('WH-1', history, send, canEdit), { initialProps: { canEdit: true } });
    expect(send).not.toHaveBeenCalled();
    hook.rerender({ canEdit: false });
    expect(send).not.toHaveBeenCalled();
  });
  it('nudges a delivered installation while it waits for a result, without queuing another operation', async () => {
    const fetch = stubApi();
    const send = queue();
    const history = [{ ...done('app.install'), status: 'delivered', completedAt: undefined, deliveredAt: 30 }, saved(1, 10)];
    const hook = renderHook(() => useAppScan('WH-1', history, send));
    await waitFor(() => expect(fetch.mock.calls.some(([p]) => String(p).endsWith('/WH-1/sync'))).toBe(true));
    hook.rerender();
    expect(fetch.mock.calls.filter(([p]) => String(p).endsWith('/WH-1/sync'))).toHaveLength(1);
    expect(send).not.toHaveBeenCalled();
  });
});
