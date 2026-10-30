import { useCallback, useEffect, useRef } from 'react';
import { useBlocker, useNavigate } from 'react-router';
import { Button } from './Button';
import { Modal } from './Modal';

/**
 * Asks before unsaved edits are lost ("warn on route leave"): any in-app navigation to another page
 * (links, the browser's Back and Forward, programmatic navigation) is held by react-router's useBlocker and the
 * "Leave without saving?" dialog decides; closing or reloading the tab gets the browser's own prompt
 * (`beforeunload`). A change of #hash on the same page is not a leave.
 *
 * Returns `navigate` for in-page buttons (it is held like a link), `exit` for the page's own way out after Save or
 * Delete (never held, and it replaces the page's history entry so Back from the next page does not reopen it), and
 * the dialog to render.
 */
export function useLeaveGuard(dirty: boolean, count: number) {
  const navigate = useNavigate();
  const bypassed = useRef(false);
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      dirty &&
      !bypassed.current &&
      (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search),
  );

  useEffect(() => {
    if (!dirty) return;
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (bypassed.current) return;
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [dirty]);

  const go = useCallback((to: string) => navigate(to), [navigate]);

  /** The page's own exit (after Save or Delete): no prompt, and the page's entry is replaced by the destination. */
  const exit = useCallback(
    (to: string, state?: unknown) => {
      bypassed.current = true;
      navigate(to, { replace: true, state });
    },
    [navigate],
  );

  const dialog = (
    <Modal
      open={blocker.state === 'blocked'}
      onClose={() => blocker.reset?.()}
      role="alertdialog"
      size="sm"
      title="Leave without saving?"
      description={count === 1 ? '1 unsaved change will be lost.' : `${count} unsaved changes will be lost.`}
      footer={
        <>
          <Button size="lg" onClick={() => blocker.reset?.()}>
            Keep editing
          </Button>
          <Button
            size="lg"
            variant="danger"
            onClick={() => {
              bypassed.current = true;
              blocker.proceed?.();
            }}
          >
            Leave without saving
          </Button>
        </>
      }
    />
  );

  return { navigate: go, exit, dialog };
}
