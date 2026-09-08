import { ApiError, createApiClient } from './client';

const jsonResponse = (status: number, body: unknown, headers?: Record<string, string>) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers }
  });

it('allows an explicit receipt retry to reload CSRF after a failed CSRF read without posting automatically', async () => {
 let reads = 0; let posts = 0;
 const client = createApiClient({ fetchImpl: async input => {
  if (input === '/api/csrf') { reads++; return reads === 1 ? jsonResponse(401, { error: { code: 'AUTH_REQUIRED', message: '请登录' } }) : jsonResponse(200, { data: { headerName: 'X-CSRF', token: 'restored-token' } }); }
  posts++; return jsonResponse(200, { data: { batchId: 8 } });
 } });
 const body = { additionalPrincipal: '3000.00', planToken: 'original', idempotencyKey: 'original-key' };
 await expect(client.api('/api/loans/4/repayment', { method: 'POST', body, handleUnauthorized: false })).rejects.toMatchObject({ status: 401 });
 expect(posts).toBe(0);
 await expect(client.api('/api/loans/4/repayment', { method: 'POST', body, handleUnauthorized: false })).resolves.toEqual({ batchId: 8 });
 expect(posts).toBe(1); expect(reads).toBe(2);
});

it('shares one CSRF load across concurrent writes and sends the returned header', async () => {
  let csrfLoads = 0;
  const requests: Array<[RequestInfo | URL, RequestInit | undefined]> = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    requests.push([input, init]);
    if (input === '/api/csrf') {
      csrfLoads += 1;
      return jsonResponse(200, { data: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'shared-token' } });
    }
    return jsonResponse(200, { data: { saved: true } });
  });
  const client = createApiClient({ fetchImpl });

  await Promise.all([
    client.api('/api/transactions', { method: 'POST', body: { amount: '12.00' } }),
    client.api('/api/budgets', { method: 'PATCH', body: { amount: '800.00' } })
  ]);

  expect(csrfLoads).toBe(1);
  const writes = requests.filter(([path]) => path !== '/api/csrf');
  expect(writes).toHaveLength(2);
  for (const [, init] of writes) {
    expect(new Headers(init?.headers).get('X-XSRF-TOKEN')).toBe('shared-token');
    expect(init?.credentials).toBe('same-origin');
  }
});

it('announces concurrent session expiry once and invalidates pending work', async () => {
  const onSessionExpired = vi.fn();
  const invalidate = vi.fn();
  const client = createApiClient({
    fetchImpl: vi.fn(async () => jsonResponse(401, { error: { code: 'AUTH_REQUIRED', message: '请先登录' } })),
    onSessionExpired,
    invalidatePendingWork: invalidate
  });

  const results = await Promise.allSettled([
    client.api('/api/session'),
    client.api('/api/family')
  ]);

  expect(results.every(result => result.status === 'rejected')).toBe(true);
  expect(onSessionExpired).toHaveBeenCalledTimes(1);
  expect(invalidate).toHaveBeenCalledTimes(1);
  expect((results[0] as PromiseRejectedResult).reason).toMatchObject({ status: 401, sessionExpired: true });
});

it('keeps credential failures separate from session expiry', async () => {
  const onSessionExpired = vi.fn();
  const client = createApiClient({
    fetchImpl: vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/csrf') {
        return jsonResponse(200, { data: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'login-token' } });
      }
      return jsonResponse(401, { error: { code: 'LOGIN_FAILED', message: '用户名或密码错误' } });
    }),
    onSessionExpired
  });

  await expect(client.api('/api/auth/login', {
    method: 'POST',
    body: new URLSearchParams({ username: 'missing@example.com', password: 'wrong-password' })
  })).rejects.toMatchObject({
    status: 401,
    code: 'LOGIN_FAILED',
    message: '用户名或密码错误',
    sessionExpired: false
  });
  expect(onSessionExpired).not.toHaveBeenCalled();
});

it('preserves server field errors and request id for an actionable message', async () => {
  const client = createApiClient({
    fetchImpl: vi.fn(async () => jsonResponse(
      400,
      { error: { code: 'VALIDATION_ERROR', message: '请检查输入内容', fields: { email: '邮箱格式不正确' } } },
      { 'X-Request-ID': 'req-42' }
    ))
  });

  await expect(client.api('/api/auth/register', { method: 'POST', body: {} })).rejects.toEqual(
    expect.objectContaining({
      status: 400,
      requestId: 'req-42',
      fields: { email: '邮箱格式不正确' }
    })
  );
});

it('passes the caller AbortSignal to fetch', async () => {
  let capturedSignal: AbortSignal | null | undefined;
  const fetchImpl = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    capturedSignal = init?.signal;
    return jsonResponse(200, { data: [] });
  });
  const client = createApiClient({ fetchImpl });
  const controller = new AbortController();
  await client.api('/api/assets', { signal: controller.signal });
  expect(capturedSignal).toBe(controller.signal);
});

it('builds a page from an array envelope and pagination headers', async () => {
  const client = createApiClient({
    fetchImpl: vi.fn(async () => jsonResponse(200, { data: [{ id: 51 }] }, {
      'X-Page': '1', 'X-Page-Size': '50', 'X-Total-Elements': '51',
      'X-Total-Pages': '2', 'X-Has-Next': 'false'
    }))
  });

  await expect(client.api('/api/transactions?page=1&size=50', { responseType: 'page' })).resolves.toEqual({
    items: [{ id: 51 }], page: 1, size: 50, totalElements: 51, totalPages: 2, hasNext: false
  });
});

it('returns a structured page envelope through the same opt-in path', async () => {
  const page = { items: [{ id: 1 }], page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false };
  const client = createApiClient({ fetchImpl: vi.fn(async () => jsonResponse(200, { data: page })) });

  await expect(client.api('/api/assets?page=0&size=20', { responseType: 'page' })).resolves.toEqual(page);
});

it('honors metadata when an exact full page is the last page', async () => {
  const items = Array.from({ length: 50 }, (_, id) => ({ id }));
  const client = createApiClient({
    fetchImpl: vi.fn(async () => jsonResponse(200, { data: items }, {
      'X-Page': '0', 'X-Page-Size': '50', 'X-Total-Elements': '50',
      'X-Total-Pages': '1', 'X-Has-Next': 'false'
    }))
  });

  const page = await client.api<{ items: unknown[]; hasNext: boolean }>('/api/categories?page=0&size=50', { responseType: 'page' });
  expect(page.items).toHaveLength(50);
  expect(page.hasNext).toBe(false);
});
