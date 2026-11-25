import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { useToasts } from '../stores/ui'
import { DocumentReaderPage } from './DocumentReaderPage'
import { DocumentsPage } from './DocumentsPage'

type Calls = { url: string; method: string; body?: unknown }[]
function mockApi(routes: [string, unknown][]) {
  const calls: Calls = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ url, method, body })
    for (const [k, v] of routes) {
      const [m, path] = k.includes(' ') ? k.split(' ') : ['GET', k]
      if (m === method && url.split('?')[0].endsWith(path)) return new Response(JSON.stringify(v))
    }
    return new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'not mocked' }), { status: 404 })
  })
  return calls
}

function show(path: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/projects/:id/documents" element={<DocumentsPage />} />
          <Route path="/documents/:id" element={<DocumentReaderPage />} />
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

const project = { id: 'p1', name: 'Database Systems', kind: 'EDUCATION', subject: { moduleCode: 'CSC2041', subjectName: 'Database Systems' }, status: 'IN_PROGRESS', myRole: 'EDITOR' }
const version = (p: object) => ({
  id: 'v1', versionNo: 1, originalFilename: 'lesson-scan.pdf', contentType: 'application/pdf', sizeBytes: 226378, status: 'READY',
  pageCount: 2, pagesRead: 2, ocrPages: 2, ocrConfidence: 95.2, language: 'mixed', moduleCodes: ['CSC2041'], forceOcr: false,
  createdAt: '2026-11-23T10:00:00Z', processedAt: '2026-11-23T10:01:00Z', uploadedBy: 'Kani', ...p,
})
const doc = (p: object) => ({
  id: 'd1', projectId: 'p1', title: 'Lecture 3 Normalisation', createdAt: '2026-11-23T10:00:00Z', updatedAt: '2026-11-23T10:01:00Z',
  createdBy: 'Kani', versionCount: 1, latest: version({}), subject: { result: 'MATCH', found: ['CSC2041'], projectCode: 'CSC2041' }, ...p,
})

describe('Course documents', () => {
  it('lists documents with how they were read, and searches them in Tamil', async () => {
    const calls = mockApi([
      ['/projects/p1/documents/search', {
        query: 'தரவு', words: ['தரவு'], more: false,
        hits: [{ documentId: 'd1', title: 'Lecture 3 Normalisation', versionId: 'v1', versionNo: 1, pageNo: 1,
          snippet: { text: 'தரவுத்தள முறைமைகள் பற்றிய', cutBefore: false, cutAfter: true, highlights: [{ start: 0, end: 8 }] } }],
      }],
      ['/projects/p1/documents', { serviceReady: true, canEdit: true, documents: [
        doc({}),
        doc({ id: 'd2', title: 'Past paper 2024', latest: version({ id: 'v9', status: 'FAILED', error: "This PDF can't be opened (damaged or password-protected)" }), subject: { result: 'UNKNOWN', found: [] } }),
        doc({ id: 'd3', title: 'IT tutorial', latest: version({ id: 'v8', ocrPages: 0, language: 'en', moduleCodes: ['IT1050'] }), subject: { result: 'DIFFERENT', found: ['IT1050'], projectCode: 'CSC2041' } }),
      ] }],
      ['/projects/p1', project],
    ])
    const user = userEvent.setup()
    show('/projects/p1/documents')
    expect(await screen.findByText('2 pages, all read by OCR, English and Tamil')).toBeInTheDocument()
    expect(screen.getByText("Couldn't read it: This PDF can't be opened (damaged or password-protected)")).toBeInTheDocument()
    expect(screen.getByText("Mentions IT1050, but this project is CSC2041. Check it's the right subject.")).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add documents' })).toBeInTheDocument()
    expect(screen.queryByText(/isn't running on this server/)).toBeNull()

    await user.type(screen.getByRole('searchbox', { name: 'Search these documents' }), 'தரவு')
    const results = await screen.findByRole('region', { name: 'Search results' })
    expect(within(results).getByText('1 passage')).toBeInTheDocument()
    expect(within(results).getByText('தரவுத்தள').tagName).toBe('MARK')
    expect(within(results).getByRole('link')).toHaveAttribute('href', '/documents/d1?version=v1&page=1')
    expect(calls.some((c) => c.url.includes('/documents/search?q=' + encodeURIComponent('தரவு')))).toBe(true)
  })

  it('viewers can read and search but not add; a missing reader is explained', async () => {
    mockApi([
      ['/projects/p1/documents', { serviceReady: false, canEdit: false, documents: [doc({ latest: version({ status: 'QUEUED', ocrPages: null }) })] }],
      ['/projects/p1', { ...project, myRole: 'VIEWER' }],
    ])
    show('/projects/p1/documents')
    expect(await screen.findByText(/new files are stored but not read yet/)).toBeInTheDocument()
    expect(screen.getByText("Waiting: the document reader isn't running on this server")).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add documents' })).toBeNull()
    expect(screen.getByRole('searchbox')).toBeInTheDocument()
  })
})

describe('Document reader', () => {
  const detail = (canEdit: boolean) => ({
    document: doc({ versionCount: 2, latest: version({ id: 'v2', versionNo: 2, status: 'FAILED', error: 'damaged' }) }),
    versions: [version({ id: 'v2', versionNo: 2, status: 'FAILED', error: 'damaged', originalFilename: 'lesson-v2.pdf' }), version({})],
    canEdit,
  })

  it('opens a search hit at its page and reads it again with OCR', async () => {
    const calls = mockApi([
      ['/documents/versions/v1/pages', { versionId: 'v1', versionNo: 1, pageCount: 2, from: 2, more: false,
        pages: [{ pageNo: 2, method: 'OCR', ocrConfidence: 95.0, text: 'Page two covers transactions.\n\nபரிவர்த்தனைகள் மற்றும் ஒருங்கமைவு' }] }],
      ['POST /documents/versions/v2/reprocess', detail(true)],
      ['/documents/d1', detail(true)],
    ])
    const user = userEvent.setup()
    show('/documents/d1?version=v1&page=2')
    expect(await screen.findByRole('heading', { name: /^Page 2\s*of 2$/ })).toBeInTheDocument()
    expect(screen.getByText('Read by OCR, 95% confident')).toBeInTheDocument()
    expect(screen.getByText(/பரிவர்த்தனைகள் மற்றும்/)).not.toHaveAttribute('lang') // mixed English and Tamil: no single language
    expect(calls.some((c) => c.url.endsWith('/documents/versions/v1/pages?from=2'))).toBe(true)
    // the newer version failed: the reader shows the last good one, and the failed one can be read again
    const v2 = screen.getByText('lesson-v2.pdf').closest('li') as HTMLElement
    expect(within(v2).getByText("Couldn't read it: damaged")).toBeInTheDocument()
    await user.click(within(v2).getByRole('button', { name: 'Read with OCR' }))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ forceOcr: true })
    expect(screen.getByRole('button', { name: 'Earlier pages' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Later pages' })).toBeDisabled()
  })

  it('viewers get no rename, delete, re-read or upload', async () => {
    mockApi([
      ['/documents/versions/v1/pages', { versionId: 'v1', versionNo: 1, pageCount: 2, from: 1, more: false, pages: [{ pageNo: 1, method: 'TEXT', text: 'CSC2041 Database Systems' }] }],
      ['/documents/d1', detail(false)],
    ])
    show('/documents/d1')
    expect(await screen.findByText('CSC2041 Database Systems')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Rename' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Read again' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Upload new version' })).toBeNull()
    expect(screen.getAllByRole('button', { name: 'Download' })).toHaveLength(2)
  })
})
