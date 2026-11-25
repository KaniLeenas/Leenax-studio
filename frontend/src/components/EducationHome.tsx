import { AlertTriangle, CheckCircle2, ClipboardCheck, Loader2, Upload } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { formatRelative } from '../lib/format'
import {
  ACTION_NAME,
  KIND_NAME,
  resourceLink,
  taskLink,
  taskText,
  useEducationDashboard,
  type DashTask,
  type EducationCounts,
} from '../lib/notifications'
import { ErrorNotice, Spinner } from './ui'

/**
 * Module 02 on the dashboard (E10): university work counted in the database for the projects
 * you can see, what's waiting for you, running jobs and recent decisions.
 */
export function EducationHome() {
  const q = useEducationDashboard()
  if (q.isPending) return <Spinner label="Counting your university work" />
  if (q.error) return <ErrorNotice error={q.error} onRetry={() => q.refetch()} />
  const d = q.data
  if (d.counts.subjectProjects === 0 && d.tasks.length === 0) return null
  return (
    <div className="flex flex-col gap-8">
      <Tasks tasks={d.tasks} />
      <Counts c={d.counts} />
      <div className="grid lg:grid-cols-2 gap-8">
        <section aria-labelledby="active-work" className="min-w-0">
          <h2 id="active-work" className="text-xl font-semibold mb-3">Running now</h2>
          {d.active.length === 0 ? <p className="text-sm text-muted">No AI jobs or renders are running.</p> : (
            <ul className="rounded-2xl border border-line bg-surface divide-y divide-[var(--line)]">
              {d.active.map((a, i) => (
                <li key={i} className="px-5 py-3 flex items-center gap-3 text-sm">
                  <Loader2 aria-hidden className="size-4 animate-spin text-marigold-600 shrink-0 motion-reduce:animate-none" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate">{a.kind === 'RENDER' ? `Rendering “${a.what}”` : `${KIND_NAME[a.what] ?? a.what}${a.status === 'QUEUED' ? ' (waiting)' : ''}`}</span>
                    <span className="block text-xs text-faint truncate">{a.projectName ?? 'No project'} · started {formatRelative(a.startedAt)}</span>
                  </span>
                  {a.progress != null && <span className="text-xs text-muted tabular">{Math.round(a.progress * 100)}%</span>}
                </li>
              ))}
            </ul>
          )}
        </section>
        <section aria-labelledby="recent-decisions" className="min-w-0">
          <h2 id="recent-decisions" className="text-xl font-semibold mb-3">Recent decisions</h2>
          {d.activity.length === 0 ? <p className="text-sm text-muted">Nothing has been sent for review yet.</p> : (
            <ul className="rounded-2xl border border-line bg-surface divide-y divide-[var(--line)]">
              {d.activity.map((a, i) => (
                <li key={i}>
                  <Link to={resourceLink(a.resourceKind, a.projectId, a.resourceId)} className="block px-5 py-3 text-sm hover:bg-surface-2/60">
                    <span className="block truncate">
                      <span className="font-medium">{a.title}</span>{a.versionNo ? ` v${a.versionNo}` : ''} {ACTION_NAME[a.action] ?? a.action.toLowerCase()}
                      {a.byName ? ` by ${a.byName}` : ''}
                    </span>
                    <span className="block text-xs text-faint truncate">{a.projectName} · {formatRelative(a.at)}{a.comment ? ` · “${a.comment}”` : ''}</span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  )
}

const TASK_ICON: Record<DashTask['kind'], ReactNode> = {
  REVIEW: <ClipboardCheck aria-hidden className="size-4 text-peacock-600 dark:text-peacock-400 shrink-0 mt-0.5" />,
  CHANGES: <ClipboardCheck aria-hidden className="size-4 text-marigold-600 shrink-0 mt-0.5" />,
  PUBLISH: <Upload aria-hidden className="size-4 text-peacock-600 dark:text-peacock-400 shrink-0 mt-0.5" />,
  FAILED_JOB: <AlertTriangle aria-hidden className="size-4 text-rose-signal shrink-0 mt-0.5" />,
}

function Tasks({ tasks }: { tasks: DashTask[] }) {
  return (
    <section aria-labelledby="waiting">
      <h2 id="waiting" className="text-xl font-semibold mb-3">Waiting for you</h2>
      {tasks.length === 0 ? (
        <p className="text-sm text-muted flex items-center gap-2"><CheckCircle2 aria-hidden className="size-4 text-peacock-600 dark:text-peacock-400" />Nothing to review, change or publish.</p>
      ) : (
        <ul className="rounded-2xl border border-line bg-surface divide-y divide-[var(--line)]">
          {tasks.map((t, i) => (
            <li key={i}>
              <Link to={taskLink(t)} className="flex gap-3 px-5 py-3 hover:bg-surface-2/60 first:rounded-t-2xl last:rounded-b-2xl">
                {TASK_ICON[t.kind]}
                <span className="min-w-0 flex-1">
                  <span className="block text-sm break-words">{taskText(t)}</span>
                  <span className="block text-xs text-faint">{t.projectName}{t.since ? ` · ${formatRelative(t.since)}` : ''}</span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function Counts({ c }: { c: EducationCounts }) {
  const cells: [string, number, string?][] = [
    ['Subject projects', c.subjectProjects],
    ['Course documents', c.documents],
    ['Analysed modules', c.analysedModules, 'Approved syllabus reports'],
    ['Approved topics', c.approvedTopics, `${c.topicAnalyses} analysed, ${c.approvedTopicAnalyses} analyses approved`],
    ['Notes', c.notes, `${c.approvedNotes} approved`],
    ['MCQs', c.questions, 'In the newest version of each bank'],
    ['Academic QA reports', c.qaReports, `${c.approvedQaReports} approved`],
    ['Pending approvals', c.pendingApprovals, 'In review now'],
    ['SEO reports', c.approvedSeoReports, 'Approved'],
    ['Presentations', c.presentations, `${c.approvedPresentations} approved`],
    ['Videos', c.videos, `${c.approvedVideos} approved`],
    ['Published videos', c.publishedVideos, 'On YouTube'],
  ]
  return (
    <section aria-labelledby="uni-numbers">
      <div className="flex items-baseline justify-between gap-3 mb-3">
        <h2 id="uni-numbers" className="text-xl font-semibold">University work</h2>
        <p className="text-xs text-faint">Counted in the database, for the projects you can see. {c.activeJobs > 0 ? `${c.activeJobs} job${c.activeJobs === 1 ? '' : 's'} running.` : ''}</p>
      </div>
      <dl className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-px rounded-2xl border border-line bg-[var(--line)] overflow-hidden">
        {cells.map(([label, value, sub]) => (
          <div key={label} className="bg-surface px-5 py-4 min-w-0">
            <dt className="text-sm text-muted">{label}</dt>
            <dd className="font-display text-2xl font-semibold tabular mt-0.5">{value.toLocaleString()}</dd>
            {sub && <dd className="text-xs text-faint truncate">{sub}</dd>}
          </div>
        ))}
      </dl>
    </section>
  )
}
