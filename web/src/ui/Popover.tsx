import type { ReactElement, ReactNode } from 'react';
import * as P from '@radix-ui/react-popover';
import { cx } from './cx';
import './Overlay.css';

/**
 * A panel anchored to its trigger (Radix Popover): filters, pickers, short forms. Escape and a click outside close
 * it and focus returns to the trigger. The trigger is the child (a Button); it gets `aria-expanded` and
 * `aria-controls`.
 */
export function Popover({
  trigger,
  children,
  label,
  align = 'start',
  open,
  onOpenChange,
  className,
}: {
  trigger: ReactElement;
  children: ReactNode;
  /** Accessible name of the panel ("Filters"). */
  label: string;
  align?: 'start' | 'center' | 'end';
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  className?: string;
}) {
  return (
    <P.Root open={open} onOpenChange={onOpenChange}>
      <P.Trigger asChild>{trigger}</P.Trigger>
      <P.Portal>
        <P.Content
          align={align}
          sideOffset={4}
          collisionPadding={8}
          aria-label={label}
          className={cx('v2-popover', className)}
        >
          {children}
        </P.Content>
      </P.Portal>
    </P.Root>
  );
}
