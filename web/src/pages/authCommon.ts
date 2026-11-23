// Page-local helpers for the two auth pages (Sign in and Set password). The footer is the shared
// AuthFooter (ui/AuthLayout) and the return path check is auth/safeReturnPath.
import { ApiError } from '../api/client';
import './authPages.css';

/** Classic wording: a network failure is the only error that is told apart. */
export function isUnreachable(err: unknown): boolean {
  return err instanceof ApiError && err.httpStatus === 0;
}

export const UNREACHABLE = 'Cannot reach the server. Check that it is running.';

// The sign-in limit's answer, shared with Settings' password change (api/auth.ts).
export { isTooManyAttempts, TOO_MANY_MESSAGE as TOO_MANY } from '../api/auth';
