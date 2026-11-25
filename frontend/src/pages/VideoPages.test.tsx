import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { characters, speechSeconds, type VideoPlan } from '../lib/videos'
import { appearTimes, ease } from '../remotion/sceneTiming'
import { useToasts } from '../stores/ui'
import { VideoPage } from './VideoPage'
import { VideosPage } from './VideosPage'

vi.mock('@remotion/player', () => ({ Player: () => <div data-testid="player" /> }))

type Call = { url: string; method: string; body?: any }
function mockApi(routes: [string, unknown, number?][]) {
  const calls: Call[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
    for (const [k, v, status] of routes) {
      const [m, path] = k.includes(' ') ? k.split(' ') : ['GET', k]
      if (m === method && url.split('?')[0].endsWith(path)) return new Response(JSON.stringify(v), { status: status ?? 200 })
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
          <Route path="/projects/:id/videos" element={<VideosPage />} />
          <Route path="/videos/:resourceId" element={<VideoPage />} />
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
const overview = (running = false) => ({
  topicsApproved: true, canEdit: true, rendererAvailable: true,
  topics: [
    { topicId: 't5', title: '1NF to 3NF', depth: 1, approvedDecks: ['BEGINNER'], mcqApproved: true, seoApproved: true,
      videos: [{ kind: 'QUIZ', resourceId: 'v1', versionNo: 2, status: 'DRAFT', renderStatus: 'CHECKED', running }] },
    { topicId: 't6', title: 'BCNF', depth: 1, approvedDecks: [], mcqApproved: true, seoApproved: false, videos: [] },
  ],
})

describe('the videos page', () => {
  it('offers each kind only once its sources are approved, and plans a quiz video', async () => {
    const calls = mockApi([
      ['POST /projects/p1/videos', { resourceId: 'v9' }],
      ['/projects/p1/videos', overview()],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/videos')
    expect(await screen.findByRole('link', { name: /Quiz video, version 2/ })).toHaveAttribute('href', '/videos/v1')
    expect(screen.getByText('rendered and checked')).toBeInTheDocument()
    const lesson = screen.getAllByRole('button', { name: 'Lesson video' })
    const quiz = screen.getAllByRole('button', { name: 'Quiz video' })
    expect(lesson[1]).toBeDisabled()
    expect(quiz[1]).toBeDisabled()
    await user.click(quiz[0])
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getByLabelText('Shape')).toHaveValue('9:16')
    expect(dialog.getByText(/The current draft will be replaced/)).toBeInTheDocument()
    await user.clear(dialog.getByLabelText('Countdown (seconds)'))
    await user.type(dialog.getByLabelText('Countdown (seconds)'), '8')
    await user.selectOptions(dialog.getByLabelText(/Narration language/), 'TA')
    await user.click(dialog.getByRole('button', { name: 'Plan the video' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')).toBeTruthy())
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ kind: 'QUIZ', topicId: 't5', language: 'TA', aspect: '9:16', countdown: 8, questions: 10 })
  })
})

const plan = (over: Partial<VideoPlan> = {}): VideoPlan => ({
  kind: 'QUIZ', topicId: 't5', topicTitle: '1NF to 3NF', title: '1NF to 3NF: quiz', language: 'EN', style: 'INFOGRAPHIC', aspect: '9:16',
  voice: { accountId: 'acc1', voiceId: 'ta-IN-Wavenet-A', label: 'ta-IN-Wavenet-A, WaveNet (Google Cloud TTS)' }, quiz: { countdown: 5 },
  scenes: [
    { id: 'quiz-intro', kind: 'QUIZ_INTRO', title: 'Quiz', lines: ['2 questions'], narration: 'Let’s test what you know.' },
    { id: 'q1', kind: 'QUESTION', title: 'Question 1', question: { stem: 'Which form removes repeating groups?', options: ['1NF', '2NF', '3NF', 'BCNF'], answer: 0 },
      narration: 'Question 1. Which form removes repeating groups?', answerNarration: 'The answer is A: 1NF.' },
  ],
  narration: { mediaId: 'm1', scriptHash: 'h', segments: [], characters: 90, voiceLabel: 'Wavenet A' },
  captions: true,
  ...over,
})
const detail = (status: string, content: VideoPlan, checks: object[] = []) => ({
  version: { id: 'vv2', resourceId: 'v1', versionNo: 2, status, origin: 'MANUAL', contentHash: 'h', createdById: 'u1', createdBy: 'Kani', createdAt: '2026-11-24T00:00:00Z', updatedAt: '2026-11-24T00:00:00Z' },
  content, checks, latest: true,
  you: { canEdit: true, canReview: true, isAuthor: true, mayDecide: status === 'IN_REVIEW', reviewerMustDiffer: true },
})
const state = (d: ReturnType<typeof detail>) => ({ kind: 'QUIZ_VIDEO', resource: { id: 'v1', projectId: 'p1', title: 'quiz video', topicId: 't5' }, versions: [d.version], latest: d, approvedVersionId: null, canEdit: true, canReview: true })
const accounts: [string, unknown][] = [
  ['/voice/voices', [{ id: 'ta-IN-Wavenet-A', name: 'ta-IN-Wavenet-A', category: 'WaveNet', language: 'ta-IN', gender: 'female', description: 'US$4 per million characters; 4 million free a month', cloned: false },
    { id: 'ta-IN-Wavenet-B', name: 'ta-IN-Wavenet-B', category: 'WaveNet', language: 'ta-IN', gender: 'male', cloned: false }]],
  ['/ai/accounts', [{ id: 'acc1', name: 'Google', providerName: 'Google Cloud Text-to-Speech', provider: 'GOOGLE_CLOUD_TTS', capabilities: ['VOICE'], status: 'ACTIVE', isDefault: true }]],
]
const passed = {
  renderJobId: 'r1', versionId: 'vv2', versionNo: 2, forThisContent: true, status: 'COMPLETED', progress: 1, createdAt: '2026-11-24T00:00:00Z',
  validation: { ok: true, problems: [], found: { width: 1080, height: 1920, durationSeconds: 50.1, fps: 30, audioCodec: 'aac' } },
  output: { id: 'out', kind: 'VIDEO', url: '/api/v1/media/out/content' },
}

describe('a video', () => {
  it('shows the narration, the preview and a checked render', async () => {
    const d = detail('DRAFT', plan())
    mockApi([
      ['/videos/v1/renders', [passed, { ...passed, renderJobId: 'r0', forThisContent: false, status: 'FAILED', validation: null, output: null, error: 'The file failed its checks: The sound is silent' }]],
      ['/videos/v1/preview', { document: { version: 1, width: 1080, height: 1920, fps: 30, background: '#000', tracks: [] }, durationSeconds: 50, media: {} }],
      ['/projects/p1/videos', overview()],
      ['/reviews/resources/v1/events', []],
      ['/reviews/resources/v1', state(d)],
      ...accounts,
    ])
    show('/videos/v1')
    expect(await screen.findByText(/Made with Wavenet A/)).toBeInTheDocument()
    expect(await screen.findByTestId('player')).toBeInTheDocument()
    expect(await screen.findByText(/Checked: 1080×1920, 0:50, 30 fps, sound/)).toBeInTheDocument()
    expect(screen.getByText(/The sound is silent/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open the MP4/ })).toHaveAttribute('href', '/api/v1/media/out/content')
    expect(screen.getByText('A. 1NF (answer)')).toBeInTheDocument()
    expect(await screen.findByText(/US\$4 per million characters/)).toBeInTheDocument()
  })

  it('changing the voice or words asks for a new narration before rendering', async () => {
    const d = detail('DRAFT', plan())
    const calls = mockApi([
      ['PUT /reviews/versions/vv2', detail('DRAFT', plan({ narration: null }))],
      ['/videos/v1/renders', []],
      ['/videos/v1/preview', { document: { version: 1, width: 1080, height: 1920, fps: 30, background: '#000', tracks: [] }, durationSeconds: 50, media: {} }],
      ['/projects/p1/videos', overview()],
      ['/reviews/resources/v1/events', []],
      ['/reviews/resources/v1', state(d)],
      ...accounts,
    ])
    const user = userEvent.setup()
    show('/videos/v1')
    await screen.findByRole('option', { name: /ta-IN-Wavenet-B/ })
    await user.selectOptions(screen.getByLabelText('Voice'), 'ta-IN-Wavenet-B')
    await user.type(screen.getByLabelText('After the answer is shown'), ' Atomic values only.')
    expect(screen.getByRole('button', { name: 'Render' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Make the narration again/ })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy())
    const saved = calls.find((c) => c.method === 'PUT')!.body.content as VideoPlan
    expect(saved.voice).toMatchObject({ accountId: 'acc1', voiceId: 'ta-IN-Wavenet-B' })
    expect(saved.scenes[1].answerNarration).toBe('The answer is A: 1NF. Atomic values only.')
  })

  it('starts narration and rendering for the version shown', async () => {
    const d = detail('DRAFT', plan())
    const calls = mockApi([
      ['POST /videos/v1/narration', { id: 'j1', status: 'QUEUED' }],
      ['POST /videos/v1/renders', { ...passed, status: 'QUEUED', validation: null, output: null }],
      ['/videos/v1/renders', []],
      ['/videos/v1/preview', { document: { version: 1, width: 1080, height: 1920, fps: 30, background: '#000', tracks: [] }, durationSeconds: 50, media: {} }],
      ['/projects/p1/videos', overview()],
      ['/reviews/resources/v1/events', []],
      ['/reviews/resources/v1', state(d)],
      ...accounts,
    ])
    const user = userEvent.setup()
    show('/videos/v1')
    await user.click(await screen.findByRole('button', { name: /Make the narration again/ }))
    await waitFor(() => expect(calls.find((c) => c.url.endsWith('/narration'))?.body).toEqual({ versionId: 'vv2' }))
    await user.selectOptions(screen.getByLabelText('Size'), '720')
    await user.click(screen.getByRole('button', { name: 'Render' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'POST' && c.url.endsWith('/renders'))?.body).toEqual({ versionId: 'vv2', resolution: 720 }))
  })

  it('in review, narration is read-only, nothing can be edited by the reviewer, and an unrendered version can’t be approved', async () => {
    const d = detail('IN_REVIEW', plan(), [{ code: 'rendered', ok: false, blocking: true, message: 'Render this version. It is checked with ffprobe before it counts.' }])
    mockApi([
      ['/videos/v1/renders', []],
      ['/videos/v1/preview', { document: { version: 1, width: 1080, height: 1920, fps: 30, background: '#000', tracks: [] }, durationSeconds: 50, media: {} }],
      ['/projects/p1/videos', overview()],
      ['/reviews/resources/v1/events', []],
      ['/reviews/resources/v1', state(d)],
      ...accounts,
    ])
    show('/videos/v1')
    expect(await screen.findByText(/Render this version. It is checked with ffprobe/)).toBeInTheDocument()
    expect(screen.queryByLabelText('After the answer is shown')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Edit as reviewer|Edit before approving/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled()
    expect(screen.getByText('Then: The answer is A: 1NF.')).toBeInTheDocument()
  })
})

describe('video helpers', () => {
  it('count what is spoken and time the build-up of a slide', () => {
    expect(characters(plan())).toBe('Let’s test what you know.'.length + 'Question 1. Which form removes repeating groups?'.length + 'The answer is A: 1NF.'.length)
    expect(speechSeconds(plan())).toBe(Math.round(characters(plan()) / 14))
    appearTimes(3, 1, 3).forEach((x, i) => expect(x).toBeCloseTo([1, 1.7, 2.4][i]))
    expect(appearTimes(0, 1, 3)).toEqual([])
    expect(ease(0, 1, 1)).toBe(0)
    expect(ease(5, 1, 1)).toBe(1)
  })
})
