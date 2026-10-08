// Thin fetch wrapper around the Headwind MDM REST API.
//
// The server (see com.hmdm.rest.json.Response) wraps every response in an
// envelope: { status: "OK" | "ERROR" | ..., message: string | null, data: T }.
// Authentication is *session based*: AuthResource#login stores the user in the
// HttpSession and the browser receives a JSESSIONID cookie. There is no bearer
// token to attach, so every request must be sent with credentials so the
// cookie rides along.

export const API_BASE: string =
  (import.meta.env.VITE_API_BASE ?? '/rest').replace(/\/$/, '');

export type ResponseStatus = 'OK' | 'ERROR' | string;

export interface ApiEnvelope<T> {
  status: ResponseStatus;
  message: string | null;
  data: T;
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: ResponseStatus,
    readonly httpStatus: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

type SessionExpiredListener = () => void;
const sessionExpiredListeners = new Set<SessionExpiredListener>();

/** Subscribe to an HTTP session expiry; routing remains owned by the React layer. */
export function onSessionExpired(listener: SessionExpiredListener): () => void {
  sessionExpiredListeners.add(listener);
  return () => sessionExpiredListeners.delete(listener);
}

function notifySessionExpired(): void {
  for (const listener of sessionExpiredListeners) listener();
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Optional cancellation, forwarded to fetch — lets callers abort long polls. */
  signal?: AbortSignal;
  // Some endpoints (e.g. /devices/search) consume a raw JSON string rather than
  // an object. We always JSON.stringify, which is correct for both.
  /** Send `body` verbatim as text/plain instead of JSON — for endpoints that read
   *  a raw String (e.g. POST /devices/{id}/description). */
  text?: boolean;
}

async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const url = `${API_BASE}${path}`;
  const init: RequestInit = {
    method: opts.method ?? 'GET',
    // Always send the session cookie. Works same-origin and (with proper CORS)
    // cross-origin.
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
    signal: opts.signal,
  };

  if (opts.body !== undefined) {
    if (opts.body instanceof FormData) {
      // Let the browser set the multipart boundary; don't force a Content-Type.
      init.body = opts.body;
    } else if (opts.text) {
      // Raw text body for endpoints that read a String (not JSON) — sending it
      // JSON-encoded would store the surrounding quotes.
      init.headers = { ...init.headers, 'Content-Type': 'text/plain' };
      init.body = String(opts.body);
    } else {
      init.headers = {
        ...init.headers,
        'Content-Type': 'application/json',
      };
      init.body = JSON.stringify(opts.body);
    }
  }

  let res: globalThis.Response;
  try {
    res = await fetch(url, init);
  } catch (e) {
    throw new ApiError(
      `Network error contacting the server: ${(e as Error).message}`,
      'ERROR',
      0,
    );
  }

  if (res.status === 401 || res.status === 403) {
    notifySessionExpired();
    throw new ApiError('Not authenticated', 'ERROR', res.status);
  }

  if (!res.ok) {
    throw new ApiError(
      `Request failed with HTTP ${res.status}`,
      'ERROR',
      res.status,
    );
  }

  // Most endpoints return the JSON envelope. Some (logout) return no body.
  const text = await res.text();
  if (!text) {
    return undefined as unknown as T;
  }

  let envelope: ApiEnvelope<T>;
  try {
    envelope = JSON.parse(text) as ApiEnvelope<T>;
  } catch {
    // e.g. an HTML error page from a proxy — surface it as an API error, not a crash.
    throw new ApiError('Unexpected non-JSON response from server', 'ERROR', res.status);
  }
  if (envelope.status && envelope.status !== 'OK') {
    throw new ApiError(
      envelope.message ?? 'The server returned an error',
      envelope.status,
      res.status,
    );
  }
  return envelope.data;
}

export const apiClient = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T>(path: string, body?: unknown, signal?: AbortSignal) =>
    request<T>(path, { method: 'POST', body, signal }),
  postText: <T>(path: string, body: string, signal?: AbortSignal) =>
    request<T>(path, { method: 'POST', body, text: true, signal }),
  put: <T>(path: string, body?: unknown, signal?: AbortSignal) =>
    request<T>(path, { method: 'PUT', body, signal }),
  del: <T>(path: string, signal?: AbortSignal) =>
    request<T>(path, { method: 'DELETE', signal }),
  postForm: <T>(path: string, form: FormData, signal?: AbortSignal) =>
    request<T>(path, { method: 'POST', body: form, signal }),
};
