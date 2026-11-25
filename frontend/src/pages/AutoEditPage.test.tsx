import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { AutoEditPage } from './AutoEditPage'

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) { this.removeAttribute('open') }
})

const openai = {
  id: 'oa', provider: 'OPENAI', providerName: 'OpenAI', capabilities: ['TEXT', 'IMAGE', 'TRANSCRIPTION'], balanceApi: false,
  name: 'OpenAI', status: 'ACTIVE', isDefault: true, maskedKey: '••••', balances: [], totals: [], lowCredit: false, expiringSoon: false, createdAt: '2026-11-19T00:00:00Z',
}
const done = {
  id: 'j1', mode: 'HIGHLIGHTS', name: 'Podcast ep 4', status: 'COMPLETED', progress: 1, settings: {},
  result: { timelineIds: ['t1', 't2'], notes: ['Found 2 of 3 requested moments.'] }, createdAt: '2026-11-19T00:00:00Z',
}
const running = { id: 'j2', mode: 'TALKING_HEAD', name: 'Vlog', status: 'RUNNING', step: 'Listening to vlog.mp4', progress: 0.4, settings: {}, createdAt: '2026-11-19T00:00:00Z' }

function mock(posts: { url: string; body: unknown }[]) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    if (init?.method === 'POST') {
      posts.push({ url, body: JSON.parse(String(init.body)) })
      return new Response(JSON.stringify({ ...running, id: 'new' }), { status: 202 })
    }
    if (url.includes('/auto-edit/jobs')) return new Response(JSON.stringify([running, done]))
    if (url.includes('/ai/accounts/oa/models')) return new Response(JSON.stringify(['gpt-5.3']))
    if (url.includes('/ai/accounts')) return new Response(JSON.stringify([openai]))
    if (url.includes('/voice/profiles')) return new Response(JSON.stringify([]))
    if (url.includes('/ai/models')) return new Response(JSON.stringify([]))
    if (url.includes('/projects')) return new Response(JSON.stringify({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 }))
    return new Response('{}', { status: 404 })
  })
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <AutoEditPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('AI auto editor', () => {
  it('shows running and finished edits with links into the editor', async () => {
    mock([])
    renderPage()
    expect(await screen.findByText('Listening to vlog.mp4')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open short 1/ })).toHaveAttribute('href', '/edit/timeline/t1')
    expect(screen.getByText('Found 2 of 3 requested moments.')).toBeInTheDocument()
  })

  it('starts a script-to-video edit with the chosen AI model', async () => {
    const posts: { url: string; body: unknown }[] = []
    mock(posts)
    renderPage()
    const start = await screen.findByRole('button', { name: 'Start automatic edit' })
    expect(start).toBeDisabled()
    await userEvent.type(screen.getByRole('textbox', { name: 'Name' }), 'Madurai food')
    await userEvent.type(screen.getByRole('textbox', { name: 'Script' }), 'வணக்கம்! இன்று மதுரை.')
    await screen.findByRole('option', { name: 'gpt-5.3' })
    await userEvent.click(start)
    expect(posts).toHaveLength(1)
    expect(posts[0].body).toMatchObject({
      mode: 'SCRIPT_TO_VIDEO', name: 'Madurai food', language: 'TA', format: 'LANDSCAPE', script: 'வணக்கம்! இன்று மதுரை.',
      textAccountId: 'oa', textModel: 'gpt-5.3', captions: true, footageIds: [],
    })
  })

  it('switches to vertical for highlights and asks for one video', async () => {
    mock([])
    renderPage()
    await userEvent.click(await screen.findByRole('radio', { name: /Shorts from a long video/ }))
    expect(screen.getByLabelText('Format')).toHaveValue('PORTRAIT')
    expect(screen.getByText(/Still needed: a name, a video/)).toBeInTheDocument()
  })
})
