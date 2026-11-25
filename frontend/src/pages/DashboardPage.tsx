import { FolderPlus, Upload } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { MediaThumb } from '../components/media'
import { EducationHome } from '../components/EducationHome'
import { NewProjectDialog } from '../components/ProjectDialogs'
import { Button, EmptyState, ErrorNotice, Spinner, StatusPill } from '../components/ui'
import { formatBytes, formatDuration, formatRelative, RATIO_LABEL, STATUS_LABEL } from '../lib/format'
import { useAuthStatus, useDashboard } from '../lib/queries'
import type { DashboardSummary, MediaAsset } from '../lib/types'

function greeting(now = new Date()) {
  const h = now.getHours()
  return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening'
}

export function DashboardPage() {
  const { data: auth } = useAuthStatus()
  const dashboard = useDashboard()
  const [newOpen, setNewOpen] = useState(false)
  const canCreate = useAuthStatus().data?.user?.canCreateProjects !== false
  const navigate = useNavigate()

  const firstName = auth?.user?.displayName.split(' ')[0]

  return (
    <>
      <header className="flex flex-wrap items-end justify-between gap-4 mb-8">
        <div>
          <h1 className="text-3xl sm:text-[2.4rem] font-semibold">
            {greeting()}
            {firstName ? `, ${firstName}` : ''}
          </h1>
          <p className="text-muted mt-1">Here's what is on your timeline.</p>
        </div>
        <div className="flex gap-2">
          <Button icon={<Upload className="size-4" />} onClick={() => navigate('/media')}>
            Upload media
          </Button>
          <Button variant="primary" icon={<FolderPlus className="size-4" />} onClick={() => setNewOpen(true)} disabled={!canCreate} title={canCreate ? undefined : 'Ask an administrator for permission to create projects'}>
            New project
          </Button>
        </div>
      </header>

      {dashboard.isPending && <Spinner label="Loading your studio" />}
      {dashboard.error && <ErrorNotice error={dashboard.error} onRetry={() => dashboard.refetch()} />}
      {dashboard.data && <DashboardBody data={dashboard.data} onNewProject={() => setNewOpen(true)} />}

      <NewProjectDialog open={newOpen} onClose={() => setNewOpen(false)} />
    </>
  )
}

function DashboardBody({ data, onNewProject }: { data: DashboardSummary; onNewProject: () => void }) {
  const { stats } = data
  return (
    <div className="flex flex-col gap-12">
      <EducationHome />
      <RecentFootage items={data.recentMedia} />

      <section aria-label="Studio totals" className="grid grid-cols-2 md:grid-cols-4 rounded-2xl border border-line bg-surface divide-line">
        <Stat label="Active projects" value={stats.activeProjects} sub={`${stats.completedProjects} completed`} />
        <Stat label="Videos" value={stats.videos} />
        <Stat label="Images and audio" value={stats.images + stats.audio} sub={`${stats.images} images, ${stats.audio} audio`} />
        <Stat label="Storage used" value={formatBytes(stats.storageBytes)} />
      </section>

      <section>
        <div className="flex items-baseline justify-between mb-4">
          <h2 className="text-xl font-semibold">Recent projects</h2>
          <Link to="/projects" className="text-sm text-muted hover:text-ink underline-offset-4 hover:underline">
            All projects
          </Link>
        </div>
        {data.recentProjects.length === 0 ? (
          <EmptyState
            title="No projects yet"
            body="A project holds the footage, narration and thumbnails for one video."
            action={
              <Button variant="primary" onClick={onNewProject}>
                New project
              </Button>
            }
          />
        ) : (
          <ul className="rounded-2xl border border-line bg-surface divide-y divide-[var(--line)]">
            {data.recentProjects.map((p) => (
              <li key={p.id}>
                <Link to={`/projects/${p.id}`} className="flex items-center gap-4 px-5 py-4 hover:bg-surface-2/60 first:rounded-t-2xl last:rounded-b-2xl">
                  <div className="min-w-0 flex-1">
                    <p className="font-medium truncate">{p.name}</p>
                    <p className="text-sm text-faint">
                      {RATIO_LABEL[p.aspectRatio]}, {p.mediaCount} {p.mediaCount === 1 ? 'file' : 'files'}
                    </p>
                  </div>
                  <StatusPill tone={p.status === 'IN_PROGRESS' ? 'progress' : p.status === 'COMPLETED' ? 'done' : 'neutral'}>
                    {STATUS_LABEL[p.status]}
                  </StatusPill>
                  <span className="hidden sm:block text-sm text-faint w-28 text-right">{formatRelative(p.updatedAt)}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

function Stat({ label, value, sub }: { label: string; value: number | string; sub?: string }) {
  return (
    <div className="px-5 py-5 border-line [&:not(:first-child)]:border-l max-md:[&:nth-child(3)]:border-l-0 max-md:[&:nth-child(n+3)]:border-t">
      <p className="text-sm text-muted">{label}</p>
      <p className="font-display text-3xl font-semibold tabular mt-1">{value}</p>
      {sub && <p className="text-xs text-faint mt-0.5">{sub}</p>}
    </div>
  )
}

/**
 * The newest uploads laid out like clips on an edit timeline: a timecode ruler,
 * clips sized by duration, and the marigold playhead resting on the latest one.
 */
function RecentFootage({ items }: { items: MediaAsset[] }) {
  if (items.length === 0) {
    return (
      <section className="rounded-2xl bg-[#1e2240] text-[#e8eaf6] px-6 py-10 sm:px-10">
        <h2 className="text-2xl font-semibold">Your timeline is empty</h2>
        <p className="text-[#a3a8c6] mt-1 max-w-lg">
          Upload footage, images or audio and your latest files will line up here, newest first.
        </p>
        <Link
          to="/media"
          className="inline-flex mt-6 h-10 items-center px-4 rounded-lg bg-marigold-500 hover:bg-marigold-400 text-[#1e2240] font-semibold"
        >
          Upload media
        </Link>
      </section>
    )
  }

  const totalMs = items.reduce((sum, m) => sum + (m.durationMs ?? 5000), 0)
  const ticks = 5

  return (
    <section aria-labelledby="recent-footage" className="rounded-2xl bg-[#1e2240] text-[#e8eaf6] p-5 sm:p-7">
      <div className="flex items-baseline justify-between mb-5">
        <h2 id="recent-footage" className="text-xl font-semibold">
          Latest on the timeline
        </h2>
        <Link to="/media" className="text-sm text-[#a3a8c6] hover:text-white underline-offset-4 hover:underline">
          Media library
        </Link>
      </div>

      <div className="overflow-x-auto -mx-1 px-1 pb-1">
        <div className="relative min-w-[640px]">
          <div className="flex justify-between text-[0.72rem] text-[#a3a8c6]/70 tabular border-b border-white/10 pb-1.5 mb-3">
            {Array.from({ length: ticks }, (_, i) => (
              <span key={i}>{formatDuration((totalMs / (ticks - 1)) * i)}</span>
            ))}
          </div>

          <ol className="flex gap-1.5">
            {items.map((m) => (
              <li key={m.id} style={{ flexGrow: Math.max(m.durationMs ?? 5000, 2500) }} className="min-w-[96px] basis-0">
                <Link to="/media" className="block group" title={m.filename}>
                  <MediaThumb asset={m} className="h-24 rounded-md ring-1 ring-white/10 group-hover:ring-marigold-400" />
                  <p className="mt-1.5 truncate text-xs text-[#a3a8c6] group-hover:text-white">{m.filename}</p>
                </Link>
              </li>
            ))}
          </ol>

          <div aria-hidden className="absolute top-5 bottom-5 left-0 w-0.5 bg-marigold-500">
            <div className="absolute -top-1.5 -left-[5px] size-3 rotate-45 bg-marigold-500" />
          </div>
        </div>
      </div>
    </section>
  )
}
