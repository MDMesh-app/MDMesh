import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from 'react';

/** The theme actually applied (data-theme on <html>). */
export type Theme = 'light' | 'dark';
/** What the user chose: a fixed theme, or follow the OS ("Match system"). */
export type ThemePreference = Theme | 'system';

interface ThemeApi {
  /** Resolved theme: what is on screen now. */
  theme: Theme;
  /** Stored choice; 'system' follows prefers-color-scheme live. */
  preference: ThemePreference;
  /** Pin a fixed theme (same as setPreference with 'light' | 'dark'). */
  setTheme: (t: Theme) => void;
  setPreference: (p: ThemePreference) => void;
  /** Flip the resolved theme and pin it. */
  toggleTheme: () => void;
}

const Ctx = createContext<ThemeApi | null>(null);
const THEME_KEY = 'mdmesh-theme';
const DARK_QUERY = '(prefers-color-scheme: dark)';

// Storage holds 'light' | 'dark' | 'system'. Nothing stored means 'system', which is what the console always did
// on a first visit; the pre-paint script in index.html resolves anything other than light/dark the same way.
function readPreference(): ThemePreference {
  try {
    const s = localStorage.getItem(THEME_KEY);
    return s === 'light' || s === 'dark' ? s : 'system';
  } catch {
    return 'system';
  }
}

function systemTheme(): Theme {
  try {
    return window.matchMedia(DARK_QUERY).matches ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

function applyTheme(t: Theme) {
  document.documentElement.setAttribute('data-theme', t);
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', t === 'dark' ? '#0f1115' : '#f6f7f9');
}

// Switch with a smooth crossfade via the View Transitions API where available,
// falling back to the CSS colour transitions otherwise. Reduced-motion skips it.
function withTransition(run: () => void) {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const doc = document as Document & {
    startViewTransition?: (cb: () => void) => void;
  };
  if (!reduce && typeof doc.startViewTransition === 'function') {
    const t = doc.startViewTransition(run) as unknown as
      | { ready?: Promise<unknown>; finished?: Promise<unknown>; updateCallbackDone?: Promise<unknown> }
      | undefined;
    // A newer switch skips a running transition and its promises reject ("Transition was skipped"): expected.
    const ignore = () => {};
    t?.ready?.catch(ignore);
    t?.finished?.catch(ignore);
    t?.updateCallbackDone?.catch(ignore);
  } else {
    run();
  }
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setPreferenceState] = useState<ThemePreference>(readPreference);
  const [system, setSystem] = useState<Theme>(systemTheme);
  const theme: Theme = preference === 'system' ? system : preference;

  // Follow OS changes live (it only shows while the preference is 'system', but tracking is cheap).
  useEffect(() => {
    let mq: MediaQueryList;
    try {
      mq = window.matchMedia(DARK_QUERY);
    } catch {
      return;
    }
    const onChange = () => setSystem(mq.matches ? 'dark' : 'light');
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  useEffect(() => {
    try {
      localStorage.setItem(THEME_KEY, preference);
    } catch {
      /* ignore */
    }
  }, [preference]);

  const setPreference = useCallback((p: ThemePreference) => {
    withTransition(() => setPreferenceState(p));
  }, []);

  const setTheme = useCallback((t: Theme) => setPreference(t), [setPreference]);

  const toggleTheme = useCallback(() => {
    setPreference(theme === 'dark' ? 'light' : 'dark');
  }, [theme, setPreference]);

  return (
    <Ctx.Provider value={{ theme, preference, setTheme, setPreference, toggleTheme }}>
      {children}
    </Ctx.Provider>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useTheme(): ThemeApi {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useTheme must be used within ThemeProvider');
  return ctx;
}
