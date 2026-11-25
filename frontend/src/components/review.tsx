import { AlertTriangle, Check as CheckIcon, X } from 'lucide-react'
import { useState } from 'react'
import { formatRelative } from '../lib/format'
import {
  ACTION_LABEL,
  nextStep,
  ORIGIN_LABEL,
  STATUS_LABEL,
  statusTone,
  useDecide,
  useReviewEvents,
  useSubmit,
  useWithdraw,
  type Check,
  type Decision,
  type VersionDetail,
  type VersionSummary,
} from '../lib/reviews'
import { toast } from '../stores/ui'
import { Button, Dialog, Field, StatusPill, Textarea } from './ui'

export function ChecksList({ checks }: { checks: Check[] }) {
  if (checks.length === 0) return null
  return (
    <ul className="space-y-1.5" aria-label="Checks">
      {checks.map((c) => (
        <li key={c.code} className="flex items-start gap-2 text-sm">
          {c.ok ? (
            <CheckIcon className="size-4 mt-0.5 shrink-0 text-emerald-600 dark:text-emerald-400" aria-label="Passed" />
          ) : c.blocking ? (
            <X className="size-4 mt-0.5 shrink-0 text-rose-signal" aria-label="Must be fixed" />
          ) : (
            <AlertTriangle className="size-4 mt-0.5 shrink-0 text-marigold-600 dark:text-marigold-400" aria-label="Warning" />
          )}
          <span className={c.ok ? 'text-muted' : ''}>{c.message}</span>
        </li>
      ))}
    </ul>
  )
}

/**
 * Status, checks and the actions open to this person on one version. Saving is the
 * page's job (it holds the edited content); this panel saves first when it needs to.
 */
export function ReviewPanel({
  detail,
  kindLabel,
  dirty,
  edited,
  saveFirst,
  onEdit,
  editLabel = 'Edit',
  editing,
  onReviewerEdit,
  allowReviewerEdits = true,
  originLabel,
}: {
  detail: VersionDetail
  kindLabel: string
  /** Unsaved changes in the page. */
  dirty: boolean
  /** The page's current content, sent when a reviewer approves with edits. */
  edited: unknown
  saveFirst: () => Promise<void>
  /** Starts a new draft from this version; omitted when not possible. */
  onEdit?: () => void
  editLabel?: string
  editing: boolean
  onReviewerEdit: (on: boolean) => void
  /** False for content Leenax makes (QA reports), which reviewers can't edit. */
  allowReviewerEdits?: boolean
  /** Replaces "started by hand" and the like, e.g. for reports Leenax makes. */
  originLabel?: string
}) {
  const v = detail.version
  const submit = useSubmit()
  const withdraw = useWithdraw()
  const decide = useDecide()
  const [asking, setAsking] = useState<Decision | null>(null)
  const [comment, setComment] = useState('')
  const blocked = detail.checks.some((c) => c.blocking && !c.ok)
  const isDraft = v.status === 'DRAFT'
  const inReview = v.status === 'IN_REVIEW'

  async function send() {
    try {
      if (dirty) await saveFirst()
      await submit.mutateAsync({ versionId: v.id })
      toast.success(`Version ${v.versionNo} sent for review`)
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Could not send it for review')
    }
  }

  function decideNow(decision: Decision) {
    const withEdits = decision === 'APPROVE' && editing && dirty
    decide.mutate(
      { versionId: v.id, decision: withEdits ? 'APPROVE_WITH_EDITS' : decision, comment: comment.trim() || undefined, content: withEdits ? edited : undefined },
      {
        onSuccess: (r) => {
          toast.success(
            decision === 'APPROVE'
              ? withEdits ? `Approved with your edits as version ${r.version.versionNo}` : `Version ${v.versionNo} approved`
              : decision === 'REJECT' ? `Version ${v.versionNo} rejected` : 'Sent back for changes',
          )
          setAsking(null)
          setComment('')
          onReviewerEdit(false)
        },
        onError: (e) => toast.error(e.message),
      },
    )
  }

  const needsComment = asking === 'REQUEST_CHANGES' || asking === 'REJECT'

  return (
    <section aria-labelledby="review-heading" className="rounded-2xl border border-line px-4 py-4">
      <div className="flex items-center justify-between gap-2">
        <h2 id="review-heading" className="font-semibold">
          Version {v.versionNo}
        </h2>
        <StatusPill tone={statusTone(v.status)}>{STATUS_LABEL[v.status]}</StatusPill>
      </div>
      <p className="text-sm text-muted mt-0.5">
        {originLabel ?? ORIGIN_LABEL[v.origin]}
        {v.createdBy && `, ${v.createdBy}`}, {formatRelative(v.createdAt)}
      </p>
      <p className="text-sm mt-3">{nextStep(detail, kindLabel)}</p>
      {detail.latest && (isDraft || inReview) && (
        <div className="mt-3">
          <ChecksList checks={detail.checks} />
        </div>
      )}

      <div className="flex flex-wrap gap-2 mt-4">
        {isDraft && detail.you.canEdit && detail.latest && (
          <Button variant="primary" loading={submit.isPending} disabled={blocked && !dirty} onClick={send}>
            Send for review
          </Button>
        )}
        {inReview && detail.you.mayDecide && (
          <>
            <Button variant="primary" disabled={blocked && !(editing && dirty)} onClick={() => setAsking('APPROVE')}>
              {editing && dirty ? 'Approve with my edits' : 'Approve'}
            </Button>
            <Button onClick={() => setAsking('REQUEST_CHANGES')}>Ask for changes</Button>
            <Button variant="ghost" onClick={() => setAsking('REJECT')}>
              Reject
            </Button>
            {!editing && allowReviewerEdits && (
              <Button variant="ghost" onClick={() => onReviewerEdit(true)}>
                Make edits
              </Button>
            )}
          </>
        )}
        {inReview && detail.you.canEdit && !detail.you.mayDecide && (
          <Button
            loading={withdraw.isPending}
            onClick={() =>
              withdraw.mutate(v.id, { onSuccess: () => toast.success('Back to draft'), onError: (e) => toast.error(e.message) })
            }
          >
            Withdraw to change it
          </Button>
        )}
        {onEdit && <Button onClick={onEdit}>{editLabel}</Button>}
      </div>

      <Dialog
        open={asking !== null}
        title={
          asking === 'APPROVE'
            ? editing && dirty ? `Approve with your edits?` : `Approve version ${v.versionNo}?`
            : asking === 'REJECT' ? `Reject version ${v.versionNo}?` : 'Ask for changes'
        }
        onClose={() => setAsking(null)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setAsking(null)}>
              Cancel
            </Button>
            <Button
              variant={asking === 'REJECT' ? 'danger' : 'primary'}
              loading={decide.isPending}
              disabled={needsComment && !comment.trim()}
              onClick={() => asking && decideNow(asking)}
            >
              {asking === 'APPROVE' ? (editing && dirty ? 'Approve with my edits' : 'Approve') : asking === 'REJECT' ? 'Reject' : 'Send back'}
            </Button>
          </>
        }
      >
        {asking === 'APPROVE' && (
          <p className="text-muted mb-3">
            {editing && dirty
              ? `Your edits become version ${v.versionNo + 1}, approved. Version ${v.versionNo} is kept, and the record lists what you changed.`
              : `The record names this exact version, your name and the time.`}
          </p>
        )}
        <Field label={needsComment ? 'What needs to change' : 'Comment (optional)'}>
          {(id) => <Textarea id={id} rows={4} maxLength={2000} value={comment} onChange={(e) => setComment(e.target.value)} />}
        </Field>
      </Dialog>
    </section>
  )
}

export function VersionList({ versions, current, approvedId, onPick }: { versions: VersionSummary[]; current: string; approvedId?: string | null; onPick: (id: string) => void }) {
  return (
    <ol className="space-y-1" aria-label="Versions">
      {versions.map((v) => (
        <li key={v.id}>
          <button
            type="button"
            onClick={() => onPick(v.id)}
            aria-current={v.id === current || undefined}
            className={
              'w-full flex items-center justify-between gap-2 rounded-lg px-3 py-1.5 text-sm text-left hover:bg-surface-2 ' +
              (v.id === current ? 'bg-surface-2 font-medium' : '')
            }
          >
            <span>
              Version {v.versionNo}
              {v.id === approvedId && <span className="sr-only"> (the approved one)</span>}
            </span>
            <span className="text-muted">{STATUS_LABEL[v.status]}</span>
          </button>
        </li>
      ))}
    </ol>
  )
}

export function History({ resourceId }: { resourceId: string }) {
  const events = useReviewEvents(resourceId)
  if (!events.data || events.data.length === 0) return null
  return (
    <ol className="space-y-3" aria-label="History">
      {events.data.map((e) => (
        <li key={e.id} className="text-sm">
          <p>
            <span className="font-medium">{ACTION_LABEL[e.action] ?? e.action}</span>
            <span className="text-muted">
              {' '}
              version {e.versionNo}
              {e.actor ? `, ${e.actor}` : ''}, {formatRelative(e.createdAt)}
            </span>
          </p>
          {e.comment && <p className="text-muted mt-0.5 whitespace-pre-wrap break-words">“{e.comment}”</p>}
          {e.details?.changes && e.details.changes.length > 0 && (
            <ul className="text-muted mt-0.5 list-disc pl-5">
              {e.details.changes.slice(0, 8).map((c, i) => (
                <li key={i}>{c}</li>
              ))}
              {e.details.changes.length > 8 && <li>and {e.details.changes.length - 8} more</li>}
            </ul>
          )}
        </li>
      ))}
    </ol>
  )
}
