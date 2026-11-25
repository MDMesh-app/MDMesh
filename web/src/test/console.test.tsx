// settled(): a test helper that has to hold until the page's loading is over, including a request that an earlier
// answer's effect sends (no placeholder in between to wait on), and a refused one, whose body the app never reads.
import { render, screen } from '@testing-library/react';
import { useEffect, useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { apiClient } from '../api/client';
import { settled, stubApi } from './console';
import { pendingCalls } from './pending';

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

const refuse = () => stubApi().mockImplementation(async () => new Response('', { status: 500 }));

describe('settled', () => {
  it('waits through a chain of requests, to the last answer set as state', async () => {
    stubApi((path) => (path === '/first' ? 'one' : 'two'));
    render(<Chained />);
    await settled();
    expect(screen.getByText('one then two')).toBeTruthy();
  });

  it('counts a refused call as answered, though its body is never read', async () => {
    refuse();
    void fetch('/refused');
    await settled();
  });

  it('works after an answer that came under fake timers', async () => {
    refuse();
    vi.useFakeTimers();
    try {
      await expect(apiClient.get('/x')).rejects.toThrow('HTTP 500');
    } finally {
      vi.useRealTimers();
    }
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

/** Shows 'loading', then what GET /x answers; unmounting aborts the call, as the console's pages do. */
function Loads() {
  const [text, setText] = useState('loading');
  useEffect(() => {
    const unmount = new AbortController();
    void fetch('/x', { signal: unmount.signal })
      .then((r) => r.json())
      .then((b: { data: string }) => setText(b.data));
    return () => unmount.abort();
  }, []);
  return <p>{text}</p>;
}

// The guard in test/dom.ts fails a test that ends with a call still pending. Vitest runs it once the test's own
// promise chain has run out, so an answer that arrived within those microtasks would pass for loaded.
describe('a test that ends before its page has loaded', () => {
  const microtasks = async () => {
    for (let i = 0; i < 50; i++) await Promise.resolve();
  };

  it('still has its call pending once its microtasks have run, for the guard to see', async () => {
    stubApi(() => 'done');
    render(<Loads />);
    expect(screen.getByText('loading')).toBeTruthy();
    await microtasks();
    expect(pendingCalls()).toBe(1);
    // This test then waits, so the guard passes it.
    await settled();
    expect(screen.getByText('done')).toBeTruthy();
  });

  it('passes one that awaited a refused call', async () => {
    refuse();
    await expect(apiClient.get('/x')).rejects.toThrow('HTTP 500');
  });

  // Passes only because the guard fails it: take the guard out, and this reads "Expect test to fail".
  it.fails('fails', () => {
    // Never answered: an answer could arrive after the file's last test, when the DOM is gone.
    stubApi().mockImplementation(() => new Promise(() => {}));
    render(<Loads />);
  });

  it.fails('fails on a call that is never answered', () => {
    stubApi().mockImplementation(() => new Promise(() => {}));
    void fetch('/held');
  });

  // Right after the test above: a stubApi() in between would start a fresh count by itself.
  it('leaves the next test clean', () => {
    expect(pendingCalls()).toBe(0);
  });
});
