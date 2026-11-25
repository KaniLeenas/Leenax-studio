import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { resourceLink, taskLink, type DashTask } from '../lib/notifications'
import { nextId, planTotals, stepLink, waitingFor, type AutomationSettings, type Step, type TopicAnalysis } from '../lib/topicAnalysis'
import { useToasts } from '../stores/ui'
import { TopicAnalysisPage, TopicAnalysisProjectPage } from './TopicAnalysisPages'

type Call = { url: string; method: string; body?: any }
function mockApi(routes: [string, unknown][]) {
  const calls: Call[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
    for (const [k, v] of routes) {
      const [m, path] = k.includes(' ') ? k.split(' ') : ['GET', k]
      if (m === method && url.split('?')[0].endsWith(path)) return new Response(JSON.stringify(typeof v === 'function' ? v() : v))
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
          <Route path="/projects/:id/analysis" element={<TopicAnalysisProjectPage />} />
          <Route path="/topic-analysis/:resourceId" element={<TopicAnalysisPage />} />
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
  ['/ai/accounts', [{ id: 'acc1', name: 'My OpenAI', providerName: 'OpenAI', capabilities: ['TEXT'], status: 'ACTIVE', isDefault: true }]],
]
const off: AutomationSettings = { enabled: false, notesLevel: 'INTERMEDIATE', mcqCount: 20, videoMinutes: 8, autoNotes: true, autoMcq: true, autoSlides: true }
const on: AutomationSettings = { ...off, enabled: true, accountId: 'acc1', accountName: 'My OpenAI', model: 'gpt-x', updatedByName: 'Kani', updatedAt: '2026-11-25T00:00:00Z' }

const steps = (over: Partial<Record<string, Partial<Step>>> = {}): Step[] =>
  ([['NOTES', 'Intermediate notes'], ['MCQ', 'Questions'], ['QA', 'Academic QA report'], ['SEO', 'SEO report'], ['SLIDES', 'Presentation (the video\'s script)'], ['LESSON_VIDEO', 'Lesson video']] as const)
    .map(([kind, label]) => ({ kind, label, status: 'NONE', auto: false, ...over[kind] }) as Step)

const overview = (over: object = {}) => ({
  topicsApproved: true, topicsVersionNo: 2, settings: off, canEdit: true, canReview: true, counts: { APPROVED: 1, NONE: 1, DRAFT: 1 },
  topics: [
    { topicId: 't1', title: 'First normal form', path: 'Normalisation', resourceId: 'a1', status: 'APPROVED', versionNo: 1, approvedNo: 1, jobAuto: false,
      next: steps({ NOTES: { status: 'APPROVED', resourceId: 'n1' }, MCQ: { job: 'RUNNING', auto: true } }) },
    { topicId: 't2', title: 'Second normal form', path: 'Normalisation', resourceId: 'a2', status: 'DRAFT', versionNo: 1, jobAuto: false, next: [] },
    { topicId: 't3', title: 'Third normal form', path: 'Normalisation', status: 'NONE', job: 'FAILED', jobError: 'The AI account has no credit', jobAuto: true, next: [] },
  ],
  ...over,
})

describe('the project’s topic analysis', () => {
  it('lists each topic’s analysis and what has followed it', async () => {
    mockApi([['/projects/p1/topic-analysis', overview()], ['/projects/p1', project]])
    show('/projects/p1/analysis')
    expect(await screen.findByRole('heading', { name: 'Topic analysis' })).toBeInTheDocument()
    expect(screen.getByText('Automation is off')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Analysis, version 1\s*Approved/ })).toHaveAttribute('href', '/topic-analysis/a1')
    const follows = screen.getByRole('list', { name: 'What follows the analysis' })
    expect(within(follows).getByRole('link', { name: 'Intermediate notes' })).toHaveAttribute('href', '/notes/n1')
    expect(within(follows).getByText('Running (started automatically)')).toBeInTheDocument()
    expect(within(follows).getByText('Waits for: Academic QA report, SEO report')).toBeInTheDocument()
    expect(screen.getByText('Analysis failed: The AI account has no credit')).toBeInTheDocument()
    expect(screen.getAllByText(/Notes, questions and the video plan follow once the analysis is approved/)).toHaveLength(2)
  })

  it('turns automation on with an account and model, and says what it may cost', async () => {
    let saved = off
    const calls = mockApi([
      ['PUT /projects/p1/topic-analysis/settings', () => (saved = on)],
      ['/projects/p1/topic-analysis', () => overview({ settings: saved })],
      ['/projects/p1', project],
      ...accounts,
    ])
    const user = userEvent.setup()
    show('/projects/p1/analysis')
    await user.click(await screen.findByRole('button', { name: 'Automation settings' }))
    const dialog = await screen.findByRole('dialog', { name: 'Automation settings' })
    await user.click(within(dialog).getByRole('checkbox', { name: /Start each step automatically/ }))
    expect(await within(dialog).findByText(/up to 12 AI requests on this account/)).toBeInTheDocument()
    await within(dialog).findByRole('option', { name: 'gpt-x' })
    await user.click(within(dialog).getByRole('button', { name: 'Save settings' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy())
    const body = calls.find((c) => c.method === 'PUT')!.body
    expect(body).toMatchObject({ enabled: true, accountId: 'acc1', model: 'gpt-x', mcqCount: 20, videoMinutes: 8, autoSlides: true })
    expect(await screen.findByText('Automation is on')).toBeInTheDocument()
    expect(screen.getByText(/20 questions follow once the notes are approved/)).toBeInTheDocument()
  })

  it('starts the analysis of the topics without one', async () => {
    const calls = mockApi([
      ['POST /projects/p1/topic-analysis/run', { queued: 1, skipped: 0, notes: [] }],
      ['/projects/p1/topic-analysis', overview()],
      ['/projects/p1', project],
      ...accounts,
    ])
    const user = userEvent.setup()
    show('/projects/p1/analysis')
    await user.click(await screen.findByRole('button', { name: 'Analyse 1 topic' }))
    const dialog = await screen.findByRole('dialog', { name: 'Analyse 1 topic' })
    await within(dialog).findByRole('option', { name: 'gpt-x' })
    await user.type(within(dialog).getByLabelText(/Guidance/), 'Use banking examples')
    await user.click(within(dialog).getByRole('button', { name: 'Start the analysis' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')).toBeTruthy())
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ accountId: 'acc1', model: 'gpt-x', guidance: 'Use banking examples' })
  })

  it('waits for the topic list', async () => {
    mockApi([['/projects/p1/topic-analysis', overview({ topicsApproved: false, topics: [], counts: {} })], ['/projects/p1', project]])
    show('/projects/p1/analysis')
    expect(await screen.findByText('The topic list isn\'t approved yet')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Analyse/ })).toBeNull()
  })
})

const analysis = (): TopicAnalysis => ({
  topicId: 't1', topicTitle: 'First normal form', topicPath: 'Normalisation', topicsVersionId: 'tv2',
  summary: 'Atomic values in every cell.', difficulty: { level: 'BEGINNER', why: 'Builds on keys.' },
  prerequisites: [{ text: 'Primary keys', basis: 'GENERAL', sources: [] }],
  objectives: [
    { id: 'O1', text: 'Define first normal form', bloom: 'REMEMBER', outcomes: ['LO2'],
      sources: [{ documentId: 'd1', versionId: 'dv1', title: 'Week 3 slides', page: 4, quote: 'a single atomic value', found: true }] },
    { id: 'O2', text: 'Convert a table to 1NF', bloom: 'APPLY', sources: [] },
  ],
  concepts: [{ id: 'C1', term: 'Atomic value', definition: 'A value that is not split further.', basis: 'DOCUMENT',
    sources: [{ documentId: 'd1', versionId: 'dv1', title: 'Week 3 slides', page: 4, quote: 'invented words', found: false }] }],
  misconceptions: [{ number: 1, wrong: '1NF means no duplicate rows', right: '1NF means atomic values', concept: 'C1', basis: 'GENERAL' }],
  examples: [{ title: 'Student phone numbers', kind: 'WORKED', concepts: ['C1'], sources: [] }],
  examRelevance: [],
  mcqCount: 20,
  mcqPlan: [{ objective: 'O1', count: 8, difficulties: { BEGINNER: 8 }, styles: ['EXAM'] }, { objective: 'O2', count: 12, difficulties: {}, styles: ['SCENARIO'] }],
  videoMinutes: 8,
  videoPlan: { hook: 'Why does one cell hold three phone numbers?', scenes: [
    { title: 'The problem', goal: 'O1', shows: 'EXAMPLE', seconds: 180, concepts: ['C1'] },
    { title: 'Fixing it', goal: 'O2', shows: 'DIAGRAM', seconds: 300, concepts: [] },
  ] },
  gaps: [{ text: 'The slides don\'t cover repeating groups' }],
  questions: [],
  analysis: { model: 'gpt-x', madeAt: '2026-11-25T00:00:00Z', quotesKept: 2, quotesFound: 1, quotesRemoved: 1 },
})

const version = (status: string, checks: object[] = [], content: TopicAnalysis = analysis()) => ({
  version: { id: 'v1', resourceId: 'a1', versionNo: 1, status, origin: 'AI', contentHash: 'h', createdById: 'u9', createdBy: 'Leenax', createdAt: '2026-11-25T00:00:00Z', updatedAt: '2026-11-25T00:00:00Z' },
  content, checks, latest: true,
  you: { canEdit: true, canReview: true, isAuthor: status === 'DRAFT', mayDecide: status === 'IN_REVIEW', reviewerMustDiffer: true },
})
const state = (d: ReturnType<typeof version>, approved: string | null = null) => ({
  kind: 'TOPIC_ANALYSIS', resource: { id: 'a1', projectId: 'p1', title: 'First normal form: topic analysis', topicId: 't1' },
  versions: [d.version], latest: d, approvedVersionId: approved, canEdit: true, canReview: true,
})

describe('one topic analysis', () => {
  it('shows the plans and their sources, and edits the question plan in a draft', async () => {
    const d = version('DRAFT')
    const calls = mockApi([
      ['PUT /reviews/versions/v1', d],
      ['/reviews/resources/a1/events', []],
      ['/reviews/resources/a1', state(d)],
    ])
    const user = userEvent.setup()
    show('/topic-analysis/a1')
    expect(await screen.findByRole('heading', { name: 'First normal form: topic analysis' })).toBeInTheDocument()
    expect(screen.getByText(/1 of 2 cited quotes found word for word on their page; 1 citation of pages it wasn't given removed/)).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /Week 3 slides, page 4/ })[0]).toHaveAttribute('href', '/documents/d1?version=dv1&page=4')
    expect(screen.getByText('(not found word for word on that page)')).toBeInTheDocument()
    expect(screen.getByText('20 planned of 20')).toBeInTheDocument()
    expect(screen.getByText(/2 scenes, 8:00 in all; about 8:00 asked/)).toBeInTheDocument()

    const o1 = screen.getByLabelText('Questions for O1')
    await user.clear(o1)
    await user.type(o1, '5')
    expect(screen.getByText(/17 planned of 20: the plan must add up/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add an objective' }))
    expect(screen.getAllByLabelText('Objective id').map((x) => (x as HTMLInputElement).value)).toEqual(['O1', 'O2', 'O3'])
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy())
    const saved = calls.find((c) => c.method === 'PUT')!.body.content as TopicAnalysis
    expect(saved.mcqPlan[0].count).toBe(5)
    expect(saved.objectives).toHaveLength(3)
  })

  it('blocks approval when the plan doesn’t add up, as the checks say', async () => {
    const d = version('IN_REVIEW', [{ code: 'question-plan', ok: false, blocking: true, message: 'The question plan adds up to 17, not 20' }])
    mockApi([['/reviews/resources/a1/events', []], ['/reviews/resources/a1', state(d)]])
    show('/topic-analysis/a1')
    expect(await screen.findByText('The question plan adds up to 17, not 20')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled()
    expect(screen.getByText(/When this analysis is approved, the notes can be written/)).toBeInTheDocument()
  })

  it('shows what has followed an approved analysis', async () => {
    const d = version('APPROVED')
    mockApi([
      ['/topic-analysis/a1/next', { projectId: 'p1', topicId: 't1', topicTitle: 'First normal form', notesLevel: 'INTERMEDIATE', settings: on,
        steps: steps({ NOTES: { status: 'IN_REVIEW', resourceId: 'n1', auto: true } }) }],
      ['/reviews/resources/a1/events', []],
      ['/reviews/resources/a1', state(d, 'v1')],
    ])
    show('/topic-analysis/a1')
    const next = await screen.findByRole('list', { name: 'What follows the analysis' })
    expect(within(next).getByRole('link', { name: 'Intermediate notes' })).toHaveAttribute('href', '/notes/n1')
    expect(within(next).getByText('In review')).toBeInTheDocument()
    expect(within(next).getByText('Waits for the notes to be approved')).toBeInTheDocument()
    expect(within(next).getByRole('link', { name: 'Lesson video' })).toHaveAttribute('href', '/projects/p1/videos')
  })
})

describe('topic analysis helpers', () => {
  it('totals the plans as the approval checks do', () => {
    const a = analysis()
    expect(planTotals(a)).toMatchObject({ questions: 20, questionsOk: true, seconds: 480, target: 480, timeOk: true })
    expect(planTotals({ ...a, videoMinutes: 15 }).timeOk).toBe(false)
    expect(planTotals({ ...a, mcqCount: 25 }).questionsOk).toBe(false)
  })
  it('numbers new items after the ones there', () => {
    expect(nextId('O', ['O1', 'O2'])).toBe('O3')
    expect(nextId('C', ['C1', 'C3'])).toBe('C4')
    expect(nextId('C', ['C3', 'X'])).toBe('C4')
  })
  it('links steps, failed jobs and notifications', () => {
    expect(stepLink('p1', { kind: 'MCQ', label: 'Questions', status: 'NONE', auto: false })).toBe('/projects/p1/materials')
    expect(stepLink('p1', { kind: 'SLIDES', label: 'Presentation', status: 'DRAFT', resourceId: 's1', auto: false })).toBe('/slides/s1')
    expect(resourceLink('TOPIC_ANALYSIS', 'p1', 'a1')).toBe('/topic-analysis/a1')
    const failed = { kind: 'FAILED_JOB', resourceKind: 'TOPIC_ANALYSIS', projectId: 'p1' } as DashTask
    expect(taskLink(failed)).toBe('/projects/p1/analysis')
  })
  it('says what each step waits for', () => {
    const all = steps({ NOTES: { status: 'APPROVED' }, QA: { status: 'APPROVED' } })
    expect(waitingFor(all[1], all, on)).toBe('Starts automatically')
    expect(waitingFor(all[1], all, off)).toBe('Write them on the notes page')
    expect(waitingFor(all[4], all, on)).toBe('Waits for: SEO report')
  })
})
