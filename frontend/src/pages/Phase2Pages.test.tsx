import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { AiAccountsPage } from './AiAccountsPage'
import { VideoGeneratorPage } from './VideoGeneratorPage'

beforeAll(() => {
  // jsdom lacks <dialog> methods
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) { this.removeAttribute('open') }
})

const providers = [
  { code: 'RUNWAY', name: 'Runway', capabilities: ['VIDEO'], balanceApi: true, keyUrl: 'https://dev.runwayml.com', keyHelp: 'Runway key' },
  { code: 'OPENAI', name: 'OpenAI', capabilities: ['TEXT'], balanceApi: false, keyUrl: 'https://platform.openai.com', keyHelp: 'OpenAI key' },
]
const account = {
  id: 'acc1', provider: 'RUNWAY', providerName: 'Runway', capabilities: ['VIDEO'], balanceApi: true, name: 'Personal', status: 'ACTIVE',
  isDefault: true, maskedKey: '••••••••1234', balances: [], totals: [{ unit: 'CREDITS', amount: 1250 }], lowCredit: false,
  expiringSoon: false, createdAt: '2026-11-19T00:00:00Z',
}
const models = [
  {
    id: 'm1', provider: 'RUNWAY', modelCode: 'veo3.1', displayName: 'Google Veo 3.1 (via Runway)', capability: 'VIDEO', enabled: true,
    options: { modes: ['TEXT_TO_VIDEO', 'IMAGE_TO_VIDEO'], durations: { values: [4, 6, 8] }, defaultDuration: 8, audio: true,
      aspectRatios: { ALL: [{ value: '1920:1080', label: '16:9 · 1080p' }, { value: '1080:1920', label: '9:16 · 1080p' }] } },
    pricing: { unit: 'CREDITS', rates: [{ perSecond: 20, audio: false }, { perSecond: 40, audio: true }] },
  },
]

function mockApi(routes: Record<string, unknown>) {
  const calls: { url: string; init?: RequestInit }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    calls.push({ url, init })
    for (const [k, v] of Object.entries(routes)) {
      if (url.includes(k)) return new Response(JSON.stringify(v), { status: 200 })
    }
    return new Response('{}', { status: 404 })
  })
  return calls
}

function renderPage(ui: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.restoreAllMocks())

describe('AI accounts page', () => {
  it('lists accounts with masked keys and offers unconnected providers', async () => {
    mockApi({ '/ai/providers': providers, '/ai/accounts': [account] })
    renderPage(<AiAccountsPage />)
    expect(await screen.findByText('Personal')).toBeInTheDocument()
    expect(screen.getByText(/••••••••1234/)).toBeInTheDocument()
    expect(screen.getByText('1,250 credits')).toBeInTheDocument()
    const others = screen.getByRole('heading', { name: 'Other providers you can connect' }).parentElement!
    expect(within(others).getByText('OpenAI')).toBeInTheDocument()
  })

  it('sends the key once and never displays it', async () => {
    const calls = mockApi({ '/ai/providers': providers, '/ai/accounts': [] })
    renderPage(<AiAccountsPage />)
    await userEvent.click(await screen.findByRole('button', { name: 'Add your first account' }))
    const key = screen.getByLabelText('API key')
    expect(key).toHaveAttribute('type', 'password')
    await userEvent.type(key, 'rw_secret_key_9999')
    await userEvent.click(screen.getByRole('button', { name: 'Check and save' }))
    const post = calls.find((c) => c.init?.method === 'POST')!
    expect(JSON.parse(String(post.init!.body))).toMatchObject({ provider: 'RUNWAY', apiKey: 'rw_secret_key_9999' })
    expect(screen.queryByText('rw_secret_key_9999')).not.toBeInTheDocument()
  })
})

describe('Video generator', () => {
  it('asks to connect an account when none exist', async () => {
    mockApi({ '/ai/accounts': [], '/ai/models': models, '/projects': { items: [], page: 0, size: 24, totalItems: 0, totalPages: 0 } })
    renderPage(<VideoGeneratorPage />)
    expect(await screen.findByText('Connect a video account first')).toBeInTheDocument()
  })

  it('fills model defaults and shows the estimate', async () => {
    mockApi({
      '/ai/accounts': [account],
      '/ai/models': models,
      '/projects': { items: [], page: 0, size: 24, totalItems: 0, totalPages: 0 },
      '/ai/generation/jobs': { items: [], page: 0, size: 24, totalItems: 0, totalPages: 0 },
      '/ai/generation/video/estimate': { perVideo: 160, total: 160, unit: 'CREDITS', variations: 1, balance: 1250, balanceUnit: 'CREDITS', balanceAfter: 1090, lowCredit: false, insufficient: false, warnings: [] },
    })
    renderPage(<VideoGeneratorPage />)
    expect(await screen.findByRole('radio', { name: '8 s' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('radio', { name: '16:9 · 1080p' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByLabelText('Generate sound')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Describe the shot'), 'Temple at dawn')
    expect(await screen.findByText('160 credits', {}, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByText(/about 1,090 credits after/)).toBeInTheDocument()
  })
})
