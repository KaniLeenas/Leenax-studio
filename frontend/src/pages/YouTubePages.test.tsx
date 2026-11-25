import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { isPublicTarget } from '../lib/publish'
import {
  authorisationSummary,
  dailyViews,
  daysBefore,
  finalDescription,
  publishProblems,
  quotaEstimate,
  trafficRows,
  yesterday,
  type Dashboard,
  type Prepared,
  type PublishForm,
  type YouTubeChannel,
} from '../lib/youtube'
import { useToasts } from '../stores/ui'
import { YouTubeAnalyticsPage } from './YouTubeAnalyticsPage'
import { YouTubePublishPage } from './YouTubePublishPage'

type Call = { url: string; method: string; body?: any }
function mockApi(routes: [string, unknown, number?][]) {
  const calls: Call[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
    for (const [k, v, status] of routes) {
      const [m, path] = k.includes(' ') ? k.split(' ') : ['GET', k]
      if (m === method && url.split('?')[0].endsWith(path)) return new Response(JSON.stringify(typeof v === 'function' ? v() : v), { status: status ?? 200 })
    }
    return new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'not mocked ' + method + ' ' + url }), { status: 404 })
  })
  return calls
}

function show(path: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/projects/:id/youtube" element={<YouTubePublishPage />} />
          <Route path="/projects/:id/youtube/analytics" element={<YouTubeAnalyticsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) { this.removeAttribute('open') }
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  useToasts.setState({ toasts: [] })
})

const project = { id: 'p1', name: 'Circuits', kind: 'EDUCATION', status: 'IN_PROGRESS', myRole: 'OWNER' }
const channel: YouTubeChannel = { id: 'a1', name: 'Leenax Lessons', status: 'CONNECTED', canManage: true, canReadAnalytics: true }
const units = { upload: 1600, thumbnail: 50, playlistItem: 50, caption: 400, dailyDefault: 10000 }
const overview = (canAuthorise = true, extra: object = {}) => ({
  canAuthorise,
  channels: [channel, { id: 'a2', name: 'Old channel', status: 'CONNECTED', canManage: false, canReadAnalytics: false }],
  videos: [{ resourceId: 'r1', kind: 'LESSON', level: 'BEGINNER', topicId: 't1', title: "Ohm's law: beginner lesson video", versionId: 'v3', versionNo: 3,
    mediaId: 'm1', seoApproved: true, captionsAvailable: true, publicationIds: [] }],
  publications: [{ id: 'pub1', resourceId: 'r0', versionId: 'v0', topicId: 't0', accountId: 'a1', title: 'Kirchhoff explained', createdAt: '2026-11-01T00:00:00Z',
    status: 'PUBLISHED', videoId: 'vidK', url: 'https://www.youtube.com/watch?v=vidK', publishedAt: '2026-11-02T00:00:00Z', settings: { privacy: 'public' },
    authorisedAt: '2026-11-01T00:00:00Z', authorisedByName: 'Kani', statement: 'Authorised posting “Kirchhoff explained” to YouTube (Leenax Lessons), publicly.',
    rightsConfirmed: true, publicConfirmed: true }],
  quotaUnits: units,
  ...extra,
})
const prepared: Prepared = {
  resourceId: 'r1', versionId: 'v3', versionNo: 3, mediaId: 'm1', topicId: 't1', videoTitle: "Ohm's law", seoVersionId: 's1',
  titles: [{ id: 'tA', text: "Ohm's law in 10 minutes", chosen: false }, { id: 'tB', text: "Ohm's Law Explained for Beginners", chosen: true }],
  description: 'Learn V = IR with worked examples.', tags: ['ohms law', 'circuits'], hashtags: ['physics', '#ohm'], playlistIdeas: ['Circuits basics'],
  captions: { language: 'en', name: 'English', cues: 42, srt: '1\n00:00:00,500 --> 00:00:02,000\nHello\n\n' }, language: 'EN', problems: [],
}
const details = { channelId: 'UC1', title: 'Leenax Lessons', customUrl: '@leenax', subscribers: 1200, videos: 14, views: 30000, subscribersHidden: false,
  playlists: [{ id: 'PLcircuits01', title: 'Circuits', privacy: 'public', items: 6 }] }

// ---- rules -------------------------------------------------------------------------------------------

describe('publishing rules', () => {
  const form = (f: Partial<PublishForm> = {}): PublishForm => ({ accountId: 'a1', titleId: 'tB', visibility: 'unlisted', when: 'NOW', playlistIds: [],
    addCaptions: false, madeForKids: false, alteredOrSynthetic: false, rightsConfirmed: true, confirmPublic: false, ...f })
  it('lists what still stops the authorisation', () => {
    expect(publishProblems(form(), prepared, channel)).toEqual([])
    expect(publishProblems(form({ rightsConfirmed: false }), prepared, channel)).toContain('Confirm that you hold the rights to everything in the video')
    expect(publishProblems(form({ visibility: 'public' }), prepared, channel)).toContain('Confirm that the video will be public')
    expect(publishProblems(form({ visibility: 'public', confirmPublic: true }), prepared, channel)).toEqual([])
    expect(publishProblems(form({ madeForKids: undefined }), prepared, channel)).toContain('Say whether the video is made for kids')
    expect(publishProblems(form({ playlistIds: ['PL1'] }), prepared, { ...channel, canManage: false })).toContain('Reconnect Leenax Lessons to add playlists and captions')
    expect(publishProblems(form(), { ...prepared, problems: ['The approved tags are too long'] }, channel)).toContain('The approved tags are too long')
  })
  it('needs a scheduled video to go public an hour after its upload', () => {
    const now = new Date('2026-11-24T10:00:00Z')
    const soon = publishProblems(form({ visibility: 'scheduled', confirmPublic: true, publishAt: '2026-11-24T10:30:00Z' }), prepared, channel, now)
    expect(soon).toContain('Make it public at least an hour after the upload starts')
    expect(publishProblems(form({ visibility: 'scheduled', confirmPublic: true, publishAt: '2026-11-25T10:00:00Z' }), prepared, channel, now)).toEqual([])
    expect(publishProblems(form({ when: 'SCHEDULE' }), prepared, channel, now)).toContain('Choose a future upload time')
  })
  it('adds the hashtags only when asked, and counts quota units', () => {
    expect(finalDescription(prepared, false)).toBe(prepared.description)
    expect(finalDescription(prepared, true)).toBe('Learn V = IR with worked examples.\n\n#physics #ohm')
    expect(quotaEstimate(units, { thumbnail: true, playlists: 2, captions: true })).toBe(1600 + 50 + 100 + 400)
    expect(authorisationSummary('T', 'Leenax', form())).toBe('Upload “T” to Leenax as unlisted, now.')
  })
  it('knows what is public on each platform', () => {
    expect(isPublicTarget('YOUTUBE', { privacy: 'unlisted' })).toBe(false)
    expect(isPublicTarget('YOUTUBE', { privacy: 'private', publishAt: '2027-01-01T00:00:00Z' })).toBe(true)
    expect(isPublicTarget('TIKTOK', { privacy: 'SELF_ONLY' })).toBe(false)
    expect(isPublicTarget('INSTAGRAM', {})).toBe(true)
  })
  it('shapes analytics for charts', () => {
    expect(dailyViews([{ day: '2026-11-02', views: 5 }, { day: '2026-11-01', views: 3 }])).toEqual([
      { date: '2026-11-01', values: { views: 3 } }, { date: '2026-11-02', values: { views: 5 } }])
    const t = trafficRows({ EXT_URL: 25, YT_SEARCH: 75 })
    expect(t[0]).toEqual({ key: 'YT_SEARCH', label: 'YouTube search', views: 75, share: 0.75 })
    expect(daysBefore('2026-03-01', 1)).toBe('2026-02-28')
    expect(yesterday(new Date('2026-11-24T01:00:00Z'))).toBe('2026-11-23')
  })
})

// ---- the publishing page ------------------------------------------------------------------------------

describe('the YouTube publishing page', () => {
  it('authorises exactly what is shown, with the rights and public confirmations', async () => {
    const calls = mockApi([
      ['POST /projects/p1/youtube/publications', { publicationId: 'pub9', post: { id: 'post9', targets: [] } }],
      ['/projects/p1/youtube/videos/r1/prepare', prepared],
      ['/projects/p1/youtube/channels/a1', details],
      ['/projects/p1/youtube', overview()],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/youtube')
    await screen.findByText("Ohm's law: beginner lesson video")
    expect(screen.getByText(/Connected before playlists and captions and analytics were supported/)).toBeTruthy()
    expect(screen.getByText(/Authorised by Kani/)).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Publish' }))
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText("Ohm's Law Explained for Beginners")
    // the report's chosen title is picked; the description and tags are shown as approved
    expect((within(dialog).getByRole('radio', { name: /Explained for Beginners/ }) as HTMLInputElement).checked).toBe(true)
    expect(within(dialog).getByText('Learn V = IR with worked examples.')).toBeTruthy()
    const go = within(dialog).getByRole('button', { name: 'Authorise and publish' }) as HTMLButtonElement
    expect(go.disabled).toBe(true)

    await user.click(within(dialog).getByRole('radio', { name: /^Public/ }))
    await within(dialog).findByText('Circuits')
    await user.click(within(dialog).getByRole('checkbox', { name: /Circuits/ }))
    const [kidsNo, synthNo] = within(dialog).getAllByRole('radio', { name: 'No' })
    await user.click(kidsNo)
    await user.click(synthNo)
    await user.click(within(dialog).getByRole('checkbox', { name: /I hold the rights/ }))
    expect(go.disabled).toBe(true)   // public still needs its own confirmation
    expect(within(dialog).getByText('· Confirm that the video will be public')).toBeTruthy()
    await user.click(within(dialog).getByRole('checkbox', { name: /anyone will be able to find and watch/ }))
    expect(within(dialog).getByText(/Upload “Ohm's Law Explained for Beginners” to Leenax Lessons as public, now\./)).toBeTruthy()
    await waitFor(() => expect(go.disabled).toBe(false))
    await user.click(go)
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    const body = calls.find((c) => c.method === 'POST')!.body
    expect(body).toMatchObject({ resourceId: 'r1', versionId: 'v3', accountId: 'a1', titleId: 'tB', visibility: 'public', when: 'NOW',
      playlistIds: ['PLcircuits01'], addCaptions: true, addHashtags: false, madeForKids: false, alteredOrSynthetic: false,
      rightsConfirmed: true, confirmPublic: true, categoryId: '27' })
  })

  it('lets only the owner or an administrator authorise', async () => {
    mockApi([['/projects/p1/youtube', overview(false)], ['/projects/p1', { ...project, myRole: 'EDITOR' }]])
    show('/projects/p1/youtube')
    await screen.findByText("Ohm's law: beginner lesson video")
    expect(screen.queryByRole('button', { name: 'Publish' })).toBeNull()
    expect(screen.getByText(/Only the project owner or an administrator can authorise YouTube publishing/)).toBeTruthy()
  })
})

// ---- the analytics page ----------------------------------------------------------------------------------

const dashboard = (fetched = true): Dashboard => ({
  accountId: 'a1', from: '2026-10-27', to: '2026-11-23', fetchedAt: fetched ? '2026-11-24T08:00:00Z' : null, fetchedByName: 'Kani',
  canRefresh: true, canManageReach: true,
  reach: { started: true, lastDay: '2026-11-22', lastCheckedAt: '2026-11-24T14:20:00Z' },
  totals: { views: 1500, minutesWatched: 4200, averageViewDuration: 168, subscribersGained: 12, likes: 80, comments: 9, shares: 4, impressions: 20000, ctr: 0.045 },
  videos: [{ videoId: 'vidK', url: 'https://www.youtube.com/watch?v=vidK', title: 'Kirchhoff explained', topicTitle: 'Circuit laws', views: 1500, minutesWatched: 4200,
    averageViewDuration: 168, averageViewPercentage: 46, impressions: 20000, ctr: 0.045, likes: 80, comments: 9, shares: 4, subscribersGained: 12, durationSeconds: 360 }],
  daily: [{ day: '2026-11-22', views: 60 }, { day: '2026-11-23', views: 75 }],
  traffic: { YT_SEARCH: 900, EXT_URL: 600 },
  searchTerms: [{ insightTrafficSourceDetail: 'kirchhoff voltage law', views: 300 }],
  retention: { vidK: [{ ratio: 0, audienceWatchRatio: 1 }, { ratio: 0.5, audienceWatchRatio: 0.55, relativeRetentionPerformance: 0.6 }, { ratio: 1, audienceWatchRatio: 0.3 }] },
  scenes: { vidK: [{ start: 0, end: 120, title: 'Intro' }, { start: 120, end: 360, title: 'Worked example' }] },
  recommendations: [{ kind: 'DROP_OFF', title: 'A drop-off in “Kirchhoff explained”', detail: 'From 2:30 to 2:45, in the scene “Worked example”.',
    rule: 'The largest fall in the audience over 5% of the video.', videoId: 'vidK',
    evidence: [{ metric: 'Audience watch ratio', value: '0.70 → 0.55', source: 'YouTube Analytics API (reports.query)' }] }],
  history: [{ accountId: 'a1', startDate: '2026-10-27', endDate: '2026-11-23', fetchedAt: '2026-11-24T08:00:00Z', createdByName: 'Kani', parts: 5 }],
  notes: ["YouTube's numbers for the last two or three days can still change."],
})

describe('the YouTube analytics page', () => {
  it('shows a snapshot with its sources, and refreshes it from YouTube', async () => {
    let taken = false
    const calls = mockApi([
      ['POST /projects/p1/youtube/analytics/refresh', () => { taken = true; return dashboard() }],
      ['/projects/p1/youtube/analytics', () => dashboard(taken)],
      ['/projects/p1/youtube', overview()],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/youtube/analytics')
    await screen.findByText(/No snapshot of 2026-10-27 to 2026-11-23 yet/)
    expect(screen.queryByText('Recommendations')).toBeNull()   // no numbers, no advice
    await user.click(screen.getByRole('button', { name: 'Refresh from YouTube' }))
    await screen.findByText('A drop-off in “Kirchhoff explained”')
    const refreshCall = calls.find((c) => c.method === 'POST')!
    expect(refreshCall.url).toContain('accountId=a1')
    expect(refreshCall.url).toMatch(/from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}/)
    expect(screen.getAllByText('4.5%').length).toBe(2)   // the tile and the video's row
    expect(screen.getByText(/Rule: The largest fall/)).toBeTruthy()
    expect(screen.getByText(/\(YouTube Analytics API \(reports\.query\)\)/)).toBeTruthy()
    expect(screen.getByRole('img', { name: 'Audience retention of Kirchhoff explained' })).toBeTruthy()
    expect(screen.getByText('kirchhoff voltage law')).toBeTruthy()
    expect(screen.getByText(/Other channels' analytics are private/)).toBeTruthy()
  })

  it('filters by date and offers reach reports to the owner', async () => {
    const calls = mockApi([
      ['POST /projects/p1/youtube/analytics/reach', { started: true }],
      ['/projects/p1/youtube/analytics', { ...dashboard(false), reach: { started: false } }],
      ['/projects/p1/youtube', overview()],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/youtube/analytics')
    await screen.findByText(/have to be turned on for the channel first/)
    await user.click(screen.getByRole('button', { name: '7 days' }))
    await waitFor(() => expect(calls.some((c) => c.url.includes(`from=${daysBefore(yesterday(), 6)}`))).toBe(true))
    await user.click(screen.getByRole('button', { name: 'Turn on reach reports' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.includes('/analytics/reach?accountId=a1'))).toBe(true))
  })
})
