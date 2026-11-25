import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { useToasts } from '../stores/ui'
import { SyllabusPage } from './SyllabusPage'
import { TopicsPage } from './TopicsPage'

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
          <Route path="/projects/:id/syllabus" element={<SyllabusPage />} />
          <Route path="/projects/:id/topics" element={<TopicsPage />} />
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

const project = { id: 'p1', name: 'Database Systems', kind: 'EDUCATION', subject: { moduleCode: 'CSC2041' }, status: 'IN_PROGRESS', myRole: 'OWNER' }
const noJob = { documents: [{ documentId: 'd1', title: 'Module descriptor', versionId: 'dv1', versionNo: 1, pages: 4, chars: 9000 }], maxChars: 150000, job: null }
const empty = (canEdit = true, canReview = true) => ({ kind: 'SYLLABUS', resource: null, versions: [], latest: null, canEdit, canReview })
const src = { documentId: 'd1', versionId: 'dv1', page: 2, quote: 'functional dependencies', found: true }
const syllabus = (title = 'Normalisation', resolution: string | null = null) => ({
  summary: 'A first database module.',
  structure: [{ id: 'u1', title, sources: [src], children: [{ id: 't1', title: '3NF', sources: [{ ...src, page: 3, found: false }], children: [] }] }],
  outcomes: [{ id: 'o1', code: 'LO1', text: 'Normalise a schema', sources: [src] }],
  assessments: [{ id: 'a1', title: 'Final exam', kind: 'EXAM', weight: 60, sources: [] }],
  contradictions: [{ id: 'c1', description: 'The exam is 60% in the descriptor and 70% in the handbook', sources: [src], resolution }],
  documents: [{ documentId: 'd1', versionId: 'dv1', title: 'Module descriptor', versionNo: 1, label: 'D1' }],
  analysis: { model: 'gpt-x', citations: 4, citationsFound: 3, citationsRemoved: 1 },
})
const version = (status: string, you: object, over: object = {}) => ({
  version: { id: 'v2', resourceId: 'r1', versionNo: 2, status, origin: 'AI', contentHash: 'h', createdById: 'u-lect', createdBy: 'Kani', createdAt: '2026-11-24T00:00:00Z', updatedAt: '2026-11-24T00:00:00Z', ...over },
  content: syllabus(),
  checks: [
    { code: 'structure', ok: true, blocking: false, message: '2 chapters, topics and subtopics' },
    { code: 'contradictions', ok: false, blocking: true, message: '1 of 1 contradictions between documents need a resolution' },
  ],
  latest: true,
  you: { canEdit: true, canReview: true, isAuthor: false, mayDecide: true, reviewerMustDiffer: true, ...you },
})
const state = (detail: ReturnType<typeof version>) => ({
  kind: 'SYLLABUS', resource: { id: 'r1', projectId: 'p1', title: 'Syllabus', createdAt: '', updatedAt: '' },
  versions: [detail.version, { ...detail.version, id: 'v1', versionNo: 1, status: 'ARCHIVED' }], latest: detail, approvedVersionId: null, approvedVersionNo: null,
  canEdit: detail.you.canEdit, canReview: detail.you.canReview,
})

describe('Syllabus', () => {
  it('starts an analysis of the chosen documents with your AI account', async () => {
    const calls = mockApi([
      ['/ai/accounts/acc1/models', ['gpt-x', 'gpt-y']],
      ['/ai/accounts', [{ id: 'acc1', name: 'My OpenAI', providerName: 'OpenAI', capabilities: ['TEXT'], status: 'ACTIVE', isDefault: true }]],
      ['POST /projects/p1/syllabus/analysis', { id: 'j1', status: 'QUEUED', documents: ['Module descriptor'], attempts: 0, createdAt: '2026-11-24T00:00:00Z' }],
      ['/projects/p1/syllabus/analysis', noJob],
      ['/projects/p1/reviews/syllabus', empty()],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/syllabus')
    await user.click(await screen.findByRole('checkbox', { name: /Module descriptor/ }))
    expect(screen.getByText('9,000 of 150,000 characters')).toBeInTheDocument()
    await screen.findByRole('option', { name: 'gpt-x' })
    await user.click(screen.getByRole('button', { name: 'Analyse documents' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ documentIds: ['d1'], accountId: 'acc1', model: 'gpt-x' }))
  })

  it('a reviewer sees sources and checks, and must say why when sending it back', async () => {
    const d = version('IN_REVIEW', {})
    const calls = mockApi([
      ['POST /reviews/versions/v2/decision', { ...d, version: { ...d.version, status: 'CHANGES_REQUESTED' } }],
      ['/reviews/resources/r1/events', [{ id: 'e1', versionId: 'v2', versionNo: 2, action: 'SUBMITTED', newStatus: 'IN_REVIEW', actor: 'Kani', createdAt: '2026-11-24T00:00:00Z' }]],
      ['/projects/p1/syllabus/analysis', noJob],
      ['/projects/p1/reviews/syllabus', state(d)],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/syllabus')
    expect(await screen.findByText('Check the syllabus and decide.')).toBeInTheDocument()
    expect(screen.getByText('1 of 1 contradictions between documents need a resolution')).toBeInTheDocument()
    expect(screen.getByText('Not resolved yet.')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /D1 p\.2/ })[0]).toHaveAttribute('href', '/documents/d1?version=dv1&page=2')
    expect(screen.getByRole('link', { name: /D1 p\.3/ })).toBeInTheDocument()
    expect(screen.getByText(/3 of 4 citations were found word for word/)).toBeInTheDocument()
    expect(await screen.findByText('Sent for review')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Ask for changes' }))
    const dialog = screen.getByRole('dialog')
    const send = within(dialog).getByRole('button', { name: 'Send back' })
    expect(send).toBeDisabled()
    await user.type(within(dialog).getByLabelText('What needs to change'), 'Resolve the exam weight')
    await user.click(send)
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ decision: 'REQUEST_CHANGES', comment: 'Resolve the exam weight' }))
  })

  it('the author can’t decide on their own version', async () => {
    const d = version('IN_REVIEW', { isAuthor: true, mayDecide: false })
    mockApi([
      ['/reviews/resources/r1/events', []],
      ['/projects/p1/syllabus/analysis', noJob],
      ['/projects/p1/reviews/syllabus', state(d)],
      ['/projects/p1', project],
    ])
    show('/projects/p1/syllabus')
    expect(await screen.findByText('You wrote this version, so another reviewer must decide.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Withdraw to change it' })).toBeInTheDocument()
  })

  it('a reviewer approves with edits, sending the edited content', async () => {
    const d = version('IN_REVIEW', {})
    const calls = mockApi([
      ['POST /reviews/versions/v2/decision', { ...d, version: { ...d.version, id: 'v3', versionNo: 3, status: 'APPROVED' } }],
      ['/reviews/resources/r1/events', []],
      ['/projects/p1/syllabus/analysis', noJob],
      ['/projects/p1/reviews/syllabus', state(d)],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/syllabus')
    await user.click(await screen.findByRole('button', { name: 'Make edits' }))
    const name = screen.getAllByRole('textbox', { name: 'Chapter name' })[0]
    await user.clear(name)
    await user.type(name, 'Normal forms')
    await user.type(screen.getByLabelText('Resolution'), 'The handbook is current: 70%')
    await user.click(screen.getByRole('button', { name: 'Approve with my edits' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Approve with my edits' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')).toBeDefined())
    const body = calls.find((c) => c.method === 'POST')!.body
    expect(body.decision).toBe('APPROVE_WITH_EDITS')
    expect(body.content.structure[0].title).toBe('Normal forms')
    expect(body.content.contradictions[0].resolution).toBe('The handbook is current: 70%')
  })

  it('an editor saves the draft before sending it for review', async () => {
    const d = { ...version('DRAFT', { mayDecide: false, isAuthor: true }), checks: [] }
    const calls = mockApi([
      ['PUT /reviews/versions/v2', d],
      ['POST /reviews/versions/v2/submit', { ...d, version: { ...d.version, status: 'IN_REVIEW' } }],
      ['/reviews/resources/r1/events', []],
      ['/projects/p1/syllabus/analysis', noJob],
      ['/projects/p1/reviews/syllabus', state(d)],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/syllabus')
    const name = (await screen.findAllByRole('textbox', { name: 'Topic name' }))[0]
    await user.type(name, ' (third normal form)')
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send for review' }))
    await waitFor(() => expect(calls.filter((c) => c.method !== 'GET').map((c) => c.method + ' ' + c.url.replace(/.*\/api\/v1/, ''))).toEqual([
      'PUT /reviews/versions/v2',
      'POST /reviews/versions/v2/submit',
    ]))
    expect(calls.find((c) => c.method === 'PUT')!.body.content.structure[0].children[0].title).toBe('3NF (third normal form)')
  })
})

describe('Topics', () => {
  const topicsState = (status: string, syllabusVersionId: string, you: object = {}) => {
    const detail = {
      version: { id: 'tv1', resourceId: 'tr', versionNo: 1, status, origin: 'MANUAL', contentHash: 'h', createdAt: '2026-11-24T00:00:00Z', updatedAt: '2026-11-24T00:00:00Z', createdBy: 'Kani' },
      content: { syllabusVersionId, nodes: [
        { id: 'a', title: 'Keys', selected: true, difficulty: 'INTRODUCTORY', prerequisites: [], syllabusItemIds: ['u1'], children: [] },
        { id: 'b', title: '3NF', selected: true, difficulty: null, prerequisites: ['a'], syllabusItemIds: ['t1'], children: [] },
        { id: 'c', title: 'Indexing', selected: false, notInSyllabus: true, prerequisites: [], syllabusItemIds: ['t9'], children: [] },
      ] },
      checks: [], latest: true,
      you: { canEdit: true, canReview: true, isAuthor: true, mayDecide: false, reviewerMustDiffer: true, ...you },
    }
    return { kind: 'TOPICS', resource: { id: 'tr', projectId: 'p1', title: 'Topics', createdAt: '', updatedAt: '' }, versions: [detail.version], latest: detail, canEdit: true, canReview: true }
  }

  it('waits for an approved syllabus', async () => {
    mockApi([
      ['/projects/p1/reviews/topics', { ...empty(), kind: 'TOPICS' }],
      ['/projects/p1/reviews/syllabus', { ...empty(), approvedVersionId: null }],
      ['/projects/p1', project],
    ])
    show('/projects/p1/topics')
    expect(await screen.findByText("The syllabus isn't approved yet")).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Build from the approved syllabus' })).toBeNull()
  })

  it('offers an update when the approved syllabus has moved on', async () => {
    const calls = mockApi([
      ['POST /projects/p1/topics/from-syllabus', topicsState('DRAFT', 's3')],
      ['/reviews/resources/tr/events', []],
      ['/projects/p1/reviews/topics', topicsState('OUTDATED', 's1')],
      ['/projects/p1/reviews/syllabus', { ...empty(), approvedVersionId: 's3', approvedVersionNo: 3 }],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/topics')
    expect(await screen.findByText(/The approved syllabus is now version 3/)).toBeInTheDocument()
    expect(screen.getByText('No longer in the syllabus')).toBeInTheDocument()
    expect(screen.getByText('Needs Keys')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Update from syllabus version 3' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/topics/from-syllabus'))).toBe(true))
  })

  it('edits selection, difficulty and prerequisites in a draft', async () => {
    const calls = mockApi([
      ['PUT /reviews/versions/tv1', topicsState('DRAFT', 's1')],
      ['/reviews/resources/tr/events', []],
      ['/projects/p1/reviews/topics', topicsState('DRAFT', 's1')],
      ['/projects/p1/reviews/syllabus', { ...empty(), approvedVersionId: 's1', approvedVersionNo: 1 }],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/topics')
    expect(await screen.findByText('2 of 3 topics included.')).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: 'Include Keys' }))
    expect(screen.getByText('1 of 3 topics included.')).toBeInTheDocument()
    await user.selectOptions(screen.getByRole('combobox', { name: 'Difficulty of 3NF' }), 'ADVANCED')
    // make Keys need 3NF: with 3NF needing Keys, that's a loop, shown at once
    await user.click(screen.getByRole('checkbox', { name: 'Include Keys' }))
    await user.click(screen.getAllByRole('button', { name: 'Prerequisites' })[0])
    await user.click(within(screen.getByRole('dialog')).getByRole('checkbox', { name: '3NF' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Save prerequisites' }))
    expect(screen.getByText('Prerequisites go round in a loop: Keys → 3NF → Keys')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeDefined())
    const nodes = calls.find((c) => c.method === 'PUT')!.body.content.nodes
    expect(nodes[1].difficulty).toBe('ADVANCED')
    expect(nodes[0].prerequisites).toEqual(['b'])
  })
})
