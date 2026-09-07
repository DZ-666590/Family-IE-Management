import type { ApiEnvelope, ApiFailure, CsrfToken, Page } from './contracts';

export const SESSION_EXPIRED_MESSAGE = '登录会话已过期，请重新登录。';

interface ApiErrorDetails {
  status: number;
  code?: string;
  fields?: Record<string, string>;
  requestId?: string;
  sessionExpired?: boolean;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly fields?: Record<string, string>;
  readonly requestId?: string;
  sessionExpired: boolean;

  constructor(message: string, details: ApiErrorDetails) {
    super(message);
    this.name = 'ApiError';
    this.status = details.status;
    this.code = details.code;
    this.fields = details.fields;
    this.requestId = details.requestId;
    this.sessionExpired = details.sessionExpired ?? false;
  }
}

export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
  body?: unknown;
  handleUnauthorized?: boolean;
  responseType?: 'json' | 'blob' | 'text' | 'page';
}

interface ApiClientOptions {
  fetchImpl?: typeof fetch;
  onSessionExpired?: (message: string) => void;
  invalidatePendingWork?: () => void;
}

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

function isBodyInit(body: unknown): body is BodyInit {
  return typeof body === 'string'
    || body instanceof URLSearchParams
    || body instanceof FormData
    || body instanceof Blob
    || body instanceof ArrayBuffer
    || ArrayBuffer.isView(body);
}

function requestIdFrom(response: Response): string | undefined {
  return response.headers.get('X-Request-ID')
    ?? response.headers.get('X-Request-Id')
    ?? undefined;
}

async function readEnvelope(response: Response): Promise<ApiEnvelope<unknown> | undefined> {
  if (response.status === 204) return undefined;
  return response.json().catch(() => undefined) as Promise<ApiEnvelope<unknown> | undefined>;
}

function pageNumber(response: Response, name: string): number {
  const raw = response.headers.get(name);
  const value = raw === null ? Number.NaN : Number(raw);
  if (!Number.isInteger(value) || value < 0) throw new Error(`分页响应缺少有效的 ${name} 元数据`);
  return value;
}

function pageBoolean(response: Response, name: string): boolean {
  const raw = response.headers.get(name);
  if (raw !== 'true' && raw !== 'false') throw new Error(`分页响应缺少有效的 ${name} 元数据`);
  return raw === 'true';
}

function isPage(value: unknown): value is Page<unknown> {
  if (!value || typeof value !== 'object') return false;
  const page = value as Partial<Page<unknown>>;
  return Array.isArray(page.items)
    && Number.isInteger(page.page) && Number.isInteger(page.size)
    && Number.isInteger(page.totalElements) && Number.isInteger(page.totalPages)
    && typeof page.hasNext === 'boolean';
}

function readPage(response: Response, data: unknown): Page<unknown> {
  if (isPage(data)) return data;
  if (!Array.isArray(data)) throw new Error('分页响应 data 必须是数组或 Page 对象');
  return {
    items: data,
    page: pageNumber(response, 'X-Page'),
    size: pageNumber(response, 'X-Page-Size'),
    totalElements: pageNumber(response, 'X-Total-Elements'),
    totalPages: pageNumber(response, 'X-Total-Pages'),
    hasNext: pageBoolean(response, 'X-Has-Next')
  };
}

export function createApiClient({
  fetchImpl = fetch,
  onSessionExpired = () => undefined,
  invalidatePendingWork = () => undefined
}: ApiClientOptions = {}) {
  let csrfPromise: Promise<CsrfToken> | null = null;
  let expiryHandled = false;
  let sessionGeneration = 0;

  async function loadCsrf(): Promise<CsrfToken> {
    csrfPromise ??= (async () => {
      const response = await fetchImpl('/api/csrf', { credentials: 'same-origin' });
      const envelope = await readEnvelope(response) as ApiEnvelope<CsrfToken> | undefined;
      if (!response.ok || !envelope?.data) {
        csrfPromise = null;
        throw new ApiError(envelope?.error?.message ?? '无法建立安全连接，请重试', {
          status: response.status,
          code: envelope?.error?.code,
          fields: envelope?.error?.fields,
          requestId: requestIdFrom(response)
        });
      }
      return envelope.data;
    })();
    return csrfPromise;
  }

  async function api<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    const generation = sessionGeneration;
    const assertCurrent = () => {
      if (generation !== sessionGeneration || options.signal?.aborted) throw new DOMException('Session changed or request aborted', 'AbortError');
    };
    assertCurrent();
    const {
      body,
      handleUnauthorized = true,
      responseType = 'json',
      ...requestInit
    } = options;
    const method = (requestInit.method ?? 'GET').toUpperCase();
    const headers = new Headers(requestInit.headers);
    if (WRITE_METHODS.has(method)) {
      const csrf = await loadCsrf();
      assertCurrent();
      headers.set(csrf.headerName, csrf.token);
    }

    let encodedBody: BodyInit | undefined;
    if (body !== undefined && body !== null) {
      if (isBodyInit(body)) {
        encodedBody = body;
      } else {
        headers.set('Content-Type', 'application/json');
        encodedBody = JSON.stringify(body);
      }
    }

    const response = await fetchImpl(path, {
      ...requestInit,
      method,
      headers,
      body: encodedBody,
      credentials: 'same-origin'
    });
    assertCurrent();

    if (!response.ok) {
      const envelope = await readEnvelope(response);
      assertCurrent();
      const failure = envelope?.error as ApiFailure | undefined;
      const error = new ApiError(failure?.message ?? '请求未能完成', {
        status: response.status,
        code: failure?.code,
        fields: failure?.fields,
        requestId: requestIdFrom(response)
      });
      // Login credential failures also use HTTP 401, but they do not mean that
      // an existing browser session expired. Keep the server's actionable
      // LOGIN_FAILED response on the login form instead of redirecting the
      // user into the session-expired flow.
      if (response.status === 401 && handleUnauthorized && failure?.code !== 'LOGIN_FAILED') {
        error.sessionExpired = true;
        if (!expiryHandled) {
          expiryHandled = true;
          invalidatePendingWork();
          onSessionExpired(SESSION_EXPIRED_MESSAGE);
        }
      }
      throw error;
    }

    if (response.status === 204) return undefined as T;
    if (responseType === 'blob' || responseType === 'text') {
      const value = responseType === 'blob' ? await response.blob() : await response.text();
      assertCurrent();
      return value as T;
    }
    const envelope = await readEnvelope(response);
    assertCurrent();
    if (responseType === 'page') return readPage(response, envelope?.data) as T;
    return envelope?.data as T;
  }

  return {
    api,
    resetSessionScope() {
      sessionGeneration += 1;
      csrfPromise = null;
      expiryHandled = false;
    },
    resetSessionExpiry() {
      expiryHandled = false;
    },
    invalidateCsrf() {
      csrfPromise = null;
    }
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
