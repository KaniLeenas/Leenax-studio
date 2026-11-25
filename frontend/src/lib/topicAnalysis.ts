import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { Level } from './materials'

// ---- vocabulary (matches TopicAnalysis.java) ------------------------------------------------------

export const BLOOM = ['REMEMBER', 'UNDERSTAND', 'APPLY', 'ANALYSE', 'EVALUATE', 'CREATE'] as const
export const BLOOM_LABEL: Record<string, string> = {
  REMEMBER: 'Remember', UNDERSTAND: 'Understand', APPLY: 'Apply', ANALYSE: 'Analyse', EVALUATE: 'Evaluate', CREATE: 'Create',
}
export const BASIS_LABEL: Record<string, string> = { DOCUMENT: 'From the course documents', GENERAL: 'General knowledge (no source)' }
export const SHOWS = ['CONCEPT', 'DEFINITION', 'DIAGRAM', 'EXAMPLE', 'COMPARISON', 'APPLICATIONS', 'SUMMARY'] as const
export const SHOWS_LABEL: Record<string, string> = {
  CONCEPT: 'Concept', DEFINITION: 'Definition', DIAGRAM: 'Diagram', EXAMPLE: 'Example', COMPARISON: 'Comparison', APPLICATIONS: 'Applications', SUMMARY: 'Summary',
}
export const EXAMPLE_LABEL: Record<string, string> = { WORKED: 'Worked example', CODE: 'Code', CALCULATION: 'Calculation', CASE: 'Case study' }

// ---- content ---------------------------------------------------------------------------------------

export interface Source { documentId?: string | null; versionId?: string | null; title?: string | null; page?: number | null; quote?: string | null; found?: boolean | null }
export interface Item { text: string; basis?: string | null; sources?: Source[] }
export interface Objective { id: string; text: string; bloom?: string | null; outcomes?: string[]; sources?: Source[] }
export interface Concept { id: string; term: string; definition?: string | null; basis?: string | null; sources?: Source[] }
export interface Misconception { number?: number; wrong: string; right?: string | null; concept?: string | null; basis?: string | null }
export interface Example { title: string; kind?: string | null; concepts?: string[]; sources?: Source[] }
export interface McqPlan { objective: string; count: number; difficulties?: Record<string, number>; styles?: string[] }
export interface Scene { title: string; goal?: string | null; shows?: string | null; seconds: number; concepts?: string[] }

export interface TopicAnalysis {
  topicId: string
  topicTitle: string
  topicPath?: string | null
  topicsVersionId?: string | null
  summary?: string | null
  difficulty?: { level?: string | null; why?: string | null } | null
  prerequisites: Item[]
  objectives: Objective[]
  concepts: Concept[]
  misconceptions: Misconception[]
  examples: Example[]
  examRelevance: Item[]
  mcqCount?: number | null
  mcqPlan: McqPlan[]
  videoMinutes?: number | null
  videoPlan?: { hook?: string | null; scenes: Scene[] } | null
  gaps: { text: string }[]
  questions: { text: string }[]
  analysis?: { model?: string; madeAt?: string; quotesKept?: number; quotesFound?: number; quotesRemoved?: number } | null
}

/** Totals the page shows next to the plan (the same sums the approval checks make). */
export function planTotals(a: TopicAnalysis) {
  const questions = (a.mcqPlan ?? []).reduce((n, p) => n + (p.count || 0), 0)
  const seconds = (a.videoPlan?.scenes ?? []).reduce((n, s) => n + (s.seconds || 0), 0)
  const target = (a.videoMinutes ?? 8) * 60
  return { questions, questionsOk: a.mcqCount == null || questions === a.mcqCount, seconds, target, timeOk: Math.abs(seconds - target) <= target / 4 }
}

/** The next free id for a new objective (O4) or concept (C7). */
export function nextId(prefix: string, taken: string[]) {
  let k = taken.length + 1
  while (taken.includes(`${prefix}${k}`)) k++
  return `${prefix}${k}`
}

export const clock = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`

// ---- the project page ------------------------------------------------------------------------------

export interface AutomationSettings {
  enabled: boolean
  accountId?: string | null
  accountName?: string | null
  model?: string | null
  notesLevel: Level
  mcqCount: number
  videoMinutes: number
  autoNotes: boolean
  autoMcq: boolean
  autoSlides: boolean
  updatedByName?: string | null
  updatedAt?: string | null
}

export type StepStatus = 'NONE' | 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'CHANGES_REQUESTED' | 'REJECTED' | 'OUTDATED' | 'ARCHIVED'

export interface Step { kind: string; label: string; status: StepStatus; resourceId?: string | null; job?: string | null; jobError?: string | null; auto: boolean }

export interface AnalysisTopic {
  topicId: string
  title: string
  path?: string | null
  resourceId?: string | null
  status: StepStatus
  versionNo?: number | null
  approvedNo?: number | null
  job?: string | null
  jobError?: string | null
  jobAuto: boolean
  jobAt?: string | null
  next: Step[]
}

export interface AnalysisOverview {
  topicsApproved: boolean
  topicsVersionNo?: number | null
  settings: AutomationSettings
  topics: AnalysisTopic[]
  canEdit: boolean
  canReview: boolean
  counts: Record<string, number>
}

export const jobLive = (j?: string | null) => j === 'QUEUED' || j === 'RUNNING'
const anyLive = (d?: AnalysisOverview) => !!d?.topics.some((t) => jobLive(t.job) || t.next.some((s) => jobLive(s.job)))

export function useTopicAnalysis(projectId: string) {
  const qc = useQueryClient()
  return useQuery({
    queryKey: ['topic-analysis', projectId],
    queryFn: async () => {
      const data = await api.get<AnalysisOverview>(`/projects/${projectId}/topic-analysis`)
      if (!anyLive(data)) qc.invalidateQueries({ queryKey: ['reviews'] })
      return data
    },
    enabled: !!projectId,
    refetchInterval: (q) => (anyLive(q.state.data) ? 5000 : false),
  })
}

export function useSaveAutomation(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (s: Omit<AutomationSettings, 'accountName' | 'updatedByName' | 'updatedAt'>) =>
      api.put<AutomationSettings>(`/projects/${projectId}/topic-analysis/settings`, s),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['topic-analysis', projectId] }),
  })
}

export function useRunAnalysis(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: { topicIds?: string[]; accountId?: string; model?: string; guidance?: string }) =>
      api.post<{ queued: number; skipped: number; notes: string[] }>(`/projects/${projectId}/topic-analysis/run`, req),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['topic-analysis', projectId] }),
  })
}

export interface NextSteps { projectId: string; topicId: string; topicTitle: string; notesLevel: Level; steps: Step[]; settings: AutomationSettings }

export function useNextSteps(resourceId?: string, poll = false) {
  return useQuery({
    queryKey: ['topic-analysis', 'next', resourceId],
    queryFn: () => api.get<NextSteps>(`/topic-analysis/${resourceId}/next`),
    enabled: !!resourceId,
    refetchInterval: (q) => (poll || q.state.data?.steps.some((s) => jobLive(s.job)) ? 5000 : false),
  })
}

/** Where a step's work opens. */
export function stepLink(projectId: string, s: Step): string {
  if (s.resourceId) {
    const at: Record<string, string> = { NOTES: 'notes', MCQ: 'mcq', QA: 'qa-report', SEO: 'seo', SLIDES: 'slides', LESSON_VIDEO: 'videos' }
    return `/${at[s.kind]}/${s.resourceId}`
  }
  const page: Record<string, string> = { NOTES: 'materials', MCQ: 'materials', QA: 'qa', SEO: 'seo', SLIDES: 'slides', LESSON_VIDEO: 'videos' }
  return `/projects/${projectId}/${page[s.kind] ?? ''}`
}

/** Why a step hasn't started automatically, in words. */
export function waitingFor(s: Step, all: Step[], st: AutomationSettings): string {
  const done = (k: string) => all.find((x) => x.kind === k)?.status === 'APPROVED'
  if (s.kind === 'NOTES') return st.enabled && st.autoNotes ? 'Starts when the analysis is approved' : 'Write them on the notes page'
  if (s.kind === 'MCQ') return !done('NOTES') ? 'Waits for the notes to be approved' : st.enabled && st.autoMcq ? 'Starts automatically' : 'Write them on the notes page'
  if (s.kind === 'QA') return !done('NOTES') ? 'Waits for the notes' : 'Run QA and make the report (needs a person)'
  if (s.kind === 'SEO') return 'Research it on the SEO page (needs a person)'
  if (s.kind === 'SLIDES') {
    const missing = ['NOTES', 'QA', 'SEO'].filter((k) => !done(k)).map((k) => all.find((x) => x.kind === k)?.label ?? k)
    if (missing.length) return `Waits for: ${missing.join(', ')}`
    return st.enabled && st.autoSlides ? 'Starts automatically' : 'Make it on the presentation page'
  }
  if (s.kind === 'LESSON_VIDEO') return done('SLIDES') ? 'Make it on the videos page (narration and rendering)' : 'Made from the approved presentation'
  return ''
}
