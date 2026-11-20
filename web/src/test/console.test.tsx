// settled(): a test helper that has to hold until the page's loading is over, including a request that an earlier
// answer's effect sends (no placeholder in between to wait on), and one whose body the app never reads.
import { render, screen } from '@testing-library/react';
import { useEffect, useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { settled, stubApi } from './console';

/** Loads /first, and once its answer is state, /second (as a row's own detail follows its list). */
function Chained() {
  const [first, setFirst] = useState<string | null>(null);
  const [second, setSecond] = useState<string | null>(null);
  useEffect(() => {
    void fetch('/first')
      .then((r) => r.json())
      .then((b: { data: string }) => setFirst(b.data));
  }, []);
  useEffect(() => {
    if (!first) return;
    void fetch('/second')
      .then((r) => r.json())
      .then((b: { data: string }) => setSecond(b.data));
  }, [first]);
  return <p>{[first, second].filter(Boolean).join(' then ') || 'nothing yet'}</p>;
}

describe('settled', () => {
  it('waits through a chain of requests, to the last answer set as state', async () => {
    stubApi((path) => (path === '/first' ? 'one' : 'two'));
    render(<Chained />);
    await settled();
    expect(screen.getByText('one then two')).toBeTruthy();
  });

  it('counts an answer whose body is never read (an error status) once the app has had it', async () => {
    const fetch = stubApi();
    fetch.mockImplementation(async () => new Response('', { status: 500 }));
    render(<p>page</p>);
    void globalThis.fetch('/refused');
    await settled();
  });

  it('fails at once under fake timers, which would never run its checks', async () => {
    stubApi();
    vi.useFakeTimers();
    try {
      await expect(settled()).rejects.toThrow('settled() needs real timers');
    } finally {
      vi.useRealTimers();
    }
  });

  it('counts an aborted call, whatever its held mock does', async () => {
    const fetch = stubApi();
    let answer = (_: Response) => {};
    fetch.mockImplementation(() => new Promise<Response>((r) => (answer = r)));
    const controller = new AbortController();
    const call = globalThis.fetch('/held', { signal: controller.signal });
    controller.abort();
    await settled();
    // Answered after all, so nothing is left pending past the test.
    answer(new Response(''));
    await call;
  });
});
