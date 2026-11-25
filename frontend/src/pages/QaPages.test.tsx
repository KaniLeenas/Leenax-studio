import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { describeProposal, counts, type Issue } from '../lib/qa'
import { useToasts } from '../stores/ui'
import { BankPage } from './BankPage'
import { QaPage } from './QaPage'
import { QaReportPage } from './QaReportPage'

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
          <Route path="/mcq/:resourceId" element={<BankPage />} />
          <Route path="/projects/:id/qa" element={<QaPage />} />
          <Route path="/qa-report/:resourceId" element={<QaReportPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) { this.removeAttribute('open') }
  Element.prototype.scrollIntoView = function () {}
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  useToasts.setState({ toasts: [] })
})

const project = { id: 'p1', name: 'Arithmetic', kind: 'EDUCATION', status: 'IN_PROGRESS', myRole: 'OWNER' }
const accounts: [string, unknown][] = [
  ['/ai/accounts/acc1/models', ['gpt-x', 'claude-y']],
  ['/ai/accounts', [{ id: 'acc1', name: 'Mine', providerName: 'OpenAI', capabilities: ['TEXT'], status: 'ACTIVE', isDefault: true }]],
]
const planted = { id: 'q1', style: 'EXAM', difficulty: 'BEGINNER', stem: '2 + 2 = ?', options: ['3', '4', '5', '6'], answer: 2, explanation: 'Adding.' }
const version = (status: string, you: object) => ({
  version: { id: 'v1', resourceId: 'r1', versionNo: 1, status, origin: 'AI', contentHash: 'h', createdById: 'u9', createdBy: 'Kani', createdAt: '2026-11-24T00:00:00Z', updatedAt: '2026-11-24T00:00:00Z' },
  content: { topicId: 't1', topicTitle: 'Addition', notesLevel: 'BEGINNER', questions: [planted, { ...planted, id: 'q2', stem: '3 + 3 = ?', options: ['5', '6', '7', '8'], answer: 1 }] },
  checks: [
    { code: 'questions', ok: true, blocking: false, message: 'Every question has four different options, one key and an explanation' },
    { code: 'qa', ok: false, blocking: true, message: 'Blocked by QA: 1 critical issue open (wrong answer key Question 1).' },
  ],
  latest: true,
  you: { canEdit: true, canReview: true, isAuthor: false, mayDecide: true, reviewerMustDiffer: true, ...you },
})
const state = (d: ReturnType<typeof version>) => ({
  kind: 'MCQ', resource: { id: 'r1', projectId: 'p1', title: 'Addition', topicId: 't1', createdAt: '', updatedAt: '' },
  versions: [d.version], latest: d, approvedVersionId: null, canEdit: true, canReview: true,
})
const issue = (over: Partial<Issue> = {}): Issue => ({
  id: 'i1', number: 1, resourceId: 'r1', runId: 'run1', foundInVersionId: 'v1', foundInVersionNo: 1, itemRef: 'q1', itemLabel: 'Question 1',
  category: 'MCQ_KEYS', type: 'WRONG_KEY', severity: 'CRITICAL', original: '2 + 2 = ?\nKey: C) 5',
  explanation: 'The key is C, but the checker’s answer is B: two plus two is four.', basis: 'AI', evidence: [{ basis: 'AI' }],
  suggestion: 'Mark B) 4 as the answer, if that’s right.', confirmations: [{ model: 'claude-y', agrees: true, note: 'Its answer: B' }],
  status: 'OPEN', createdAt: '2026-11-24T00:00:00Z',
  correction: { id: 'c1', issueId: 'i1', proposal: { questionId: 'q1', answer: 1 }, status: 'PROPOSED', createdAt: '2026-11-24T00:00:00Z' },
  ...over,
})
const qa = (issues: Issue[], over: object = {}) => ({
  resourceId: 'r1', kind: 'MCQ', writerModel: 'gpt-x', issues, job: null, canRun: true, canEdit: true, canReview: true,
  runs: [{ id: 'run1', resourceId: 'r1', versionId: 'v1', versionNo: 1, mode: 'CHECK', model: 'claude-y', provider: 'Anthropic', secondModel: null,
    writerModel: 'gpt-x', independent: true, result: { limitations: [] }, createdAt: '2026-11-24T00:00:00Z' }],
  ...over,
})
const base: [string, unknown][] = [
  ['/reviews/resources/r1/comments', []],
  ['/reviews/resources/r1/events', []],
  ['/projects/p1/materials', { topicsApproved: true, topics: [{ id: 't1', title: 'Addition', depth: 1 }], notes: [], banks: [], jobs: [], canEdit: true, canReview: true }],
  ['/auth/status', { authenticated: true, user: { id: 'u1' } }],
]

describe('QA on a question bank', () => {
  it('a planted wrong key is critical, blocks approval, and its correction can be accepted', async () => {
    const d = version('IN_REVIEW', {})
    const calls = mockApi([
      ['POST /qa/corrections/c1/accept', qa([issue({ status: 'CORRECTED', correction: { ...issue().correction!, status: 'ACCEPTED' } })])],
      ['/qa/resources/r1', qa([issue()])],
      ['/reviews/resources/r1', state(d)],
      ...base,
    ])
    const user = userEvent.setup()
    show('/mcq/r1')
    expect(await screen.findByText('Blocked by QA: 1 critical issue open (wrong answer key Question 1).')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled()
    expect(within(screen.getByRole('listitem', { name: 'Question 1' })).getByText('QA: 1 open (critical)')).toBeInTheDocument()
    expect(within(screen.getByRole('listitem', { name: 'Question 2' })).getByText('QA: nothing open')).toBeInTheDocument()

    const card = within(await screen.findByRole('listitem', { name: 'Issue 1' }))
    expect(card.getByText('Critical')).toBeInTheDocument()
    expect(card.getByText('Wrong answer key')).toBeInTheDocument()
    expect(card.getByText('Confirmed by claude-y: Its answer: B')).toBeInTheDocument()
    expect(card.getByText(/Mark B \(4\) as the answer/)).toBeInTheDocument()
    await user.click(card.getByRole('button', { name: 'Accept into the draft' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/qa/corrections/c1/accept'))).toBe(true))
  })

  it('runs QA with a second model and warns when the checker wrote it', async () => {
    const d = version('DRAFT', { isAuthor: true, mayDecide: false })
    const calls = mockApi([
      ['POST /qa/resources/r1/runs', { id: 'j1', status: 'QUEUED', createdAt: '2026-11-24T00:00:00Z' }],
      ['/qa/resources/r1', qa([], { runs: [] })],
      ['/reviews/resources/r1', state(d)],
      ...accounts,
      ...base,
    ])
    const user = userEvent.setup()
    show('/mcq/r1')
    expect(await screen.findByText(/Not checked yet/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Run QA' }))
    const dialog = within(screen.getByRole('dialog'))
    await screen.findAllByRole('option', { name: 'gpt-x' })
    expect(dialog.getByText(/gpt-x wrote this, so this check won’t be independent/)).toBeInTheDocument()
    await user.selectOptions(dialog.getByLabelText('Model'), 'claude-y')
    expect(dialog.queryByText(/won’t be independent/)).not.toBeInTheDocument()
    await user.click(dialog.getByLabelText(/Confirm the findings with a second model/))
    await waitFor(() => expect(dialog.getByLabelText('Second model')).toHaveValue('gpt-x'))
    await user.click(dialog.getByRole('button', { name: 'Start QA' }))
    await waitFor(() =>
      expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ versionId: 'v1', accountId: 'acc1', model: 'claude-y', recheckOnly: false, secondAccountId: 'acc1', secondModel: 'gpt-x' }),
    )
  })

  it('dismissing an issue needs a reason', async () => {
    const d = version('IN_REVIEW', {})
    const calls = mockApi([
      ['POST /qa/issues/i1/dismiss', qa([issue({ status: 'DISMISSED', statusNote: 'The lecturer confirmed C' })])],
      ['/qa/resources/r1', qa([issue({ correction: null })])],
      ['/reviews/resources/r1', state(d)],
      ...base,
    ])
    const user = userEvent.setup()
    show('/mcq/r1')
    const card = within(await screen.findByRole('listitem', { name: 'Issue 1' }))
    await user.click(card.getByRole('button', { name: 'Dismiss…' }))
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getByText(/Dismissing a critical issue lets this be approved/)).toBeInTheDocument()
    const go = dialog.getByRole('button', { name: 'Dismiss' })
    expect(go).toBeDisabled()
    await user.type(dialog.getByLabelText('Reason'), 'The lecturer confirmed C')
    await user.click(go)
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ reason: 'The lecturer confirmed C' }))
  })
})

const report = {
  topicId: 't1', topicTitle: 'Addition', generatedAt: '2026-11-24T00:00:00Z', headline: 'BLOCKED',
  items: [
    { resourceId: 'n1', kind: 'NOTES', level: 'BEGINNER', label: 'Beginner notes', versionId: 'nv1', versionNo: 1, versionStatus: 'APPROVED',
      run: { runId: 'x', versionNo: 1, model: 'claude-y', independent: true, current: true }, categories: {}, claimsByBasis: { UPLOADED: 9, AI: 2 }, openBySeverity: {}, corrected: 0, dismissed: 0 },
    { resourceId: 'r1', kind: 'MCQ', label: 'Question bank', versionId: 'v1', versionNo: 1, versionStatus: 'APPROVED', questions: 2, difficulty: { BEGINNER: 2 },
      run: { runId: 'y', versionNo: 1, model: 'gpt-x', independent: false, current: true }, categories: {}, claimsByBasis: {}, openBySeverity: { CRITICAL: 1 }, corrected: 0, dismissed: 0 }],
  issues: [{ number: 1, resourceId: 'r1', resource: 'Question bank', where: 'Question 1', severity: 'CRITICAL', type: 'WRONG_KEY', category: 'MCQ_KEYS', status: 'OPEN', explanation: 'Key C, answer B.', basis: 'AI' }],
  corrections: [],
  coverage: [{ subtopic: 'Carrying', covered: false, levels: [] }],
  parts: {
    COMBINED: { checked: 100, passed: 99, failed: 1, corrected: 0, open: 1, openCritical: 1, score: 99, headline: 'BLOCKED', categories: { MCQ_KEYS: { checked: 2, passed: 1, failed: 1 } } },
    NOTES: { checked: 98, passed: 98, failed: 0, corrected: 0, open: 0, openCritical: 0, score: 100, headline: 'PASSED', categories: {} },
    MCQ: { checked: 2, passed: 1, failed: 1, corrected: 0, open: 1, openCritical: 1, score: 50, headline: 'BLOCKED', categories: {} },
  },
  method: 'Passed is checked minus failed.', recommendations: ['Deal with the 1 open critical issue.'], limitations: ['QA is done by AI models.'],
}

describe('QA reports', () => {
  it('shows Blocked whatever the score, per part, and has no hand edits', async () => {
    const d = { ...version('IN_REVIEW', {}), content: report, checks: [{ code: 'qa', ok: false, blocking: true, message: 'Blocked by QA: 1 critical issue open' }] }
    mockApi([
      ['/reviews/resources/q9/events', []],
      ['/reviews/resources/q9', { kind: 'QA', resource: { id: 'q9', projectId: 'p1', title: 'Addition: QA report', topicId: 't1' }, versions: [d.version], latest: d, approvedVersionId: null, canEdit: true, canReview: true }],
      ...base,
    ])
    const user = userEvent.setup()
    show('/qa-report/q9')
    expect(await screen.findByText('Blocked')).toBeInTheDocument()
    expect(screen.getByText('99%')).toBeInTheDocument()
    expect(screen.getByText(/Not independent: the same model wrote it/)).toBeInTheDocument()
    expect(screen.getByText('not found in any level of the notes')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Make edits' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Notes' }))
    expect(screen.getByText(/No critical or major issues are open/)).toBeInTheDocument()
    expect(screen.getByText('Issues (0)')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Questions report as Word' })).toHaveAttribute('href', '/api/v1/exports/versions/v1?format=docx&part=MCQ')
  })

  it('the project QA page makes a topic’s report', async () => {
    const calls = mockApi([
      ['POST /projects/p1/qa/reports/t1', { kind: 'QA', resource: { id: 'q9', projectId: 'p1' } }],
      ['/projects/p1/qa', { topicsApproved: true, canEdit: true, canReview: true, topics: [
        { topicId: 't1', title: 'Addition', depth: 1, items: [
          { resourceId: 'n1', kind: 'NOTES', level: 'BEGINNER', versionNo: 2, versionStatus: 'DRAFT', checkedVersionNo: 1, current: false, open: {}, running: false },
          { resourceId: 'r1', kind: 'MCQ', versionNo: 1, versionStatus: 'IN_REVIEW', checkedVersionNo: 1, current: true, open: { CRITICAL: 1 }, running: false }] },
        { topicId: 't2', title: 'Subtraction', depth: 1, items: [] }] }],
      ['/reviews/resources/q9/events', []],
      ['/reviews/resources/q9', { kind: 'QA', resource: { id: 'q9', projectId: 'p1' }, versions: [], latest: null, canEdit: true, canReview: true }],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/qa')
    const list = within(await screen.findByRole('list', { name: 'QA for Addition' }))
    expect(list.getByText('checked version 1, now version 2')).toBeInTheDocument()
    expect(list.getByText('1 critical open')).toBeInTheDocument()
    expect(screen.queryByText('Subtraction')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Make the QA report' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/projects/p1/qa/reports/t1'))).toBe(true))
  })
})

describe('qa helpers', () => {
  it('describes proposed corrections in words', () => {
    expect(describeProposal({ questionId: 'q1', answer: 1 }, ['3', '4', '5', '6'])).toBe('Mark B (4) as the answer')
    expect(describeProposal({ find: 'are allowed', replace: 'are not allowed' })).toBe('Replace “are allowed” with “are not allowed”')
  })
  it('counts open issues by severity', () => {
    expect(counts([issue(), issue({ id: 'i2', severity: 'MINOR', type: 'GRAMMAR' }), issue({ id: 'i3', status: 'RESOLVED' })])).toEqual({ CRITICAL: 1, MAJOR: 0, MINOR: 1, closed: 1 })
  })
})
