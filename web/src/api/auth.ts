import SparkMD5 from 'spark-md5';
import { apiClient } from './client';

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
  // The server may also signal a forced password reset or 2FA step.
  passwordReset?: boolean;
  passwordResetToken?: string;
  twoFactor?: boolean;
  superAdmin?: boolean;
  singleCustomer?: boolean;
  userRole?: {
    superAdmin?: boolean;
    permissions?: { name: string }[];
  };
}

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

export async function logout(): Promise<void> {
  try {
    await apiClient.post<void>('/public/auth/logout');
  } catch {
    // Best effort: invalidating the session client-side is enough for the UI.
  }
}
