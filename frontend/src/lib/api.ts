/**
 * Thin API client.
 * - Auth is an HttpOnly session cookie; nothing secret is ever stored in JS.
 * - Every state-changing request echoes the XSRF-TOKEN cookie as X-XSRF-TOKEN.
 */

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

const BASE = '/api/v1'

export function readCookie(name: string, cookieString: string = document.cookie): string | undefined {
  for (const part of cookieString.split(';')) {
    const [k, ...rest] = part.trim().split('=')
    if (k === name) return decodeURIComponent(rest.join('='))
  }
  return undefined
}

function csrfHeaders(method: string): Record<string, string> {
  if (method === 'GET' || method === 'HEAD') return {}
  const token = readCookie('XSRF-TOKEN')
  return token ? { 'X-XSRF-TOKEN': token } : {}
}

async function toError(res: Response): Promise<ApiError> {
  try {
    const body = await res.json()
    return new ApiError(res.status, body.code ?? 'ERROR', body.message ?? res.statusText)
  } catch {
    const fallback =
      res.status === 413 ? 'File is larger than the upload limit' :
      res.status === 502 || res.status === 503 ? 'The server is not reachable. Check that the backend is running.' :
      `Request failed (${res.status})`
    return new ApiError(res.status, 'ERROR', fallback)
  }
}

let onUnauthorized: (() => void) | undefined
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

export async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  extraHeaders: Record<string, string> = {},
): Promise<T> {
  const res = await fetch(BASE + path, {
    method,
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...csrfHeaders(method),
      ...extraHeaders,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (res.status === 401 && !path.startsWith('/auth/')) onUnauthorized?.()
  if (!res.ok) throw await toError(res)
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

export const api = {
  get: <T,>(path: string) => request<T>('GET', path),
  post: <T,>(path: string, body?: unknown, headers?: Record<string, string>) => request<T>('POST', path, body ?? {}, headers),
  put: <T,>(path: string, body: unknown) => request<T>('PUT', path, body),
  patch: <T,>(path: string, body: unknown) => request<T>('PATCH', path, body),
  delete: (path: string) => request<void>('DELETE', path),
}

/** Multipart upload with progress (fetch has no upload progress, so XHR). */
export function uploadFile<T>(
  path: string,
  file: File,
  onProgress: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', BASE + path)
    xhr.withCredentials = true
    const token = readCookie('XSRF-TOKEN')
    if (token) xhr.setRequestHeader('X-XSRF-TOKEN', token)
    xhr.setRequestHeader('Accept', 'application/json')

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(e.loaded / e.total)
    }
    xhr.onload = () => {
      let body: { code?: string; message?: string } | undefined
      try {
        body = JSON.parse(xhr.responseText)
      } catch {
        body = undefined
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(body as T)
      } else {
        if (xhr.status === 401) onUnauthorized?.()
        const message =
          body?.message ?? (xhr.status === 413 ? 'File is larger than the upload limit' : `Upload failed (${xhr.status})`)
        reject(new ApiError(xhr.status, body?.code ?? 'ERROR', message))
      }
    }
    xhr.onerror = () => reject(new ApiError(0, 'NETWORK', 'Network error. Check your connection and try again.'))
    xhr.onabort = () => reject(new ApiError(0, 'ABORTED', 'Upload cancelled'))
    signal?.addEventListener('abort', () => xhr.abort())

    const form = new FormData()
    form.append('file', file)
    xhr.send(form)
  })
}
