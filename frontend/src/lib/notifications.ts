import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import { desktop } from './desktop'

// ---- notifications (NotificationController.java) ---------------------------------------------

export type NotificationKind =
  | 'REVIEW_REQUESTED' | 'APPROVED' | 'CHANGES_REQUESTED' | 'REJECTED' | 'OUTDATED'
  | 'JOB_DONE' | 'JOB_FAILED' | 'RENDER_DONE' | 'RENDER_FAILED' | 'PUBLISHED' | 'PUBLISH_FAILED'

export interface AppNotification {
  id: string
  projectId?: string | null
  projectName?: string | null
  kind: NotificationKind
  title: string
  body?: string | null
  link?: string | null
  createdAt: string
  readAt?: string | null
}

export const isProblem = (k: NotificationKind) => k === 'JOB_FAILED' || k === 'RENDER_FAILED' || k === 'PUBLISH_FAILED' || k === 'REJECTED' || k === 'CHANGES_REQUESTED'

/** Links inside the app open with the router; a YouTube address opens in a new tab. */
export const isExternal = (link?: string | null) => !!link && /^https?:\/\//.test(link)

export function useUnreadCount(enabled = true) {
  return useQuery({
    queryKey: ['notifications', 'unread'],
    queryFn: () => api.get<number>('/notifications/unread'),
    enabled,
    refetchInterval: 60_000,
    // the desktop app keeps checking while it is in the tray, so Windows notifications arrive
    refetchIntervalInBackground: !!desktop(),
  })
}

export function useNotifications(open: boolean) {
  return useQuery({
    queryKey: ['notifications', 'list'],
    queryFn: () => api.get<{ items: AppNotification[]; unread: number }>('/notifications?limit=30'),
    enabled: open,
  })
}

export function useNotificationAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ action, id }: { action: 'read' | 'readAll' | 'delete'; id?: string }) =>
      action === 'readAll' ? api.post('/notifications/read') : action === 'read' ? api.post(`/notifications/${id}/read`) : api.delete(`/notifications/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['notifications'] }),
  })
}

// ---- the education dashboard (EducationDashboard.java) ----------------------------------------

export interface EducationCounts {
  subjectProjects: number
  documents: number
  analysedModules: number
  approvedTopics: number
  notes: number
  approvedNotes: number
  questions: number
  qaReports: number
  approvedQaReports: number
  pendingApprovals: number
  approvedSeoReports: number
  presentations: number
  approvedPresentations: number
  videos: number
  approvedVideos: number
  publishedVideos: number
  activeJobs: number
  topicAnalyses: number
  approvedTopicAnalyses: number
}

export interface DashTask {
  kind: 'REVIEW' | 'CHANGES' | 'PUBLISH' | 'FAILED_JOB'
  projectId: string
  projectName: string
  resourceId?: string | null
  resourceKind: string
  title: string
  versionNo?: number | null
  since?: string | null
  byName?: string | null
}

export interface ActiveJob {
  kind: 'AI' | 'RENDER'
  projectId?: string | null
  projectName?: string | null
  what: string
  status: string
  progress?: number | null
  startedAt: string
  target?: string | null
}

export interface ActivityItem {
  at: string
  action: string
  resourceKind: string
  resourceId: string
  title: string
  projectId: string
  projectName: string
  byName?: string | null
  versionNo?: number | null
  comment?: string | null
}

export interface EducationOverview {
  counts: EducationCounts
  tasks: DashTask[]
  active: ActiveJob[]
  activity: ActivityItem[]
}

export function useEducationDashboard() {
  return useQuery({
    queryKey: ['dashboard', 'education'],
    queryFn: () => api.get<EducationOverview>('/dashboard/education'),
    refetchInterval: (q) => ((q.state.data?.active.length ?? 0) > 0 ? 15_000 : false),
  })
}

/** Where a piece of reviewed work opens (mirrors leenax_resource_link in V18). */
export function resourceLink(kind: string, projectId: string, resourceId?: string | null): string {
  switch (kind) {
    case 'SYLLABUS': return `/projects/${projectId}/syllabus`
    case 'TOPICS': return `/projects/${projectId}/topics`
    case 'TOPIC_ANALYSIS': return `/topic-analysis/${resourceId}`
    case 'NOTES': return `/notes/${resourceId}`
    case 'MCQ': return `/mcq/${resourceId}`
    case 'QA': return `/qa-report/${resourceId}`
    case 'SEO': return `/seo/${resourceId}`
    case 'SLIDES': return `/slides/${resourceId}`
    case 'LESSON_VIDEO':
    case 'QUIZ_VIDEO': return `/videos/${resourceId}`
    default: return `/projects/${projectId}`
  }
}

/** Where a task is done. */
export function taskLink(t: DashTask): string {
  if (t.kind === 'PUBLISH') return `/projects/${t.projectId}/youtube`
  if (t.kind === 'FAILED_JOB') {
    const page: Record<string, string> = { SYLLABUS: 'syllabus', TOPIC_ANALYSIS: 'analysis', NOTES: 'materials', MCQ: 'materials', QA: 'qa', SEO: 'seo', SLIDES: 'slides', NARRATION: 'videos' }
    return `/projects/${t.projectId}/${page[t.resourceKind] ?? ''}`.replace(/\/$/, '')
  }
  return resourceLink(t.resourceKind, t.projectId, t.resourceId)
}

export const KIND_NAME: Record<string, string> = {
  SYLLABUS: 'Syllabus report', TOPICS: 'Topic list', TOPIC_ANALYSIS: 'Topic analysis', NOTES: 'Notes', MCQ: 'Question bank', QA: 'QA report', SEO: 'SEO report',
  SLIDES: 'Presentation', LESSON_VIDEO: 'Lesson video', QUIZ_VIDEO: 'Quiz video', NARRATION: 'Narration',
}

export const ACTION_NAME: Record<string, string> = {
  SUBMITTED: 'sent for review', WITHDRAWN: 'withdrawn from review', APPROVED: 'approved', APPROVED_WITH_EDITS: 'approved with edits',
  CHANGES_REQUESTED: 'sent back for changes', REJECTED: 'rejected', OUTDATED: 'marked outdated', ARCHIVED: 'archived',
}

export function taskText(t: DashTask): string {
  switch (t.kind) {
    case 'REVIEW': return `Review ${KIND_NAME[t.resourceKind]?.toLowerCase() ?? 'work'}: ${t.title}${t.byName ? ` (by ${t.byName})` : ''}`
    case 'CHANGES': return `Make the changes asked for: ${t.title}`
    case 'PUBLISH': return `Ready to publish on YouTube: ${t.title}`
    case 'FAILED_JOB': return `${KIND_NAME[t.resourceKind] ?? 'A job'} failed: ${t.title}`
  }
}
