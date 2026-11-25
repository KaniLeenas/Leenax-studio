import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { Level } from './materials'
import type { ResourceState } from './reviews'

// ---- vocabulary (matches Qa.java) ---------------------------------------------------------------

export type Severity = 'CRITICAL' | 'MAJOR' | 'MINOR'
export type IssueStatus = 'OPEN' | 'CORRECTED' | 'RESOLVED' | 'DISMISSED'
export type Basis = 'UPLOADED' | 'EXTERNAL' | 'AI' | 'ESTIMATE' | 'UNVERIFIED'
export type Headline = 'BLOCKED' | 'NOT_CHECKED' | 'NEEDS_ATTENTION' | 'PASSED'

export const SEVERITY_LABEL: Record<Severity, string> = { CRITICAL: 'Critical', MAJOR: 'Major', MINOR: 'Minor' }
export const STATUS_LABEL: Record<IssueStatus, string> = {
  OPEN: 'Open',
  CORRECTED: 'Corrected, needs a recheck',
  RESOLVED: 'Resolved',
  DISMISSED: 'Dismissed',
}
export const BASIS_LABEL: Record<Basis, string> = {
  UPLOADED: 'Course document',
  EXTERNAL: 'Outside link from the AI (not opened)',
  AI: 'The checker’s own explanation',
  ESTIMATE: 'Estimate',
  UNVERIFIED: 'Unverified',
}
export const TYPE_LABEL: Record<string, string> = {
  FACTUAL_ERROR: 'Factual error',
  WRONG_KEY: 'Wrong answer key',
  AMBIGUOUS: 'Ambiguous question',
  MISSING_TOPIC: 'Missing syllabus topic',
  CONTRADICTION: 'Contradiction',
  UNSUPPORTED_CLAIM: 'Unsupported claim',
  MATH_ERROR: 'Maths error',
  CODE_ERROR: 'Code error',
  INCONSISTENT: 'Inconsistent',
  WEAK_EXPLANATION: 'Weak explanation',
  WEAK_EXAMPLE: 'Weak example',
  BAD_REFERENCE: 'Reference not found',
  COVERAGE_GAP: 'Subtopic not covered',
  WRONG_DIFFICULTY: 'Difficulty label',
  DUPLICATE: 'Repeated question',
  RULE_BREAK: 'Breaks a question rule',
  GRAMMAR: 'Grammar',
  OTHER: 'Other',
}
export const CATEGORY_LABEL: Record<string, string> = {
  ACCURACY: 'Accuracy',
  COVERAGE: 'Syllabus coverage',
  EXPLANATIONS: 'Explanations',
  REFERENCES: 'References',
  MCQ_KEYS: 'MCQ keys',
  DIFFICULTY: 'Difficulty',
  DUPLICATES: 'Duplicates',
}
export const HEADLINE_LABEL: Record<Headline, string> = {
  BLOCKED: 'Blocked',
  NOT_CHECKED: 'Not checked',
  NEEDS_ATTENTION: 'Needs attention',
  PASSED: 'Passed',
}
export const HEADLINE_HELP: Record<Headline, string> = {
  BLOCKED: 'A critical issue is open, so this can’t be approved, whatever the score.',
  NOT_CHECKED: 'Something here hasn’t been checked by QA in its current form.',
  NEEDS_ATTENTION: 'Major issues are open. The reviewer decides whether they matter.',
  PASSED: 'No critical or major issues are open. A person still reads and approves it.',
}

export const isOpen = (s: IssueStatus) => s === 'OPEN' || s === 'CORRECTED'

// ---- data ---------------------------------------------------------------------------------------

export interface Evidence {
  basis: Basis
  label?: string | null
  documentId?: string | null
  versionId?: string | null
  page?: number | null
  quote?: string | null
  found?: boolean | null
  url?: string | null
  note?: string | null
}

export interface Correction {
  id: string
  issueId: string
  proposal: { find?: string; replace?: string; questionId?: string; answer?: number; options?: string[]; stem?: string; explanation?: string }
  status: 'PROPOSED' | 'ACCEPTED' | 'REJECTED'
  appliedVersionId?: string | null
  note?: string | null
  decidedBy?: string | null
  decidedAt?: string | null
  createdAt: string
}

export interface Issue {
  id: string
  number: number
  resourceId: string
  runId: string
  foundInVersionId: string
  foundInVersionNo: number
  itemRef?: string | null
  itemLabel?: string | null
  category: string
  type: string
  severity: Severity
  original?: string | null
  explanation: string
  basis: Basis
  evidence: Evidence[]
  suggestion?: string | null
  confirmations: { model: string; agrees: boolean; note?: string | null }[]
  status: IssueStatus
  statusNote?: string | null
  recheck?: { fixed: boolean; note?: string; versionNo?: number; model?: string; at?: string } | null
  decidedBy?: string | null
  decidedAt?: string | null
  createdAt: string
  correction?: Correction | null
}

export interface Tally {
  checked: number
  passed: number
  failed: number
}

export interface Run {
  id: string
  resourceId: string
  versionId: string
  versionNo: number
  mode: 'CHECK' | 'RECHECK'
  model: string
  provider?: string | null
  secondModel?: string | null
  writerModel?: string | null
  independent: boolean
  result: {
    categories?: Record<string, Tally>
    claims?: { itemRef?: string; claim: string; verdict: string; basis: Basis }[] | null
    questions?: { questionId: string; number: number; key?: number | null; answer?: number | null; secondAnswer?: number | null; verdict: string }[] | null
    limitations?: string[]
    summary?: string | null
  }
  createdBy?: string | null
  createdAt: string
}

export interface QaJob {
  id: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  error?: string | null
  summary?: string | null
  createdAt: string
  startedBy?: string | null
}

export interface ResourceQa {
  resourceId: string
  kind: 'NOTES' | 'MCQ'
  writerModel?: string | null
  runs: Run[]
  issues: Issue[]
  job?: QaJob | null
  canRun: boolean
  canEdit: boolean
  canReview: boolean
}

export const jobBusy = (j?: QaJob | null) => j?.status === 'QUEUED' || j?.status === 'RUNNING'

export function useResourceQa(resourceId?: string) {
  const qc = useQueryClient()
  return useQuery({
    queryKey: ['qa', resourceId],
    queryFn: async () => {
      const prev = qc.getQueryData<ResourceQa>(['qa', resourceId])
      const data = await api.get<ResourceQa>(`/qa/resources/${resourceId}`)
      // a run that just finished may have made a new draft (no) or changed the checks shown in review (yes)
      if (prev && jobBusy(prev.job) && !jobBusy(data.job)) qc.invalidateQueries({ queryKey: ['reviews'] })
      return data
    },
    enabled: !!resourceId,
    refetchInterval: (q) => (jobBusy(q.state.data?.job) ? 4000 : false),
  })
}

function useQaMutation<V>(resourceId: string, fn: (v: V) => Promise<unknown>) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: (data) => {
      if (data && typeof data === 'object' && 'issues' in data) qc.setQueryData(['qa', resourceId], data)
      else qc.invalidateQueries({ queryKey: ['qa', resourceId] })
      qc.invalidateQueries({ queryKey: ['reviews'] })
      qc.invalidateQueries({ queryKey: ['qa-overview'] })
    },
  })
}

export type RunRequest = { versionId?: string; accountId: string; model: string; secondAccountId?: string; secondModel?: string; recheckOnly: boolean }

export function useStartQa(resourceId: string) {
  return useQaMutation(resourceId, (req: RunRequest) => api.post<QaJob>(`/qa/resources/${resourceId}/runs`, req))
}

export function useIssueActions(resourceId: string) {
  return {
    accept: useQaMutation(resourceId, ({ id, note }: { id: string; note?: string }) => api.post<ResourceQa>(`/qa/corrections/${id}/accept`, { note })),
    reject: useQaMutation(resourceId, ({ id, note }: { id: string; note?: string }) => api.post<ResourceQa>(`/qa/corrections/${id}/reject`, { note })),
    dismiss: useQaMutation(resourceId, ({ id, reason }: { id: string; reason: string }) => api.post<ResourceQa>(`/qa/issues/${id}/dismiss`, { reason })),
    reopen: useQaMutation(resourceId, ({ id, reason }: { id: string; reason: string }) => api.post<ResourceQa>(`/qa/issues/${id}/reopen`, { reason })),
  }
}

/** What a proposed correction would do, in words. */
export function describeProposal(p: Correction['proposal'], options?: string[]): string {
  const letter = (i: number) => 'ABCD'[i] ?? '?'
  const parts: string[] = []
  if (p.answer != null) parts.push(`mark ${letter(p.answer)}${options?.[p.answer] ? ` (${options[p.answer]})` : ''} as the answer`)
  if (p.stem) parts.push(`reword the question as “${p.stem}”`)
  if (p.options) parts.push(`change the options to ${p.options.map((o, i) => `${letter(i)}) ${o}`).join(', ')}`)
  if (p.explanation) parts.push(`change the explanation to “${p.explanation}”`)
  if (p.find != null) parts.push(`replace “${p.find}” with “${p.replace ?? ''}”`)
  const s = parts.join('; ')
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : 'No change described'
}

/** Open issues per question, for the bank editor. */
export function openByItem(issues: Issue[]): Map<string, Issue[]> {
  const m = new Map<string, Issue[]>()
  for (const i of issues) {
    if (!isOpen(i.status) || !i.itemRef) continue
    m.set(i.itemRef, [...(m.get(i.itemRef) ?? []), i])
  }
  return m
}

export function counts(issues: Issue[]) {
  const c = { CRITICAL: 0, MAJOR: 0, MINOR: 0, closed: 0 }
  for (const i of issues) {
    if (isOpen(i.status)) c[i.severity]++
    else c.closed++
  }
  return c
}

// ---- overview and reports -----------------------------------------------------------------------

export interface QaItem {
  resourceId: string
  kind: 'NOTES' | 'MCQ'
  level?: Level | null
  versionNo?: number | null
  versionStatus?: string | null
  checkedVersionNo?: number | null
  current: boolean
  open: Partial<Record<Severity, number>>
  running: boolean
}

export interface QaTopic {
  topicId: string
  title: string
  depth: number
  items: QaItem[]
  reportId?: string | null
  reportVersionNo?: number | null
  reportStatus?: string | null
  reportHeadline?: Headline | null
}

export interface QaOverview {
  topicsApproved: boolean
  topics: QaTopic[]
  canEdit: boolean
  canReview: boolean
}

export function useQaOverview(projectId: string) {
  return useQuery({
    queryKey: ['qa-overview', projectId],
    queryFn: () => api.get<QaOverview>(`/projects/${projectId}/qa`),
    enabled: !!projectId,
    refetchInterval: (q) => (q.state.data?.topics.some((t) => t.items.some((i) => i.running)) ? 5000 : false),
  })
}

export function useMakeReport(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (topicId: string) => api.post<ResourceState>(`/projects/${projectId}/qa/reports/${encodeURIComponent(topicId)}`, {}),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['qa-overview', projectId] })
      qc.invalidateQueries({ queryKey: ['reviews'] })
    },
  })
}

export interface ReportItem {
  resourceId: string
  kind: 'NOTES' | 'MCQ'
  level?: Level | null
  label: string
  versionId: string
  versionNo: number
  versionStatus: string
  run?: { runId: string; versionNo: number; model: string; provider?: string | null; secondModel?: string | null; writerModel?: string | null; independent: boolean; at?: string; current: boolean } | null
  categories: Record<string, Tally>
  claimsByBasis: Record<string, number>
  difficulty?: Record<string, number> | null
  questions?: number | null
  openBySeverity: Partial<Record<Severity, number>>
  corrected: number
  dismissed: number
}

export interface ReportPart {
  checked: number
  passed: number
  failed: number
  corrected: number
  open: number
  openCritical: number
  score?: number | null
  headline: Headline
  categories: Record<string, Tally>
}

export interface QaReport {
  topicId: string
  topicTitle: string
  generatedAt: string
  items: ReportItem[]
  issues: { number: number; resourceId: string; resource: string; where?: string | null; severity: Severity; type: string; category: string; status: IssueStatus; explanation: string; basis: Basis; correction?: string | null; statusNote?: string | null }[]
  corrections: { issue: number; resource: string; status: string; decidedBy?: string | null; at?: string | null; note?: string | null }[]
  coverage: { subtopic: string; covered: boolean; levels: string[] }[]
  parts: Record<'NOTES' | 'MCQ' | 'COMBINED', ReportPart>
  headline: Headline
  method: string
  recommendations: string[]
  limitations: string[]
}

export const reportExportUrl = (versionId: string, format: 'pdf' | 'docx', part: 'NOTES' | 'MCQ' | 'COMBINED') =>
  `/api/v1/exports/versions/${versionId}?format=${format}&part=${part}`
