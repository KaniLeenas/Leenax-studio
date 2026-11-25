import { useState } from 'react'
import { formatRelative } from '../lib/format'
import { useCommentActions, useComments, type ReviewComment } from '../lib/materials'
import { toast } from '../stores/ui'
import { Button, Textarea } from './ui'

/**
 * Comments on the versions of one resource. They never change a version: reviewers can
 * comment on a version in review, and editors resolve them when they've acted on them.
 */
export function Comments({
  resourceId,
  versionId,
  versionNo,
  canComment,
  meId,
  pendingQuote,
  clearQuote,
  onShowQuote,
}: {
  resourceId: string
  versionId: string
  versionNo: number
  canComment: boolean
  meId?: string
  pendingQuote?: string | null
  clearQuote: () => void
  onShowQuote?: (quote: string) => boolean
}) {
  const list = useComments(resourceId)
  const { add, resolve, remove } = useCommentActions(resourceId)
  const [body, setBody] = useState('')
  const [showResolved, setShowResolved] = useState(false)
  const all = list.data ?? []
  const open = all.filter((c) => !c.resolvedAt)
  const done = all.filter((c) => c.resolvedAt)

  function post() {
    add.mutate(
      { versionId, quote: pendingQuote ?? undefined, body: body.trim() },
      {
        onSuccess: () => {
          setBody('')
          clearQuote()
        },
        onError: (e) => toast.error(e.message),
      },
    )
  }

  const item = (c: ReviewComment) => (
    <li key={c.id} className="text-sm rounded-xl border border-line px-3 py-2.5">
      {c.quote && (
        <button
          type="button"
          className="block text-left text-muted border-l-2 border-marigold-500 pl-2 mb-1 hover:text-ink"
          onClick={() => onShowQuote && !onShowQuote(c.quote!) && toast.info('That passage isn’t in this version')}
        >
          “{c.quote.length > 140 ? c.quote.slice(0, 140) + '…' : c.quote}”
        </button>
      )}
      <p className="whitespace-pre-wrap break-words">{c.body}</p>
      <p className="text-faint mt-1">
        {c.author ?? 'Someone'}, {formatRelative(c.createdAt)}
        {c.versionNo !== versionNo && `, on version ${c.versionNo}`}
        {c.resolvedAt && `. Resolved${c.resolvedBy ? ` by ${c.resolvedBy}` : ''}`}
      </p>
      {canComment && (
        <div className="flex gap-1 mt-1 -ml-2">
          <Button size="sm" variant="ghost" onClick={() => resolve.mutate({ id: c.id, resolved: !c.resolvedAt }, { onError: (e) => toast.error(e.message) })}>
            {c.resolvedAt ? 'Reopen' : 'Resolve'}
          </Button>
          {!c.resolvedAt && c.authorId === meId && (
            <Button size="sm" variant="ghost" onClick={() => remove.mutate(c.id, { onError: (e) => toast.error(e.message) })}>
              Delete
            </Button>
          )}
        </div>
      )}
    </li>
  )

  return (
    <section aria-labelledby="comments-h">
      <h2 id="comments-h" className="font-semibold mb-2">
        Comments{open.length > 0 && <span className="text-muted font-normal"> ({open.length} open)</span>}
      </h2>
      {open.length === 0 && done.length === 0 && <p className="text-sm text-muted mb-2">No comments yet.</p>}
      <ul className="space-y-2">{open.map(item)}</ul>
      {done.length > 0 && (
        <>
          <button type="button" className="text-sm text-muted hover:text-ink mt-2" onClick={() => setShowResolved((s) => !s)} aria-expanded={showResolved}>
            {showResolved ? 'Hide' : 'Show'} {done.length} resolved
          </button>
          {showResolved && <ul className="space-y-2 mt-2">{done.map(item)}</ul>}
        </>
      )}
      {canComment && (
        <div className="mt-3">
          {pendingQuote && (
            <p className="text-sm text-muted border-l-2 border-marigold-500 pl-2 mb-2">
              On “{pendingQuote.length > 120 ? pendingQuote.slice(0, 120) + '…' : pendingQuote}”{' '}
              <button type="button" className="underline" onClick={clearQuote}>
                clear
              </button>
            </p>
          )}
          <Textarea aria-label={`Comment on version ${versionNo}`} rows={2} maxLength={2000} placeholder="Add a comment" value={body} onChange={(e) => setBody(e.target.value)} />
          <Button className="mt-2" size="sm" loading={add.isPending} disabled={!body.trim()} onClick={post}>
            Comment
          </Button>
        </div>
      )}
    </section>
  )
}
