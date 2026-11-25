import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { blankSlide, geometry, move, withLayout, type Deck } from '../lib/slides'
import { useToasts } from '../stores/ui'
import { DeckPage } from './DeckPage'
import { SlidesPage } from './SlidesPage'

vi.mock('../lib/figures', () => ({
  drawMath: () => Promise.resolve('<svg xmlns="http://www.w3.org/2000/svg"/>'),
  drawMermaid: () => Promise.resolve('<svg xmlns="http://www.w3.org/2000/svg"/>'),
  keepFigure: vi.fn(),
  svgDataUrl: (s: string) => 'data:image/svg+xml;base64,' + btoa(s),
}))

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
          <Route path="/projects/:id/slides" element={<SlidesPage />} />
          <Route path="/slides/:resourceId" element={<DeckPage />} />
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
const overview = {
  topicsApproved: true, canEdit: true, styles: {},
  topics: [
    { topicId: 't5', title: '1NF to 3NF', depth: 1, approvedNotes: ['BEGINNER', 'ADVANCED'], qaApproved: true, seoApproved: true,
      decks: [{ level: 'BEGINNER', resourceId: 'd1', versionNo: 2, status: 'DRAFT', slides: 12, running: false }] },
    { topicId: 't6', title: 'BCNF', depth: 1, approvedNotes: ['BEGINNER'], qaApproved: true, seoApproved: false, decks: [] },
  ],
}

describe('the presentations page', () => {
  it('only offers slides once notes, QA and SEO are approved, and starts them with the chosen style', async () => {
    const calls = mockApi([
      ['POST /projects/p1/slides', { id: 'j1', status: 'QUEUED' }],
      ['/projects/p1/slides', overview],
      ['/projects/p1', project],
      ...accounts,
    ])
    const user = userEvent.setup()
    show('/projects/p1/slides')
    expect(await screen.findByRole('link', { name: /Beginner slides, version 2, 12 slides/ })).toHaveAttribute('href', '/slides/d1')
    expect(screen.getByText('SEO report not approved')).toBeInTheDocument()
    const buttons = screen.getAllByRole('button', { name: /Make slides/ })
    expect(buttons[1]).toBeDisabled()
    await user.click(buttons[0])
    const dialog = within(screen.getByRole('dialog'))
    await screen.findByRole('option', { name: 'gpt-x' })
    expect(dialog.getByText(/The current draft will be rewritten/)).toBeInTheDocument()
    await user.selectOptions(dialog.getByLabelText('From the notes for'), 'ADVANCED')
    expect(dialog.queryByText(/The current draft will be rewritten/)).not.toBeInTheDocument()
    await user.click(dialog.getByLabelText('Dark technology'))
    await user.click(dialog.getByRole('button', { name: 'Make slides' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')).toBeTruthy())
    expect(calls.find((c) => c.method === 'POST')!.body).toMatchObject({ topicId: 't5', level: 'ADVANCED', style: 'DARK_TECH', slides: 14, accountId: 'acc1', model: 'gpt-x' })
  })
})

const deck = (over: Partial<Deck> = {}): Deck => ({
  topicId: 't5', topicTitle: '1NF to 3NF', level: 'BEGINNER', style: 'LECTURE', title: 'Normalisation', model: 'gpt-x',
  slides: [
    { id: 'a', type: 'TITLE', layout: 'TITLE', title: 'Normalisation', subtitle: 'Database Systems' },
    { id: 'b', type: 'OBJECTIVES', layout: 'BULLETS', title: 'You will', bullets: ['Explain anomalies', 'Reach 3NF'], notes: 'Start with a story.', sources: ['Why normalise'] },
    { id: 'c', type: 'COMPARISON', layout: 'TABLE', title: 'Forms', table: { headers: ['Form', 'Removes'], rows: [['1NF', 'Repeating groups']] }, notes: 'n' },
    { id: 'd', type: 'REFERENCES', layout: 'BULLETS', title: 'References', bullets: ['Codd (1970)'] },
  ],
  fit: { checked: true, ok: false, method: 'Measured with Carlito', slides: [
    { id: 'a', layout: 'TITLE', ok: true, problems: [], boxes: [{ field: 'title', size: 48, fits: true, lines: 1, needed: 58, room: 143, min: 28, max: 48 }] },
    { id: 'b', layout: 'BULLETS', ok: true, problems: [], boxes: [{ field: 'bullets', size: 28, fits: true, lines: 2, needed: 80, room: 365, min: 16, max: 28 }] },
    { id: 'c', layout: 'TABLE', ok: false, problems: ['Table: The table needs 400 points of height at 11 pt but has 372. Cut rows or words.'], boxes: [{ field: 'table', size: 11, fits: false, lines: 2, needed: 400, room: 372, min: 11, max: 20 }] },
    { id: 'd', layout: 'BULLETS', ok: true, problems: [], boxes: [] },
  ] },
  ...over,
})
const version = (status: string, content: Deck = deck(), checks: object[] = []) => ({
  version: { id: 'v1', resourceId: 'd1', versionNo: 2, status, origin: 'AI', contentHash: 'h', createdById: 'u1', createdBy: 'Kani', createdAt: '2026-11-24T00:00:00Z', updatedAt: '2026-11-24T00:00:00Z' },
  content, checks, latest: true,
  you: { canEdit: true, canReview: true, isAuthor: status === 'DRAFT', mayDecide: status === 'IN_REVIEW', reviewerMustDiffer: true },
})
const state = (d: ReturnType<typeof version>) => ({ kind: 'SLIDES', resource: { id: 'd1', projectId: 'p1', title: '1NF to 3NF: beginner slides', topicId: 't5', level: 'BEGINNER' }, versions: [d.version], latest: d, approvedVersionId: null, canEdit: true, canReview: true })

describe('a presentation', () => {
  it('outlines a slide whose text doesn’t fit and says why', async () => {
    const d = version('DRAFT')
    mockApi([['/reviews/resources/d1/events', []], ['/reviews/resources/d1', state(d)]])
    const user = userEvent.setup()
    show('/slides/d1')
    expect(await screen.findByText(/Text doesn’t fit on 1 slide/)).toBeInTheDocument()
    const strip = within(screen.getByRole('list', { name: 'Slides' }))
    expect(strip.getAllByText('doesn’t fit')).toHaveLength(1)
    await user.click(strip.getByRole('button', { name: /Forms/ }))
    expect(screen.getByText(/The table needs 400 points/)).toBeInTheDocument()
    expect(screen.getByText('Sizes: table 11 pt')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /PowerPoint/ })).toHaveAttribute('href', '/api/v1/exports/versions/v1?format=pptx')
  })

  it('edits, reorders and adds slides, measures as you edit and saves the draft', async () => {
    const d = version('DRAFT')
    const measured = { ...deck().fit!, ok: true, slides: deck().fit!.slides!.map((s) => ({ ...s, ok: true, problems: [] })) }
    const calls = mockApi([
      ['POST /projects/p1/slides/fit', measured],
      ['PUT /reviews/versions/v1', d],
      ['/reviews/resources/d1/events', []],
      ['/reviews/resources/d1', state(d)],
    ])
    const user = userEvent.setup()
    show('/slides/d1')
    const strip = within(await screen.findByRole('list', { name: 'Slides' }))
    await user.click(strip.getByRole('button', { name: /You will/ }))
    const bullets = screen.getByLabelText('Bullet points')
    await user.clear(bullets)
    await user.type(bullets, 'Spot anomalies{Enter}Reach 3NF')
    await user.click(screen.getByRole('button', { name: 'Move up' }))
    await user.selectOptions(screen.getByLabelText('Type of slide to add'), 'EXAM_TIPS')
    await user.click(screen.getByRole('button', { name: 'Add after' }))
    expect(screen.getByRole('heading', { name: 'Slide 2: Exam tips' })).toBeInTheDocument()
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/projects/p1/slides/fit'))).toBe(true), { timeout: 3000 })
    const sent = calls.find((c) => c.url.endsWith('/slides/fit'))!.body
    expect(sent.fit).toBeUndefined()
    await waitFor(() => expect(screen.getByText('All text fits its box at a readable size.')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy())
    const saved = calls.find((c) => c.method === 'PUT')!.body.content as Deck
    expect(saved.slides.map((s) => s.id).slice(0, 1)).toEqual(['b'])
    expect(saved.slides[0].bullets).toEqual(['Spot anomalies', 'Reach 3NF'])
    expect(saved.slides[1].type).toBe('EXAM_TIPS')
    expect(saved.slides).toHaveLength(5)
  })

  it('redoes one slide as a proposal you choose to keep', async () => {
    const d = version('DRAFT')
    const proposal = { slide: { id: 'b', type: 'OBJECTIVES', layout: 'BULLETS', title: 'Goals', bullets: ['New goal'], notes: 'Fresh notes' },
      fit: { checked: true, ok: true, slides: [{ id: 'b', layout: 'BULLETS', ok: true, problems: [], boxes: [] }] } }
    const calls = mockApi([
      ['POST /slides/d1/slides/b/regenerate', proposal],
      ['POST /projects/p1/slides/fit', deck().fit],
      ['/reviews/resources/d1/events', []],
      ['/reviews/resources/d1', state(d)],
      ...accounts,
    ])
    const user = userEvent.setup()
    show('/slides/d1')
    const strip = within(await screen.findByRole('list', { name: 'Slides' }))
    await user.click(strip.getByRole('button', { name: /You will/ }))
    await user.click(screen.getByRole('button', { name: 'Redo with AI' }))
    const dialog = within(screen.getByRole('dialog'))
    await screen.findByRole('option', { name: 'gpt-x' })
    await user.type(dialog.getByLabelText(/What to change/), 'Shorter')
    await user.click(dialog.getByRole('button', { name: 'Write a new version' }))
    expect(await dialog.findByText('Fresh notes')).toBeInTheDocument()
    expect(calls.find((c) => c.url.endsWith('/regenerate'))!.body).toMatchObject({ versionId: 'v1', accountId: 'acc1', model: 'gpt-x', guidance: 'Shorter' })
    expect(calls.some((c) => c.method === 'PUT')).toBe(false)
    await user.click(dialog.getByRole('button', { name: 'Use this slide' }))
    expect(screen.getByLabelText('Title')).toHaveValue('Goals')
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeInTheDocument()
  })

  it('in review, shows the slides read-only with speaker notes and the blocking checks', async () => {
    const d = version('IN_REVIEW', deck(), [{ code: 'seo-approved', ok: false, blocking: true, message: 'The topic’s SEO report isn’t approved' }])
    mockApi([['/reviews/resources/d1/events', []], ['/reviews/resources/d1', state(d)]])
    const user = userEvent.setup()
    show('/slides/d1')
    expect(await screen.findByText('The topic’s SEO report isn’t approved')).toBeInTheDocument()
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Redo with AI' })).not.toBeInTheDocument()
    await user.click(within(screen.getByRole('list', { name: 'Slides' })).getByRole('button', { name: /You will/ }))
    expect(screen.getByText('Start with a story.')).toBeInTheDocument()
    expect(screen.getByText('From the notes: Why normalise')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled()
  })
})

describe('slide helpers', () => {
  it('match the document service’s boxes and keep content when the layout changes', () => {
    expect(geometry('BULLETS').bullets).toEqual({ x: 43, y: 128, w: 874, h: 372 })
    expect(geometry('EXAMPLE').code).toEqual({ x: 43, y: 252, w: 874, h: 248 })
    const s = blankSlide('COMPARISON')
    expect(s.layout).toBe('TABLE')
    const b = withLayout({ ...s, bullets: ['kept'] }, 'BULLETS')
    expect(b.bullets).toEqual(['kept'])
    expect(b.table).toEqual(s.table)
    expect(move([1, 2, 3], 0, -1)).toEqual([1, 2, 3])
    expect(move([1, 2, 3], 2, -1)).toEqual([1, 3, 2])
  })
})
