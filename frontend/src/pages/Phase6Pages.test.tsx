import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { useToasts } from '../stores/ui'
import { CalendarPage } from './CalendarPage'
import { PublisherPage } from './PublisherPage'
import { SocialAccountsPage } from './SocialAccountsPage'

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) { this.removeAttribute('open') }
})

const platforms = [
  { platform: 'YOUTUBE', name: 'YouTube', signIn: 'GOOGLE', configured: true, redirectUri: 'https://s/api/v1/social/oauth/google/callback', setupHint: 'g', limits: ['Uploads stay private until audited.'] },
  { platform: 'FACEBOOK', name: 'Facebook Page', signIn: 'META', configured: false, redirectUri: 'https://s/api/v1/social/oauth/meta/callback', setupHint: 'Create a Business app', limits: [] },
  { platform: 'INSTAGRAM', name: 'Instagram', signIn: 'META', configured: false, redirectUri: 'https://s/api/v1/social/oauth/meta/callback', setupHint: 'Create a Business app', limits: [] },
  { platform: 'TIKTOK', name: 'TikTok', signIn: 'TIKTOK', configured: true, redirectUri: 'https://s/api/v1/social/oauth/tiktok/callback', setupHint: 't', limits: [] },
]
const yt = { id: 'a-yt', platform: 'YOUTUBE', displayName: 'Kani Travels', status: 'CONNECTED', connectedAt: '2026-11-01T00:00:00Z' }
const tt = { id: 'a-tt', platform: 'TIKTOK', displayName: 'kani.tt', status: 'NEEDS_RECONNECT', lastError: 'TikTok sign-in expired. Reconnect TikTok.', connectedAt: '2026-11-01T00:00:00Z' }
const ttOk = { ...tt, status: 'CONNECTED', lastError: undefined }
const video = { id: 'm1', kind: 'VIDEO', source: 'UPLOAD', filename: 'ella.mp4', contentType: 'video/mp4', sizeBytes: 9_000_000, width: 1080, height: 1920, durationMs: 30_000, createdAt: '2026-11-01T00:00:00Z', url: '/m1' }

type Call = { url: string; method: string; body?: unknown }

function mock(calls: Call[], opts: { accounts?: unknown[]; posts?: unknown[]; calendar?: unknown[] } = {}) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
    // signed in as an administrator: these pages show administrator controls
    if (url.endsWith('/auth/status')) return new Response(JSON.stringify({ setupRequired: false, setupEnabled: false, authenticated: true, user: { id: 'u1', email: 'kani@leenax.lk', displayName: 'Kani', role: 'ADMIN', canCreateProjects: true } }))
    if (url.endsWith('/social/platforms')) return new Response(JSON.stringify(platforms))
    if (url.endsWith('/social/accounts')) return new Response(JSON.stringify(opts.accounts ?? [yt, tt]))
    if (url.includes('/oauth/google/start')) return new Response(JSON.stringify({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?x=1' }))
    if (url.includes('/tiktok-creator')) {
      return new Response(JSON.stringify({ username: 'kani', nickname: 'Kani', privacyOptions: ['PUBLIC_TO_EVERYONE', 'SELF_ONLY'], commentDisabled: true, duetDisabled: false, stitchDisabled: false, maxVideoSeconds: 600 }))
    }
    if (url.includes('/media/m1')) return new Response(JSON.stringify(video))
    if (url.includes('/publish/calendar')) return new Response(JSON.stringify(opts.calendar ?? []))
    if (url.includes('/publish/posts') && method === 'POST' && url.endsWith('/reschedule')) return new Response(JSON.stringify({}))
    if (url.includes('/publish/posts') && method === 'POST') {
      return new Response(JSON.stringify({ id: 'p-new', title: 'x', status: 'SCHEDULED', scheduledAt: '2026-12-01T03:30:00Z', targets: [], createdAt: '', updatedAt: '', version: 0 }), { status: 201 })
    }
    if (url.includes('/publish/posts')) return new Response(JSON.stringify(opts.posts ?? []))
    return new Response('{}', { status: 404 })
  })
}

function renderAt(ui: React.ReactElement, path = '/') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  useToasts.setState({ toasts: [] })
})

describe('Social accounts', () => {
  it('lists connected accounts, flags one that needs reconnecting and explains unset platforms', async () => {
    mock([])
    renderAt(<SocialAccountsPage />)
    expect(await screen.findByText('Kani Travels')).toBeInTheDocument()
    expect(screen.getByText('TikTok sign-in expired. Reconnect TikTok.')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Reconnect' })).toBeInTheDocument()
    expect(screen.getByText('Create a Business app')).toBeInTheDocument()
    expect(screen.getByText('https://s/api/v1/social/oauth/meta/callback')).toBeInTheDocument()
    expect(screen.getByText('Uploads stay private until audited.')).toBeInTheDocument()
  })

  it('sends the browser to the official sign-in page', async () => {
    const calls: Call[] = []
    mock(calls, { accounts: [] })
    const assign = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign } as Location)
    renderAt(<SocialAccountsPage />)
    await userEvent.click(await screen.findByRole('button', { name: 'Connect with Google' }))
    await waitFor(() => expect(assign).toHaveBeenCalledWith('https://accounts.google.com/o/oauth2/v2/auth?x=1'))
    expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/social/oauth/google/start'))).toBe(true)
  })

  it('reports the sign-in result from the address', async () => {
    mock([])
    renderAt(<SocialAccountsPage />, '/publish/accounts?error=Sign-in%20was%20cancelled.')
    await waitFor(() => expect(useToasts.getState().toasts.map((t) => t.message)).toContain('Sign-in was cancelled.'))
  })
})

describe('Publisher', () => {
  it('requires the TikTok choices, then schedules one post for both accounts', async () => {
    const calls: Call[] = []
    mock(calls, { accounts: [yt, ttOk] })
    const user = userEvent.setup()
    renderAt(<PublisherPage />, '/publish/new?media=m1')
    expect(await screen.findByDisplayValue('ella')).toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: /Kani Travels/ }))
    await user.click(screen.getByRole('checkbox', { name: /kani\.tt/ }))
    await user.type(screen.getByLabelText('Caption for every platform'), 'Sunrise at Ella')
    await user.type(screen.getByLabelText('Hashtags'), 'srilanka')
    await user.click(screen.getByRole('button', { name: 'Copy to all platforms' }))

    // TikTok tab: creator info, no default privacy, comments locked off by the creator
    expect(await screen.findByText('Kani')).toBeInTheDocument()
    expect(screen.getByLabelText('Caption')).toHaveValue('Sunrise at Ella\n\n#srilanka')
    expect(screen.getByLabelText('Who can see this post')).toHaveValue('')
    expect(screen.getByRole('checkbox', { name: /Comment/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Publish now' })).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Who can see this post'), 'SELF_ONLY')
    await user.click(screen.getByRole('checkbox', { name: /Music Usage Confirmation/ }))

    // YouTube tab: title and audience
    await user.click(screen.getByRole('tab', { name: /Kani Travels/ }))
    await user.click(screen.getByRole('radio', { name: /not made for kids/ }))

    await user.click(screen.getByRole('radio', { name: /Schedule/ }))
    await user.type(screen.getByLabelText('Post at'), '2026-12-01T09:00')
    const submit = screen.getByRole('button', { name: 'Schedule' })
    await waitFor(() => expect(submit).toBeEnabled())
    await user.click(submit)

    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/publish/posts'))).toBe(true))
    const body = calls.find((c) => c.method === 'POST' && c.url.endsWith('/publish/posts'))!.body as {
      action: string; mediaId: string; scheduledAt: string; targets: { accountId: string; settings: Record<string, unknown> }[]
    }
    expect(body.action).toBe('SCHEDULE')
    expect(body.mediaId).toBe('m1')
    expect(body.scheduledAt).toBe(new Date('2026-12-01T09:00').toISOString())
    const tiktok = body.targets.find((t) => t.accountId === 'a-tt')!.settings
    expect(tiktok.privacy).toBe('SELF_ONLY')
    expect(tiktok.musicConsent).toBe(true)
    expect(tiktok).not.toHaveProperty('disclose')
    const youtube = body.targets.find((t) => t.accountId === 'a-yt')!.settings
    expect(youtube.title).toBe('ella')
    expect(youtube.madeForKids).toBe(false)
    expect(youtube.description).toBe('Sunrise at Ella\n\n#srilanka')
  })
})

describe('Calendar', () => {
  it('shows posts on their day and moves a scheduled post by drag and drop', async () => {
    const calls: Call[] = []
    const today = new Date()
    const at = new Date(today.getFullYear(), today.getMonth(), today.getDate(), 23, 50)
    const scheduled = {
      id: 'p1', title: 'Ella sunrise', status: 'SCHEDULED', scheduledAt: at.toISOString(), createdAt: '', updatedAt: '', version: 0,
      targets: [{ id: 't1', platform: 'YOUTUBE', accountName: 'Kani Travels', status: 'SCHEDULED', settings: {}, progress: 0, attempts: 0 }],
    }
    const undated = { ...scheduled, id: 'p2', title: 'Idea: tea country', status: 'DRAFT', scheduledAt: undefined }
    mock(calls, { calendar: [scheduled], posts: [undated] })
    renderAt(<CalendarPage />)
    const entry = await screen.findByTitle('Ella sunrise (drag to move)')
    expect(await screen.findByTitle('Idea: tea country (drag to move)')).toBeInTheDocument()

    // Drop onto the cell for the day after today (always in the 6-week grid unless today is the grid's last day).
    const tomorrow = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1)
    const label = tomorrow.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' })
    // Tomorrow is in this month's six-week grid unless today is the grid's last day; then it is in next month's.
    if (!screen.queryByRole('button', { name: label })) await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    const cell = (await screen.findByRole('button', { name: label })).parentElement!
    const data = new Map<string, string>()
    const dataTransfer = { setData: (k: string, v: string) => data.set(k, v), getData: (k: string) => data.get(k) ?? '', get types() { return [...data.keys()] }, effectAllowed: '' }
    const { fireEvent } = await import('@testing-library/react')
    fireEvent.dragStart(entry, { dataTransfer })
    fireEvent.dragOver(cell, { dataTransfer })
    fireEvent.drop(cell, { dataTransfer })
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/publish/posts/p1/reschedule'))).toBe(true))
    const body = calls.find((c) => c.url.endsWith('/reschedule'))!.body as { scheduledAt: string }
    const moved = new Date(body.scheduledAt)
    expect([moved.getDate(), moved.getHours(), moved.getMinutes()]).toEqual([tomorrow.getDate(), 23, 50])
  })

  it('opens a post with its accounts and actions', async () => {
    const today = new Date()
    const published = {
      id: 'p3', title: 'Galle fort', status: 'PARTIAL', publishedAt: new Date(today.getFullYear(), today.getMonth(), today.getDate(), 10).toISOString(),
      createdAt: '', updatedAt: '', version: 0,
      targets: [
        { id: 't1', accountId: 'a-yt', platform: 'YOUTUBE', accountName: 'Kani Travels', status: 'PUBLISHED', settings: {}, progress: 1, attempts: 0, remoteUrl: 'https://www.youtube.com/watch?v=x', warning: 'YouTube kept this video private.' },
        { id: 't2', accountId: 'a-tt', platform: 'TIKTOK', accountName: 'kani.tt', status: 'FAILED', settings: {}, progress: 0, attempts: 1, errorMessage: 'TikTok refused it.' },
      ],
    }
    mock([], { calendar: [published] })
    renderAt(<CalendarPage />)
    await userEvent.click(await screen.findByTitle('Galle fort'))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Partly published')).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: /Open on YouTube/ })).toHaveAttribute('href', 'https://www.youtube.com/watch?v=x')
    expect(within(dialog).getByText('YouTube kept this video private.')).toBeInTheDocument()
    expect(within(dialog).getByText('TikTok refused it.')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: /Try again/ })).toBeInTheDocument()
    expect(within(dialog).queryByRole('link', { name: /Edit/ })).not.toBeInTheDocument()
  })
})
