import { Component, type ErrorInfo, type ReactNode } from 'react';
import { isChunkLoadError } from './AppErrorBoundary';
import { Button } from './Button';
import { Card } from './Card';
import { EmptyState } from './EmptyState';
import { IconWarning } from './icons';

interface Props {
  /** Key the boundary by the pathname so navigation resets it. */
  children: ReactNode;
}

interface State {
  /** Separate from `error`, as a component may throw a falsy value (`throw undefined`). */
  crashed: boolean;
  error: unknown;
}

/**
 * What a crashed page shows in its place: a failed chunk (the console was updated while the tab was open) offers a
 * reload, anything else a retry. Shared by this boundary and the per-page ExtensionBoundary.
 */
export function PageCrash({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const chunk = isChunkLoadError(error);
  const detail = error instanceof Error ? error.message : String(error);
  return (
    <Card role="alert">
      <EmptyState
        icon={<IconWarning size={18} />}
        title={chunk ? 'Couldn’t load this page' : 'This page ran into a problem'}
        action={
          chunk ? (
            <Button variant="primary" size="lg" onClick={() => window.location.reload()}>
              Reload
            </Button>
          ) : (
            <Button variant="primary" size="lg" onClick={onRetry}>
              Try again
            </Button>
          )
        }
      >
        {chunk
          ? 'The console was probably updated while this tab was open. Reload to get the current version.'
          : import.meta.env.DEV
            ? detail
            : 'The rest of the console still works. Try again, or reload the page.'}
      </EmptyState>
    </Card>
  );
}

/**
 * Wraps the page inside AppShell (keyed by pathname): a crash or a failed page chunk replaces only the page, so
 * the sidebar and navigation keep working.
 */
export class PageErrorBoundary extends Component<Props, State> {
  state: State = { crashed: false, error: null };

  static getDerivedStateFromError(error: unknown): State {
    return { crashed: true, error };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    console.error('page crashed', error, info.componentStack);
  }

  render() {
    const { crashed, error } = this.state;
    if (!crashed) return this.props.children;
    return <PageCrash error={error} onRetry={() => this.setState({ crashed: false, error: null })} />;
  }
}
