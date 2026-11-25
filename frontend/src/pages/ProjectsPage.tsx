import { FolderPlus, Search } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { NewProjectDialog } from '../components/ProjectDialogs'
import { Button, EmptyState, ErrorNotice, Input, PageHeader, Spinner, StatusPill } from '../components/ui'
import { cn } from '../lib/cn'
import { formatRelative, LANGUAGE_LABEL, RATIO_LABEL, STATUS_LABEL } from '../lib/format'
import { useDebounced } from '../lib/hooks'
import { useAuthStatus, useProjects } from '../lib/queries'
import type { Project, ProjectStatus } from '../lib/types'

const FILTERS: { label: string; value?: ProjectStatus }[] = [
  { label: 'All' },
  { label: 'Drafts', value: 'DRAFT' },
  { label: 'In progress', value: 'IN_PROGRESS' },
  { label: 'Completed', value: 'COMPLETED' },
  { label: 'Archived', value: 'ARCHIVED' },
]

export function ProjectsPage() {
  const [status, setStatus] = useState<ProjectStatus>()
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [newOpen, setNewOpen] = useState(false)
  const canCreate = useAuthStatus().data?.user?.canCreateProjects !== false
  const debounced = useDebounced(search)
  const projects = useProjects({ status, search: debounced, page })


  return (
    <>
      <PageHeader
        title="My projects"
        description="Each project collects the footage, voice, images and edits for one video."
        actions={
          <Button variant="primary" icon={<FolderPlus className="size-4" />} onClick={() => setNewOpen(true)} disabled={!canCreate} title={canCreate ? undefined : 'Ask an administrator for permission to create projects'}>
            New project
          </Button>
        }
      />

      <div className="flex flex-wrap items-center gap-3 mb-6">
        <div role="tablist" aria-label="Filter by status" className="flex flex-wrap gap-1 rounded-xl bg-surface-2 p-1">
          {FILTERS.map((f) => (
            <button
              key={f.label}
              role="tab"
              aria-selected={status === f.value}
              onClick={() => {
                setStatus(f.value)
                setPage(0)
              }}
              className={cn(
                'px-3 h-8 rounded-lg text-sm',
                status === f.value ? 'bg-surface text-ink font-medium shadow-sm' : 'text-muted hover:text-ink',
              )}
            >
              {f.label}
            </button>
          ))}
        </div>
        <div className="relative ml-auto w-full sm:w-64">
          <Search className="size-4 absolute left-3 top-1/2 -translate-y-1/2 text-faint" aria-hidden />
          <Input
            aria-label="Search projects"
            placeholder="Search projects"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value)
              setPage(0)
            }}
            className="pl-9"
          />
        </div>
      </div>

      {projects.isPending && <Spinner label="Loading projects" />}
      {projects.error && <ErrorNotice error={projects.error} onRetry={() => projects.refetch()} />}
      {projects.data &&
        (projects.data.items.length === 0 ? (
          debounced || status ? (
            <EmptyState title="No matching projects" body="Try a different search or status filter." />
          ) : (
            <EmptyState
              title="Start your first project"
              body="Give it a working title. You can add footage, narration and thumbnails as you go."
              action={
                <Button variant="primary" onClick={() => setNewOpen(true)}>
                  New project
                </Button>
              }
            />
          )
        ) : (
          <>
            <ul className="grid sm:grid-cols-2 xl:grid-cols-3 gap-4">
              {projects.data.items.map((p) => (
                <ProjectCard key={p.id} project={p} />
              ))}
            </ul>
            {projects.data.totalPages > 1 && (
              <div className="flex items-center justify-center gap-3 mt-8">
                <Button size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                  Previous
                </Button>
                <span className="text-sm text-muted tabular">
                  Page {page + 1} of {projects.data.totalPages}
                </span>
                <Button size="sm" disabled={page + 1 >= projects.data.totalPages} onClick={() => setPage((p) => p + 1)}>
                  Next
                </Button>
              </div>
            )}
          </>
        ))}

      <NewProjectDialog open={newOpen} onClose={() => setNewOpen(false)} />
    </>
  )
}

/** Card shape mirrors the project's own frame: wide, tall or square. */
function ProjectCard({ project: p }: { project: Project }) {
  const frame =
    p.aspectRatio === 'PORTRAIT' ? 'w-6 h-10' : p.aspectRatio === 'SQUARE' ? 'w-8 h-8' : 'w-12 h-7'
  return (
    <li>
      <Link
        to={`/projects/${p.id}`}
        className="flex h-full flex-col rounded-2xl border border-line bg-surface p-5 hover:border-marigold-500/70 transition-colors"
      >
        <div className="flex items-start justify-between gap-3">
          <div className={cn('rounded-[5px] border-2 border-current text-faint shrink-0', frame)} aria-hidden />
          <StatusPill tone={p.status === 'IN_PROGRESS' ? 'progress' : p.status === 'COMPLETED' ? 'done' : p.status === 'ARCHIVED' ? 'muted' : 'neutral'}>
            {STATUS_LABEL[p.status]}
          </StatusPill>
        </div>
        <h2 className="mt-4 text-lg font-semibold leading-snug line-clamp-2">{p.name}</h2>
        {p.description && <p className="mt-1 text-sm text-muted line-clamp-2">{p.description}</p>}
        <p className="mt-auto pt-4 text-sm text-faint">
          {RATIO_LABEL[p.aspectRatio]}, {LANGUAGE_LABEL[p.language]}
          <br />
          {p.mediaCount} {p.mediaCount === 1 ? 'file' : 'files'}, updated {formatRelative(p.updatedAt)}
        </p>
      </Link>
    </li>
  )
}
