import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, readCookie } from './api'

afterEach(() => {
  vi.restoreAllMocks()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
})

describe('api client', () => {
  it('reads cookies', () => {
    expect(readCookie('XSRF-TOKEN', 'a=1; XSRF-TOKEN=abc%3D; b=2')).toBe('abc=')
    expect(readCookie('missing', 'a=1')).toBeUndefined()
  })

  it('sends the CSRF token on state-changing requests only', async () => {
    document.cookie = 'XSRF-TOKEN=tok123'
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{}', { status: 200 }))

    await api.get('/projects')
    await api.post('/projects', { name: 'x' })

    const getHeaders = fetchMock.mock.calls[0][1]!.headers as Record<string, string>
    const postHeaders = fetchMock.mock.calls[1][1]!.headers as Record<string, string>
    expect(getHeaders['X-XSRF-TOKEN']).toBeUndefined()
    expect(postHeaders['X-XSRF-TOKEN']).toBe('tok123')
    expect(fetchMock.mock.calls[1][1]!.credentials).toBe('same-origin')
  })

  it('turns error bodies into ApiError', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'Project not found' }), { status: 404 }),
    )
    await expect(api.get('/projects/1')).rejects.toMatchObject({ status: 404, code: 'NOT_FOUND', message: 'Project not found' })
  })

  it('explains an unreachable backend', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('<html>Bad Gateway</html>', { status: 502 }))
    const err = (await api.get('/dashboard').catch((e: unknown) => e)) as ApiError
    expect(err).toBeInstanceOf(ApiError)
    expect(err.message).toMatch(/backend is running/)
  })
})
