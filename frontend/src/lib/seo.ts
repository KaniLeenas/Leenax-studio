import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, uploadFile } from './api'

// ---- numbers with their source (matches Seo.Metric) ---------------------------------------------

/** Every number carries where it came from, or is marked estimated with how. */
export interface Metric {
  value: number | null
  unit?: string | null
  source?: string | null
  retrievedAt?: string | null
  estimated?: boolean | null
  method?: string | null
  note?: string | null
  inputs?: Record<string, Metric> | null
}

export function formatNumber(v: number): string {
  return Number.isInteger(v) ? v.toLocaleString('en-GB') : v.toLocaleString('en-GB', { maximumFractionDigits: 2 })
}

/** "0.42", "125,000 views", "4 of 5"; or why there's no number. */
export function metricText(m?: Metric | null): string {
  if (!m) return 'n/a'
  if (m.value == null) return m.note ?? 'not available'
  const u = m.unit ?? ''
  if (u === '1-5') return `${formatNumber(m.value)} of 5`
  if (u === '0-1' || u === 'weight' || u === '') return formatNumber(m.value)
  return `${formatNumber(m.value)} ${u}`
}

/** "estimated", or the source with its date. */
export function metricLabel(m?: Metric | null): string {
  if (!m || m.value == null) return ''
  if (m.estimated) return 'estimated'
  return [m.source, m.retrievedAt?.slice(0, 10)].filter(Boolean).join(', ')
}

// ---- research -----------------------------------------------------------------------------------

export interface Keyword {
  text: string
  language: 'en' | 'ta'
  kind: string
  intent?: string | null
  audience?: string | null
  source?: string | null
  searched: boolean
  relevance?: Metric | null
  demand?: Metric | null
  competition?: Metric | null
  gap?: Metric | null
  score?: Metric | null
  partial?: boolean | null
  trend?: string | null
}

export interface Competitor {
  videoId: string
  url: string
  title: string
  channel: string
  publishedAt?: string | null
  duration?: Metric | null
  views?: Metric | null
  likes?: Metric | null
  comments?: Metric | null
  description?: string | null
  tags?: string[]
  thumbnail?: string | null
  covers: string[]
  foundBy?: string[]
  rank?: number | null
}

export interface Research {
  topicId: string
  topicTitle: string
  subtopics: string[]
  languages: string[]
  region: string
  startedAt: string
  keywords: Keyword[]
  searches: { query: string; language: string; retrievedAt: string; cached: boolean; results: Metric }[]
  competitors: Competitor[]
  coverage: { subtopic: string; competitorsCovering: Metric }[]
  analysis?: { titleStrategies?: string; contentGaps?: string[]; repeatedQuestions?: string[]; missingExplanations?: string[]; languageOpportunities?: string[]; differentiation?: string[]; source?: string } | null
  trends?: { filename: string; term: string; geo?: string | null; lastDate?: string; recent: Metric; direction?: string | null }[]
  sources: { name: string; detail?: string | null; retrievedAt?: string | null }[]
  budget?: Record<string, Metric>
  limitations: string[]
  model?: string
}

export interface ResearchRow {
  id: string
  projectId: string
  topicId: string
  status: 'COMPLETE' | 'PARTIAL'
  data: Research
  createdBy?: string | null
  createdAt: string
}

export function useResearch(researchId?: string) {
  return useQuery({
    queryKey: ['seo-research', researchId],
    queryFn: () => api.get<ResearchRow>(`/seo/research/${researchId}`),
    enabled: !!researchId,
    staleTime: Infinity, // research never changes
  })
}

// ---- the report (approvable content) ------------------------------------------------------------

export const TITLE_STYLES = ['EDUCATIONAL', 'TUTORIAL', 'BEGINNER', 'EXAM', 'PRACTICAL', 'QUESTION', 'REVISION', 'MCQ'] as const
export const STYLE_LABEL: Record<string, string> = {
  EDUCATIONAL: 'Educational', TUTORIAL: 'Tutorial', BEGINNER: 'Beginner-friendly', EXAM: 'Exam-focused',
  PRACTICAL: 'Practical', QUESTION: 'Question', REVISION: 'Revision', MCQ: 'MCQ-focused',
}
export const CRITERIA_LABEL: Record<string, string> = {
  keywordRelevance: 'Keyword relevance', searchIntent: 'Search intent', clarity: 'Clarity', accuracy: 'Accuracy', readability: 'Readability',
  audienceRelevance: 'Audience relevance', differentiation: 'Differentiation', clickAppeal: 'Click appeal', length: 'Length',
}
export const STRATEGIES = ['LESSON', 'MCQ', 'EXAM', 'REVISION', 'SHORTS'] as const
export const STRATEGY_LABEL: Record<string, string> = {
  LESSON: 'Lesson video', MCQ: 'MCQ video', EXAM: 'Exam preparation', REVISION: 'Revision', SHORTS: 'Shorts',
}
export const KIND_LABEL: Record<string, string> = {
  PRIMARY: 'Primary', SECONDARY: 'Secondary', LONG_TAIL: 'Long-tail', QUESTION: 'Question', BEGINNER: 'Beginner', EXAM: 'Exam',
  PRACTICAL: 'Practical', REGIONAL: 'Regional',
}

export interface TitleCheck {
  textHash?: string
  length?: Metric | null
  hasKeyword?: boolean | null
  warnings: string[]
  ai?: Record<string, Metric> | null
  overall?: Metric | null
  reasoning?: string | null
}

export interface Title {
  id: string
  text: string
  style: string
  language: 'en' | 'ta'
  check?: TitleCheck | null
}

export interface Strategy {
  titleIdea?: string | null
  angle?: string | null
  format?: string | null
  keywords?: string[]
  notes?: string | null
}

export interface SeoReport {
  topicId: string
  topicTitle: string
  researchId: string
  titles: Title[]
  recommendedTitleId?: string | null
  recommendationReason?: string | null
  chosenTitleId?: string | null
  description?: string | null
  primaryKeywords: string[]
  secondaryKeywords: string[]
  tags: string[]
  hashtags: string[]
  thumbnailText?: string | null
  thumbnailPrompt?: string | null
  chapters: { title: string; start: Metric }[]
  playlists: { title: string; why?: string | null }[]
  nextVideos: { title: string; why?: string | null }[]
  strategies: Record<string, Strategy>
  model?: string | null
}

/** The same limits as the server: YouTube's own. */
export const LIMITS = { title: 100, titleShown: 70, description: 5000, tags: 500, hashtags: 15 }

/** Characters YouTube counts for tags: each tag, a comma between, and quotes around tags with spaces. */
export function tagChars(tags: string[]): number {
  return tags.reduce((n, t, i) => n + t.length + (t.includes(' ') ? 2 : 0) + (i > 0 ? 1 : 0), 0)
}

/** Mirrors SeoLogic.checkTitle's rules, so problems show while typing. */
const CLICKBAIT = /(you won'?t believe|shocking|mind[- ]?blowing|guaranteed|100\s*%|secret|exposed|never seen before|insane|unbelievable|!!|\?\?|பிரமிப்பூட்டும்|அதிர்ச்சி)/iu
export function titleWarnings(text: string): string[] {
  const n = [...text].length
  const out: string[] = []
  if (n === 0) out.push('Empty')
  if (n > LIMITS.title) out.push(`Longer than YouTube allows (${LIMITS.title} characters)`)
  else if (n > LIMITS.titleShown) out.push(`May be cut off in search results after about ${LIMITS.titleShown} characters`)
  if (CLICKBAIT.test(text)) out.push('Sounds like clickbait')
  return out
}

export function secondsText(s: number): string {
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = Math.floor(s % 60)
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}` : `${m}:${String(sec).padStart(2, '0')}`
}

export function parseTime(t: string): number | null {
  const parts = t.trim().split(':').map((x) => Number(x))
  if (parts.length < 1 || parts.length > 3 || parts.some((x) => !Number.isFinite(x) || x < 0)) return null
  return parts.reduce((acc, x) => acc * 60 + x, 0)
}

export const exportUrl = (versionId: string, format: 'pdf' | 'docx' | 'xlsx') => `/api/v1/exports/versions/${versionId}?format=${format}`

// ---- overview -----------------------------------------------------------------------------------

export interface Budget {
  dailySearches: number
  usedToday: number
  left: number
  unitsToday: number
  cacheDays: number
  resetsAt: string
  quotaDay: string
}

export interface TrendsFile {
  id: string
  filename: string
  geo?: string | null
  period: string
  firstDate?: string | null
  lastDate?: string | null
  terms: string[]
  status: string
  createdBy?: string | null
  createdAt: string
}

export interface TopicSeo {
  topicId: string
  title: string
  depth: number
  hasNotes: boolean
  researchId?: string | null
  researchStatus?: 'COMPLETE' | 'PARTIAL' | null
  researchedAt?: string | null
  reportId?: string | null
  reportVersionNo?: number | null
  reportStatus?: string | null
  chosenTitle?: string | null
  running: boolean
  jobError?: string | null
}

export interface SeoOverview {
  topicsApproved: boolean
  budget: Budget
  trends: TrendsFile[]
  topics: TopicSeo[]
  youtube: { id: string; name: string; status: string }[]
  canEdit: boolean
}

export function useSeoOverview(projectId: string) {
  return useQuery({
    queryKey: ['seo', projectId],
    queryFn: () => api.get<SeoOverview>(`/projects/${projectId}/seo`),
    enabled: !!projectId,
    refetchInterval: (q) => (q.state.data?.topics.some((t) => t.running) ? 5000 : false),
  })
}

export type ResearchRequest = {
  topicId: string
  youtubeAccountId?: string
  accountId: string
  model: string
  languages: string[]
  region: string
  searches: number
  trendsIds: string[]
  guidance?: string
}

export function useStartResearch(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: ResearchRequest) => api.post(`/projects/${projectId}/seo/research`, req),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['seo', projectId] }),
  })
}

export function useTrendsActions(projectId: string) {
  const qc = useQueryClient()
  const done = () => qc.invalidateQueries({ queryKey: ['seo', projectId] })
  return {
    upload: useMutation({ mutationFn: (file: File) => uploadFile<TrendsFile>(`/projects/${projectId}/seo/trends`, file, () => {}), onSuccess: done }),
    remove: useMutation({ mutationFn: (id: string) => api.delete(`/seo/trends/${id}`), onSuccess: done }),
  }
}
