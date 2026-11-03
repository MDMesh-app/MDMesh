import { Component, type ErrorInfo, type ReactNode } from 'react';
import { AuthLayout } from './AuthLayout';
import { Button } from './Button';

/**
 * True for a failed dynamic import: a chunk that no longer exists after a deploy, or a network drop. Also any page
 * or contribution module lazyComponent could not load (a `LazyLoadError`, ext/types.ts): React.lazy keeps the
 * failure, so only a reload can recover from it.
 */
export function isChunkLoadError(error: unknown): boolean {
  const msg = error instanceof Error ? `${error.name} ${error.message}` : String(error);
  return /ChunkLoadError|LazyLoadError|dynamically imported module|Importing a module script failed|error loading dynamically|Failed to fetch/i.test(
    msg,
  );
}

interface State {
  /** Separate from `error`, as a component may throw a falsy value (`throw undefined`). */
  crashed: boolean;
  error: unknown;
}

/**
 * Last-resort boundary around the whole route table (App.tsx). Signed-in pages have their own boundary inside the
 * shell (PageErrorBoundary), so this one shows only when the shell itself, or the sign-in pages' chunk, fails: an
 * open tab after a server update asks for a chunk hash that is gone, or a crash before the shell renders. It uses
 * the sign-in card, so it never depends on anything that might have failed to load beyond the entry chunk.
 */
export class AppErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { crashed: false, error: null };

  static getDerivedStateFromError(error: unknown): State {
    return { crashed: true, error };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    console.error('console failed to load', error, info.componentStack);
  }

  render() {
    if (!this.state.crashed) return this.props.children;
    const chunk = isChunkLoadError(this.state.error);
    return (
      <div role="alert">
        <AuthLayout
          title="Couldn’t load the console"
          subtitle={
            chunk
              ? 'The console was probably updated while this tab was open. Reload to get the current version.'
              : 'Something went wrong while starting it. Reload to try again.'
          }
        >
          <Button variant="primary" size="lg" block onClick={() => window.location.reload()}>
            Reload
          </Button>
        </AuthLayout>
      </div>
    );
  }
}
