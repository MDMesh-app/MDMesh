import { createContext, useContext, type ReactElement, type ReactNode } from 'react';
import * as T from '@radix-ui/react-tooltip';
import './Overlay.css';

const Provided = createContext(false);

/**
 * When focus last moved by the keyboard (Tab, arrows, Home/End). A tooltip opens on focus only then: focus a script
 * sets (a dialog focusing its first control as it opens) shows none, so the dialog's first Escape closes the dialog.
 */
let keyboardMoveAt = -Infinity;
const MOVE_KEYS = new Set(['Tab', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End']);
if (typeof document !== 'undefined') {
  document.addEventListener(
    'keydown',
    (e) => {
      if (MOVE_KEYS.has(e.key)) keyboardMoveAt = performance.now();
    },
    true,
  );
}
const KEYBOARD_FOCUS_MS = 300;

/** Wraps the app once (App.tsx): one hover delay for every tooltip, and moving between triggers skips it. */
export function TooltipProvider({ children }: { children: ReactNode }) {
  return (
    <T.Provider delayDuration={400} skipDelayDuration={300}>
      <Provided.Provider value>{children}</Provided.Provider>
    </T.Provider>
  );
}

/**
 * A short label on hover or keyboard focus (Radix Tooltip), in place of the native `title`: it shows for keyboard
 * users too and stays on screen while pointed at. Its content is a description, not the control's name: an icon
 * button still needs its `label`. The child must take a ref (a DOM element or a forwardRef component).
 */
export function Tooltip({
  content,
  side = 'top',
  children,
}: {
  content: ReactNode;
  side?: 'top' | 'bottom' | 'left' | 'right';
  children: ReactElement;
}) {
  const provided = useContext(Provided);
  if (content === null || content === undefined || content === '') return children;
  const tip = (
    <T.Root>
      <T.Trigger
        asChild
        onFocus={(e) => {
          if (performance.now() - keyboardMoveAt > KEYBOARD_FOCUS_MS) e.preventDefault();
        }}
      >
        {children}
      </T.Trigger>
      <T.Portal>
        <T.Content side={side} sideOffset={6} collisionPadding={8} className="v2-tooltip">
          {content}
        </T.Content>
      </T.Portal>
    </T.Root>
  );
  // Rendered outside the app's provider (a component on its own, in a test or an extension's preview): its own.
  return provided ? tip : <TooltipProvider>{tip}</TooltipProvider>;
}
