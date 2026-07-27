import { t } from '../i18n';

export const API_PREFIX = '/api/v1';

export interface ApiErrorBody {
  code?: string;
  message?: string;
  field?: string | null;
}

/** A failed API call with the structured error returned by the server. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null = null,
    readonly field: string | null = null,
    /** The decoded response is retained for typed errors such as auth challenges. */
    readonly body: unknown = null,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export const TRANSPORT_STATUS = 0;

export const isTransportError = (error: unknown): error is ApiError =>
  error instanceof ApiError && error.status === TRANSPORT_STATUS;

export type AccessTokenProvider = () => string | null | Promise<string | null>;
export type CsrfTokenProvider = () => string | null;
export type UnauthorizedListener = () => void;
export type ForbiddenListener = () => void;
export interface RequestBehavior {
  /** Public authentication failures must not expire an already valid browser session. */
  broadcastAuthFailure?: boolean;
  timeoutMs?: number;
}

export const DEFAULT_TIMEOUT_MS = 30_000;
export const PROVIDER_TIMEOUT_MS = 10 * 60_000;

// Calling a browser's native fetch as an object method can give it the wrong
// receiver and cause "Illegal invocation". The wrapper keeps injection easy
// for tests while always invoking the platform function through globalThis.
const browserFetch: typeof fetch = (input, init) => globalThis.fetch(input, init);

const isJson = (response: Response) =>
  (response.headers.get('content-type') ?? '').toLowerCase().includes('json');

const isEmpty = (response: Response) => response.headers.get('content-length') === '0';

function transportError(cause: unknown, budget: AbortSignal): ApiError {
  if (budget.aborted) return new ApiError(t('common.requestTimeout'), TRANSPORT_STATUS, 'timeout');
  if (cause instanceof DOMException && cause.name === 'AbortError') {
    return new ApiError(cause.message, TRANSPORT_STATUS, 'aborted');
  }
  return new ApiError(t('common.networkError'), TRANSPORT_STATUS, 'network');
}

/**
 * HTTP transport for the executable API.
 *
 * Authentication is intentionally a transport concern: cookie auth works via
 * same-origin credentials today, while a future bearer provider can be
 * installed once at composition instead of modifying every feature call.
 */
export class ApiHttpClient {
  private csrfToken?: CsrfTokenProvider;
  private unauthorizedListeners = new Set<UnauthorizedListener>();
  private forbiddenListeners = new Set<ForbiddenListener>();

  constructor(
    private readonly fetchImpl: typeof fetch = browserFetch,
    private readonly accessToken?: AccessTokenProvider,
  ) {}

  setCsrfTokenProvider(provider?: CsrfTokenProvider) {
    this.csrfToken = provider;
  }

  onUnauthorized(listener: UnauthorizedListener): () => void {
    this.unauthorizedListeners.add(listener);
    return () => this.unauthorizedListeners.delete(listener);
  }

  onForbidden(listener: ForbiddenListener): () => void {
    this.forbiddenListeners.add(listener);
    return () => this.forbiddenListeners.delete(listener);
  }

  notifyUnauthorized() {
    this.unauthorizedListeners.forEach((listener) => listener());
  }

  endpoint(path: string): string {
    return `${API_PREFIX}${path}`;
  }

  async raw(
    path: string,
    init: RequestInit = {},
    behavior: RequestBehavior = {},
  ): Promise<Response> {
    const headers = new Headers(init.headers);
    const token = await this.accessToken?.();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    const method = (init.method ?? 'GET').toUpperCase();
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      const csrf = this.csrfToken?.();
      if (csrf) headers.set('X-CSRF-Token', csrf);
    }
    const budget = AbortSignal.timeout(behavior.timeoutMs ?? DEFAULT_TIMEOUT_MS);
    const signal = init.signal ? AbortSignal.any([init.signal, budget]) : budget;
    let response: Response;
    try {
      response = await this.fetchImpl(this.endpoint(path), {
        credentials: 'same-origin',
        ...init,
        headers,
        signal,
      });
    } catch (cause) {
      throw transportError(cause, budget);
    }
    if (response.status === 401 && behavior.broadcastAuthFailure !== false) {
      this.unauthorizedListeners.forEach((listener) => listener());
    } else if (response.status === 403 && behavior.broadcastAuthFailure !== false) {
      this.forbiddenListeners.forEach((listener) => listener());
    }
    return response;
  }

  async json<T>(
    path: string,
    init?: RequestInit,
    behavior?: RequestBehavior,
  ): Promise<T> {
    const response = await this.raw(path, init, behavior);
    if (!response.ok) throw await this.toError(response);
    if (response.status === 204 || isEmpty(response)) return null as T;
    if (!isJson(response)) {
      throw new ApiError(t('common.unexpectedResponse'), response.status, 'unexpected_response');
    }
    return await response.json() as T;
  }

  socketUrl(path: string): string {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${location.host}${this.endpoint(path)}`;
  }

  private async toError(response: Response): Promise<ApiError> {
    let message = `HTTP ${response.status}`;
    let code: string | null = null;
    let field: string | null = null;
    let body: unknown = null;
    try {
      body = await response.json();
      const errorBody = body as ApiErrorBody;
      message = errorBody.message || message;
      code = errorBody.code ?? null;
      field = errorBody.field ?? null;
    } catch {
      // Non-JSON upstream failures retain their useful HTTP status.
    }
    return new ApiError(message, response.status, code, field, body);
  }
}

export const browserApiHttp = new ApiHttpClient();

export const jsonBody = (method: 'POST' | 'PUT' | 'PATCH', body: unknown): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

export const post = (body: unknown) => jsonBody('POST', body);
export const put = (body: unknown) => jsonBody('PUT', body);
