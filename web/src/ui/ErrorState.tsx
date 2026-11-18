import type { ReactNode } from 'react';
import { Button } from './Button';
import { Callout } from './Card';
import { IconAlert } from './icons';
import { cx } from './cx';

/**
 * The console's one load-failure state: "Couldn't load {thing}." (plus an optional detail) and a Retry button at the
 * right. Numbers that failed to load show as "—" beside it, never as 0.
 */
export function ErrorState({
  thing,
  detail,
  onRetry,
  retrying,
  className,
}: {
  /** What failed, as the sentence needs it: "devices", "this configuration". */
  thing: ReactNode;
  detail?: ReactNode;
  onRetry?: () => void;
  retrying?: boolean;
  className?: string;
}) {
  return (
    <Callout tone="alert" role="alert" icon={<IconAlert />} className={cx('v2-errorstate', className)}>
      <span className="v2-errorstate__text">
        Couldn’t load {thing}.{detail ? <> {detail}</> : null}
      </span>
      {onRetry ? (
        <Button size="sm" onClick={onRetry} disabled={retrying}>
          {retrying ? 'Retrying…' : 'Retry'}
        </Button>
      ) : null}
    </Callout>
  );
}
