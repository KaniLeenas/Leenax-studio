import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EducationHome } from '../components/EducationHome'
import { NotificationsButton } from '../components/Notifications'
import { resourceLink, taskLink, taskText, type DashTask } from '../lib/notifications'

type Call = { url: string; method: string }
function mockApi(routes: [string, unknown][]) {
  const calls: Call[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push({ url, method })
    for (const [k, v] of routes) {
      const [m, path] = k.includes(' ') ? k.split(' ') : ['GET', k]
      if (m === method && url.split('?')[0].endsWith(path)) {
        const body = typeof v === 'function' ? v() : v
        return body === undefined ? new Response(null, { status: 204 }) : new Response(JSON.stringify(body), { status: 200 })
      }
    }
    return new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'not mocked ' + url }), { status: 404 })
  })
  return calls
}

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function show(ui: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/']}>
        <Routes><Route path="*" element={<>{ui}<Where /></>} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

const items = [
  { id: 'n1', projectId: 'p1', projectName: 'Circuits', kind: 'REVIEW_REQUESTED', title: 'Asha asked you to review “Ohm notes”', body: 'Circuits', link: '/notes/r1', createdAt: new Date().toISOString() },
  { id: 'n2', projectId: 'p1', projectName: 'Circuits', kind: 'JOB_FAILED', title: 'The notes failed', body: 'The AI account has no credit (Circuits)', link: '/projects/p1/materials', createdAt: new Date().toISOString() },
  { id: 'n3', kind: 'PUBLISHED', title: '“Ohm’s Law Explained” is on YouTube (Leenax Lessons)', link: 'https://www.youtube.com/watch?v=v1', createdAt: new Date().toISOString(), readAt: new Date().toISOString() },
]

describe('notifications', () => {
  it('shows the unread count, opens the latest, and marks one read when it is opened', async () => {
    let unread = 2
    const calls = mockApi([
      ['POST /notifications/n1/read', () => { unread = 1; return undefined }],
      ['POST /notifications/read', () => { unread = 0; return undefined }],
      ['/notifications/unread', () => unread],
      ['/notifications', () => ({ items, unread })],
    ])
    const user = userEvent.setup()
    show(<NotificationsButton placement="sidebar" />)
    const bell = await screen.findByRole('button', { name: 'Notifications, 2 unread' })
    await user.click(bell)
    const panel = await screen.findByRole('dialog', { name: 'Notifications' })
    await within(panel).findByText('Asha asked you to review “Ohm notes”')
    expect(within(panel).getByText('The AI account has no credit (Circuits)')).toBeTruthy()
    expect(within(panel).getByRole('link', { name: /is on YouTube/ }).getAttribute('target')).toBe('_blank')
    await user.click(within(panel).getByRole('link', { name: /Asha asked you to review/ }))
    expect(screen.getByTestId('where').textContent).toBe('/notes/r1')
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/notifications/n1/read'))).toBe(true))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Notifications, 1 unread' })).toBeTruthy())
    await user.click(screen.getByRole('button', { name: /Notifications/ }))
    await user.click(await screen.findByRole('button', { name: 'Mark all read' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Notifications' })).toBeTruthy())
  })

  it('closes with Escape', async () => {
    mockApi([['/notifications/unread', 0], ['/notifications', { items: [], unread: 0 }]])
    const user = userEvent.setup()
    show(<NotificationsButton placement="topbar" />)
    await user.click(await screen.findByRole('button', { name: 'Notifications' }))
    await screen.findByText(/Nothing yet/)
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

const overview = {
  counts: { subjectProjects: 2, documents: 7, analysedModules: 1, approvedTopics: 14, notes: 9, approvedNotes: 6, questions: 120, qaReports: 4,
    approvedQaReports: 3, pendingApprovals: 2, approvedSeoReports: 3, presentations: 5, approvedPresentations: 4, videos: 3, approvedVideos: 2,
    publishedVideos: 1, activeJobs: 1, topicAnalyses: 5, approvedTopicAnalyses: 3 },
  tasks: [
    { kind: 'REVIEW', projectId: 'p1', projectName: 'Circuits', resourceId: 'r1', resourceKind: 'NOTES', title: 'Ohm notes', versionNo: 2, since: new Date().toISOString(), byName: 'Asha' },
    { kind: 'PUBLISH', projectId: 'p1', projectName: 'Circuits', resourceId: 'v1', resourceKind: 'LESSON_VIDEO', title: 'Ohm lesson video', versionNo: 1, since: new Date().toISOString() },
  ],
  active: [{ kind: 'RENDER', projectId: 'p1', projectName: 'Circuits', what: 'Ohm lesson', status: 'RENDERING', progress: 0.4, startedAt: new Date().toISOString(), target: 'v1' }],
  activity: [{ at: new Date().toISOString(), action: 'CHANGES_REQUESTED', resourceKind: 'SEO', resourceId: 's1', title: 'SEO report: Ohm', projectId: 'p1', projectName: 'Circuits', byName: 'Kani', versionNo: 3, comment: 'Shorter titles' }],
}

describe('the education dashboard', () => {
  it('shows real counts, what is waiting for you, running work and recent decisions', async () => {
    mockApi([['/dashboard/education', overview]])
    const user = userEvent.setup()
    show(<EducationHome />)
    await screen.findByText('Review notes: Ohm notes (by Asha)')
    expect(screen.getByText('120')).toBeTruthy()
    expect(screen.getByText('6 approved')).toBeTruthy()
    expect(screen.getByText(/1 job running/)).toBeTruthy()
    expect(screen.getByText('Rendering “Ohm lesson”')).toBeTruthy()
    expect(screen.getByText('40%')).toBeTruthy()
    expect(screen.getByText(/sent back for changes by Kani/)).toBeTruthy()
    await user.click(screen.getByText('Ready to publish on YouTube: Ohm lesson video'))
    expect(screen.getByTestId('where').textContent).toBe('/projects/p1/youtube')
  })

  it('stays out of the way with no university projects', async () => {
    mockApi([['/dashboard/education', { ...overview, counts: { ...overview.counts, subjectProjects: 0 }, tasks: [] }]])
    const { container } = show(<EducationHome />)
    await waitFor(() => expect(container.querySelector('[aria-labelledby="uni-numbers"]')).toBeNull())
    await waitFor(() => expect(screen.queryByText(/Counting/)).toBeNull())
  })

  it('links each task and resource to where it is done', () => {
    const t = (o: Partial<DashTask>): DashTask => ({ kind: 'REVIEW', projectId: 'p1', projectName: 'P', resourceKind: 'NOTES', title: 'x', ...o })
    expect(taskLink(t({ resourceId: 'r1' }))).toBe('/notes/r1')
    expect(taskLink(t({ kind: 'FAILED_JOB', resourceKind: 'QA' }))).toBe('/projects/p1/qa')
    expect(taskLink(t({ kind: 'FAILED_JOB', resourceKind: 'OTHER' }))).toBe('/projects/p1')
    expect(resourceLink('TOPICS', 'p1')).toBe('/projects/p1/topics')
    expect(resourceLink('QUIZ_VIDEO', 'p1', 'q1')).toBe('/videos/q1')
    expect(taskText(t({ kind: 'CHANGES', title: 'Topic list' }))).toBe('Make the changes asked for: Topic list')
  })
})
