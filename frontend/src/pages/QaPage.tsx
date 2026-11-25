import { ChevronLeft } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Button, EmptyState, ErrorNotice, PageHeader, Spinner, StatusPill } from '../components/ui'
import { cn } from '../lib/cn'
import { LEVEL_LABEL } from '../lib/materials'
import { HEADLINE_LABEL, useMakeReport, useQaOverview, type Headline, type QaItem } from '../lib/qa'
import { useProject } from '../lib/queries'
import { STATUS_LABEL, statusTone, type ReviewStatus } from '../lib/reviews'
import { toast } from '../stores/ui'

/** /projects/:id/qa — QA status of every topic's notes and questions, and each topic's QA report. */
export function QaPage() {
  const { id = '' } = useParams()
  const project = useProject(id)
  const data = useQaOverview(id)
  const make = useMakeReport(id)
  const navigate = useNavigate()

  if (project.isPending || data.isPending) return <Spinner label="Loading QA" />
  if (project.error || data.error) return <ErrorNotice error={project.error ?? data.error} onRetry={() => data.refetch()} />
  const p = project.data
  const d = data.data
  const withWork = d.topics.filter((t) => t.items.length > 0)

  return (
    <>
      <Link to={`/projects/${p.id}/materials`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Notes and questions
      </Link>
      <PageHeader
        title="Academic QA"
        description="Each topic’s notes and questions are checked claim by claim against the course documents. A topic’s QA report brings the results together for approval."
      />
      {!d.topicsApproved || withWork.length === 0 ? (
        <EmptyState
          title="Nothing to check yet"
          body="QA checks notes and question banks. Write some for an approved topic first."
          action={<Link className="underline underline-offset-2" to={`/projects/${id}/materials`}>Go to notes and questions</Link>}
        />
      ) : (
        <ul className="divide-y divide-line border-y border-line">
          {withWork.map((t) => (
            <li key={t.topicId} className="py-4 grid gap-3 lg:grid-cols-[minmax(0,16rem)_1fr_16rem] lg:items-start">
              <p className={cn('break-words', t.depth === 1 ? 'font-semibold' : 'font-medium')}>{t.title}</p>
              <ul className="space-y-1" aria-label={`QA for ${t.title}`}>
                {t.items.map((i) => <ItemLine key={i.resourceId} item={i} />)}
              </ul>
              <div className="text-sm">
                {t.reportId ? (
                  <Link to={`/qa-report/${t.reportId}`} className="inline-flex flex-wrap items-center gap-2 hover:underline">
                    QA report, version {t.reportVersionNo}
                    {t.reportStatus && <StatusPill tone={statusTone(t.reportStatus as ReviewStatus)}>{STATUS_LABEL[t.reportStatus as ReviewStatus]}</StatusPill>}
                  </Link>
                ) : (
                  <p className="text-muted">No QA report yet</p>
                )}
                {t.reportHeadline && <p className={cn('mt-0.5', t.reportHeadline === 'BLOCKED' && 'text-rose-signal font-medium')}>{HEADLINE_LABEL[t.reportHeadline as Headline]}</p>}
                {d.canEdit && t.reportStatus !== 'IN_REVIEW' && (
                  <Button
                    className="mt-2 -ml-3"
                    size="sm"
                    variant="ghost"
                    loading={make.isPending && make.variables === t.topicId}
                    onClick={() => make.mutate(t.topicId, {
                      onSuccess: (s) => { toast.success('QA report made'); navigate(`/qa-report/${s.resource!.id}`) },
                      onError: (e) => toast.error(e.message),
                    })}
                  >
                    {t.reportId ? 'Make it again' : 'Make the QA report'}
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

function ItemLine({ item: i }: { item: QaItem }) {
  const name = i.kind === 'MCQ' ? 'Question bank' : `${LEVEL_LABEL[i.level!] ?? i.level} notes`
  const crit = i.open.CRITICAL ?? 0, major = i.open.MAJOR ?? 0, minor = i.open.MINOR ?? 0
  const state = i.running ? 'QA running…'
    : i.checkedVersionNo == null ? 'not checked'
    : i.current ? `version ${i.versionNo} checked`
    : `checked version ${i.checkedVersionNo}, now version ${i.versionNo}`
  return (
    <li className="text-sm flex flex-wrap gap-x-2">
      <Link className="hover:underline font-medium" to={i.kind === 'MCQ' ? `/mcq/${i.resourceId}` : `/notes/${i.resourceId}`}>{name}</Link>
      <span className={cn(i.checkedVersionNo == null || !i.current ? 'text-muted' : '')}>{state}</span>
      {crit + major + minor > 0 && (
        <span className={cn(crit > 0 ? 'text-rose-signal font-medium' : 'text-muted')}>
          {[crit && `${crit} critical`, major && `${major} major`, minor && `${minor} minor`].filter(Boolean).join(', ')} open
        </span>
      )}
    </li>
  )
}
