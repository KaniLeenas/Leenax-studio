import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { ProjectMembersPanel } from '../components/ProjectMembers'
import { NewProjectDialog } from '../components/ProjectDialogs'
import type { Project } from '../lib/types'
import { useToasts } from '../stores/ui'
import { LinkPage, RegisterPage } from './AuthPages'
import { UsersPage } from './UsersPage'

const kani = { id: 'u1', email: 'kani@leenax.lk', displayName: 'Kani', role: 'ADMIN', status: 'ACTIVE', canCreateProjects: true, mfaEnabled: true, lastLoginAt: '2026-11-23T10:00:00Z', createdAt: '2026-11-01T00:00:00Z' }
const asha = { id: 'u2', email: 'asha@leenax.lk', displayName: 'Asha', role: 'MEMBER', status: 'ACTIVE', canCreateProjects: true, mfaEnabled: false, createdAt: '2026-11-20T00:00:00Z' }
const bala = { id: 'u3', email: 'bala@leenax.lk', displayName: 'Bala', role: 'MEMBER', status: 'INVITED', canCreateProjects: false, mfaEnabled: false, createdAt: '2026-11-22T00:00:00Z' }

type Calls = { url: string; method: string; body?: unknown }[]
function mockApi(routes: [string, unknown | ((b: unknown) => unknown), number?][], me = { id: 'u1', email: 'kani@leenax.lk', displayName: 'Kani', role: 'ADMIN' }) {
  const calls: Calls = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ url, method, body })
    if (url.endsWith('/auth/status')) return new Response(JSON.stringify({ setupRequired: false, setupEnabled: false, authenticated: true, user: me }))
    for (const [k, v, status] of routes) {
      const [m, path] = k.includes(' ') ? k.split(' ') : ['GET', k]
      if (m === method && url.split('?')[0].endsWith(path)) {
        return new Response(JSON.stringify(typeof v === 'function' ? (v as (b: unknown) => unknown)(body) : v), { status: status ?? 200 })
      }
    }
    return new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'not mocked' }), { status: 404 })
  })
  return calls
}

function show(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
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

const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalItems: items.length, totalPages: 1 })

describe('Users', () => {
  it('shows a paginated people directory and invites someone with a one-time link', async () => {
    const calls = mockApi([
      ['/users/logins', page([{ id: 'l1', userId: 'u1', email: 'kani@leenax.lk', success: true, ipAddress: '10.0.0.4', userAgent: 'Mozilla/5.0 (Windows NT 10.0) Chrome/131.0', createdAt: '2026-11-23T10:00:00Z' },
        { id: 'l2', email: 'bala@leenax.lk', success: false, reason: 'account_not_active', createdAt: '2026-11-23T09:00:00Z' }])],
      ['/users', { users: [kani, asha, bala], page: 0, size: 50, totalItems: 3, totalPages: 1 }],
      ['POST /users', (b: unknown) => ({ user: { ...bala, id: 'u4', ...(b as object), status: 'INVITED' }, path: '/activate?token=tok123456789012345678901', expiresAt: '2026-11-30T10:00:00Z' })],
    ])
    const user = userEvent.setup()
    show(<UsersPage />)
    expect(await screen.findByText('3 accounts')).toBeInTheDocument()
    const rows = screen.getAllByRole('row')
    expect(within(rows[1]).getByText('(you)')).toBeInTheDocument()
    expect(within(rows[1]).queryByRole('button', { name: 'Deactivate' })).toBeNull()      // not yourself
    expect(within(rows[3]).getByText('Invited')).toBeInTheDocument()
    expect(within(rows[3]).getByText("Can't create projects")).toBeInTheDocument()
    expect(await screen.findByText('Chrome on Windows')).toBeInTheDocument()
    expect(screen.getByText('Refused: account not active')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Invite someone' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Chitra')
    await user.type(within(dialog).getByLabelText('Email'), 'chitra@leenax.lk')
    await user.click(within(dialog).getByLabelText('Can create their own projects'))
    await user.click(within(dialog).getByRole('button', { name: 'Invite and make link' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/users'))).toBe(true))
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ displayName: 'Chitra', email: 'chitra@leenax.lk', role: 'MEMBER', canCreateProjects: false })
    expect(await screen.findByDisplayValue(`${window.location.origin}/activate?token=tok123456789012345678901`)).toBeInTheDocument()
    expect(screen.getByText(/It won't be shown again/)).toBeInTheDocument()
  })

  it('keeps invitations available and asks before deactivating', async () => {
    const calls = mockApi([
      ['/users/logins', page([])],
      ['/users', { users: [kani, asha], page: 0, size: 50, totalItems: 102, totalPages: 3 }],
      ['POST /users/u2/deactivate', { ...asha, status: 'DEACTIVATED' }],
    ])
    const user = userEvent.setup()
    show(<UsersPage />)
    expect(await screen.findByText('102 accounts')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Invite someone' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Deactivate' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/signed out at once/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Deactivate' }))
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/users/u2/deactivate'))).toBe(true))
  })
})

describe('Public registration', () => {
  it('shows registration only when enabled and asks the user to verify by email', async () => {
    mockApi([
      ['/auth/registration', { enabled: true }],
      ['POST /auth/register', { message: 'If this address can register, a verification link will be sent.' }, 202],
    ])
    const user = userEvent.setup()
    show(<RegisterPage />)
    await user.type(await screen.findByLabelText('Full name'), 'Asha')
    await user.type(screen.getByLabelText('Email address'), 'asha@example.com')
    await user.type(screen.getByLabelText('Password'), 'a long secure password')
    await user.type(screen.getByLabelText('Confirm password'), 'a long secure password')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeInTheDocument()
  })

  it('activates public registrations through email verification without resubmitting the password', async () => {
    const calls = mockApi([
      ['/auth/link', { purpose: 'ACTIVATE', email: 'asha@example.com', displayName: 'Asha', expiresAt: '2026-12-01T00:00:00Z', passwordChosen: true }],
      ['POST /auth/link', null, 200],
    ])
    const user = userEvent.setup()
    show(<LinkPage token="tok123456789012345678901" onDone={() => {}} />)
    await user.click(await screen.findByRole('button', { name: 'Verify email and activate' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/auth/link'))).toBe(true))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ token: 'tok123456789012345678901', password: '' })
    expect(await screen.findByRole('heading', { name: 'Your account is ready' })).toBeInTheDocument()
  })
})

describe('One-time links', () => {
  it('lets an invited person choose a password', async () => {
    const calls = mockApi([
      ['/auth/link', { purpose: 'ACTIVATE', email: 'bala@leenax.lk', displayName: 'Bala', expiresAt: '2026-11-30T00:00:00Z' }],
      ['POST /auth/link', {}],
    ])
    const user = userEvent.setup()
    const done = vi.fn()
    show(<LinkPage token="tok123456789012345678901" onDone={done} />)
    expect(await screen.findByRole('heading', { name: 'Welcome, Bala' })).toBeInTheDocument()
    await user.type(screen.getByLabelText('Password'), 'kolam at dawn 2026')
    await user.type(screen.getByLabelText('Confirm password'), 'kolam at dusk 2026')
    await user.click(screen.getByRole('button', { name: 'Activate account' }))
    expect(screen.getByRole('alert')).toHaveTextContent('do not match')
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
    await user.clear(screen.getByLabelText('Confirm password'))
    await user.type(screen.getByLabelText('Confirm password'), 'kolam at dawn 2026')
    await user.click(screen.getByRole('button', { name: 'Activate account' }))
    expect(await screen.findByRole('heading', { name: 'Your account is ready' })).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ token: 'tok123456789012345678901', password: 'kolam at dawn 2026' })
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(done).toHaveBeenCalled()
  })

  it('explains a used or expired link', async () => {
    mockApi([['/auth/link', { code: 'NOT_FOUND', message: 'This link has expired' }, 404]])
    show(<LinkPage token="tok123456789012345678901" onDone={() => {}} />)
    expect(await screen.findByRole('heading', { name: "This link doesn't work" })).toBeInTheDocument()
  })
})

const project = (myRole: Project['myRole']): Project => ({
  id: 'p1', name: 'DBMS', status: 'IN_PROGRESS', aspectRatio: 'LANDSCAPE', language: 'TA', kind: 'EDUCATION', myRole, mediaCount: 0,
  createdAt: '2026-11-20T00:00:00Z', updatedAt: '2026-11-23T00:00:00Z',
})
const members = [
  { userId: 'u1', displayName: 'Kani', email: 'kani@leenax.lk', role: 'OWNER', status: 'ACTIVE', addedAt: '' },
  { userId: 'u2', displayName: 'Asha', email: 'asha@leenax.lk', role: 'VIEWER', status: 'ACTIVE', addedAt: '' },
]

describe('Project members', () => {
  it('lets an owner add people and change roles, but never remove the last owner', async () => {
    const calls = mockApi([
      ['/projects/p1/members', members],
      ['/users/colleagues', page([{ id: 'u1', displayName: 'Kani', email: 'kani@leenax.lk' }, { id: 'u2', displayName: 'Asha', email: 'asha@leenax.lk' }, { id: 'u5', displayName: 'Devi', email: 'devi@leenax.lk' }])],
      ['POST /projects/p1/members', [...members, { userId: 'u5', displayName: 'Devi', email: 'devi@leenax.lk', role: 'REVIEWER', status: 'ACTIVE', addedAt: '' }]],
      ['PUT /projects/p1/members/u2', [members[0], { ...members[1], role: 'EDITOR' }]],
    ])
    const user = userEvent.setup()
    show(<ProjectMembersPanel project={project('OWNER')} />)
    expect(await screen.findByLabelText('Role of Kani')).toBeDisabled()                 // the only owner
    expect(screen.queryByRole('button', { name: 'Leave this project' })).toBeNull()
    await user.selectOptions(screen.getByLabelText('Role of Asha'), 'EDITOR')
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ role: 'EDITOR' })
    await screen.findByRole('option', { name: 'Devi' })
    expect(screen.queryByRole('option', { name: 'Asha' })).toBeNull()                 // already a member
    await user.selectOptions(screen.getByLabelText('Add someone'), 'u5')
    await user.selectOptions(screen.getByLabelText('As'), 'REVIEWER')
    await user.click(screen.getByRole('button', { name: 'Add' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ userId: 'u5', role: 'REVIEWER' })
  })

  it('shows a viewer the members without controls, and lets them leave', async () => {
    mockApi([['/projects/p1/members', members]], { id: 'u2', email: 'asha@leenax.lk', displayName: 'Asha', role: 'MEMBER' })
    show(<ProjectMembersPanel project={project('VIEWER')} />)
    expect(await screen.findByText('asha@leenax.lk')).toBeInTheDocument()
    expect(screen.queryByLabelText('Role of Kani')).toBeNull()
    expect(screen.queryByLabelText('Add someone')).toBeNull()
    expect(await screen.findByRole('button', { name: 'Leave this project' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove Kani' })).toBeNull()
  })
})

describe('New project', () => {
  it('records a university subject and warns about one that already exists', async () => {
    const calls = mockApi([
      ['/projects/similar', [{ id: 'p0', name: 'Database Management Systems', moduleCode: 'IT2040', academicYear: '2025/26', reason: 'Same module code' }]],
      ['POST /projects', (b: unknown) => ({ ...project('OWNER'), id: 'p9', ...(b as object) })],
    ])
    const user = userEvent.setup()
    show(<NewProjectDialog open onClose={() => {}} />)
    await user.click(screen.getByRole('radio', { name: 'University subject' }))
    await user.type(screen.getByLabelText('Project name'), 'DBMS 2026')
    await user.type(screen.getByLabelText('Module code'), 'IT2040')
    await user.type(screen.getByLabelText('Academic year'), '2026/27')
    expect(await screen.findByText('This may already exist')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Database Management Systems' })).toHaveAttribute('href', '/projects/p0')
    await user.click(screen.getByRole('button', { name: 'Create project' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')!.body).toMatchObject({
      name: 'DBMS 2026', kind: 'EDUCATION', subject: { moduleCode: 'IT2040', academicYear: '2026/27' },
    })
  })
})
