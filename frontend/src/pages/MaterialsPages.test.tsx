import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { useToasts } from '../stores/ui'
import { BankPage } from './BankPage'
import { MaterialsPage } from './MaterialsPage'
import { NotesPage } from './NotesPage'
import { QuizzesPage } from './QuizzesPage'

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
          <Route path="/projects/:id/materials" element={<MaterialsPage />} />
          <Route path="/projects/:id/quizzes" element={<QuizzesPage />} />
          <Route path="/notes/:resourceId" element={<NotesPage />} />
          <Route path="/mcq/:resourceId" element={<BankPage />} />
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
const topics = [
  { id: 't1', title: 'Normalisation', depth: 1, path: null },
  { id: 't2', title: 'Third normal form', depth: 2, path: 'Normalisation' },
]
const overview = (over: object = {}) => ({
  topicsApproved: true,
  topicsVersionNo: 3,
  topics,
  notes: [
    { resourceId: 'n1', kind: 'NOTES', topicId: 't1', level: 'BEGINNER', title: 'Normalisation: beginner', latestNo: 1, latestStatus: 'APPROVED', approvedNo: 1, approvedVersionId: 'nv1' },
    { resourceId: 'n2', kind: 'NOTES', topicId: 't2', level: 'EXAM', title: '3NF: exam', latestNo: 1, latestStatus: 'IN_REVIEW', approvedNo: null },
  ],
  banks: [{ resourceId: 'b1', kind: 'MCQ', topicId: 't1', title: 'Normalisation: questions', latestNo: 2, latestStatus: 'APPROVED', approvedNo: 2, approvedVersionId: 'bv2', questionCount: 12 }],
  jobs: [],
  canEdit: true,
  canReview: true,
  ...over,
})

const you = { canEdit: true, canReview: true, isAuthor: true, mayDecide: false, reviewerMustDiffer: true }
const detail = (kind: string, content: object, status = 'DRAFT', over: object = {}) => ({
  version: { id: 'v1', resourceId: 'r1', versionNo: 1, status, origin: 'HUMAN', contentHash: 'h', createdById: 'u1', createdBy: 'Kani', createdAt: '2026-11-24T00:00:00Z', updatedAt: '2026-11-24T00:00:00Z' },
  content,
  checks: [],
  latest: true,
  you,
  ...over,
  kind,
})
const resourceState = (kind: string, d: ReturnType<typeof detail>, title: string) => ({
  kind,
  resource: { id: 'r1', projectId: 'p1', title, topicId: 't1', level: kind === 'NOTES' ? 'BEGINNER' : null, createdAt: '', updatedAt: '' },
  versions: [d.version],
  latest: d,
  approvedVersionId: d.version.status === 'APPROVED' ? d.version.id : null,
  approvedVersionNo: null,
  canEdit: true,
  canReview: true,
})

describe('Notes and questions overview', () => {
  it('waits for an approved topic list', async () => {
    mockApi([['/projects/p1/materials', overview({ topicsApproved: false, topics: [] })], ['/projects/p1', project]])
    show('/projects/p1/materials')
    expect(await screen.findByText('The topic list isn’t approved yet'.replace('’', "'"))).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to topics' })).toHaveAttribute('href', '/projects/p1/topics')
  })

  it('shows every level per topic and writes notes with your AI account', async () => {
    const calls = mockApi([
      ...accounts,
      ['POST /projects/p1/notes/generate', { id: 'j1', kind: 'NOTES', target: 't1:EXAM', status: 'QUEUED', createdAt: '2026-11-24T00:00:00Z' }],
      ['/projects/p1/materials', overview()],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/materials')
    const row = within(await screen.findByLabelText('Notes for Normalisation'))
    expect(row.getByRole('link', { name: /Beginner/ })).toHaveAttribute('href', '/notes/n1')
    expect(screen.getByRole('link', { name: /12 questions/ })).toHaveAttribute('href', '/mcq/b1')
    // Questions for 3NF need approved notes first: its only notes are still in review
    expect(screen.getByText('Questions need approved notes')).toBeInTheDocument()

    await user.click(row.getByRole('button', { name: /Exam/ }))
    const dialog = within(screen.getByRole('dialog'))
    await screen.findByRole('option', { name: 'gpt-x' })
    await user.type(dialog.getByLabelText(/Guidance/), 'Use banking examples')
    await user.click(dialog.getByRole('button', { name: 'Write with AI' }))
    await waitFor(() =>
      expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ topicId: 't1', level: 'EXAM', accountId: 'acc1', model: 'gpt-x', guidance: 'Use banking examples' }),
    )
  })

  it('shows work in progress and failures', async () => {
    mockApi([
      ['/projects/p1/materials', overview({ jobs: [
        { id: 'j1', kind: 'MCQ', target: 't1', status: 'RUNNING', createdAt: new Date().toISOString(), startedBy: 'Kani' },
        { id: 'j2', kind: 'NOTES', target: 't2:ADVANCED', status: 'FAILED', error: 'The AI account ran out of credit', createdAt: new Date().toISOString() },
      ] })],
      ['/projects/p1', project],
    ])
    show('/projects/p1/materials')
    const list = within(await screen.findByRole('list', { name: 'Work in progress' }))
    expect(list.getByText(/Writing questions for Normalisation, started by Kani/)).toBeInTheDocument()
    expect(list.getByText('Advanced notes for Third normal form failed: The AI account ran out of credit')).toBeInTheDocument()
  })
})

const q = (id: string, over: object = {}) => ({
  id, style: 'EXAM', difficulty: 'BEGINNER', stem: `Question ${id}?`, options: ['1NF', '2NF', '3NF', 'BCNF'], answer: 2, explanation: 'Because.', verification: 'UNVERIFIED', ...over,
})

describe('Question bank', () => {
  it('checks each question as you edit, and saves the key you mark', async () => {
    const bank = { topicId: 't1', topicTitle: 'Normalisation', notesLevel: 'BEGINNER', questions: [q('a'), q('b', { answer: null, options: ['1NF', '2NF', '2nf', 'All of the above'] })] }
    const d = detail('MCQ', bank)
    const calls = mockApi([
      ['PUT /reviews/versions/v1', d],
      ['/reviews/resources/r1/comments', []],
      ['/reviews/resources/r1/events', []],
      ['/reviews/resources/r1', resourceState('MCQ', d, 'Normalisation: questions')],
      ['/projects/p1/materials', overview()],
      ['/auth/status', { authenticated: true, user: { id: 'u1' } }],
    ])
    const user = userEvent.setup()
    show('/mcq/r1')
    expect(await screen.findByText(/, 1 need fixing/)).toBeInTheDocument()
    const second = within(screen.getByRole('listitem', { name: 'Question 2' }))
    expect(second.getByText('Needs fixing: options repeat, no correct answer marked, “all/none of the above” option.')).toBeInTheDocument()

    await user.clear(second.getByLabelText('Option C'))
    await user.type(second.getByLabelText('Option C'), '3NF')
    await user.clear(second.getByLabelText('Option D'))
    await user.type(second.getByLabelText('Option D'), 'BCNF')
    await user.click(second.getByLabelText('Option C is correct'))
    expect(second.queryByText(/Needs fixing/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy())
    const saved = calls.find((c) => c.method === 'PUT')!.body.content
    expect(saved.questions[1]).toMatchObject({ options: ['1NF', '2NF', '3NF', 'BCNF'], answer: 2 })
    expect(saved.questions[0]).toEqual(bank.questions[0])
  })

  it('downloads the version in four formats', async () => {
    const d = detail('MCQ', { topicId: 't1', topicTitle: 'Normalisation', questions: [q('a')] }, 'APPROVED')
    mockApi([
      ['/reviews/resources/r1/comments', []],
      ['/reviews/resources/r1/events', []],
      ['/reviews/resources/r1', resourceState('MCQ', d, 'Normalisation: questions')],
      ['/projects/p1/materials', overview()],
      ['/auth/status', { authenticated: true, user: { id: 'u1' } }],
    ])
    show('/mcq/r1')
    for (const [name, f] of [['PDF', 'pdf'], ['Word', 'docx'], ['Excel', 'xlsx'], ['CSV', 'csv']])
      expect(await screen.findByRole('link', { name })).toHaveAttribute('href', `/api/v1/exports/versions/v1?format=${f}`)
    expect(screen.getByLabelText('Correct answer')).toBeInTheDocument()
    expect(screen.queryByLabelText('Option A')).not.toBeInTheDocument()
  })
})

describe('Notes', () => {
  it('shows the notes, their sources and comments, and posts a comment', async () => {
    const notes = {
      topicId: 't1', topicTitle: 'Normalisation', level: 'BEGINNER', model: 'gpt-x',
      doc: { type: 'doc', content: [{ type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: 'Why normalise' }] }, { type: 'paragraph', content: [{ type: 'text', text: 'To remove update anomalies.' }] }] },
      references: [{ label: 'Module descriptor p.2', documentId: 'd1', versionId: 'dv1', page: 2, found: true }],
    }
    const d = detail('NOTES', notes, 'IN_REVIEW', { you: { ...you, isAuthor: false, mayDecide: true } })
    const calls = mockApi([
      ['POST /reviews/versions/v1/comments', [{ id: 'c1', versionId: 'v1', versionNo: 1, body: 'Add an example', authorId: 'u1', author: 'Kani', createdAt: '2026-11-24T00:00:00Z' }]],
      ['/reviews/resources/r1/comments', []],
      ['/reviews/resources/r1/events', []],
      ['/reviews/resources/r1', resourceState('NOTES', d, 'Normalisation: beginner')],
      ['/auth/status', { authenticated: true, user: { id: 'u1' } }],
    ])
    const user = userEvent.setup()
    show('/notes/r1')
    expect(await screen.findByRole('heading', { name: 'Normalisation', level: 1 })).toBeInTheDocument()
    expect(screen.getByText('Beginner notes, first written by gpt-x')).toBeInTheDocument()
    expect(await screen.findByText('Why normalise')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Module descriptor p.2' })).toHaveAttribute('href', '/documents/d1?version=dv1&page=2')
    expect(screen.getByRole('link', { name: 'Word' })).toHaveAttribute('href', '/api/v1/exports/versions/v1?format=docx')

    await user.type(screen.getByLabelText('Comment on version 1'), 'Add an example')
    await user.click(screen.getByRole('button', { name: 'Comment' }))
    await waitFor(() => expect(calls.find((c) => c.url.endsWith('/comments') && c.method === 'POST')?.body).toEqual({ body: 'Add an example' }))
    expect(await screen.findByText('Add an example')).toBeInTheDocument()
  })
})

describe('Quizzes', () => {
  const quiz = {
    id: 'qz1', title: 'Week 3 check', seed: 42, config: {}, createdBy: 'Kani', createdAt: '2026-11-24T00:00:00Z',
    questions: [{ number: 1, bankId: 'b1', bankTitle: 'Normalisation: questions', bankVersionNo: 2, questionId: 'a', style: 'EXAM', stem: 'Which form?', options: ['1NF', '2NF', '3NF', 'BCNF'], answer: 2, explanation: 'Transitive.' }],
  }

  it('draws a quiz from the approved banks', async () => {
    const calls = mockApi([
      ['POST /projects/p1/quizzes', quiz],
      ['/projects/p1/quizzes', []],
      ['/quizzes/qz1', quiz],
      ['/projects/p1/materials', overview()],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/quizzes')
    await user.click(await screen.findByRole('button', { name: 'New quiz' }))
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getByLabelText(/Normalisation: questions/)).toBeChecked()
    await user.type(dialog.getByLabelText('Title'), 'Week 3 check')
    await user.clear(dialog.getByLabelText('Questions'))
    await user.type(dialog.getByLabelText('Questions'), '10')
    await user.type(dialog.getByLabelText('Seed (optional)'), '42')
    await user.click(within(dialog.getByRole('group', { name: 'Difficulty' })).getByLabelText('Beginner'))
    await user.click(dialog.getByRole('button', { name: 'Make quiz' }))
    await waitFor(() =>
      expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ title: 'Week 3 check', bankIds: ['b1'], count: 10, difficulties: ['BEGINNER'], styles: [], shuffleOptions: true, seed: 42 }),
    )
    expect(await screen.findByText(/1 questions, seed 42/)).toBeInTheDocument()
  })

  it('hides answers until asked, and downloads with or without them', async () => {
    mockApi([
      ['/projects/p1/quizzes', [{ id: 'qz1', title: 'Week 3 check', questionCount: 1, seed: 42, createdAt: '2026-11-24T00:00:00Z' }]],
      ['/quizzes/qz1', quiz],
      ['/projects/p1/materials', overview()],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/quizzes')
    await user.click(await screen.findByRole('button', { name: 'Week 3 check' }))
    const dialog = within(await screen.findByRole('dialog'))
    expect(await dialog.findByText('Which form?')).toBeInTheDocument()
    expect(dialog.queryByLabelText('Correct answer')).not.toBeInTheDocument()
    const pdfs = dialog.getAllByRole('link', { name: 'PDF' })
    expect(pdfs[0]).toHaveAttribute('href', '/api/v1/exports/quizzes/qz1?format=pdf&answers=none')
    expect(pdfs[1]).toHaveAttribute('href', '/api/v1/exports/quizzes/qz1?format=pdf&answers=end')
    await user.click(dialog.getByLabelText('Show answers here'))
    expect(dialog.getByLabelText('Correct answer')).toBeInTheDocument()
  })
})
