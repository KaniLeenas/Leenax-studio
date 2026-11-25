import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'

// ---- types (match ReviewDtos.java) --------------------------------------------------------------

export type ReviewKind = 'syllabus' | 'topics'
export type ReviewStatus = 'DRAFT' | 'IN_REVIEW' | 'CHANGES_REQUESTED' | 'APPROVED' | 'REJECTED' | 'OUTDATED' | 'ARCHIVED'
export type Origin = 'AI' | 'MANUAL' | 'EDIT' | 'REVIEWER'
export type Decision = 'APPROVE' | 'APPROVE_WITH_EDITS' | 'REQUEST_CHANGES' | 'REJECT'

export interface Check {
  code: string
  ok: boolean
  blocking: boolean
  message: string
}

export interface VersionSummary {
  id: string
  resourceId: string
  versionNo: number
  status: ReviewStatus
  origin: Origin
  contentHash: string
  basedOn?: string | null
  dependsOn?: string | null
  createdById?: string | null
  createdBy?: string | null
  createdAt: string
  updatedAt: string
}

export interface You {
  canEdit: boolean
  canReview: boolean
  isAuthor: boolean
  mayDecide: boolean
  reviewerMustDiffer: boolean
}

export interface VersionDetail<C = unknown> {
  version: VersionSummary
  content: C
  checks: Check[]
  latest: boolean
  you: You
}

export interface ResourceState<C = unknown> {
  kind: 'SYLLABUS' | 'TOPICS' | 'TOPIC_ANALYSIS' | 'NOTES' | 'MCQ' | 'QA' | 'SEO' | 'SLIDES' | 'LESSON_VIDEO' | 'QUIZ_VIDEO'
  resource?: { id: string; projectId: string; title: string; topicId?: string | null; level?: string | null; createdAt: string; updatedAt: string } | null
  versions: VersionSummary[]
  latest?: VersionDetail<C> | null
  approvedVersionId?: string | null
  approvedVersionNo?: number | null
  canEdit: boolean
  canReview: boolean
}

export interface ReviewEvent {
  id: string
  versionId: string
  versionNo: number
  action: string
  previousStatus?: string | null
  newStatus: string
  comment?: string | null
  details?: { changes?: string[]; reviewedVersion?: number; origin?: string; note?: string } | null
  actor?: string | null
  createdAt: string
}

// ---- words --------------------------------------------------------------------------------------

export const STATUS_LABEL: Record<ReviewStatus, string> = {
  DRAFT: 'Draft',
  IN_REVIEW: 'In review',
  CHANGES_REQUESTED: 'Changes requested',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  OUTDATED: 'Outdated',
  ARCHIVED: 'Archived',
}

export const statusTone = (s: ReviewStatus): 'neutral' | 'progress' | 'done' | 'muted' =>
  s === 'APPROVED' ? 'done' : s === 'IN_REVIEW' ? 'progress' : s === 'DRAFT' || s === 'CHANGES_REQUESTED' ? 'neutral' : 'muted'

export const ORIGIN_LABEL: Record<Origin, string> = {
  AI: 'written by the AI analysis',
  MANUAL: 'started by hand',
  EDIT: 'edited',
  REVIEWER: "the reviewer's edits",
}

export const ACTION_LABEL: Record<string, string> = {
  CREATED: 'Created',
  SUBMITTED: 'Sent for review',
  WITHDRAWN: 'Withdrawn from review',
  APPROVED: 'Approved',
  APPROVED_WITH_EDITS: 'Approved with edits',
  CHANGES_REQUESTED: 'Asked for changes',
  REJECTED: 'Rejected',
  OUTDATED: 'Marked outdated',
  ARCHIVED: 'Archived',
}

/** What to tell someone looking at a version, about what they can do next. */
/** "Syllabus" → "syllabus", but "QA report" stays as it is. */
const inSentence = (s: string) => (/^[A-Z]{2}/.test(s) ? s : s.charAt(0).toLowerCase() + s.slice(1))

export function nextStep(d: VersionDetail, kindLabel: string): string {
  const s = d.version.status
  const blocked = d.checks.filter((c) => c.blocking && !c.ok).length
  switch (s) {
    case 'DRAFT':
      if (!d.you.canEdit) return 'A draft. Its authors will send it for review.'
      return blocked > 0 ? `Fix ${blocked === 1 ? 'the item' : `the ${blocked} items`} marked below, then send it for review.` : 'Ready to send for review.'
    case 'IN_REVIEW':
      if (d.you.mayDecide) return `Check the ${inSentence(kindLabel)} and decide.`
      if (d.you.canReview && d.you.isAuthor) return 'You wrote this version, so another reviewer must decide.'
      return 'Waiting for a reviewer.'
    case 'CHANGES_REQUESTED':
      return d.you.canEdit ? 'Edit it to make a new version with the changes.' : 'Sent back to its authors for changes.'
    case 'REJECTED':
      return d.you.canEdit ? 'Rejected. Edit it to start a new version.' : 'Rejected.'
    case 'APPROVED':
      return d.you.canEdit ? 'Approved. Editing makes a new draft; this version stays approved until a new one is.' : 'Approved.'
    case 'OUTDATED':
      return 'Something it was built on has changed.'
    case 'ARCHIVED':
      return 'Replaced by a newer version.'
  }
}

// ---- queries ------------------------------------------------------------------------------------

export function useReviewState<C>(projectId: string, kind: ReviewKind) {
  return useQuery({
    queryKey: ['reviews', projectId, kind],
    queryFn: () => api.get<ResourceState<C>>(`/projects/${projectId}/reviews/${kind}`),
    enabled: !!projectId,
  })
}

/** Where a page's resource is: the project's syllabus or topics, or a notes or bank resource by id. */
export type ReviewTarget = { projectId: string; kind: ReviewKind } | { resourceId: string }

export function useReviewTarget<C>(t: ReviewTarget) {
  const byId = 'resourceId' in t
  return useQuery({
    queryKey: byId ? ['reviews', 'resource', t.resourceId] : ['reviews', t.projectId, t.kind],
    queryFn: () => api.get<ResourceState<C>>(byId ? `/reviews/resources/${t.resourceId}` : `/projects/${t.projectId}/reviews/${t.kind}`),
    enabled: byId ? !!t.resourceId : !!t.projectId,
  })
}

export function useVersion<C>(versionId: string | undefined) {
  return useQuery({
    queryKey: ['reviews', 'version', versionId],
    queryFn: () => api.get<VersionDetail<C>>(`/reviews/versions/${versionId}`),
    enabled: !!versionId,
  })
}

export function useReviewEvents(resourceId: string | undefined) {
  return useQuery({
    queryKey: ['reviews', 'events', resourceId],
    queryFn: () => api.get<ReviewEvent[]>(`/reviews/resources/${resourceId}/events`),
    enabled: !!resourceId,
  })
}

function useDone() {
  const qc = useQueryClient()
  return () => qc.invalidateQueries({ queryKey: ['reviews'] })
}

export function useSaveDraft() {
  const done = useDone()
  return useMutation({
    mutationFn: ({ versionId, content }: { versionId: string; content: unknown }) =>
      api.put<VersionDetail>(`/reviews/versions/${versionId}`, { content }),
    onSuccess: done,
  })
}

export function useEditVersion() {
  const done = useDone()
  return useMutation({
    mutationFn: ({ resourceId, fromVersionId, content }: { resourceId: string; fromVersionId: string; content?: unknown }) =>
      api.post<VersionDetail>(`/reviews/resources/${resourceId}/versions`, { fromVersionId, content }),
    onSuccess: done,
  })
}

export function useSubmit() {
  const done = useDone()
  return useMutation({
    mutationFn: ({ versionId, comment }: { versionId: string; comment?: string }) =>
      api.post<VersionDetail>(`/reviews/versions/${versionId}/submit`, { comment }),
    onSuccess: done,
  })
}

export function useWithdraw() {
  const done = useDone()
  return useMutation({
    mutationFn: (versionId: string) => api.post<VersionDetail>(`/reviews/versions/${versionId}/withdraw`, {}),
    onSuccess: done,
  })
}

export function useDecide() {
  const done = useDone()
  return useMutation({
    mutationFn: ({ versionId, decision, comment, content }: { versionId: string; decision: Decision; comment?: string; content?: unknown }) =>
      api.post<VersionDetail>(`/reviews/versions/${versionId}/decision`, { decision, comment, content }),
    onSuccess: done,
  })
}
