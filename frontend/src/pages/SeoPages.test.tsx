import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { metricLabel, metricText, parseTime, secondsText, tagChars, titleWarnings } from '../lib/seo'
import { useToasts } from '../stores/ui'
import { SeoPage } from './SeoPage'
import { SeoReportPage } from './SeoReportPage'

type Call = { url: string; method: string; body?: any }
function mockApi(routes: [string, unknown][]) {
  const calls: Call[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
    for (const [k, v] of routes) {
      const [m, path] = k.includes(' ') ? k.split(' ') : ['GET', k]
      if (m === method && url.split('?')[0].endsWith(path)) return new Response(JSON.stringify(v))
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
          <Route path="/projects/:id/seo" element={<SeoPage />} />
          <Route path="/seo/:resourceId" element={<SeoReportPage />} />
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

const project = { id: 'p1', name: 'Database Systems', kind: 'EDUCATION', status: 'IN_PROGRESS', myRole: 'OWNER' }
const accounts: [string, unknown][] = [
  ['/ai/accounts/acc1/models', ['gpt-x']],
  ['/ai/accounts', [{ id: 'acc1', name: 'Mine', providerName: 'OpenAI', capabilities: ['TEXT'], status: 'ACTIVE', isDefault: true }]],
]
const overview = (over: object = {}) => ({
  topicsApproved: true, canEdit: true,
  budget: { dailySearches: 100, usedToday: 97, left: 3, unitsToday: 9700, cacheDays: 7, resetsAt: '2026-11-25T08:00:00Z', quotaDay: '2026-11-24' },
  trends: [{ id: 'tr1', filename: 'multiTimeline.csv', geo: 'Sri Lanka', period: 'WEEK', firstDate: '2025-11-23', lastDate: '2026-11-22', terms: ['normalization'], status: 'ACTIVE', createdAt: '2026-11-24T00:00:00Z' }],
  topics: [
    { topicId: 't5', title: '1NF to 3NF', depth: 1, hasNotes: true, running: false, reportId: 's1', reportVersionNo: 1, reportStatus: 'DRAFT', chosenTitle: 'Third normal form explained', researchId: 'r1', researchStatus: 'PARTIAL', researchedAt: '2026-11-24T00:00:00Z' },
    { topicId: 't6', title: 'BCNF', depth: 1, hasNotes: false, running: false },
  ],
  youtube: [{ id: 'yt1', name: 'Leenax Lessons', status: 'CONNECTED' }],
  ...over,
})

describe('the SEO page', () => {
  it('shows the day’s search budget and won’t ask for more than is left', async () => {
    const calls = mockApi([
      ['POST /projects/p1/seo/research', { id: 'j1', status: 'QUEUED' }],
      ['/projects/p1/seo', overview()],
      ['/projects/p1', project],
      ...accounts,
    ])
    const user = userEvent.setup()
    show('/projects/p1/seo')
    expect((await screen.findByText(/of 100 YouTube searches left today/)).parentElement).toHaveTextContent('3 of 100 YouTube searches left today')
    expect(screen.getByText(/, partial/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Research' }))
    const dialog = within(screen.getByRole('dialog'))
    await screen.findByRole('option', { name: 'gpt-x' })
    const searches = dialog.getByLabelText('YouTube searches')
    expect(searches).toHaveValue(3)
    await user.clear(searches)
    await user.type(searches, '5')
    expect(dialog.getByText('Only 3 left today')).toBeInTheDocument()
    expect(dialog.getByRole('button', { name: 'Research' })).toBeDisabled()
    await user.clear(searches)
    await user.type(searches, '2')
    await user.click(dialog.getByLabelText('Tamil'))
    await user.click(dialog.getByRole('button', { name: 'Research' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      topicId: 't6', youtubeAccountId: 'yt1', accountId: 'acc1', model: 'gpt-x', languages: ['en'], region: 'LK', searches: 2, trendsIds: ['tr1'],
    }))
  })

  it('says when no YouTube account is connected, and allows research from the cache only', async () => {
    mockApi([['/projects/p1/seo', overview({ youtube: [] })], ['/projects/p1', project], ...accounts])
    const user = userEvent.setup()
    show('/projects/p1/seo')
    await user.click(await screen.findByRole('button', { name: 'Research' }))
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getByText(/No YouTube account is connected/)).toBeInTheDocument()
    await screen.findByRole('option', { name: 'gpt-x' })
    expect(dialog.getByRole('button', { name: 'Research' })).toBeDisabled()
    await user.clear(dialog.getByLabelText('YouTube searches'))
    await user.type(dialog.getByLabelText('YouTube searches'), '0')
    expect(dialog.getByRole('button', { name: 'Research' })).toBeEnabled()
  })
})

const est = (value: number, unit = '0-1') => ({ value, unit, estimated: true, method: 'm' })
const report = () => ({
  topicId: 't5', topicTitle: '1NF to 3NF', researchId: 'r1', model: 'gpt-x',
  titles: Array.from({ length: 10 }, (_, i) => ({ id: `t${i + 1}`, text: `Third normal form explained ${i + 1}`, style: 'EDUCATIONAL', language: 'en',
    check: { warnings: [], length: { value: 30, unit: 'characters', source: 'Counted by Leenax' }, overall: est(4, '1-5'), ai: { clarity: est(4, '1-5') }, reasoning: 'Clear' } })),
  recommendedTitleId: 't1', recommendationReason: 'Clearest and keyword first', chosenTitleId: 't1',
  description: 'A lesson on 3NF.', primaryKeywords: ['third normal form'], secondaryKeywords: [], tags: ['3nf', 'dbms'], hashtags: ['#dbms'],
  chapters: [{ title: 'Intro', start: est(0, 'seconds') }, { title: '3NF', start: est(95, 'seconds') }, { title: 'Recap', start: est(400, 'seconds') }],
  playlists: [], nextVideos: [{ title: 'BCNF', why: 'The next topic' }], strategies: {},
})
const research = {
  id: 'r1', projectId: 'p1', topicId: 't5', status: 'PARTIAL', createdAt: '2026-11-24T00:00:00Z',
  data: {
    topicId: 't5', topicTitle: '1NF to 3NF', subtopics: ['BCNF'], languages: ['en'], region: 'LK', startedAt: '2026-11-24T00:00:00Z',
    keywords: [{ text: 'third normal form', language: 'en', kind: 'PRIMARY', searched: true, relevance: est(1), competition: est(0.44), gap: est(0.6), score: est(0.62), partial: true }],
    searches: [], coverage: [{ subtopic: 'BCNF', competitorsCovering: { value: 0, unit: 'of 1 competitor videos', source: 'Counted by Leenax' } }],
    competitors: [{ videoId: 'v1', url: 'https://www.youtube.com/watch?v=v1', title: '3NF explained', channel: 'DB Lab', covers: [],
      views: { value: 125000, unit: 'views', source: 'YouTube Data API videos.list (public numbers)', retrievedAt: '2026-11-23T10:00:00Z' },
      likes: { value: null, unit: 'likes', note: 'Hidden by the channel' } }],
    sources: [{ name: 'YouTube Data API search.list', detail: '1 keyword' }], limitations: ['No Google Trends file was used, so every opportunity score is partial (no demand signal).'],
  },
}
const version = (status: string, checks: object[] = []) => ({
  version: { id: 'v1', resourceId: 's1', versionNo: 1, status, origin: 'AI', contentHash: 'h', createdById: 'u1', createdBy: 'Kani', createdAt: '2026-11-24T00:00:00Z', updatedAt: '2026-11-24T00:00:00Z' },
  content: report(), checks, latest: true,
  you: { canEdit: true, canReview: true, isAuthor: status === 'DRAFT', mayDecide: status === 'IN_REVIEW', reviewerMustDiffer: true },
})
const state = (d: ReturnType<typeof version>) => ({ kind: 'SEO', resource: { id: 's1', projectId: 'p1', title: '1NF to 3NF: SEO report', topicId: 't5' }, versions: [d.version], latest: d, approvedVersionId: null, canEdit: true, canReview: true })

describe('the SEO report', () => {
  it('lets you choose and edit titles, with the rules checked as you type', async () => {
    const d = version('DRAFT')
    const calls = mockApi([
      ['PUT /reviews/versions/v1', d],
      ['/reviews/resources/s1/events', []],
      ['/reviews/resources/s1', state(d)],
      ['/seo/research/r1', research],
    ])
    const user = userEvent.setup()
    show('/seo/s1')
    expect(await screen.findByText(/Recommended: “Third normal form explained 1”/)).toBeInTheDocument()
    const titleInputs = screen.getAllByLabelText('Title')
    await user.clear(titleInputs[1])
    await user.type(titleInputs[1], 'SHOCKING 3NF secret')
    expect(screen.getByText(/Sounds like clickbait/)).toBeInTheDocument()
    await user.click(screen.getByLabelText('Use “Third normal form explained 3”'))
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy())
    const saved = calls.find((c) => c.method === 'PUT')!.body.content
    expect(saved.chosenTitleId).toBe('t3')
    expect(saved.titles[1].text).toBe('SHOCKING 3NF secret')
    expect(screen.getByText(/of 500 characters YouTube allows for tags/)).toHaveTextContent('8 of 500')
  })

  it('shows the research with every number’s source or estimate, and blocks approval on an unlabelled number', async () => {
    const d = version('IN_REVIEW', [{ code: 'labels', ok: false, blocking: true, message: 'Numbers without a source or an “estimated” label: $.searchVolume' }])
    mockApi([
      ['/reviews/resources/s1/events', []],
      ['/reviews/resources/s1', state(d)],
      ['/seo/research/r1', research],
    ])
    show('/seo/s1')
    expect(await screen.findByText('Numbers without a source or an “estimated” label: $.searchVolume')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled()
    expect(await screen.findByText('125,000 views')).toBeInTheDocument()
    expect(screen.getByText('· YouTube, 2026-11-23')).toBeInTheDocument()
    expect(screen.getByText('Hidden by the channel')).toBeInTheDocument()
    expect(screen.getByText('0.62')).toBeInTheDocument()
    expect(screen.getAllByText('est.').length).toBeGreaterThan(0)
    expect(screen.getByText('partial')).toBeInTheDocument()
    expect(screen.getByText(/No Google Trends file was used/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Excel' })).toHaveAttribute('href', '/api/v1/exports/versions/v1?format=xlsx')
    expect(screen.getByText('1:35')).toBeInTheDocument()
  })
})

describe('seo helpers', () => {
  it('formats numbers with their label', () => {
    expect(metricText({ value: 125000, unit: 'views', source: 's' })).toBe('125,000 views')
    expect(metricText({ value: 4, unit: '1-5', estimated: true })).toBe('4 of 5')
    expect(metricText({ value: null, note: 'Hidden by the channel' })).toBe('Hidden by the channel')
    expect(metricLabel({ value: 0.4, estimated: true })).toBe('estimated')
    expect(metricLabel({ value: 3, source: 'YouTube', retrievedAt: '2026-11-23T10:00:00Z' })).toBe('YouTube, 2026-11-23')
  })
  it('counts tags and times as YouTube does', () => {
    expect(tagChars(['3nf', 'normal form'])).toBe(3 + 1 + 11 + 2)
    expect(secondsText(95)).toBe('1:35')
    expect(secondsText(3725)).toBe('1:02:05')
    expect(parseTime('4:05')).toBe(245)
    expect(parseTime('x')).toBeNull()
    expect(titleWarnings('a'.repeat(101))[0]).toMatch(/Longer than YouTube allows/)
    expect(titleWarnings('Normal forms you won’t believe')).toEqual([])
    expect(titleWarnings("Normal forms you won't believe")).toEqual(['Sounds like clickbait'])
  })
})
