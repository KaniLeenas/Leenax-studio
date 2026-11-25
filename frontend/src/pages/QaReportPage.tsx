import { AlertOctagon, CheckCircle2, ChevronLeft, CircleDashed, Download, TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { History, ReviewPanel, VersionList } from '../components/review'
import { Button, ErrorNotice, Spinner } from '../components/ui'
import { useReviewPageAt } from '../components/useReviewPage'
import { cn } from '../lib/cn'
import { formatRelative } from '../lib/format'
import {
  BASIS_LABEL,
  CATEGORY_LABEL,
  HEADLINE_HELP,
  HEADLINE_LABEL,
  reportExportUrl,
  SEVERITY_LABEL,
  STATUS_LABEL,
  TYPE_LABEL,
  useMakeReport,
  type Basis,
  type Headline,
  type QaReport,
} from '../lib/qa'
import { toast } from '../stores/ui'

type PartKey = 'COMBINED' | 'NOTES' | 'MCQ'
const PART_LABEL: Record<PartKey, string> = { COMBINED: 'Combined', NOTES: 'Notes', MCQ: 'Questions' }
const HEADLINE_ICON: Record<Headline, typeof CheckCircle2> = { BLOCKED: AlertOctagon, NOT_CHECKED: CircleDashed, NEEDS_ATTENTION: TriangleAlert, PASSED: CheckCircle2 }

/** /qa-report/:resourceId — a topic's QA report: counts first, the blocking rule, and its approval. */
export function QaReportPage() {
  const { resourceId = '' } = useParams()
  const page = useReviewPageAt<QaReport>({ resourceId })
  const [part, setPart] = useState<PartKey>('COMBINED')
  const projectId = page.state.data?.resource?.projectId ?? ''
  const make = useMakeReport(projectId)
  const navigate = useNavigate()

  if (page.loading) return <Spinner label="Opening the QA report" />
  if (page.error) return <ErrorNotice error={page.error} onRetry={() => page.state.refetch()} />
  const state = page.state.data!
  const d = page.detail
  const rep = page.content
  if (!state.resource || !d || !rep) return <ErrorNotice error={new Error('This report has no versions')} />
  const r = state.resource
  const summary = rep.parts[part]
  const items = rep.items.filter((i) => part === 'COMBINED' || i.kind === part)
  const ids = new Set(items.map((i) => i.resourceId))
  const issues = rep.issues.filter((i) => ids.has(i.resourceId))
  const Icon = HEADLINE_ICON[summary.headline]
  const newest = state.versions[0]

  return (
    <>
      <Link to={`/projects/${r.projectId}/qa`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Academic QA
      </Link>
      <header className="mb-6">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold break-words">{rep.topicTitle}: QA report</h1>
        <p className="text-muted mt-1">Made {formatRelative(rep.generatedAt)} from the QA results. Leenax makes this report; nobody edits it.</p>
      </header>

      <div className="grid gap-8 xl:grid-cols-[minmax(0,1fr)_21rem] items-start">
        <div className="min-w-0 space-y-8">
          <div role="tablist" aria-label="Report" className="flex gap-1 border-b border-line">
            {(['COMBINED', 'NOTES', 'MCQ'] as PartKey[]).map((k) => (
              <button key={k} role="tab" type="button" aria-selected={part === k} onClick={() => setPart(k)}
                className={cn('px-4 h-10 -mb-px border-b-2 text-sm', part === k ? 'border-marigold-500 font-medium' : 'border-transparent text-muted hover:text-ink')}>
                {PART_LABEL[k]}
              </button>
            ))}
          </div>

          <section aria-label="Summary">
            <div className={cn('rounded-2xl border-2 px-5 py-4 flex items-start gap-3',
              summary.headline === 'BLOCKED' ? 'border-rose-signal' : summary.headline === 'PASSED' ? 'border-emerald-600/60' : 'border-marigold-500/70')}>
              <Icon className={cn('size-7 shrink-0 mt-0.5', summary.headline === 'BLOCKED' ? 'text-rose-signal' : summary.headline === 'PASSED' ? 'text-emerald-600' : 'text-marigold-600')} aria-hidden />
              <div>
                <p className="text-2xl font-semibold">{HEADLINE_LABEL[summary.headline]}</p>
                <p className="text-muted">{HEADLINE_HELP[summary.headline]}</p>
              </div>
            </div>
            <dl className="mt-4 grid grid-cols-2 sm:grid-cols-7 gap-px rounded-xl overflow-hidden border border-line bg-[var(--line)]">
              {([['Checked', summary.checked], ['Passed', summary.passed], ['Failed', summary.failed], ['Corrected', summary.corrected],
                ['Still open', summary.open], ['Critical open', summary.openCritical], ['Score', summary.score == null ? '–' : `${summary.score}%`]] as [string, string | number][]).map(([k, v]) => (
                <div key={k} className={cn('bg-[var(--bg)] px-3 py-2', k === 'Score' && 'col-span-2 sm:col-span-1')}>
                  <dt className="text-xs text-muted">{k}</dt>
                  <dd className={cn('text-lg font-semibold tabular-nums', k === 'Critical open' && Number(v) > 0 && 'text-rose-signal')}>{v}</dd>
                </div>
              ))}
            </dl>
            {Object.keys(summary.categories).length > 0 && (
              <table className="mt-4 w-full text-sm">
                <caption className="text-left font-semibold mb-1">By category</caption>
                <thead><tr className="text-left text-muted"><th className="font-normal py-1">Category</th><th className="font-normal">Checked</th><th className="font-normal">Passed</th><th className="font-normal">Failed (still open)</th></tr></thead>
                <tbody>
                  {Object.entries(summary.categories).map(([k, t]) => (
                    <tr key={k} className="border-t border-line"><td className="py-1.5">{CATEGORY_LABEL[k] ?? k}</td><td className="tabular-nums">{t.checked}</td><td className="tabular-nums">{t.passed}</td><td className={cn('tabular-nums', t.failed > 0 && 'font-medium')}>{t.failed}</td></tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>

          <section aria-labelledby="checked-h">
            <h2 id="checked-h" className="text-lg font-semibold mb-2">What was checked</h2>
            <ul className="space-y-3">
              {items.map((i) => (
                <li key={i.resourceId} className="rounded-xl border border-line px-4 py-3 text-sm">
                  <p className="font-medium">
                    <Link className="hover:underline" to={i.kind === 'MCQ' ? `/mcq/${i.resourceId}` : `/notes/${i.resourceId}`}>{i.label}</Link>
                    <span className="text-muted font-normal">, version {i.versionNo} ({i.versionStatus.toLowerCase().replace('_', ' ')})</span>
                  </p>
                  {i.run ? (
                    <p className="text-muted mt-0.5">
                      Checked by {i.run.model}{i.run.provider ? ` (${i.run.provider})` : ''}
                      {i.run.secondModel ? `, confirmed by ${i.run.secondModel}` : ', no second model'}.
                      {!i.run.independent && <span className="text-ink"> Not independent: the same model wrote it.</span>}
                      {!i.run.current && <span className="text-ink"> Checked version {i.run.versionNo}, not this one.</span>}
                    </p>
                  ) : <p className="text-ink mt-0.5">Not checked by QA.</p>}
                  {Object.keys(i.claimsByBasis).length > 0 && (
                    <p className="text-muted mt-0.5">Claims by basis: {Object.entries(i.claimsByBasis).map(([b, n]) => `${BASIS_LABEL[b as Basis] ?? b} ${n}`).join(', ')}</p>
                  )}
                  {i.kind === 'MCQ' && i.difficulty && (
                    <p className="text-muted mt-0.5">{i.questions} questions: {Object.entries(i.difficulty).map(([k, n]) => `${k === 'NOT_SET' ? 'no difficulty' : k.toLowerCase()} ${n}`).join(', ')}</p>
                  )}
                </li>
              ))}
            </ul>
          </section>

          {part !== 'MCQ' && rep.coverage.length > 0 && (
            <section aria-labelledby="cov-h">
              <h2 id="cov-h" className="text-lg font-semibold mb-2">Syllabus coverage</h2>
              <ul className="text-sm space-y-1">
                {rep.coverage.map((c) => (
                  <li key={c.subtopic} className="flex gap-2">
                    {c.covered ? <CheckCircle2 className="size-4 mt-0.5 text-emerald-600 shrink-0" aria-label="Covered" /> : <TriangleAlert className="size-4 mt-0.5 text-marigold-600 shrink-0" aria-label="Not found" />}
                    <span><span className="font-medium">{c.subtopic}</span> <span className="text-muted">{c.covered ? `in ${c.levels.join(', ')}` : 'not found in any level of the notes'}</span></span>
                  </li>
                ))}
              </ul>
            </section>
          )}

          <section aria-labelledby="issues-h">
            <h2 id="issues-h" className="text-lg font-semibold mb-2">Issues ({issues.length})</h2>
            {issues.length === 0 ? <p className="text-muted text-sm">None recorded.</p> : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm min-w-[40rem]">
                  <thead><tr className="text-left text-muted"><th className="font-normal py-1 pr-2">#</th><th className="font-normal pr-2">Severity</th><th className="font-normal pr-2">Where</th><th className="font-normal pr-2">Issue</th><th className="font-normal pr-2">Basis</th><th className="font-normal">Status</th></tr></thead>
                  <tbody>
                    {issues.map((x) => (
                      <tr key={x.resourceId + x.number} className="border-t border-line align-top">
                        <td className="py-1.5 pr-2 tabular-nums">{x.number}</td>
                        <td className={cn('pr-2', x.severity === 'CRITICAL' && 'text-rose-signal font-medium')}>{SEVERITY_LABEL[x.severity]}</td>
                        <td className="pr-2">{x.resource}{x.where && <span className="block text-muted">{x.where}</span>}</td>
                        <td className="pr-2"><span className="font-medium">{TYPE_LABEL[x.type] ?? x.type}:</span> {x.explanation}</td>
                        <td className="pr-2 text-muted">{BASIS_LABEL[x.basis]}</td>
                        <td>{STATUS_LABEL[x.status]}{x.statusNote && <span className="block text-muted">{x.statusNote}</span>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {rep.corrections.length > 0 && (
            <section aria-labelledby="corr-h">
              <h2 id="corr-h" className="text-lg font-semibold mb-2">Correction history</h2>
              <ul className="text-sm space-y-1">
                {rep.corrections.map((c, k) => (
                  <li key={k}>Issue {c.issue} ({c.resource}): {c.status.toLowerCase()}{c.decidedBy ? ` by ${c.decidedBy}` : ''}{c.note ? `: ${c.note}` : ''}</li>
                ))}
              </ul>
            </section>
          )}

          <section aria-labelledby="rec-h">
            <h2 id="rec-h" className="text-lg font-semibold mb-2">Recommendations</h2>
            <ul className="list-disc pl-5 space-y-1">{rep.recommendations.map((x, k) => <li key={k}>{x}</li>)}</ul>
          </section>
          <section aria-labelledby="method-h">
            <h2 id="method-h" className="text-lg font-semibold mb-2">How it’s scored</h2>
            <p className="text-muted">{rep.method}</p>
          </section>
          <section aria-labelledby="lim-h">
            <h2 id="lim-h" className="text-lg font-semibold mb-2">Limits of this check</h2>
            <ul className="list-disc pl-5 space-y-1 text-muted">{rep.limitations.map((x, k) => <li key={k}>{x}</li>)}</ul>
          </section>
        </div>

        <aside className="space-y-6 xl:sticky xl:top-6">
          <ReviewPanel
            detail={d}
            kindLabel="QA report"
            dirty={false}
            edited={rep}
            saveFirst={async () => {}}
            editing={false}
            onReviewerEdit={() => {}}
            allowReviewerEdits={false}
            originLabel="made by Leenax from the QA results"
          />
          {state.canEdit && newest?.status !== 'IN_REVIEW' && (
            <Button
              className="w-full"
              loading={make.isPending}
              onClick={() => make.mutate(rep.topicId, {
                onSuccess: (s) => { toast.success('Made again from the latest QA results'); if (s.resource && s.resource.id !== r.id) navigate(`/qa-report/${s.resource.id}`) },
                onError: (e) => toast.error(e.message),
              })}
            >
              Make it again from the latest QA
            </Button>
          )}
          <section aria-labelledby="export-h">
            <h2 id="export-h" className="font-semibold mb-2">Download version {d.version.versionNo}</h2>
            {(['COMBINED', 'NOTES', 'MCQ'] as PartKey[]).map((k) => (
              <div key={k} className="flex items-center gap-2 mb-2">
                <span className="text-sm w-24">{PART_LABEL[k]}</span>
                {(['pdf', 'docx'] as const).map((f) => (
                  <a key={f} className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-surface px-3 h-8 text-sm hover:bg-surface-2" href={reportExportUrl(d.version.id, f, k)}
                    aria-label={`${PART_LABEL[k]} report as ${f === 'pdf' ? 'PDF' : 'Word'}`}>
                    <Download className="size-3.5" aria-hidden /> {f === 'pdf' ? 'PDF' : 'Word'}
                  </a>
                ))}
              </div>
            ))}
          </section>
          <section aria-labelledby="versions-h">
            <h2 id="versions-h" className="font-semibold mb-2">Versions</h2>
            <VersionList versions={state.versions} current={d.version.id} approvedId={state.approvedVersionId} onPick={page.pick} />
          </section>
          <section aria-labelledby="history-h">
            <h2 id="history-h" className="font-semibold mb-2">History</h2>
            <History resourceId={r.id} />
          </section>
        </aside>
      </div>
    </>
  )
}
