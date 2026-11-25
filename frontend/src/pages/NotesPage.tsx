import type { Editor, JSONContent } from '@tiptap/core'
import { ChevronLeft, CheckCircle2, CircleHelp, Download } from 'lucide-react'
import { useCallback, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Comments } from '../components/Comments'
import { QaPanel } from '../components/QaPanel'
import { findQuote, NotesEditor } from '../components/NotesEditor'
import { History, ReviewPanel, VersionList } from '../components/review'
import { Button, ErrorNotice, Spinner } from '../components/ui'
import { useReviewPageAt } from '../components/useReviewPage'
import { useAuthStatus } from '../lib/queries'
import { exportUrl, LEVEL_LABEL, useUpdateFromUpstream, type Level, type Notes } from '../lib/materials'
import { toast } from '../stores/ui'

/** /notes/:resourceId — one topic's notes at one level: the editor, its sources, comments and review. */
export function NotesPage() {
  const { resourceId = '' } = useParams()
  const page = useReviewPageAt<Notes>({ resourceId })
  const me = useAuthStatus()
  const update = useUpdateFromUpstream()
  const editor = useRef<Editor | null>(null)
  const [quote, setQuote] = useState<string | null>(null)
  const [reset, setReset] = useState(0)
  const onReady = useCallback((e: Editor) => {
    editor.current = e
  }, [])

  if (page.loading) return <Spinner label="Opening the notes" />
  if (page.error) return <ErrorNotice error={page.error} onRetry={() => page.state.refetch()} />
  const state = page.state.data!
  const d = page.detail
  const n = page.content
  if (!state.resource || !d || !n) return <ErrorNotice error={new Error('These notes have no versions')} />
  const r = state.resource
  const topicsChanged = d.checks.some((c) => c.code === 'topics' && !c.ok && c.message.includes('changed'))
  const newest = state.versions[0]
  const canComment = state.canEdit || state.canReview

  return (
    <>
      <Link to={`/projects/${r.projectId}/materials`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Notes and questions
      </Link>
      <header className="mb-6">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold break-words">{n.topicTitle}</h1>
        <p className="text-muted mt-1">
          {LEVEL_LABEL[n.level as Level]} notes{n.model ? `, first written by ${n.model}` : ''}
        </p>
      </header>

      {d.latest && (d.version.status === 'OUTDATED' || topicsChanged) && (
        <div className="mb-6 rounded-xl border border-marigold-500/50 px-4 py-3 text-sm flex flex-wrap items-center gap-3" role="status">
          <p className="flex-1 min-w-0">The topic list has changed since these notes were written. Check they still fit, then tie them to the approved topic list.</p>
          {state.canEdit && newest?.status !== 'IN_REVIEW' && (
            <Button size="sm" variant="primary" loading={update.isPending} onClick={() => update.mutate(r.id, { onSuccess: () => toast.success('Tied to the approved topic list'), onError: (e) => toast.error(e.message) })}>
              Tie to the approved topics
            </Button>
          )}
        </div>
      )}

      <div className="grid gap-8 xl:grid-cols-[minmax(0,1fr)_21rem] items-start">
        <div className="min-w-0 space-y-6">
          {page.dirty && d.version.status === 'DRAFT' && (
            <div className="sticky top-0 z-20 -mx-2 px-2 py-2 bg-[var(--bg)] flex items-center gap-3 border-b border-line">
              <span className="text-sm text-muted flex-1">Unsaved changes</span>
              <Button variant="ghost" onClick={() => { page.discard(); setReset((x) => x + 1) }}>
                Discard
              </Button>
              <Button variant="primary" loading={page.saving} onClick={() => page.saveDraft().then(() => toast.success('Draft saved'), (e) => toast.error(e.message))}>
                Save draft
              </Button>
            </div>
          )}
          {page.reviewerEditing && (
            <p className="rounded-xl border border-marigold-500/50 px-4 py-3 text-sm">
              You're editing as the reviewer. Approving with your edits saves them as a new, approved version.
            </p>
          )}
          <NotesEditor
            key={`${d.version.id}:${d.version.updatedAt}:${page.editable}:${reset}`}
            content={n.doc as JSONContent}
            editable={page.editable}
            onChange={(doc) => page.setContent({ ...n, doc })}
            projectId={r.projectId}
            keepFigures={canComment}
            onComment={canComment ? setQuote : undefined}
            onReady={onReady}
          />
          {(n.references ?? []).length > 0 && (
            <section aria-labelledby="refs-h">
              <h2 id="refs-h" className="text-lg font-semibold mb-2">Sources</h2>
              <ul className="space-y-1">
                {(n.references ?? []).map((ref, i) => (
                  <li key={i} className="flex items-start gap-2 text-sm">
                    {ref.found ? (
                      <CheckCircle2 className="size-4 mt-0.5 text-emerald-600 dark:text-emerald-400 shrink-0" aria-label="Quoted words found on the page" />
                    ) : (
                      <CircleHelp className="size-4 mt-0.5 text-marigold-600 shrink-0" aria-label="Quoted words not found on the page" />
                    )}
                    <span>
                      {ref.documentId && ref.versionId && ref.page ? (
                        <Link className="hover:underline" to={`/documents/${ref.documentId}?version=${ref.versionId}&page=${ref.page}`}>
                          {ref.label}
                        </Link>
                      ) : (
                        ref.label
                      )}
                      {ref.note && <span className="text-muted">: {ref.note}</span>}
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          )}
          <QaPanel
            resourceId={r.id}
            versionId={d.version.id}
            versionNo={d.version.versionNo}
            onShowItem={(i) => !!i.original && findQuote(editor.current, i.original)}
          />
        </div>

        <aside className="space-y-6 xl:sticky xl:top-6">
          <ReviewPanel
            detail={d}
            kindLabel="Notes"
            dirty={page.dirty}
            edited={n}
            saveFirst={page.saveDraft}
            editing={page.reviewerEditing}
            onReviewerEdit={(on) => {
              page.setReviewerEditing(on)
              if (!on) page.discard()
            }}
            onEdit={topicsChanged ? undefined : page.startDraft}
          />
          <section aria-labelledby="export-h">
            <h2 id="export-h" className="font-semibold mb-2">Download version {d.version.versionNo}</h2>
            <div className="flex gap-2">
              <a className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface px-3 h-9 text-sm hover:bg-surface-2" href={exportUrl(d.version.id, 'pdf')}>
                <Download className="size-4" aria-hidden /> PDF
              </a>
              <a className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface px-3 h-9 text-sm hover:bg-surface-2" href={exportUrl(d.version.id, 'docx')}>
                <Download className="size-4" aria-hidden /> Word
              </a>
            </div>
            {page.dirty && <p className="text-sm text-muted mt-1">Downloads use the saved version, not unsaved changes.</p>}
          </section>
          <Comments
            resourceId={r.id}
            versionId={d.version.id}
            versionNo={d.version.versionNo}
            canComment={canComment}
            meId={me.data?.user?.id}
            pendingQuote={quote}
            clearQuote={() => setQuote(null)}
            onShowQuote={(q) => findQuote(editor.current, q)}
          />
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
