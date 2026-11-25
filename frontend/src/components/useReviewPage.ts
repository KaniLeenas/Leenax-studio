import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useEditVersion, useReviewTarget, useSaveDraft, useVersion, type ReviewKind, type ReviewTarget, type VersionDetail } from '../lib/reviews'
import { toast } from '../stores/ui'

/**
 * Which version a syllabus or topics page shows (?version=, else the newest), the edited
 * copy of its content, and saving. Drafts are edited in place; a reviewer may edit a
 * version in review to approve it with edits; anything else is edited by making a new draft.
 */
export function useReviewPage<C>(projectId: string, kind: ReviewKind): ReturnType<typeof useReviewPageAt<C>> {
  return useReviewPageAt<C>({ projectId, kind })
}

export function useReviewPageAt<C>(target: ReviewTarget) {
  const state = useReviewTarget<C>(target)
  const [params, setParams] = useSearchParams()
  const picked = params.get('version')
  const latestId = state.data?.latest?.version.id
  const showingLatest = !picked || picked === latestId
  const other = useVersion<C>(showingLatest ? undefined : picked ?? undefined)
  const detail: VersionDetail<C> | undefined = showingLatest ? (state.data?.latest ?? undefined) : other.data

  const [draft, setDraft] = useState<C | null>(null)
  const [reviewerEditing, setReviewerEditing] = useState(false)
  const stamp = detail ? `${detail.version.id}:${detail.version.updatedAt}` : ''
  useEffect(() => {
    setDraft(null)
    setReviewerEditing(false)
  }, [stamp])

  const content = (draft ?? detail?.content) as C | undefined
  const dirty = draft !== null && detail !== undefined && JSON.stringify(draft) !== JSON.stringify(detail.content)
  const status = detail?.version.status
  const editable =
    !!detail && detail.latest && ((status === 'DRAFT' && detail.you.canEdit) || (status === 'IN_REVIEW' && detail.you.mayDecide && reviewerEditing))

  const newest = state.data?.versions[0]
  const canStartDraft =
    !!detail && !!newest && detail.you.canEdit && newest.status !== 'DRAFT' && newest.status !== 'IN_REVIEW' && status !== 'DRAFT' && status !== 'IN_REVIEW'

  const save = useSaveDraft()
  const edit = useEditVersion()

  useEffect(() => {
    if (!dirty) return
    const warn = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  return useMemo(
    () => ({
      state,
      detail,
      loading: state.isPending || (!showingLatest && other.isPending),
      error: state.error ?? other.error,
      content,
      setContent: (c: C) => setDraft(c),
      discard: () => setDraft(null),
      dirty,
      editable,
      reviewerEditing,
      setReviewerEditing,
      pick: (id: string) => setParams(id === latestId ? {} : { version: id }),
      saving: save.isPending,
      async saveDraft() {
        if (!detail || draft === null) return
        await save.mutateAsync({ versionId: detail.version.id, content: draft })
        setDraft(null)
      },
      startDraft: canStartDraft
        ? () =>
            detail &&
            state.data?.resource &&
            edit.mutate(
              { resourceId: state.data.resource.id, fromVersionId: detail.version.id },
              {
                onSuccess: (r) => {
                  toast.success(`Version ${r.version.versionNo} is a new draft`)
                  setParams({})
                },
                onError: (e) => toast.error(e.message),
              },
            )
        : undefined,
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [state, detail, other.isPending, other.error, content, dirty, editable, reviewerEditing, save.isPending, draft, canStartDraft, latestId, showingLatest],
  )
}
