import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { ImageGeneratorPage } from './ImageGeneratorPage'
import { SubtitlesPage } from './SubtitlesPage'
import { ThumbnailsPage } from './ThumbnailsPage'
import { VoiceStudioPage } from './VoiceStudioPage'

// jsdom has no canvas; the Konva renderer is covered by the thumbnail helper tests and a browser check.
vi.mock('../components/ThumbnailCanvas', () => ({
  ThumbnailCanvas: () => <div data-testid="canvas" />,
  exportStage: vi.fn(),
  loadMediaImage: vi.fn(),
}))

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) { this.removeAttribute('open') }
})

const eleven = {
  id: 'el1', provider: 'ELEVENLABS', providerName: 'ElevenLabs', capabilities: ['VOICE', 'TRANSCRIPTION', 'SOUND'], balanceApi: true,
  name: 'My ElevenLabs', status: 'ACTIVE', isDefault: true, maskedKey: '••••abcd', balances: [], totals: [], lowCredit: false,
  expiringSoon: false, createdAt: '2026-11-19T00:00:00Z',
}
const voiceModels = [
  { id: 'v1', provider: 'ELEVENLABS', modelCode: 'eleven_v3', displayName: 'Eleven v3', capability: 'VOICE', enabled: true, options: { maxChars: 4800 }, pricing: {} },
]
const audio = {
  id: 'a1', kind: 'AUDIO', source: 'AI_GENERATED', filename: 'வணக்கம்.mp3', contentType: 'audio/mpeg', sizeBytes: 1000, durationMs: 4200,
  createdAt: '2026-11-19T00:00:00Z', url: 'https://media.example/a1',
}

type Route = unknown | ((init?: RequestInit) => unknown)

function mockApi(routes: Record<string, Route>) {
  const calls: { url: string; init?: RequestInit }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    calls.push({ url, init })
    for (const [k, v] of Object.entries(routes)) {
      const [method, path] = k.includes(' ') ? k.split(' ') : ['GET', k]
      if (url.includes(path) && (init?.method ?? 'GET') === method) {
        const body = typeof v === 'function' ? (v as (i?: RequestInit) => unknown)(init) : v
        return new Response(JSON.stringify(body), { status: 200 })
      }
    }
    return new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'not mocked' }), { status: 404 })
  })
  return calls
}

function renderPage(ui: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('Voice & audio', () => {
  it('asks to connect an account when there is none', async () => {
    mockApi({ '/ai/accounts': [], '/voice/profiles': [], '/ai/models': [] })
    renderPage(<VoiceStudioPage />)
    expect(await screen.findByText('Connect a voice account first')).toBeInTheDocument()
  })

  it('makes a Tamil voice-over with a provider voice and shows the result', async () => {
    const calls = mockApi({
      '/ai/accounts': [eleven],
      '/voice/profiles': [],
      '/ai/models': voiceModels,
      '/voice/voices': [{ id: 'voiceA', name: 'Aria', gender: 'female', cloned: false }],
      '/projects': { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 },
      'POST /voice/narrations': { media: audio, chunks: 1, characters: 7 },
    })
    renderPage(<VoiceStudioPage />)
    await screen.findByRole('option', { name: /Aria/ })
    await userEvent.type(screen.getByLabelText('Script'), 'வணக்கம்')
    await userEvent.click(screen.getByRole('button', { name: 'Make voice-over' }))
    expect(await screen.findByText('வணக்கம்.mp3')).toBeInTheDocument()
    const post = calls.find((c) => c.url.endsWith('/voice/narrations'))!
    const body = JSON.parse(String(post.init!.body))
    expect(body).toMatchObject({ voice: { accountId: 'el1', voiceId: 'voiceA' }, text: 'வணக்கம்', language: 'TA' })
    // No secret ever goes to the browser: only ids are sent.
    expect(JSON.stringify(body)).not.toMatch(/key/i)
  })

  it('keeps the clone button disabled until consent is given and typed', async () => {
    mockApi({
      '/ai/accounts': [eleven],
      '/voice/profiles': [],
      '/voice/clone/consent': { statement: 'I confirm this is my own voice and I consent to creating a private AI clone of it.' },
    })
    renderPage(<VoiceStudioPage />)
    await userEvent.click((await screen.findAllByRole('tab', { name: 'My voices' }))[0])
    const create = await screen.findByRole('button', { name: 'Create my voice' })
    expect(create).toBeDisabled()
    await userEvent.click(screen.getByLabelText('These recordings are of my own voice.'))
    await userEvent.type(screen.getByLabelText('Type this sentence to confirm'), 'I confirm')
    expect(create).toBeDisabled()
  })
})

describe('Subtitles', () => {
  it('starts with a file picker', async () => {
    mockApi({ '/ai/accounts': [eleven] })
    renderPage(<SubtitlesPage />)
    expect(screen.getByText('Pick a video or audio file')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Choose from library' })).toBeInTheDocument()
  })
})

describe('Image generator', () => {
  it('asks for an image account when only voice accounts exist', async () => {
    mockApi({ '/ai/accounts': [eleven], '/ai/models': [] })
    renderPage(<ImageGeneratorPage />)
    expect(await screen.findByText('Connect an image account first')).toBeInTheDocument()
  })
})

describe('Thumbnail studio', () => {
  it('shows built-in templates when creating a design', async () => {
    mockApi({ '/thumbnails?templates=false': [], '/thumbnails?templates=true': [] })
    renderPage(<ThumbnailsPage />)
    expect(await screen.findByText('No designs yet')).toBeInTheDocument()
    await userEvent.click(screen.getAllByRole('button', { name: 'New design' })[0])
    expect(await screen.findByRole('radio', { name: /Bold headline/ })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Blank' })).toHaveAttribute('aria-checked', 'true')
  })
})
