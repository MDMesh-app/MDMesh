import { useState } from 'react';
import { applyRunning, useSupervisor } from '../data/useSupervisor';
import { Button } from './Button';
import { Notice } from './Card';
import { IconSync } from './icons';

// Version baked into this build. 'dev' for local and source builds, which never show the prompt: only a real,
// versioned image can be stale.
const APP_VERSION = (import.meta.env.VITE_APP_VERSION as string) || 'dev';
/** How fresh the live version must be while the console is open (the classic prompt polled every minute). */
const POLL_MS = 60 * 1000;

/**
 * After a server/web update the web image serves a new console, but an open tab keeps running the old code. When the
 * supervisor's live version differs from the one baked into this build, a notice above the page offers a reload.
 * Hidden while an update is still applying (the sidebar card shows that) and once dismissed.
 */
export function ReloadPrompt() {
  const supervisor = useSupervisor(APP_VERSION === 'dev' ? undefined : POLL_MS);
  const [dismissed, setDismissed] = useState(false);
  const s = supervisor.status;
  const live = s?.current && !applyRunning(s) ? s.current : null;

  if (APP_VERSION === 'dev' || dismissed || !live || live === APP_VERSION) return null;
  return (
    <Notice
      tone="idle"
      icon={<IconSync size={16} />}
      aria-label="New console version"
      action={
        <>
          <Button variant="primary" size="sm" onClick={() => window.location.reload()}>
            Reload
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setDismissed(true)}>
            Later
          </Button>
        </>
      }
    >
      <span>
        Console <b className="v2-mono">v{live}</b> is live. Reload to load it.
      </span>
    </Notice>
  );
}
