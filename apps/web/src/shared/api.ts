export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body) headers.set('Content-Type', 'application/json');
  if (options.method && options.method !== 'GET') {
    const res = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' });
    if (!res.ok) throw new ApiError(res.status, 'CSRF_UNAVAILABLE', '请求校验服务暂时不可用');
    const token = await res.json();
    headers.set('X-XSRF-TOKEN', token.token);
  }
  const res = await fetch('/api/v1' + path, { ...options, headers, credentials: 'same-origin' });
  const data = await res.json();
  if (!res.ok) {
    if (res.status === 401 && path !== '/auth/login' && path !== '/auth/me')
      window.dispatchEvent(new Event('auth-expired'));
    throw new ApiError(
      res.status,
      data.error?.code ?? 'REQUEST_FAILED',
      data.error?.message ?? '请求失败',
    );
  }
  return data;
}
const requestKeys = new Map<string, string>();
window.addEventListener('auth-expired', () => requestKeys.clear());
export async function write<T>(
  path: string,
  body: unknown,
  method = 'POST',
  key?: string,
): Promise<T> {
  const operation =
    method === 'POST' && (path === '/loans' || /^\/loans\/[^/]+\/(return|renew)$/.test(path));
  const signature = path + '|' + JSON.stringify(body);
  if (operation && !key) {
    key = requestKeys.get(signature) ?? crypto.randomUUID();
    requestKeys.set(signature, key);
  }
  const result = await api<T>(path, {
    method,
    body: JSON.stringify(body),
    headers: key ? { 'Idempotency-Key': key } : undefined,
  });
  requestKeys.delete(signature);
  return result;
}
export function query(values: Record<string, unknown>) {
  return (
    '?' +
    new URLSearchParams(
      Object.entries(values)
        .filter(([, v]) => v !== undefined && v !== null && v !== '')
        .map(([k, v]) => [k, String(v)]),
    ).toString()
  );
}
