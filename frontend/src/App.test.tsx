import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'

function renderApp(status: object, path = '/') {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = String(input)
    if (url.endsWith('/auth/status')) return new Response(JSON.stringify(status), { status: 200 })
    if (url.endsWith('/dashboard'))
      return new Response(
        JSON.stringify({
          stats: { totalProjects: 0, activeProjects: 0, completedProjects: 0, videos: 0, images: 0, audio: 0, storageBytes: 0 },
          recentProjects: [],
          recentMedia: [],
        }),
        { status: 200 },
      )
    return new Response('{}', { status: 404 })
  })
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.restoreAllMocks())

describe('App auth gate', () => {
  it('shows owner setup on first run', async () => {
    renderApp({ setupRequired: true, setupEnabled: true, authenticated: false })
    expect(await screen.findByRole('heading', { name: 'Set up your studio' })).toBeInTheDocument()
    expect(screen.getByLabelText('Setup token')).toBeInTheDocument()
  })

  it('explains how to enable setup when the token is missing', async () => {
    renderApp({ setupRequired: true, setupEnabled: false, authenticated: false })
    expect(await screen.findByText(/switched off on this server/)).toBeInTheDocument()
  })

  it('shows sign-in when signed out', async () => {
    renderApp({ setupRequired: false, setupEnabled: true, authenticated: false })
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('shows the dashboard when signed in', async () => {
    renderApp({ setupRequired: false, setupEnabled: true, authenticated: true, user: { email: 'k@example.com', displayName: 'Kani R' } })
    expect(await screen.findByText(/, Kani$/)).toBeInTheDocument()
    expect(await screen.findByText('Your timeline is empty')).toBeInTheDocument()
  })

  it('opens analytics now that every phase is built', async () => {
    renderApp({ setupRequired: false, setupEnabled: true, authenticated: true, user: { email: 'k@example.com', displayName: 'Kani' } }, '/analytics')
    expect(await screen.findByRole('heading', { name: 'Analytics' })).toBeInTheDocument()
    expect(screen.queryByText(/Arrives in phase/)).not.toBeInTheDocument()
  })
})
