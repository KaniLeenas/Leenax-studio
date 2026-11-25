import { AlertOctagon, AlertTriangle, CheckCircle2, CircleHelp, Info, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { formatRelative } from '../lib/format'
import {
  BASIS_LABEL,
  counts,
  describeProposal,
  isOpen,
  jobBusy,
  SEVERITY_LABEL,
  STATUS_LABEL,
  TYPE_LABEL,
  useIssueActions,
  useResourceQa,
  useStartQa,
  type Evidence,
  type Issue,
  type Severity,
} from '../lib/qa'
import { cn } from '../lib/cn'
import { toast } from '../stores/ui'
import { TextModelPicker, useDefaultAccount, useUsableAccounts } from './studio'
import { Button, Dialog, ErrorNotice, Field, Spinner, Textarea } from './ui'

const SEVERITY_ICON: Record<Severity, typeof Info> = { CRITICAL: AlertOctagon, MAJOR: AlertTriangle, MINOR: Info }
const SEVERITY_TONE: Record<Severity, string> = {
  CRITICAL: 'border-rose-signal/60',
  MAJOR: 'border-marigold-500/60',
  MINOR: 'border-line',
}

/**
 * Academic QA for one version of notes or a question bank: the last check, starting a check
 * or recheck, and the issue log. Corrections are proposals: an editor accepts one (it goes
 * into a draft) and a recheck closes the issue; a reviewer can dismiss one with a reason.
 */
export function QaPanel({
  resourceId,
  versionId,
  versionNo,
  questionOptions,
  onShowItem,
}: {
  resourceId: string
  versionId: string
  versionNo: number
  /** For banks: each question's options, to describe a proposed key change. */
  questionOptions?: Map<string, string[]>
  /** Finds the passage (notes) or question (bank) an issue is about; false when it isn't there. */
  onShowItem?: (issue: Issue) => boolean
}) {
  const qa = useResourceQa(resourceId)
  const [running, setRunning] = useState<null | 'check' | 'recheck'>(null)
  const [showClosed, setShowClosed] = useState(false)

  if (qa.isPending) return <Spinner label="Loading QA" />
  if (qa.error) return <ErrorNotice error={qa.error} onRetry={() => qa.refetch()} />
  const d = qa.data
  const last = d.runs.find((r) => r.mode === 'CHECK')
  const c = counts(d.issues)
  const open = d.issues.filter((i) => isOpen(i.status))
  const closed = d.issues.filter((i) => !isOpen(i.status))
  const busy = jobBusy(d.job)

  return (
    <section aria-labelledby="qa-h" className="rounded-2xl border border-line px-5 py-4">
      <div className="flex flex-wrap items-start gap-3">
        <div className="flex-1 min-w-0">
          <h2 id="qa-h" className="text-lg font-semibold flex items-center gap-2">
            <ShieldCheck className="size-5 text-muted" aria-hidden />
            Academic QA
          </h2>
          {last ? (
            <p className="text-sm text-muted mt-0.5">
              Version {last.versionNo} checked by {last.model}
              {last.provider ? ` (${last.provider})` : ''}
              {last.secondModel ? `, confirmed by ${last.secondModel}` : ''}, {formatRelative(last.createdAt)}.
              {last.versionNo !== versionNo && <span className="text-ink"> Version {versionNo} hasn’t been checked.</span>}
              {!last.independent && <span className="text-ink"> Not independent: {last.model} also wrote it.</span>}
            </p>
          ) : (
            <p className="text-sm text-muted mt-0.5">Not checked yet. QA checks each claim against the course documents and answers each question without seeing its key.</p>
          )}
        </div>
        {d.canRun && (
          <div className="flex gap-2">
            {open.length > 0 && (
              <Button size="sm" disabled={busy} onClick={() => setRunning('recheck')}>
                Recheck open issues
              </Button>
            )}
            <Button size="sm" variant="primary" disabled={busy} onClick={() => setRunning('check')}>
              {last ? `Check version ${versionNo}` : 'Run QA'}
            </Button>
          </div>
        )}
      </div>

      {d.job && (busy || d.job.status === 'FAILED' || Date.now() - Date.parse(d.job.createdAt) < 600_000) && (
        <p role="status" aria-live="polite" className={cn('mt-3 rounded-xl border px-3 py-2 text-sm', d.job.status === 'FAILED' ? 'border-rose-signal/50' : 'border-line bg-surface-2')}>
          {busy ? `QA is ${d.job.status === 'QUEUED' ? 'waiting to start' : 'checking'}${d.job.startedBy ? `, started by ${d.job.startedBy}` : ''}. This takes a minute or two.`
            : d.job.status === 'FAILED' ? `QA failed: ${d.job.error ?? 'unknown error'}`
            : `QA finished: ${d.job.summary ?? 'done'}.`}
        </p>
      )}

      {d.issues.length > 0 && (
        <p className="mt-3 text-sm">
          {c.CRITICAL + c.MAJOR + c.MINOR === 0 ? 'No issues open.' : (
            <>
              Open:{' '}
              {(['CRITICAL', 'MAJOR', 'MINOR'] as Severity[]).filter((s) => c[s] > 0).map((s, i) => (
                <span key={s} className={cn(s === 'CRITICAL' && 'text-rose-signal font-medium')}>
                  {i > 0 && ', '}
                  {c[s]} {SEVERITY_LABEL[s].toLowerCase()}
                </span>
              ))}
              {c.CRITICAL > 0 && '. Critical issues block approval until they’re corrected and rechecked, or dismissed by a reviewer.'}
            </>
          )}
        </p>
      )}

      {open.length > 0 && (
        <ol className="mt-3 space-y-3" aria-label="Open issues">
          {open.map((i) => (
            <IssueCard key={i.id} issue={i} resourceId={resourceId} canEdit={d.canEdit} canReview={d.canReview} options={questionOptions} onShow={onShowItem} />
          ))}
        </ol>
      )}
      {closed.length > 0 && (
        <>
          <button type="button" className="mt-3 text-sm text-muted hover:text-ink" aria-expanded={showClosed} onClick={() => setShowClosed((s) => !s)}>
            {showClosed ? 'Hide' : 'Show'} {closed.length} resolved or dismissed
          </button>
          {showClosed && (
            <ol className="mt-2 space-y-3" aria-label="Closed issues">
              {closed.map((i) => (
                <IssueCard key={i.id} issue={i} resourceId={resourceId} canEdit={d.canEdit} canReview={d.canReview} options={questionOptions} onShow={onShowItem} />
              ))}
            </ol>
          )}
        </>
      )}
      {last?.result.limitations && last.result.limitations.length > 0 && (
        <ul className="mt-3 text-sm text-muted list-disc pl-5">
          {last.result.limitations.map((l, k) => <li key={k}>{l}</li>)}
        </ul>
      )}
      {running && <RunDialog resourceId={resourceId} versionId={versionId} versionNo={versionNo} recheck={running === 'recheck'} writerModel={d.writerModel} onClose={() => setRunning(null)} />}
    </section>
  )
}

function IssueCard({
  issue: i, resourceId, canEdit, canReview, options, onShow,
}: { issue: Issue; resourceId: string; canEdit: boolean; canReview: boolean; options?: Map<string, string[]>; onShow?: (i: Issue) => boolean }) {
  const { accept, reject, dismiss, reopen } = useIssueActions(resourceId)
  const [reasonFor, setReasonFor] = useState<null | 'dismiss' | 'reopen'>(null)
  const [reason, setReason] = useState('')
  const Icon = SEVERITY_ICON[i.severity]
  const corr = i.correction
  const opts = i.itemRef ? options?.get(i.itemRef) : undefined
  const fail = (e: Error) => toast.error(e.message)

  return (
    <li className={cn('rounded-xl border-l-4 border border-line px-4 py-3', SEVERITY_TONE[i.severity])} aria-label={`Issue ${i.number}`}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
        <Icon className={cn('size-4', i.severity === 'CRITICAL' ? 'text-rose-signal' : i.severity === 'MAJOR' ? 'text-marigold-600' : 'text-muted')} aria-hidden />
        <span className="font-semibold">{SEVERITY_LABEL[i.severity]}</span>
        <span>{TYPE_LABEL[i.type] ?? i.type}</span>
        <span className="text-faint">#{i.number}, found in version {i.foundInVersionNo}</span>
        <span className="flex-1" />
        <span className={cn('text-muted', isOpen(i.status) && 'text-ink')}>{STATUS_LABEL[i.status]}</span>
      </div>
      {i.itemLabel && (
        onShow ? (
          <button type="button" className="mt-1 text-left text-sm text-muted hover:text-ink underline-offset-2 hover:underline"
            onClick={() => !onShow(i) && toast.info('That isn’t in the version on screen')}>
            {i.itemLabel}
          </button>
        ) : <p className="mt-1 text-sm text-muted">{i.itemLabel}</p>
      )}
      <p className="mt-1 break-words">{i.explanation}</p>
      {i.original && i.type !== 'COVERAGE_GAP' && (
        <blockquote className="mt-1 text-sm text-muted border-l-2 border-line pl-2 whitespace-pre-wrap break-words">{i.original}</blockquote>
      )}
      <EvidenceList evidence={i.evidence} basis={i.basis} />
      {i.confirmations.map((c, k) => (
        <p key={k} className="text-sm mt-1">
          {c.agrees ? 'Confirmed' : 'Not confirmed'} by {c.model}{c.note ? `: ${c.note}` : ''}
        </p>
      ))}
      {i.suggestion && <p className="text-sm mt-1"><span className="text-muted">Suggestion:</span> {i.suggestion}</p>}
      {corr && (
        <div className="mt-2 rounded-lg bg-surface-2 px-3 py-2 text-sm">
          <p>
            <span className="text-muted">Proposed correction:</span> {describeProposal(corr.proposal, opts)}.
          </p>
          {corr.status === 'PROPOSED' && isOpen(i.status) ? (
            <div className="flex flex-wrap gap-2 mt-2">
              {canEdit && (
                <Button size="sm" variant="primary" loading={accept.isPending} onClick={() => accept.mutate({ id: corr.id }, {
                  onSuccess: () => toast.success('Correction put in the draft. Recheck to close the issue.'), onError: fail })}>
                  Accept into the draft
                </Button>
              )}
              {(canEdit || canReview) && (
                <Button size="sm" variant="ghost" loading={reject.isPending} onClick={() => reject.mutate({ id: corr.id }, { onError: fail })}>
                  Reject
                </Button>
              )}
            </div>
          ) : (
            <p className="text-muted mt-1">
              {corr.status === 'ACCEPTED' ? 'Accepted' : corr.status === 'REJECTED' ? 'Rejected' : 'Not decided'}
              {corr.decidedBy ? ` by ${corr.decidedBy}` : ''}{corr.note ? `: ${corr.note}` : ''}
            </p>
          )}
        </div>
      )}
      {(i.statusNote || i.recheck) && (
        <p className="text-sm text-muted mt-2">
          {i.recheck && `Recheck on version ${i.recheck.versionNo ?? '?'}: ${i.recheck.fixed ? 'fixed' : 'not fixed'}${i.recheck.note ? `. ${i.recheck.note}` : ''}`}
          {i.recheck && i.statusNote && ' '}
          {i.statusNote && <>{i.status === 'DISMISSED' ? `Dismissed${i.decidedBy ? ` by ${i.decidedBy}` : ''}: ` : ''}{i.statusNote}</>}
        </p>
      )}
      {canReview && (
        <div className="mt-2 -ml-2">
          {isOpen(i.status) ? (
            <Button size="sm" variant="ghost" onClick={() => setReasonFor('dismiss')}>Dismiss…</Button>
          ) : (
            <Button size="sm" variant="ghost" onClick={() => setReasonFor('reopen')}>Reopen…</Button>
          )}
        </div>
      )}
      <Dialog
        open={reasonFor !== null}
        title={reasonFor === 'dismiss' ? `Dismiss issue ${i.number}?` : `Reopen issue ${i.number}?`}
        onClose={() => setReasonFor(null)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setReasonFor(null)}>Cancel</Button>
            <Button
              variant="primary"
              disabled={!reason.trim()}
              loading={dismiss.isPending || reopen.isPending}
              onClick={() => (reasonFor === 'dismiss' ? dismiss : reopen).mutate({ id: i.id, reason: reason.trim() }, {
                onSuccess: () => { setReasonFor(null); setReason('') }, onError: fail })}
            >
              {reasonFor === 'dismiss' ? 'Dismiss' : 'Reopen'}
            </Button>
          </>
        }
      >
        <p className="text-muted mb-3">
          {reasonFor === 'dismiss'
            ? i.severity === 'CRITICAL'
              ? 'Dismissing a critical issue lets this be approved. The reason is kept with the issue and shown in the QA report.'
              : 'The reason is kept with the issue and shown in the QA report.'
            : 'It will count as open again.'}
        </p>
        <Field label="Reason">
          {(fid) => <Textarea id={fid} rows={3} maxLength={2000} value={reason} onChange={(e) => setReason(e.target.value)} />}
        </Field>
      </Dialog>
    </li>
  )
}

function EvidenceList({ evidence, basis }: { evidence: Evidence[]; basis: string }) {
  const shown = evidence.filter((e) => e.basis !== 'ESTIMATE' || !e.note?.startsWith('rule:'))
  if (shown.length === 0) {
    return <p className="text-sm text-muted mt-1">Basis: {basis === 'ESTIMATE' ? 'a rule in Leenax (no AI)' : BASIS_LABEL[basis as keyof typeof BASIS_LABEL]}</p>
  }
  return (
    <ul className="mt-1 space-y-0.5">
      {shown.map((e, k) => (
        <li key={k} className="text-sm flex items-start gap-1.5">
          {e.basis === 'UPLOADED' && (e.found ? (
            <CheckCircle2 className="size-4 mt-0.5 text-emerald-600 shrink-0" aria-label="Quoted words found on the page" />
          ) : (
            <CircleHelp className="size-4 mt-0.5 text-marigold-600 shrink-0" aria-label="Quoted words not found on the page" />
          ))}
          <span className="min-w-0 break-words">
            <span className="text-muted">Basis:</span>{' '}
            {e.basis === 'UPLOADED' && e.documentId && e.versionId && e.page ? (
              <Link className="underline underline-offset-2" to={`/documents/${e.documentId}?version=${e.versionId}&page=${e.page}`}>{e.label}</Link>
            ) : e.basis === 'EXTERNAL' && e.url ? (
              <>{BASIS_LABEL.EXTERNAL}: <span className="break-all">{e.url}</span></>
            ) : (
              BASIS_LABEL[e.basis]
            )}
            {e.quote && <span className="text-muted"> “{e.quote}”</span>}
            {e.note && e.basis !== 'EXTERNAL' && <span className="text-muted">. {e.note}</span>}
          </span>
        </li>
      ))}
    </ul>
  )
}

function RunDialog({ resourceId, versionId, versionNo, recheck, writerModel, onClose }: {
  resourceId: string; versionId: string; versionNo: number; recheck: boolean; writerModel?: string | null; onClose: () => void
}) {
  const { usable, isPending } = useUsableAccounts('TEXT')
  const [accountId, setAccountId] = useState('')
  const [model, setModel] = useState('')
  const [second, setSecond] = useState(false)
  const [secondAccountId, setSecondAccountId] = useState('')
  const [secondModel, setSecondModel] = useState('')
  const start = useStartQa(resourceId)
  useDefaultAccount(usable, accountId, setAccountId)
  useDefaultAccount(usable, secondAccountId, setSecondAccountId)
  const same = !!writerModel && model.trim().toLowerCase() === writerModel.toLowerCase()
  return (
    <Dialog
      open
      title={recheck ? 'Recheck the open issues' : `Check version ${versionNo}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={start.isPending}
            disabled={!accountId || !model || (second && (!secondAccountId || !secondModel))}
            onClick={() => start.mutate(
              { versionId, accountId, model, recheckOnly: recheck, ...(second && !recheck ? { secondAccountId, secondModel } : {}) },
              { onSuccess: () => { toast.success(recheck ? 'Rechecking' : 'QA started'); onClose() }, onError: (e) => toast.error(e.message) },
            )}
          >
            {recheck ? 'Recheck' : 'Start QA'}
          </Button>
        </>
      }
    >
      <p className="text-muted mb-4">
        {recheck
          ? `Each open or corrected issue is checked again against version ${versionNo}. Fixed ones are resolved; the rest stay open.`
          : `QA rechecks what’s open, then checks version ${versionNo} claim by claim against the course documents. Questions are answered without seeing their keys. Nothing is changed: corrections are proposals for you to accept or reject.`}
      </p>
      {isPending ? <Spinner label="Loading AI accounts" /> : (
        <div className="space-y-4">
          <TextModelPicker accounts={usable} accountId={accountId} setAccountId={setAccountId} model={model} setModel={setModel} required
            hint={writerModel ? `For an independent check, use a different model from the one that wrote it (${writerModel}).` : 'Uses your own account.'} />
          {same && <p className="text-sm text-rose-signal">{writerModel} wrote this, so this check won’t be independent. The report will say so.</p>}
          {!recheck && (
            <>
              <label className="flex items-start gap-2 text-sm">
                <input type="checkbox" className="mt-0.5 size-4 accent-marigold-600" checked={second} onChange={(e) => setSecond(e.target.checked)} />
                <span>Confirm the findings with a second model (costs more; it answers the questions too)</span>
              </label>
              {second && <TextModelPicker accounts={usable} accountId={secondAccountId} setAccountId={setSecondAccountId} model={secondModel} setModel={setSecondModel} required prefix="Second" />}
            </>
          )}
        </div>
      )}
    </Dialog>
  )
}
