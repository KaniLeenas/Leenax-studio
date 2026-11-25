import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { useToasts } from '../stores/ui'
import { CharactersPage } from './CharactersPage'
import { ScriptsPage } from './ScriptsPage'
import { StoryboardEditorPage } from './StoryboardEditorPage'

const base = { balanceApi: true, status: 'ACTIVE', isDefault: true, maskedKey: '••••1234', balances: [], totals: [], lowCredit: false, expiringSoon: false, createdAt: '2026-11-19T00:00:00Z' }
const openai = { ...base, id: 'oa', provider: 'OPENAI', providerName: 'OpenAI', capabilities: ['TEXT', 'IMAGE'], name: 'OpenAI' }
const runway = { ...base, id: 'rw', provider: 'RUNWAY', providerName: 'Runway', capabilities: ['VIDEO'], name: 'Runway' }
const models = [
  { id: 'img', provider: 'OPENAI', modelCode: 'gpt-image-1', displayName: 'GPT Image', capability: 'IMAGE', enabled: true, options: {}, pricing: {} },
  { id: 'gen4', provider: 'RUNWAY', modelCode: 'gen4_turbo', displayName: 'Gen-4 Turbo', capability: 'VIDEO', enabled: true, options: { modes: ['IMAGE_TO_VIDEO'], durations: { values: [5, 10] } }, pricing: {} },
]
const image = { id: 'img1', kind: 'IMAGE', source: 'AI_GENERATED', filename: 'scene.png', contentType: 'image/png', sizeBytes: 10, createdAt: '2026-11-20T00:00:00Z', url: 'https://media.example/img1' }

const script = {
  id: 's1', title: 'ஜிகர்தண்டா இரவு', language: 'TA', videoType: 'YOUTUBE_SHORT', idea: 'Night market', body: '[INTRO]\nவணக்கம்!\n\n[SCENE 1: Stall]\n(Visual: busy stall)\nஇது மதுரை.',
  sections: {}, metadata: { YOUTUBE: { title: 'Madurai at night', description: 'A night walk', hashtags: ['madurai'] } }, versionNo: 1, wordCount: 3, spokenMinutes: 0.03,
  createdAt: '2026-11-20T00:00:00Z', updatedAt: '2026-11-20T00:00:00Z',
}

function scene(id: string, position: number, extra: Record<string, unknown> = {}) {
  return {
    id, position, title: `Shot ${position + 1}`, description: 'Kani at the stall', narration: 'இது மதுரை.', dialogue: [], durationSeconds: 7.5,
    cameraAngle: 'CLOSE_UP', cameraMovement: 'DOLLY_IN', characterIds: [], suggestedImagePrompt: 'auto image prompt', suggestedVideoPrompt: 'auto video prompt', references: [], ...extra,
  }
}
const board = {
  id: 'b1', title: 'Madurai night', aspectRatio: 'PORTRAIT', language: 'TA', style: '35mm film', narrationParts: [], totalSeconds: 15,
  scenes: [scene('sc1', 0, { image }), scene('sc2', 1, { dialogue: [{ character: 'Meena', line: 'Welcome!' }] })],
  createdAt: '2026-11-20T00:00:00Z', updatedAt: '2026-11-20T00:00:00Z',
}

type Calls = { url: string; method: string; body?: unknown }[]
function mockApi(routes: [string, unknown | ((body: unknown) => unknown)][]) {
  const calls: Calls = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ url, method, body })
    for (const [k, v] of routes) {
      const [m, path] = k.includes(' ') ? k.split(' ') : ['GET', k]
      if (m === method && url.split('?')[0].endsWith(path)) return new Response(JSON.stringify(typeof v === 'function' ? (v as (b: unknown) => unknown)(body) : v))
    }
    return new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'not mocked' }), { status: 404 })
  })
  return calls
}

function renderAt(path: string, routePath: string, ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path={routePath} element={ui} />
          <Route path="*" element={<p>elsewhere</p>} />
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

describe('Scripts & ideas', () => {
  it('suggests ideas, fills the idea and writes a Tamil script with the chosen model', async () => {
    const calls = mockApi([
      ['/ai/accounts/oa/models', ['gpt-5', 'gpt-5-mini']],
      ['/ai/accounts', [openai]],
      ['/projects', { items: [], totalItems: 0, page: 0, totalPages: 0 }],
      ['/scripts', []],
      ['POST /scripts/ideas', { ideas: [{ title: 'Jigarthanda at midnight', hook: 'Ever tried it cold at 1am?', angle: 'One stall, one family' }] }],
      ['POST /scripts/write', { ...script, id: 's2' }],
      ['/scripts/s2', { ...script, id: 's2' }],
    ])
    const user = userEvent.setup()
    renderAt('/create/scripts', '/create/scripts', <ScriptsPage />)
    await screen.findByRole('option', { name: 'gpt-5' })
    await user.type(screen.getByLabelText('Topic'), 'Madurai food')
    await user.click(screen.getByRole('button', { name: 'Suggest 6 ideas' }))
    await user.click(await screen.findByRole('button', { name: 'Use this idea' }))
    expect(screen.getByLabelText(/Working title/)).toHaveValue('Jigarthanda at midnight')
    expect(screen.getByLabelText('Idea')).toHaveValue('Ever tried it cold at 1am?\nOne stall, one family')
    await user.click(screen.getByRole('button', { name: 'Write script' }))
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/scripts/write'))).toBe(true))
    const sent = calls.find((c) => c.url.endsWith('/scripts/write'))!.body as Record<string, unknown>
    expect(sent).toMatchObject({ writer: { accountId: 'oa', model: 'gpt-5' }, language: 'TA', videoType: 'YOUTUBE_SHORT', minutes: 1 })
    expect(calls.find((c) => c.url.endsWith('/scripts/ideas'))!.body).toMatchObject({ topic: 'Madurai food', count: 6 })
    expect(await screen.findByDisplayValue('ஜிகர்தண்டா இரவு')).toBeInTheDocument()
  })

  it('asks to save before rewriting, then makes a new version', async () => {
    const calls = mockApi([
      ['/ai/accounts/oa/models', ['gpt-5']],
      ['/ai/accounts', [openai]],
      ['/projects', { items: [], totalItems: 0, page: 0, totalPages: 0 }],
      ['/scripts', [script]],
      ['/scripts/s1', script],
      ['POST /scripts/s1/rewrite', { ...script, id: 's3', parentId: 's1', versionNo: 2 }],
      ['/scripts/s3', { ...script, id: 's3', parentId: 's1', versionNo: 2 }],
    ])
    const user = userEvent.setup()
    renderAt('/create/scripts?id=s1', '/create/scripts', <ScriptsPage />)
    const body = await screen.findByRole('textbox', { name: 'Script' })
    await screen.findByRole('option', { name: 'gpt-5' })
    expect(screen.getByText('Madurai at night')).toBeInTheDocument()                 // saved post text
    expect(screen.getByText('#madurai')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled()
    fireEvent.change(body, { target: { value: script.body + ' மேலும்' } })
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Make it shorter' }))
    expect(calls.some((c) => c.url.includes('/rewrite'))).toBe(false)
    expect(useToasts.getState().toasts.at(-1)?.message).toMatch(/Save your changes first/)
    fireEvent.change(body, { target: { value: script.body } })
    await user.click(screen.getByRole('button', { name: 'Make it shorter' }))
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/s1/rewrite'))).toBe(true))
    expect(calls.find((c) => c.url.endsWith('/s1/rewrite'))!.body).toEqual({ writer: { accountId: 'oa', model: 'gpt-5' }, instruction: 'Make it shorter' })
    expect(await screen.findByText(/Version 2/)).toBeInTheDocument()
  }, 15_000)
})

describe('Character studio', () => {
  it('adds a shared character with a voice', async () => {
    const calls = mockApi([
      ['/characters', []],
      ['POST /characters', (b: unknown) => ({ id: 'c1', references: [], createdAt: '', updatedAt: '', ...(b as object) })],
      ['/projects', { items: [], totalItems: 0, page: 0, totalPages: 0 }],
      ['/voice/profiles', [{ id: 'vp1', accountId: 'el', provider: 'ELEVENLABS', providerVoiceId: 'x', name: 'Meena voice', kind: 'STOCK', settings: {}, sampleMediaIds: [], createdAt: '' }]],
    ])
    const user = userEvent.setup()
    renderAt('/create/characters', '/create/characters', <CharactersPage />)
    expect(await screen.findByText('No characters yet')).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: 'Add character' })[0])
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Meena')
    await user.type(within(dialog).getByLabelText('Appearance'), 'Silver hair in a bun')
    await within(dialog).findByRole('option', { name: 'Meena voice' })
    await user.selectOptions(within(dialog).getByLabelText('Voice'), 'vp1')
    await user.click(within(dialog).getAllByRole('button', { name: 'Add character' }).at(-1)!)
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/characters'))).toBe(true))
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      name: 'Meena', appearance: 'Silver hair in a bun', voiceProfileId: 'vp1', referenceMediaIds: [],
    })
  })
})

describe('Storyboard editor', () => {
  function routes(extra: [string, unknown][] = []): [string, unknown][] {
    return [
      ...extra,
      ['/storyboards/b1', board],
      ['/characters', [{ id: 'meena', name: 'Meena', references: [], createdAt: '', updatedAt: '' }]],
      ['/ai/accounts', [openai, runway]],
      ['/ai/models', models],
      ['/projects', { items: [], totalItems: 0, page: 0, totalPages: 0 }],
    ]
  }

  it('shows scenes and blocks voicing lines until the speaker has a voice', async () => {
    mockApi(routes())
    renderAt('/create/storyboard/b1', '/create/storyboard/:id', <StoryboardEditorPage />)
    expect(await screen.findByRole('heading', { name: 'Madurai night' })).toBeInTheDocument()
    expect(screen.getByDisplayValue('Shot 1')).toBeInTheDocument()
    expect(screen.getByText(/1 of 2/)).toBeInTheDocument()                           // images
    expect(screen.getByRole('button', { name: 'Voice the lines' })).toBeDisabled()
    expect(screen.getByText(/Give Meena a voice/i)).toBeInTheDocument()
  })

  it('animates the still at a length the model accepts', async () => {
    const calls = mockApi(routes([['POST /storyboards/scenes/sc1/video', { ...board, scenes: [scene('sc1', 0, { image, videoJob: { id: 'j', status: 'QUEUED', provider: 'Runway', model: 'Gen-4 Turbo' } }), board.scenes[1]] }]]))
    const user = userEvent.setup()
    renderAt('/create/storyboard/b1', '/create/storyboard/:id', <StoryboardEditorPage />)
    await screen.findByRole('heading', { name: 'Madurai night' })
    await screen.findByRole('option', { name: 'Gen-4 Turbo' })
    const first = screen.getAllByRole('article')[0]
    await waitFor(() => expect(within(first).getByRole('button', { name: 'Make video' })).toBeEnabled())
    expect(within(screen.getAllByRole('article')[1]).getByRole('button', { name: 'Make video' })).toBeDisabled() // no image, model is image-to-video only
    await user.click(within(first).getByRole('button', { name: 'Make video' }))
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/scenes/sc1/video'))).toBe(true))
    expect(calls.find((c) => c.url.endsWith('/scenes/sc1/video'))!.body).toEqual({ accountId: 'rw', modelId: 'gen4', mode: 'IMAGE_TO_VIDEO', durationSeconds: 10 })
    expect(await within(screen.getAllByRole('article')[0]).findByText(/Making video/)).toBeInTheDocument()
  })

  it('saves scene edits and parses dialogue lines', async () => {
    const calls = mockApi(routes([['PUT /storyboards/scenes/sc2', board]]))
    const user = userEvent.setup()
    renderAt('/create/storyboard/b1', '/create/storyboard/:id', <StoryboardEditorPage />)
    await screen.findByRole('heading', { name: 'Madurai night' })
    const second = screen.getAllByRole('article')[1]
    const save = within(second).getByRole('button', { name: 'Save scene' })
    expect(save).toBeDisabled()
    const dialogue = within(second).getByLabelText('Dialogue')
    await user.type(dialogue, '{enter}Kani: Two glasses')
    await user.click(within(second).getByRole('button', { name: 'Meena' }))
    await user.click(save)
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    expect(calls.find((c) => c.method === 'PUT')!.body).toMatchObject({
      dialogue: [{ character: 'Meena', line: 'Welcome!' }, { character: 'Kani', line: 'Two glasses' }],
      characterIds: ['meena'],
      durationSeconds: 7.5,
    })
  })
})
