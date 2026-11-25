import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useToasts } from '../stores/ui'
import { AnalyticsPage } from './AnalyticsPage'
import { LoginPage } from './AuthPages'
import { SettingsPage } from './SettingsPage'

const today = new Date().toISOString().slice(0, 10)
const overview = {
  days: 30, from: new Date(Date.now() - 29 * 86400000).toISOString(), to: new Date().toISOString(), timeZone: 'UTC',
  costs: {
    totals: [{ unit: 'CREDITS', amount: 380 }, { unit: 'USD', amount: 1.52 }],
    daily: [{ date: today, unit: 'CREDITS', amount: 320 }, { date: today, unit: 'USD', amount: 0.42 }],
    byProvider: [{ provider: 'RUNWAY', capability: 'VIDEO', unit: 'CREDITS', amount: 380, calls: 2 }, { provider: 'KLING', capability: 'VIDEO', amount: 0, calls: 1 }],
    calls: 5, failed: 1, unknownCost: 0,
  },
  publishing: {
    posts: 2, published: 2, failed: 1, upcoming: 1,
    byPlatform: [{ platform: 'YOUTUBE', published: 1, failed: 0 }, { platform: 'INSTAGRAM', published: 1, failed: 0 }, { platform: 'TIKTOK', published: 0, failed: 1 }],
    daily: [{ date: today, platform: 'YOUTUBE', count: 1 }, { date: today, platform: 'INSTAGRAM', count: 1 }],
    recentFailures: [{ postId: 'p', title: 'Ella', platform: 'TIKTOK', accountName: 'Kani', error: 'TikTok refused it', at: new Date().toISOString() }],
  },
  performance: {
    byPlatform: [{ platform: 'YOUTUBE', posts: 1, withNumbers: 1, views: 250, likes: 9, comments: 2 }, { platform: 'INSTAGRAM', posts: 1, withNumbers: 0 }],
    posts: [
      { postId: 'p', targetId: 't1', title: 'Ella', platform: 'YOUTUBE', accountName: 'Kani Eats', url: 'https://youtube.com/watch?v=1', views: 250, likes: 9, comments: 2 },
      { postId: 'p', targetId: 't2', title: 'Ella', platform: 'INSTAGRAM', accountName: '@kani', note: 'No numbers returned yet. Reconnect Facebook and Instagram to allow reading insights.' },
    ],
    lastCollectedAt: new Date().toISOString(),
  },
}

type Call = { url: string; method: string; body?: unknown }

function mock(calls: Call[], extra: (url: string, method: string, body: unknown) => Response | undefined = () => undefined) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ url, method, body })
    // signed in as an administrator: these pages show administrator controls
    if (url.endsWith('/auth/status')) return new Response(JSON.stringify({ setupRequired: false, setupEnabled: false, authenticated: true, user: { id: 'u1', email: 'kani@leenax.lk', displayName: 'Kani', role: 'ADMIN', canCreateProjects: true } }))
    const r = extra(url, method, body)
    if (r) return r
    if (url.includes('/analytics/overview')) return new Response(JSON.stringify(overview))
    if (url.endsWith('/analytics/refresh')) return new Response(JSON.stringify({ updated: 2 }))
    return new Response('{}', { status: 404 })
  })
}

function renderAt(ui: React.ReactElement, path = '/') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter></QueryClientProvider>)
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  useToasts.setState({ toasts: [] })
})

describe('Analytics', () => {
  it('shows totals, keeps currencies apart and explains missing numbers', async () => {
    const calls: Call[] = []
    mock(calls)
    renderAt(<AnalyticsPage />)
    expect(await screen.findByText('Posts published per day')).toBeInTheDocument()
    expect(calls.find((c) => c.url.includes('/analytics/overview'))!.url).toContain('tz=')
    expect(screen.getAllByText('380 credits').length).toBeGreaterThan(0)
    expect(screen.getAllByText('$1.52').length).toBeGreaterThan(0)             // other unit shown separately, never added
    expect(screen.getByText('Spent in credits')).toBeInTheDocument()
    expect(screen.getByText('Spent in US dollars')).toBeInTheDocument()
    expect(screen.getByText('TikTok refused it', { exact: false })).toBeInTheDocument()
    expect(screen.getByText(/Reconnect Facebook and Instagram to allow reading insights/)).toBeInTheDocument()
    expect(screen.getByText('not reported')).toBeInTheDocument()
    const posts = screen.getByRole('table', { name: 'Posts, most viewed first' })
    expect(within(posts).getByRole('link', { name: /Ella/ })).toHaveAttribute('href', 'https://youtube.com/watch?v=1')
  })

  it('offers every chart as a table and refreshes numbers on request', async () => {
    const calls: Call[] = []
    mock(calls)
    const user = userEvent.setup()
    renderAt(<AnalyticsPage />)
    await screen.findByText('Posts published per day')
    await user.click(screen.getAllByRole('button', { name: 'Show table' })[0])
    const table = screen.getByRole('table', { name: 'Posts published per day, by platform' })
    expect(within(table).getAllByRole('row')).toHaveLength(31)    // header + 30 days
    await user.click(screen.getByRole('button', { name: 'Refresh numbers' }))
    await waitFor(() => expect(useToasts.getState().toasts.map((t) => t.message)).toContain('Numbers updated for 2 posts'))
    await user.click(screen.getByRole('radio', { name: '7 days' }))
    await waitFor(() => expect(calls.some((c) => c.url.includes('days=7'))).toBe(true))
  })
})

describe('Two-step sign-in', () => {
  it('asks for the app code after a correct password', async () => {
    const calls: Call[] = []
    mock(calls, (url) => {
      if (url.endsWith('/auth/login')) return new Response(JSON.stringify({ mfaRequired: true }))
      if (url.endsWith('/auth/mfa/login')) return new Response(JSON.stringify({ code: 'INVALID_LOGIN', message: "That code didn't match" }), { status: 401 })
      return undefined
    })
    const user = userEvent.setup()
    renderAt(<LoginPage />)
    await user.type(screen.getByLabelText('Email'), 'k@example.com')
    await user.type(screen.getByLabelText('Password'), 'pw')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('heading', { name: 'Enter your code' })).toBeInTheDocument()
    await user.type(screen.getByLabelText('6-digit code'), '123456')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText("That code didn't match")).toBeInTheDocument()
    expect(calls.find((c) => c.url.endsWith('/auth/mfa/login'))!.body).toEqual({ code: '123456' })
    await user.click(screen.getByRole('button', { name: 'Use a recovery code' }))
    expect(screen.getByLabelText('Recovery code')).toBeInTheDocument()
  })

  it('sets it up from Settings and shows the recovery codes once', async () => {
    const calls: Call[] = []
    let enabled = false
    mock(calls, (url) => {
      if (url.endsWith('/auth/me')) return new Response(JSON.stringify({ email: 'k@example.com', displayName: 'Kani', mfaEnabled: enabled, recoveryCodesLeft: enabled ? 10 : 0 }))
      if (url.endsWith('/auth/mfa/setup')) return new Response(JSON.stringify({ secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Leenax%20Studio:k?secret=JBSWY3DPEHPK3PXP' }))
      if (url.endsWith('/auth/mfa/enable')) { enabled = true; return new Response(JSON.stringify({ recoveryCodes: ['abcde-fghjk', 'mnpqr-stuvw'] })) }
      if (url.includes('/audit')) return new Response(JSON.stringify({ items: [{ id: '1', action: 'LOGIN_FAILED', details: {}, ipAddress: '10.0.0.5', createdAt: new Date().toISOString() }], page: 0, size: 25, totalItems: 1, totalPages: 1 }))
      return undefined
    })
    const user = userEvent.setup()
    renderAt(<SettingsPage />, '/settings?tab=security')
    expect(await screen.findByText('Failed sign-in (wrong email or password)')).toBeInTheDocument()
    expect(screen.getByText('10.0.0.5')).toBeInTheDocument()
    await user.type(await screen.findByLabelText('Your password'), 'pw')
    await user.click(screen.getByRole('button', { name: 'Set up two-step sign-in' }))
    expect(await screen.findByText('JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP')).toBeInTheDocument()
    const turnOn = screen.getByRole('button', { name: 'Turn on' })
    expect(turnOn).toBeDisabled()
    await user.type(screen.getByLabelText('6-digit code'), '123456')
    await user.click(turnOn)
    expect(await screen.findByText('abcde-fghjk')).toBeInTheDocument()
    expect(calls.find((c) => c.url.endsWith('/auth/mfa/enable'))!.body).toEqual({ code: '123456' })
    await user.click(screen.getByRole('button', { name: "I've saved them" }))
    expect(await screen.findByText(/10 recovery codes left/)).toBeInTheDocument()
  })
})

describe('System health', () => {
  it('lists services, queues and backup warnings', async () => {
    mock([], (url) => url.endsWith('/system/status') ? new Response(JSON.stringify({
      checkedAt: new Date().toISOString(), uptime: 'PT26H5M', javaVersion: '21.0.5', schemaVersion: '7',
      services: [{ name: 'Database', state: 'OK', detail: 'PostgreSQL, schema version 7' }, { name: 'Video export service', state: 'DOWN', detail: 'Not reachable' }],
      queues: [{ name: 'Social posts', waiting: 2, running: 0, failedToday: 1 }],
      mediaFiles: 120, mediaBytes: 5e9, databaseBytes: 7e7,
      lastBackup: { finishedAt: new Date().toISOString(), ok: true, databaseBytes: 7e7, mediaBytes: 5e9, folder: '20261119-031500' },
      warnings: ['Video export service is down.', '1 background job failed in the last 24 hours.'],
    })) : undefined)
    renderAt(<SettingsPage />, '/settings?tab=system')
    expect(await screen.findByRole('alert')).toHaveTextContent('Video export service is down.')
    expect(screen.getByText('PostgreSQL, schema version 7')).toBeInTheDocument()
    expect(screen.getByText('backups/20261119-031500')).toBeInTheDocument()
    expect(screen.getByText(/Up 1 d 2 h/)).toBeInTheDocument()
  })
})
