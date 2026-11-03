import SparkMD5 from 'spark-md5';
import { ApiError, apiClient } from './client';

// Endpoints (see server: com.hmdm.rest.resource.AuthResource):
//   POST /rest/public/auth/login   body: { login, password }
//   POST /rest/public/auth/logout
//   GET  /rest/public/auth/options
//
// IMPORTANT / SECURITY TODO: the existing AngularJS client hashes the password
// with MD5 (hex, upper-cased) before sending it, unless the server advertises
// an RSA public key via /options (transmit.password mode). The server then
// re-hashes and compares. MD5 is cryptographically broken and this scheme is
// weak (it is effectively a password-equivalent token in transit). We replicate
// it here only to stay wire-compatible with the unmodified server. A future
// hardening pass should prefer the RSA path and/or move to a proper token flow.
// Reference: server/src/main/webapp/app/components/main/controller/login.controller.js:48

/** Subset of the user object returned on successful login (UserView). */
export interface AuthUser {
  id: number;
  login: string;
  name?: string;
  email?: string;
  /** The account must set a new password first: AuthFilter refuses its session everything else until then. */
  passwordReset?: boolean;
  passwordResetToken?: string;
  /**
   * Set on the sign-in answer when the customer's settings require a second sign-in step (AuthResource.login). Until
   * that step passes, AuthFilter drops the session on any call outside /rest/private/twofactor. It describes that
   * sign-in, not the account.
   */
  twoFactor?: boolean;
  superAdmin?: boolean;
  /** False for a user an admin limited to some device groups (User.allDevicesAvailable; absent counts as true). */
  allDevicesAvailable?: boolean;
  /** May apply, roll back or schedule updates: a super-admin, or the admin of a single-customer install (0.3.8+). */
  deploymentAdmin?: boolean;
  singleCustomer?: boolean;
  userRole?: {
    /** Seeded roles: 1 super-admin, 2 admin, 3 user (UserRole.id). */
    id?: number;
    /** Free-text role name. The seeded names are Russian (Headwind's Liquibase seed), so don't show them raw. */
    name?: string;
    superAdmin?: boolean;
    permissions?: { name: string }[];
  };
}

/** Whether the user may apply, roll back or schedule updates, as the server says (deploymentAdmin). The updater
 *  checks the same answer with the server; the console only hides what would be refused. */
export function mayManageDeployment(u: AuthUser | null | undefined): boolean {
  return u?.deploymentAdmin === true;
}

/** The server refuses sign-in for a while after too many failed ones (AuthResource.TOO_MANY_ATTEMPTS, v0.3.9), and
 *  the current-password check of a password change counts. The console's calls get it in the error envelope; an API
 *  client's token sign-in gets HTTP 429. */
export const TOO_MANY_ATTEMPTS = 'error.auth.too.many.attempts';

export function isTooManyAttempts(err: unknown): boolean {
  return err instanceof ApiError && (err.message === TOO_MANY_ATTEMPTS || err.httpStatus === 429);
}

export const TOO_MANY_MESSAGE = 'Too many failed sign-ins. Wait a few minutes, then try again.';

export interface AuthOptions {
  signup: boolean;
  recover: boolean;
  /** Base64 RSA public key, present only when transmit.password mode is on. */
  publicKey?: string;
}

function hashPassword(plain: string): string {
  // Matches login.controller.js: md5(password).toUpperCase()
  return SparkMD5.hash(plain).toUpperCase();
}

export async function fetchAuthOptions(): Promise<AuthOptions> {
  return apiClient.get<AuthOptions>('/public/auth/options');
}

export async function login(
  username: string,
  password: string,
): Promise<AuthUser> {
  // We only implement the default MD5 path here. If the server is configured
  // with transmit.password (RSA), publicKey would be present in /options and a
  // JSEncrypt-style flow would be required — left as a documented TODO above.
  const payload = {
    login: username,
    password: hashPassword(password),
  };
  return apiClient.post<AuthUser>('/public/auth/login', payload);
}

/**
 * The signed-in user as the server has it now (`GET /private/users/current`, UserResource.getCurrentUserDetails:
 * the users row with its role and the role's permissions, the same data AuthFilter checks every request against).
 * Its `authToken` and `passwordResetToken` are write-only on that object, so the answer carries neither.
 */
export async function fetchCurrentUser(): Promise<AuthUser | null> {
  return apiClient.get<AuthUser | null>('/private/users/current');
}

export async function logout(): Promise<void> {
  try {
    await apiClient.post<void>('/public/auth/logout');
  } catch {
    // Best effort: invalidating the session client-side is enough for the UI.
  }
}

/**
 * Complete a forced password reset (first-login flow). The server's
 * `POST /public/passwordReset/reset` is public (no session needed) and clears the
 * `passwordReset` flag. `newPassword` is MD5-hashed like login (the server re-hashes).
 */
export async function submitForcedPasswordReset(
  passwordResetToken: string,
  newPassword: string,
): Promise<void> {
  await apiClient.post('/public/passwordReset/reset', {
    passwordResetToken,
    newPassword: hashPassword(newPassword),
  });
}

/**
 * Change the signed-in user's own password (`PUT /private/users/current`, UserResource.updatePassword). Both
 * passwords are MD5-hashed like login; the server checks `oldPassword` against the stored hash and answers
 * `error.password.wrong` when it does not match. `id` must be the signed-in user's id.
 */
export async function changeOwnPassword(
  user: { id: number; login: string },
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  await apiClient.put('/private/users/current', {
    id: user.id,
    login: user.login,
    oldPassword: hashPassword(currentPassword),
    newPassword: hashPassword(newPassword),
  });
}
