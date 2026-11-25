import { Check, Copy, Link2, Plus, ShieldCheck, UserMinus, UserPlus } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Button, Dialog, ErrorNotice, Field, Input, Select, Spinner, StatusPill } from '../components/ui'
import { cn } from '../lib/cn'
import { formatDate, formatRelative } from '../lib/format'
import {
  device,
  linkUrl,
  seatsText,
  useInvite,
  useIssueLink,
  useLogins,
  usePersonAction,
  useSetSeats,
  useUpdatePerson,
  useUsers,
  type IssuedLink,
  type Person,
} from '../lib/people'
import { useAuthStatus } from '../lib/queries'
import type { SystemRole } from '../lib/types'
import { toast } from '../stores/ui'

const STATUS: Record<Person['status'], { label: string; tone: 'done' | 'progress' | 'muted' }> = {
  ACTIVE: { label: 'Active', tone: 'done' },
  INVITED: { label: 'Invited', tone: 'progress' },
  DEACTIVATED: { label: 'Deactivated', tone: 'muted' },
}

export function UsersPage() {
  const users = useUsers()
  const me = useAuthStatus().data?.user
  const [inviting, setInviting] = useState(false)
  const [editing, setEditing] = useState<Person>()
  const [link, setLink] = useState<IssuedLink>()
  const [confirm, setConfirm] = useState<Person>()
  const action = usePersonAction()
  const issue = useIssueLink()

  if (users.isPending) return <Spinner label="Loading people" />
  if (users.error) return <ErrorNotice error={users.error} onRetry={() => users.refetch()} />
  const { seats } = users.data
  const full = seats.used >= seats.limit

  return (
    <>
      <header className="flex flex-wrap items-end justify-between gap-4 mb-8">
        <div className="min-w-0">
          <h1 className="text-3xl sm:text-[2.15rem] font-semibold">Users</h1>
          <p className="text-muted mt-1 max-w-2xl">
            The people who can sign in. Each gets a one-time link to choose their password; what they see inside a project depends on their role there.
          </p>
        </div>
        <Button variant="primary" icon={<UserPlus className="size-4" />} onClick={() => setInviting(true)} disabled={full}>
          Invite someone
        </Button>
      </header>

      <SeatBar seats={seats} />

      <section aria-labelledby="people-heading" className="mt-8">
        <h2 id="people-heading" className="text-lg font-semibold mb-3">
          People
        </h2>
        <div className="rounded-2xl border border-line bg-surface overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-muted">
              <tr className="border-b border-line">
                <th scope="col" className="font-medium px-4 py-3">Name</th>
                <th scope="col" className="font-medium px-4 py-3">Role</th>
                <th scope="col" className="font-medium px-4 py-3">Status</th>
                <th scope="col" className="font-medium px-4 py-3">Two-step</th>
                <th scope="col" className="font-medium px-4 py-3">Last sign-in</th>
                <th scope="col" className="font-medium px-4 py-3"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {users.data.users.map((u) => {
                const self = u.email.toLowerCase() === me?.email.toLowerCase()
                return (
                  <tr key={u.id} className={cn(u.status === 'DEACTIVATED' && 'text-muted')}>
                    <td className="px-4 py-3 min-w-56">
                      <span className="block font-medium text-ink">
                        {u.displayName}
                        {self && <span className="text-faint font-normal"> (you)</span>}
                      </span>
                      <span className="block text-muted">{u.email}</span>
                    </td>
                    <td className="px-4 py-3 whitespace-nowrap">
                      {u.role === 'ADMIN' ? 'Administrator' : 'Member'}
                      {u.role === 'MEMBER' && !u.canCreateProjects && <span className="block text-xs text-faint">Can't create projects</span>}
                    </td>
                    <td className="px-4 py-3">
                      <StatusPill tone={STATUS[u.status].tone}>{STATUS[u.status].label}</StatusPill>
                    </td>
                    <td className="px-4 py-3">
                      {u.mfaEnabled ? (
                        <span className="inline-flex items-center gap-1">
                          <ShieldCheck className="size-4 text-peacock-500" aria-hidden /> On
                        </span>
                      ) : (
                        <span className="text-muted">Off</span>
                      )}
                    </td>
                    <td className="px-4 py-3 whitespace-nowrap text-muted">{u.lastLoginAt ? formatRelative(u.lastLoginAt) : 'Never'}</td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap justify-end gap-1.5">
                        <Button size="sm" variant="ghost" onClick={() => setEditing(u)}>
                          Edit
                        </Button>
                        {u.status === 'INVITED' && (
                          <Button size="sm" variant="ghost" icon={<Link2 className="size-4" />} loading={issue.isPending && issue.variables?.id === u.id}
                            onClick={() => issue.mutate({ id: u.id, kind: 'activation' }, { onSuccess: setLink, onError: (e) => toast.error(e.message) })}>
                            New link
                          </Button>
                        )}
                        {u.status === 'ACTIVE' && !self && (
                          <Button size="sm" variant="ghost" icon={<Link2 className="size-4" />} loading={issue.isPending && issue.variables?.id === u.id}
                            onClick={() => issue.mutate({ id: u.id, kind: 'reset' }, { onSuccess: setLink, onError: (e) => toast.error(e.message) })}>
                            Reset link
                          </Button>
                        )}
                        {u.status === 'DEACTIVATED' ? (
                          <Button size="sm" variant="ghost" disabled={full} title={full ? 'All seats are taken' : undefined}
                            onClick={() => action.mutate({ id: u.id, action: 'reactivate' }, { onSuccess: (p) => toast.success(`${p.displayName} can sign in again`), onError: (e) => toast.error(e.message) })}>
                            Reactivate
                          </Button>
                        ) : (
                          !self && (
                            <Button size="sm" variant="ghost" icon={<UserMinus className="size-4" />} onClick={() => setConfirm(u)}>
                              Deactivate
                            </Button>
                          )
                        )}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </section>

      <SignInHistory people={users.data.users} />

      {inviting && <InviteDialog onClose={() => setInviting(false)} onLink={(l) => { setInviting(false); setLink(l) }} />}
      {editing && <EditDialog person={editing} self={editing.email.toLowerCase() === me?.email.toLowerCase()} onClose={() => setEditing(undefined)} />}
      {link && <LinkDialog link={link} onClose={() => setLink(undefined)} />}
      <Dialog
        open={!!confirm}
        title={`Deactivate ${confirm?.displayName ?? ''}?`}
        onClose={() => setConfirm(undefined)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirm(undefined)}>
              Keep active
            </Button>
            <Button
              variant="danger"
              loading={action.isPending}
              onClick={() =>
                confirm &&
                action.mutate(
                  { id: confirm.id, action: 'deactivate' },
                  { onSuccess: (p) => { toast.success(`${p.displayName} is deactivated`); setConfirm(undefined) }, onError: (e) => toast.error(e.message) },
                )
              }
            >
              Deactivate
            </Button>
          </>
        }
      >
        <p className="text-muted">
          They're signed out at once and can't sign in again. Their work, history and project roles stay, and their seat is freed. You can reactivate them later.
        </p>
      </Dialog>
    </>
  )
}

function SeatBar({ seats }: { seats: { used: number; limit: number } }) {
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState(String(seats.limit))
  const set = useSetSeats()
  const pct = Math.min(100, Math.round((seats.used / Math.max(seats.limit, 1)) * 100))

  function save(e: FormEvent) {
    e.preventDefault()
    const n = Number(value)
    if (!Number.isInteger(n) || n < 1 || n > 50) return toast.error('Choose a number from 1 to 50')
    set.mutate(n, { onSuccess: () => { toast.success(`Seat limit is now ${n}`); setEditing(false) }, onError: (err) => toast.error(err.message) })
  }

  return (
    <section aria-label="Seats" className="rounded-2xl border border-line bg-surface p-5 flex flex-wrap items-center gap-x-6 gap-y-3">
      <div className="flex-1 min-w-60">
        <p className="font-medium">{seatsText(seats)}</p>
        <div className="mt-2 h-2 rounded-full bg-track overflow-hidden" role="meter" aria-label="Seats in use" aria-valuemin={0} aria-valuemax={seats.limit} aria-valuenow={seats.used}>
          <div className={cn('h-full rounded-full', pct >= 100 ? 'bg-marigold-500' : 'bg-peacock-500')} style={{ width: `${pct}%` }} />
        </div>
        <p className="text-xs text-faint mt-2">Active and invited people take a seat. Deactivated people don't.</p>
      </div>
      {editing ? (
        <form onSubmit={save} className="flex items-end gap-2">
          <div className="w-28">
            <Field label="Seat limit">{(id) => <Input id={id} type="number" min={1} max={50} value={value} onChange={(e) => setValue(e.target.value)} />}</Field>
          </div>
          <Button type="submit" variant="primary" loading={set.isPending}>
            Save
          </Button>
          <Button type="button" variant="ghost" onClick={() => setEditing(false)}>
            Cancel
          </Button>
        </form>
      ) : (
        <Button variant="ghost" onClick={() => { setValue(String(seats.limit)); setEditing(true) }}>
          Change limit
        </Button>
      )}
    </section>
  )
}

function RoleChoice({ role, setRole, canCreate, setCanCreate }: { role: SystemRole; setRole: (r: SystemRole) => void; canCreate: boolean; setCanCreate: (v: boolean) => void }) {
  return (
    <>
      <Field label="Role" hint={role === 'ADMIN' ? 'Sees every project and manages people, AI keys, channels and backups.' : 'Sees only the projects they are added to, in the role given there.'}>
        {(id, d) => (
          <Select id={id} aria-describedby={d} value={role} onChange={(e) => setRole(e.target.value as SystemRole)}>
            <option value="MEMBER">Member</option>
            <option value="ADMIN">Administrator</option>
          </Select>
        )}
      </Field>
      {role === 'MEMBER' && (
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" className="accent-marigold-500" checked={canCreate} onChange={(e) => setCanCreate(e.target.checked)} />
          Can create their own projects
        </label>
      )}
    </>
  )
}

function InviteDialog({ onClose, onLink }: { onClose: () => void; onLink: (l: IssuedLink) => void }) {
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<SystemRole>('MEMBER')
  const [canCreate, setCanCreate] = useState(true)
  const invite = useInvite()

  function submit(e: FormEvent) {
    e.preventDefault()
    invite.mutate(
      { displayName: name.trim(), email: email.trim(), role, canCreateProjects: role === 'ADMIN' || canCreate },
      { onSuccess: onLink, onError: (err) => toast.error(err.message) },
    )
  }

  return (
    <Dialog open title="Invite someone" onClose={onClose}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <Field label="Name">{(id) => <Input id={id} maxLength={80} value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" />}</Field>
        <Field label="Email" hint="They sign in with it. No email is sent: you'll get a link to pass on.">
          {(id, d) => <Input id={id} aria-describedby={d} type="email" maxLength={254} value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="off" />}
        </Field>
        <RoleChoice role={role} setRole={setRole} canCreate={canCreate} setCanCreate={setCanCreate} />
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" icon={<Plus className="size-4" />} loading={invite.isPending} disabled={!name.trim() || !email.includes('@')}>
            Invite and make link
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

function EditDialog({ person, self, onClose }: { person: Person; self: boolean; onClose: () => void }) {
  const [name, setName] = useState(person.displayName)
  const [role, setRole] = useState<SystemRole>(person.role)
  const [canCreate, setCanCreate] = useState(person.canCreateProjects)
  const update = useUpdatePerson()
  return (
    <Dialog
      open
      title={`Edit ${person.displayName}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="primary"
            loading={update.isPending}
            disabled={!name.trim()}
            onClick={() =>
              update.mutate(
                { id: person.id, displayName: name.trim(), role, canCreateProjects: role === 'ADMIN' || canCreate },
                { onSuccess: () => { toast.success('Saved'); onClose() }, onError: (e) => toast.error(e.message) },
              )
            }
          >
            Save
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <p className="text-sm text-muted">{person.email}</p>
        <Field label="Name">{(id) => <Input id={id} maxLength={80} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
        <RoleChoice role={role} setRole={setRole} canCreate={canCreate} setCanCreate={setCanCreate} />
        {self && role === 'MEMBER' && person.role === 'ADMIN' && (
          <p className="text-sm text-muted" role="note">You'll lose access to this page as soon as you save.</p>
        )}
      </div>
    </Dialog>
  )
}

function LinkDialog({ link, onClose }: { link: IssuedLink; onClose: () => void }) {
  const url = linkUrl(link.path)
  const [copied, setCopied] = useState(false)
  const activating = link.path.startsWith('/activate')
  return (
    <Dialog
      open
      title={activating ? `Link for ${link.user.displayName}` : `Password reset for ${link.user.displayName}`}
      onClose={onClose}
      footer={<Button variant="primary" onClick={onClose}>Done</Button>}
    >
      <div className="flex flex-col gap-4">
        <p className="text-muted">
          Send this to {link.user.displayName} through a channel you trust. It works once, until {formatDate(link.expiresAt)}. It won't be shown again: if it's lost, make a new one.
        </p>
        <div className="flex gap-2">
          <label htmlFor="issued-link" className="sr-only">One-time link</label>
          <Input id="issued-link" readOnly value={url} onFocus={(e) => e.currentTarget.select()} className="font-mono text-xs" />
          <Button
            icon={copied ? <Check className="size-4" /> : <Copy className="size-4" />}
            onClick={() =>
              navigator.clipboard?.writeText(url).then(
                () => { setCopied(true); toast.success('Link copied') },
                () => toast.error("Copying isn't allowed here. Select the link instead."),
              )
            }
          >
            {copied ? 'Copied' : 'Copy'}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}

function SignInHistory({ people }: { people: Person[] }) {
  const [who, setWho] = useState('')
  const logins = useLogins(who || undefined)
  const name = (id?: string) => people.find((p) => p.id === id)?.displayName
  return (
    <section aria-labelledby="history-heading" className="mt-10">
      <div className="flex flex-wrap items-end justify-between gap-3 mb-3">
        <h2 id="history-heading" className="text-lg font-semibold">
          Sign-in history
        </h2>
        <div className="w-56">
          <label htmlFor="history-person" className="sr-only">Show sign-ins for</label>
          <Select id="history-person" value={who} onChange={(e) => setWho(e.target.value)}>
            <option value="">Everyone</option>
            {people.map((p) => (
              <option key={p.id} value={p.id}>
                {p.displayName}
              </option>
            ))}
          </Select>
        </div>
      </div>
      {logins.isPending && <Spinner label="Loading sign-ins" />}
      {logins.error && <ErrorNotice error={logins.error} onRetry={() => logins.refetch()} />}
      {logins.data && logins.data.items.length === 0 && <p className="text-muted text-sm">No sign-ins recorded yet.</p>}
      {logins.data && logins.data.items.length > 0 && (
        <div className="rounded-2xl border border-line bg-surface overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-muted">
              <tr className="border-b border-line">
                <th scope="col" className="font-medium px-4 py-2.5">When</th>
                <th scope="col" className="font-medium px-4 py-2.5">Who</th>
                <th scope="col" className="font-medium px-4 py-2.5">Result</th>
                <th scope="col" className="font-medium px-4 py-2.5">Address</th>
                <th scope="col" className="font-medium px-4 py-2.5">Device</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {logins.data.items.map((l) => (
                <tr key={l.id}>
                  <td className="px-4 py-2.5 whitespace-nowrap">{formatRelative(l.createdAt)}</td>
                  <td className="px-4 py-2.5">{name(l.userId) ?? l.email}</td>
                  <td className="px-4 py-2.5">
                    {l.success ? 'Signed in' : l.reason === 'account_not_active' ? 'Refused: account not active' : l.reason === 'wrong_code' ? 'Wrong two-step code' : 'Wrong password'}
                  </td>
                  <td className="px-4 py-2.5 text-muted tabular">{l.ipAddress ?? ''}</td>
                  <td className="px-4 py-2.5 text-muted">{device(l.userAgent)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
