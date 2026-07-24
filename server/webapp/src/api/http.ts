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
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export type AccessTokenProvider = () => string | null | Promise<string | null>;

/**
 * HTTP transport for the executable API.
 *
 * Authentication is intentionally a transport concern: cookie auth works via
 * same-origin credentials today, while a future bearer provider can be
 * installed once at composition instead of modifying every feature call.
 */
export class ApiHttpClient {
  constructor(
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly accessToken?: AccessTokenProvider,
  ) {}

  endpoint(path: string): string {
    return `${API_PREFIX}${path}`;
  }

  async raw(path: string, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers);
    const token = await this.accessToken?.();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    return this.fetchImpl(this.endpoint(path), {
      credentials: 'same-origin',
      ...init,
      headers,
    });
  }

  async json<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await this.raw(path, init);
    if (!response.ok) throw await this.toError(response);
    return (response.status === 204 ? null : await response.json()) as T;
  }

  socketUrl(path: string): string {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${location.host}${this.endpoint(path)}`;
  }

  private async toError(response: Response): Promise<ApiError> {
    let message = `HTTP ${response.status}`;
    let code: string | null = null;
    let field: string | null = null;
    try {
      const body = (await response.json()) as ApiErrorBody;
      message = body.message || message;
      code = body.code ?? null;
      field = body.field ?? null;
    } catch {
      // Non-JSON upstream failures retain their useful HTTP status.
    }
    return new ApiError(message, response.status, code, field);
  }
}

export const browserApiHttp = new ApiHttpClient();

export const jsonBody = (method: 'POST' | 'PUT', body: unknown): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

export const post = (body: unknown) => jsonBody('POST', body);
export const put = (body: unknown) => jsonBody('PUT', body);
