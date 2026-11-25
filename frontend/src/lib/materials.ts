import type { JSONContent } from '@tiptap/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { ResourceState } from './reviews'

// ---- content (matches Learning.java) ------------------------------------------------------------

export const LEVELS = ['BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'UNIVERSITY', 'EXAM'] as const
export type Level = (typeof LEVELS)[number]
export const LEVEL_LABEL: Record<Level, string> = {
  BEGINNER: 'Beginner',
  INTERMEDIATE: 'Intermediate',
  ADVANCED: 'Advanced',
  UNIVERSITY: 'University',
  EXAM: 'Exam-focused',
}

export const STYLES = ['BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'APPLICATION', 'SCENARIO', 'CODE', 'CALCULATION', 'EXAM'] as const
export type Style = (typeof STYLES)[number]
export const STYLE_LABEL: Record<Style, string> = {
  BEGINNER: 'Beginner',
  INTERMEDIATE: 'Intermediate',
  ADVANCED: 'Advanced',
  APPLICATION: 'Application',
  SCENARIO: 'Scenario',
  CODE: 'Code',
  CALCULATION: 'Calculation',
  EXAM: 'Exam-focused',
}

export const DIFFICULTIES = ['BEGINNER', 'INTERMEDIATE', 'ADVANCED'] as const
export type QDifficulty = (typeof DIFFICULTIES)[number]

export interface Reference {
  label: string
  documentId?: string | null
  versionId?: string | null
  page?: number | null
  note?: string | null
  found?: boolean | null
}

export interface Notes {
  topicId: string
  topicTitle?: string | null
  level: Level
  topicsVersionId?: string | null
  doc: JSONContent
  references?: Reference[]
  model?: string | null
}

export interface Question {
  id: string
  style: Style
  difficulty?: QDifficulty | null
  stem?: string | null
  code?: { language?: string | null; text: string } | null
  options: string[]
  answer?: number | null
  explanation?: string | null
  optionExplanations?: string[] | null
  subtopic?: string | null
  source?: Reference | null
  verification?: 'UNVERIFIED' | 'PASSED' | 'FAILED'
}

export interface Bank {
  topicId: string
  topicTitle?: string | null
  notesVersionId?: string | null
  notesLevel?: Level | null
  allowAllNone?: boolean
  questions: Question[]
}

export const LETTERS = ['A', 'B', 'C', 'D']

/** "All/none of the above" and friends, in English and Tamil (mirrors McqRules.java). */
const ALL_NONE =
  /(^|[^\p{L}\p{N}])(all|none|both|neither)\s+of\s+(the\s+)?(above|these|them|those)([^\p{L}\p{N}]|$)|(^|[^\p{L}\p{N}])all\s+the\s+above([^\p{L}\p{N}]|$)|மேற்கண்ட\s*அனைத்தும்|மேலே\s*உள்ள\s*அனைத்தும்|மேற்கூறிய\s*அனைத்தும்|இவை\s*அனைத்தும்|எதுவுமில்லை|எதுவும்\s*இல்லை/iu

const norm = (s?: string | null) => (s ?? '').normalize('NFC').toLowerCase().match(/[\p{L}\p{M}\p{N}]+/gu)?.join(' ') ?? ''

/** What's wrong with a question, as the server will say it (empty when fine). */
export function questionProblems(q: Question, allowAllNone: boolean): string[] {
  const out: string[] = []
  if (!q.stem?.trim()) out.push('no question text')
  const opts = q.options ?? []
  if (opts.length !== 4 || opts.some((o) => !o?.trim())) out.push('needs four options')
  else if (new Set(opts.map(norm)).size < 4) out.push('options repeat')
  if (q.answer == null || q.answer < 0 || q.answer > 3) out.push('no correct answer marked')
  if (!q.explanation?.trim()) out.push('no explanation')
  if (!allowAllNone && opts.some((o) => o && ALL_NONE.test(o))) out.push('“all/none of the above” option')
  return out
}

export function newQuestion(): Question {
  return { id: 'q' + Math.random().toString(16).slice(2, 12), style: 'EXAM', difficulty: 'INTERMEDIATE', stem: '', options: ['', '', '', ''], answer: null, explanation: '', verification: 'UNVERIFIED' }
}

// ---- overview -----------------------------------------------------------------------------------

export interface TopicRow {
  id: string
  title: string
  depth: number
  path?: string | null
  difficulty?: string | null
}

export interface Material {
  resourceId: string
  kind: 'NOTES' | 'MCQ'
  topicId: string
  level?: Level | null
  title: string
  latestNo?: number | null
  latestStatus?: string | null
  approvedNo?: number | null
  approvedVersionId?: string | null
  questionCount?: number | null
}

export interface MaterialJob {
  id: string
  kind: 'NOTES' | 'MCQ'
  target: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  error?: string | null
  summary?: string | null
  createdAt: string
  startedBy?: string | null
  resultVersionId?: string | null
}

export interface MaterialsOverview {
  topicsApproved: boolean
  topicsVersionNo?: number | null
  topics: TopicRow[]
  notes: Material[]
  banks: Material[]
  jobs: MaterialJob[]
  canEdit: boolean
  canReview: boolean
}

export const jobRunning = (j?: MaterialJob | null) => j?.status === 'QUEUED' || j?.status === 'RUNNING'

export function useMaterials(projectId: string) {
  const qc = useQueryClient()
  return useQuery({
    queryKey: ['materials', projectId],
    queryFn: async () => {
      const data = await api.get<MaterialsOverview>(`/projects/${projectId}/materials`)
      if (!data.jobs.some(jobRunning)) qc.invalidateQueries({ queryKey: ['reviews'] })
      return data
    },
    enabled: !!projectId,
    refetchInterval: (q) => (q.state.data?.jobs.some(jobRunning) ? 4000 : false),
  })
}

function useDone() {
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: ['materials'] })
    qc.invalidateQueries({ queryKey: ['reviews'] })
  }
}

export function useGenerateNotes(projectId: string) {
  const done = useDone()
  return useMutation({
    mutationFn: (req: { topicId: string; level: Level; accountId: string; model: string; guidance?: string }) =>
      api.post<MaterialJob>(`/projects/${projectId}/notes/generate`, req),
    onSuccess: done,
  })
}

export function useGenerateQuestions(projectId: string) {
  const done = useDone()
  return useMutation({
    mutationFn: (req: {
      topicId: string
      notesLevel: Level
      count: number
      difficulties: string[]
      styles: string[]
      accountId: string
      model: string
      guidance?: string
      allowAllNone: boolean
    }) => api.post<MaterialJob>(`/projects/${projectId}/mcq/generate`, req),
    onSuccess: done,
  })
}

export function useByHand(projectId: string, kind: 'notes' | 'mcq') {
  const done = useDone()
  return useMutation({
    mutationFn: (req: { topicId: string; level?: Level }) => api.post<ResourceState>(`/projects/${projectId}/${kind}/manual`, req),
    onSuccess: done,
  })
}

export function useUpdateFromUpstream() {
  const done = useDone()
  return useMutation({ mutationFn: (resourceId: string) => api.post<ResourceState>(`/materials/${resourceId}/update`, {}), onSuccess: done })
}

export function regenerateQuestion(resourceId: string, req: { versionId: string; questionId: string; accountId: string; model: string; guidance?: string }) {
  return api.post<Question>(`/mcq/${resourceId}/regenerate`, req)
}

export const exportUrl = (versionId: string, format: string) => `/api/v1/exports/versions/${versionId}?format=${format}`
export const quizExportUrl = (quizId: string, format: string, answers: 'end' | 'none') => `/api/v1/exports/quizzes/${quizId}?format=${format}&answers=${answers}`

// ---- quizzes ------------------------------------------------------------------------------------

export interface QuizQuestion {
  number: number
  bankId: string
  bankTitle: string
  bankVersionNo: number
  questionId: string
  style: Style
  difficulty?: QDifficulty | null
  stem: string
  code?: { language?: string | null; text: string } | null
  options: string[]
  answer: number
  explanation?: string | null
  optionExplanations?: string[] | null
  subtopic?: string | null
  source?: string | null
  verification?: string | null
}

export interface Quiz {
  id: string
  title: string
  seed: number
  config: Record<string, unknown>
  questions: QuizQuestion[]
  createdBy?: string | null
  createdAt: string
}

export interface QuizSummary {
  id: string
  title: string
  questionCount: number
  seed: number
  createdBy?: string | null
  createdAt: string
}

export function useQuizzes(projectId: string) {
  return useQuery({ queryKey: ['quizzes', projectId], queryFn: () => api.get<QuizSummary[]>(`/projects/${projectId}/quizzes`), enabled: !!projectId })
}

export function useQuiz(id: string | null) {
  return useQuery({ queryKey: ['quizzes', 'one', id], queryFn: () => api.get<Quiz>(`/quizzes/${id}`), enabled: !!id })
}

export function useCreateQuiz(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: { title: string; bankIds: string[]; count: number; difficulties: string[]; styles: string[]; shuffleOptions: boolean; seed?: number }) =>
      api.post<Quiz>(`/projects/${projectId}/quizzes`, req),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['quizzes', projectId] }),
  })
}

export function useDeleteQuiz(projectId: string) {
  const qc = useQueryClient()
  return useMutation({ mutationFn: (id: string) => api.delete(`/quizzes/${id}`), onSuccess: () => qc.invalidateQueries({ queryKey: ['quizzes', projectId] }) })
}

// ---- comments -----------------------------------------------------------------------------------

export interface ReviewComment {
  id: string
  versionId: string
  versionNo: number
  quote?: string | null
  body: string
  authorId?: string | null
  author?: string | null
  createdAt: string
  resolvedAt?: string | null
  resolvedBy?: string | null
}

export function useComments(resourceId?: string) {
  return useQuery({ queryKey: ['reviews', 'comments', resourceId], queryFn: () => api.get<ReviewComment[]>(`/reviews/resources/${resourceId}/comments`), enabled: !!resourceId })
}

export function useCommentActions(resourceId?: string) {
  const qc = useQueryClient()
  const set = (list: ReviewComment[]) => qc.setQueryData(['reviews', 'comments', resourceId], list)
  return {
    add: useMutation({ mutationFn: ({ versionId, quote, body }: { versionId: string; quote?: string; body: string }) => api.post<ReviewComment[]>(`/reviews/versions/${versionId}/comments`, { quote, body }), onSuccess: set }),
    resolve: useMutation({ mutationFn: ({ id, resolved }: { id: string; resolved: boolean }) => api.post<ReviewComment[]>(`/reviews/comments/${id}/resolve`, { resolved }), onSuccess: set }),
    remove: useMutation({ mutationFn: (id: string) => api.delete(`/reviews/comments/${id}`), onSuccess: () => qc.invalidateQueries({ queryKey: ['reviews', 'comments', resourceId] }) }),
  }
}
