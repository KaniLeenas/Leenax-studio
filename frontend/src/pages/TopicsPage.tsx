import { ChevronLeft } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { History, ReviewPanel, VersionList } from '../components/review'
import { TreeEditor } from '../components/TreeEditor'
import { Button, EmptyState, ErrorNotice, PageHeader, Spinner } from '../components/ui'
import { useReviewPage } from '../components/useReviewPage'
import { useProject } from '../lib/queries'
import { useReviewState } from '../lib/reviews'
import { flatten, prerequisiteLoop, useUpdateTopics, type Syllabus, type Topics } from '../lib/syllabus'
import { toast } from '../stores/ui'

/** /projects/:id/topics — the topic list, built from the approved syllabus. */
export function TopicsPage() {
  const { id = '' } = useParams()
  const project = useProject(id)
  const page = useReviewPage<Topics>(id, 'topics')
  const syllabus = useReviewState<Syllabus>(id, 'syllabus')
  const build = useUpdateTopics(id)

  if (project.isPending || page.loading || syllabus.isPending) return <Spinner label="Loading topics" />
  if (project.error || page.error || syllabus.error) return <ErrorNotice error={project.error ?? page.error ?? syllabus.error} onRetry={() => page.state.refetch()} />
  const p = project.data
  const state = page.state.data!
  const approvedSyllabus = syllabus.data.approvedVersionId
  const approvedNo = syllabus.data.approvedVersionNo
  const runBuild = () =>
    build.mutate(undefined, {
      onSuccess: () => toast.success(state.resource ? 'Updated from the approved syllabus' : 'Topic list built'),
      onError: (e) => toast.error(e.message),
    })

  const header = (
    <>
      <Link to={`/projects/${p.id}`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        {p.name}
      </Link>
      <PageHeader title="Topics" description="What the course will cover, in order, with difficulty and prerequisites. Built from the approved syllabus." />
    </>
  )

  if (!state.resource || !page.detail || !page.content) {
    return (
      <>
        {header}
        {!approvedSyllabus ? (
          <EmptyState
            title="The syllabus isn't approved yet"
            body="Topics are built only from an approved syllabus, so nothing is planned from an unchecked draft."
            action={
              <Link to={`/projects/${id}/syllabus`} className="underline underline-offset-2">
                Go to the syllabus
              </Link>
            }
          />
        ) : state.canEdit ? (
          <EmptyState
            title="No topic list yet"
            body={`Start from syllabus version ${approvedNo}: every chapter, topic and subtopic becomes a topic you can select, reorder, merge, split, and give a difficulty and prerequisites.`}
            action={
              <Button variant="primary" loading={build.isPending} onClick={runBuild}>
                Build from the approved syllabus
              </Button>
            }
          />
        ) : (
          <EmptyState title="No topic list yet" body="The project's owners and editors build it from the approved syllabus." />
        )}
      </>
    )
  }

  const d = page.detail
  const t = page.content
  const behind = !!approvedSyllabus && t.syllabusVersionId !== approvedSyllabus
  const newestStatus = state.versions[0]?.status
  const all = flatten(t.nodes)
  const selected = all.filter((r) => r.item.selected !== false).length
  const loop = page.editable ? prerequisiteLoop(t.nodes) : null

  return (
    <>
      {header}
      {(behind || d.version.status === 'OUTDATED') && d.latest && (
        <div className="mb-6 rounded-xl border border-marigold-500/50 px-4 py-3 text-sm flex flex-wrap items-center gap-3" role="status">
          <p className="flex-1 min-w-0">
            {approvedSyllabus
              ? `The approved syllabus is now version ${approvedNo}. Update the list to bring in its changes; your names, order, merges, difficulty and prerequisites are kept.`
              : 'The syllabus is being changed. When a new version is approved, update the list from it.'}
          </p>
          {approvedSyllabus && state.canEdit && newestStatus !== 'IN_REVIEW' && (
            <Button size="sm" variant="primary" loading={build.isPending} onClick={runBuild}>
              Update from syllabus version {approvedNo}
            </Button>
          )}
        </div>
      )}

      <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_21rem] items-start">
        <div className="min-w-0">
          {page.dirty && d.version.status === 'DRAFT' && (
            <div className="sticky top-0 z-10 -mx-2 px-2 py-2 mb-3 bg-[var(--bg)] flex items-center gap-3 border-b border-line">
              <span className="text-sm text-muted flex-1">Unsaved changes</span>
              <Button variant="ghost" onClick={page.discard}>
                Discard
              </Button>
              <Button variant="primary" loading={page.saving} onClick={() => page.saveDraft().then(() => toast.success('Draft saved'), (e) => toast.error(e.message))}>
                Save draft
              </Button>
            </div>
          )}
          {page.reviewerEditing && (
            <p className="rounded-xl border border-marigold-500/50 px-4 py-3 text-sm mb-4">
              You're editing as the reviewer. Approving with your edits saves them as a new, approved version.
            </p>
          )}
          <p className="text-sm text-muted mb-3">
            {selected} of {all.length} topics included.
          </p>
          {loop && <p className="text-sm text-rose-signal mb-3">Prerequisites go round in a loop: {loop.join(' → ')}</p>}
          <TreeEditor items={t.nodes} onChange={(nodes) => page.setContent({ ...t, nodes })} editable={page.editable} topics docs={[]} emptyText="No topics." />
        </div>

        <aside className="space-y-6 lg:sticky lg:top-6">
          <ReviewPanel
            detail={d}
            kindLabel="Topics"
            dirty={page.dirty}
            edited={t}
            saveFirst={page.saveDraft}
            editing={page.reviewerEditing}
            onReviewerEdit={(on) => {
              page.setReviewerEditing(on)
              if (!on) page.discard()
            }}
            onEdit={behind ? undefined : page.startDraft}
          />
          <section aria-labelledby="versions-h">
            <h2 id="versions-h" className="font-semibold mb-2">Versions</h2>
            <VersionList versions={state.versions} current={d.version.id} approvedId={state.approvedVersionId} onPick={page.pick} />
          </section>
          <section aria-labelledby="history-h">
            <h2 id="history-h" className="font-semibold mb-2">History</h2>
            <History resourceId={state.resource.id} />
          </section>
        </aside>
      </div>
    </>
  )
}
