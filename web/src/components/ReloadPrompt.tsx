import { useEffect, useState } from 'react';
import { getUpdateStatus, isApplyTerminal } from '../api/updates';

// Version baked into this SPA build (Task 6). Defaults to 'dev' for local/source builds, which
// suppresses the prompt entirely — only a real, versioned image can trigger it.
const APP_VERSION = (import.meta.env.VITE_APP_VERSION as string) || 'dev';

/** After a server/web apply, the recreated web image serves a new SPA, but the *open* tab keeps
 *  running the old JS. Comparing the baked build version to the supervisor's live `current` catches
 *  that staleness from both manual and unattended updates, whenever the admin has the console open. */
export function ReloadPrompt() {
  const [liveVersion, setLiveVersion] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    if (APP_VERSION === 'dev') return; // never poll/prompt for source or local builds
    let on = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const loop = async () => {
      const s = await getUpdateStatus();
      if (!on) return;
      // Don't nag mid-apply — the UpdateBanner owns that window; show the reload once it settles.
      const applying = !!s?.apply && !isApplyTerminal(s.apply.phase);
      setLiveVersion(s?.current && !applying ? s.current : null);
      timer = setTimeout(loop, 60 * 1000);
    };
    void loop();
    return () => { on = false; clearTimeout(timer); };
  }, []);

  if (APP_VERSION === 'dev' || dismissed) return null;
  if (!liveVersion || liveVersion === APP_VERSION) return null;

  return (
    <div className="reload-bar">
      <span>
        New console <b>v{liveVersion}</b> is live — reload to load it.
      </span>
      <span className="reload-actions">
        <button className="btn btn-sm btn-primary" onClick={() => window.location.reload()}>
          Reload
        </button>
        <button className="btn btn-sm btn-ghost" onClick={() => setDismissed(true)} aria-label="Dismiss">
          Later
        </button>
      </span>
    </div>
  );
}
