import { UserPlus, X } from 'lucide-react'
import { useState } from 'react'
import { canOwnProject, PROJECT_ROLES, useChangeMembers, useColleagues, useProjectMembers, type Member } from '../lib/people'
import { useAuthStatus } from '../lib/queries'
import type { Project, ProjectRole } from '../lib/types'
import { toast } from '../stores/ui'
import { Button, ErrorNotice, Input, Select, Spinner } from './ui'

/** Who is in the project and in what role. Owners and administrators change it; anyone may leave. */
export function ProjectMembersPanel({ project, onLeft }: { project: Project; onLeft?: () => void }) {
  const members = useProjectMembers(project.id)
  const me = useAuthStatus().data?.user
  const manage = canOwnProject(project.myRole)
  const [colleagueSearch, setColleagueSearch] = useState('')
  const colleagues = useColleagues(manage, colleagueSearch)
  const change = useChangeMembers(project.id)
  const [adding, setAdding] = useState('')
  const [role, setRole] = useState<ProjectRole>('EDITOR')

  if (members.isPending) return <Spinner label="Loading members" />
  if (members.error) return <ErrorNotice error={members.error} onRetry={() => members.refetch()} />
  const list = members.data
  const owners = list.filter((m) => m.role === 'OWNER').length
  const available = (colleagues.data?.items ?? []).filter((c) => !list.some((m) => m.userId === c.id))
  const isMe = (m: Member) => m.email.toLowerCase() === me?.email.toLowerCase()

  const run = (c: Parameters<typeof change.mutate>[0], ok: string, after?: () => void) =>
    change.mutate(c, { onSuccess: () => { toast.success(ok); after?.() }, onError: (e) => toast.error(e.message) })

  return (
    <div className="flex flex-col gap-4">
      <ul className="divide-y divide-line rounded-2xl border border-line bg-surface">
        {list.map((m) => {
          const lastOwner = m.role === 'OWNER' && owners <= 1
          return (
            <li key={m.userId} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <div className="flex-1 min-w-48">
                <p className="font-medium">
                  {m.displayName}
                  {isMe(m) && <span className="text-faint font-normal"> (you)</span>}
                  {m.status !== 'ACTIVE' && <span className="text-faint font-normal text-sm"> {m.status === 'INVITED' ? 'invited' : 'deactivated'}</span>}
                </p>
                <p className="text-sm text-muted">{m.email}</p>
              </div>
              {manage ? (
                <div className="w-40">
                  <label htmlFor={`role-${m.userId}`} className="sr-only">
                    Role of {m.displayName}
                  </label>
                  <Select
                    id={`role-${m.userId}`}
                    value={m.role}
                    disabled={change.isPending || lastOwner}
                    title={lastOwner ? 'A project needs at least one owner' : undefined}
                    onChange={(e) => run({ kind: 'role', userId: m.userId, role: e.target.value as ProjectRole }, `${m.displayName} is now ${e.target.value.toLowerCase()}`)}
                  >
                    {PROJECT_ROLES.map((r) => (
                      <option key={r.id} value={r.id}>
                        {r.label}
                      </option>
                    ))}
                  </Select>
                </div>
              ) : (
                <span className="text-sm">{PROJECT_ROLES.find((r) => r.id === m.role)?.label}</span>
              )}
              {!((manage || isMe(m)) && !lastOwner) && <span className="size-8" aria-hidden />}
              {(manage || isMe(m)) && !lastOwner && (
                <button
                  type="button"
                  className="size-8 grid place-items-center rounded-lg text-muted hover:text-ink hover:bg-surface-2"
                  aria-label={isMe(m) ? 'Leave this project' : `Remove ${m.displayName}`}
                  title={isMe(m) ? 'Leave this project' : `Remove ${m.displayName}`}
                  onClick={() => run({ kind: 'remove', userId: m.userId }, isMe(m) ? 'You left the project' : `Removed ${m.displayName}`, isMe(m) ? onLeft : undefined)}
                >
                  <X className="size-4" />
                </button>
              )}
            </li>
          )
        })}
      </ul>

      {manage && (
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            if (adding) run({ kind: 'add', userId: adding, role }, 'Added', () => setAdding(''))
          }}
        >
          <div className="w-56">
            <label htmlFor="add-member" className="text-sm font-medium block mb-1.5">
              Add someone
            </label>
            <Input aria-label="Search active people" value={colleagueSearch} onChange={(e) => { setColleagueSearch(e.target.value); setAdding('') }} placeholder="Search name or email" />
            <Select id="add-member" className="mt-2" value={adding} onChange={(e) => setAdding(e.target.value)} disabled={available.length === 0}>
              <option value="">{available.length === 0 ? 'No matching people' : 'Choose a person'}</option>
              {available.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.displayName}
                </option>
              ))}
            </Select>
          </div>
          <div className="w-40">
            <label htmlFor="add-role" className="text-sm font-medium block mb-1.5">
              As
            </label>
            <Select id="add-role" value={role} onChange={(e) => setRole(e.target.value as ProjectRole)}>
              {PROJECT_ROLES.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.label}
                </option>
              ))}
            </Select>
          </div>
          <Button type="submit" icon={<UserPlus className="size-4" />} loading={change.isPending && change.variables?.kind === 'add'} disabled={!adding}>
            Add
          </Button>
        </form>
      )}
      <dl className="grid sm:grid-cols-2 gap-x-6 gap-y-1 text-sm">
        {PROJECT_ROLES.map((r) => (
          <div key={r.id} className="flex gap-2">
            <dt className="font-medium w-20 shrink-0">{r.label}</dt>
            <dd className="text-muted">{r.can}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}
