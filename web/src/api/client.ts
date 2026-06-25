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

interface RequestOptions {
  method?: string;
  body?: unknown;
  // Some endpoints (e.g. /devices/search) consume a raw JSON string rather than
  // an object. We always JSON.stringify, which is correct for both.
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
  };

  if (opts.body !== undefined) {
    if (opts.body instanceof FormData) {
      // Let the browser set the multipart boundary; don't force a Content-Type.
      init.body = opts.body;
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

  const envelope = JSON.parse(text) as ApiEnvelope<T>;
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
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  postForm: <T>(path: string, form: FormData) =>
    request<T>(path, { method: 'POST', body: form }),
};
