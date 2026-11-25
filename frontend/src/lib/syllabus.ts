import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { ResourceState } from './reviews'

// ---- content (matches Content.java) -------------------------------------------------------------

export interface Source {
  documentId: string
  versionId: string
  page: number
  quote?: string | null
  found?: boolean | null
}

export interface SyllabusNode {
  id: string
  title: string
  sources?: Source[]
  children?: SyllabusNode[]
}

export interface Outcome {
  id: string
  code?: string | null
  text: string
  sources?: Source[]
}

export const ASSESSMENT_KINDS = ['EXAM', 'COURSEWORK', 'QUIZ', 'PRACTICAL', 'PRESENTATION', 'PROJECT', 'OTHER'] as const
export type AssessmentKind = (typeof ASSESSMENT_KINDS)[number]
export const ASSESSMENT_LABEL: Record<AssessmentKind, string> = {
  EXAM: 'Exam',
  COURSEWORK: 'Coursework',
  QUIZ: 'Quiz',
  PRACTICAL: 'Practical',
  PRESENTATION: 'Presentation',
  PROJECT: 'Project',
  OTHER: 'Other',
}

export interface Assessment {
  id: string
  title: string
  kind: AssessmentKind
  weight?: number | null
  details?: string | null
  sources?: Source[]
}

export interface Contradiction {
  id: string
  description: string
  sources?: Source[]
  resolution?: string | null
}

export interface DocumentRef {
  documentId: string
  versionId: string
  title?: string | null
  versionNo?: number | null
  label?: string | null
}

export interface Syllabus {
  summary?: string | null
  structure: SyllabusNode[]
  outcomes: Outcome[]
  assessments: Assessment[]
  contradictions: Contradiction[]
  documents: DocumentRef[]
  analysis?: { model?: string; generatedAt?: string; citations?: number; citationsFound?: number; citationsRemoved?: number } | null
}

export type Difficulty = 'INTRODUCTORY' | 'INTERMEDIATE' | 'ADVANCED'
export const DIFFICULTY_LABEL: Record<Difficulty, string> = {
  INTRODUCTORY: 'Introductory',
  INTERMEDIATE: 'Intermediate',
  ADVANCED: 'Advanced',
}

export interface Topic {
  id: string
  title: string
  syllabusItemIds?: string[]
  selected?: boolean
  difficulty?: Difficulty | null
  prerequisites?: string[]
  sources?: Source[]
  notInSyllabus?: boolean | null
  children?: Topic[]
}

export interface Topics {
  syllabusVersionId: string
  nodes: Topic[]
}

export const MAX_DEPTH = 3
export const LEVEL_NAME = ['Chapter', 'Topic', 'Subtopic'] as const

// ---- tree editing (pure; shared by the syllabus structure and the topic list) -------------------

export interface TreeItem {
  id: string
  title: string
  children?: TreeItem[]
}

export function newId(): string {
  return 'n' + Math.random().toString(16).slice(2, 12)
}

/** Where an item sits: its siblings array path and index. */
function locate<T extends TreeItem>(items: T[], id: string): { list: T[]; index: number; depth: number; parent: T | null } | null {
  const walk = (list: T[], depth: number, parent: T | null): ReturnType<typeof locate<T>> => {
    for (let i = 0; i < list.length; i++) {
      if (list[i].id === id) return { list, index: i, depth, parent }
      const found = walk((list[i].children ?? []) as T[], depth + 1, list[i])
      if (found) return found
    }
    return null
  }
  return walk(items, 1, null)
}

function clone<T>(v: T): T {
  return structuredClone(v)
}

export function depthOf<T extends TreeItem>(items: T[], id: string): number {
  return locate(items, id)?.depth ?? 0
}

/** How many levels sit under an item (0 for a leaf). */
function height(item: TreeItem): number {
  return (item.children ?? []).reduce((m, c) => Math.max(m, 1 + height(c)), 0)
}

export function flatten<T extends TreeItem>(items: T[], depth = 1): { item: T; depth: number }[] {
  return items.flatMap((item) => [{ item, depth }, ...flatten((item.children ?? []) as T[], depth + 1)])
}

export function rename<T extends TreeItem>(items: T[], id: string, title: string): T[] {
  return update(items, id, (n) => ({ ...n, title }))
}

export function update<T extends TreeItem>(items: T[], id: string, f: (n: T) => T): T[] {
  return items.map((n) => (n.id === id ? f(n) : { ...n, children: n.children ? update(n.children as T[], id, f) : n.children }))
}

/** Adds a new item after {@code afterId} (or at the end), at the same level. */
export function addSibling<T extends TreeItem>(items: T[], afterId: string | null, fresh: T): T[] {
  const next = clone(items)
  if (!afterId) return [...next, fresh]
  const at = locate(next, afterId)
  if (!at) return [...next, fresh]
  at.list.splice(at.index + 1, 0, fresh)
  return next
}

export function addChild<T extends TreeItem>(items: T[], parentId: string, fresh: T): T[] {
  const next = clone(items)
  const at = locate(next, parentId)
  if (!at || at.depth >= MAX_DEPTH) return items
  const parent = at.list[at.index]
  parent.children = [...(parent.children ?? []), fresh]
  return next
}

export function remove<T extends TreeItem>(items: T[], id: string): T[] {
  return items.filter((n) => n.id !== id).map((n) => ({ ...n, children: n.children ? remove(n.children as T[], id) : n.children }))
}

export function move<T extends TreeItem>(items: T[], id: string, by: -1 | 1): T[] {
  const next = clone(items)
  const at = locate(next, id)
  if (!at) return items
  const to = at.index + by
  if (to < 0 || to >= at.list.length) return items
  const [n] = at.list.splice(at.index, 1)
  at.list.splice(to, 0, n)
  return next
}

/** Makes the item the last child of the item above it, if the levels allow. */
export function indent<T extends TreeItem>(items: T[], id: string): T[] {
  const next = clone(items)
  const at = locate(next, id)
  if (!at || at.index === 0) return items
  const node = at.list[at.index]
  if (at.depth + 1 + height(node) > MAX_DEPTH) return items
  const above = at.list[at.index - 1]
  at.list.splice(at.index, 1)
  above.children = [...(above.children ?? []), node]
  return next
}

/** Moves the item up a level, right after its parent. */
export function outdent<T extends TreeItem>(items: T[], id: string): T[] {
  const next = clone(items)
  const at = locate(next, id)
  if (!at || !at.parent) return items
  const parentAt = locate(next, at.parent.id)
  if (!parentAt) return items
  const [node] = at.list.splice(at.index, 1)
  parentAt.list.splice(parentAt.index + 1, 0, node)
  return next
}

export function canIndent<T extends TreeItem>(items: T[], id: string): boolean {
  return indent(items, id) !== items
}

export function canOutdent<T extends TreeItem>(items: T[], id: string): boolean {
  return (locate(items, id)?.depth ?? 1) > 1
}

/**
 * Merges the item with the next one at the same level: the first keeps its name, gets the
 * second's children, and (for topics) its syllabus items, sources and prerequisites. Anything
 * that needed the second now needs the first.
 */
export function mergeWithNext<T extends TreeItem>(items: T[], id: string): T[] {
  const next = clone(items)
  const at = locate(next, id)
  if (!at || at.index + 1 >= at.list.length) return items
  const a = at.list[at.index] as T & Partial<Topic> & { sources?: Source[] }
  const b = at.list[at.index + 1] as T & Partial<Topic> & { sources?: Source[] }
  a.children = [...(a.children ?? []), ...(b.children ?? [])]
  if (a.syllabusItemIds || b.syllabusItemIds) a.syllabusItemIds = unique([...(a.syllabusItemIds ?? []), ...(b.syllabusItemIds ?? [])])
  if (a.sources || b.sources) a.sources = uniqueSources([...(a.sources ?? []), ...(b.sources ?? [])])
  if (a.prerequisites || b.prerequisites) a.prerequisites = unique([...(a.prerequisites ?? []), ...(b.prerequisites ?? [])]).filter((p) => p !== a.id && p !== b.id)
  if (b.selected) a.selected = true
  if (a.notInSyllabus && !b.notInSyllabus) a.notInSyllabus = b.notInSyllabus
  at.list.splice(at.index + 1, 1)
  return relink(next, b.id, a.id)
}

/** Splits an item in two: a copy with no children and no syllabus link goes after it, to rename. */
export function split<T extends TreeItem>(items: T[], id: string, secondTitle?: string): T[] {
  const next = clone(items)
  const at = locate(next, id)
  if (!at) return items
  const a = at.list[at.index] as T & Partial<Topic>
  const b = { ...clone(a), id: newId(), title: secondTitle ?? `${a.title} (part 2)`, children: [] } as T & Partial<Topic>
  if ('syllabusItemIds' in b) b.syllabusItemIds = []
  if ('notInSyllabus' in b) b.notInSyllabus = null
  at.list.splice(at.index + 1, 0, b)
  return next
}

/** Points prerequisites at {@code to} instead of {@code from}, and drops self-references. */
function relink<T extends TreeItem>(items: T[], from: string, to: string): T[] {
  return items.map((n) => {
    const t = n as T & Partial<Topic>
    const prerequisites = t.prerequisites
      ? unique(t.prerequisites.map((p) => (p === from ? to : p))).filter((p) => p !== t.id)
      : t.prerequisites
    return { ...n, ...(prerequisites ? { prerequisites } : {}), children: n.children ? relink(n.children as T[], from, to) : n.children }
  })
}

/** After removing topics, drop prerequisites that point at them. */
export function dropMissingPrerequisites(topics: Topic[]): Topic[] {
  const ids = new Set(flatten(topics).map((f) => f.item.id))
  const fix = (list: Topic[]): Topic[] =>
    list.map((t) => ({ ...t, prerequisites: (t.prerequisites ?? []).filter((p) => ids.has(p)), children: fix(t.children ?? []) }))
  return fix(topics)
}

/** A prerequisite loop among selected topics, as titles, or null. Mirrors the server check. */
export function prerequisiteLoop(topics: Topic[]): string[] | null {
  const all = flatten(topics).map((f) => f.item).filter((t) => t.selected !== false)
  const byId = new Map(all.map((t) => [t.id, t]))
  const state = new Map<string, 1 | 2>()
  const path: string[] = []
  const visit = (id: string): string[] | null => {
    if (state.get(id) === 2) return null
    if (state.get(id) === 1) return [...path.slice(path.indexOf(id)), id].map((x) => byId.get(x)!.title)
    const t = byId.get(id)
    if (!t) return null
    state.set(id, 1)
    path.push(id)
    for (const p of t.prerequisites ?? []) {
      if (!byId.has(p)) continue
      const found = visit(p)
      if (found) return found
    }
    path.pop()
    state.set(id, 2)
    return null
  }
  for (const t of all) {
    const found = visit(t.id)
    if (found) return found
  }
  return null
}

function unique<T>(xs: T[]): T[] {
  return [...new Set(xs)]
}

function uniqueSources(xs: Source[]): Source[] {
  const seen = new Set<string>()
  return xs.filter((s) => {
    const k = `${s.versionId}:${s.page}:${s.quote ?? ''}`
    if (seen.has(k)) return false
    seen.add(k)
    return true
  })
}

/** "D1 p.3": the short name of a source, by the documents the syllabus lists. */
export function sourceLabel(s: Source, docs: DocumentRef[]): string {
  const d = docs.find((x) => x.versionId === s.versionId)
  return `${d?.label ?? d?.title ?? 'Document'} p.${s.page}`
}

export const sourceHref = (s: Source) => `/documents/${s.documentId}?version=${s.versionId}&page=${s.page}`

// ---- analysis -----------------------------------------------------------------------------------

export interface DocumentChoice {
  documentId: string
  title: string
  versionId: string
  versionNo: number
  pages: number
  chars: number
}

export interface AnalysisJob {
  id: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  documents: string[]
  model?: string | null
  resultVersionId?: string | null
  error?: string | null
  attempts: number
  createdAt: string
  finishedAt?: string | null
  startedBy?: string | null
}

export interface AnalysisOverview {
  documents: DocumentChoice[]
  maxChars: number
  job?: AnalysisJob | null
}

export const isRunning = (j?: AnalysisJob | null) => j?.status === 'QUEUED' || j?.status === 'RUNNING'

export function useAnalysis(projectId: string) {
  const qc = useQueryClient()
  return useQuery({
    queryKey: ['syllabus', 'analysis', projectId],
    queryFn: async () => {
      const data = await api.get<AnalysisOverview>(`/projects/${projectId}/syllabus/analysis`)
      // When an analysis finishes, the new version shows up in the review state.
      if (data.job && !isRunning(data.job)) qc.invalidateQueries({ queryKey: ['reviews', projectId] })
      return data
    },
    enabled: !!projectId,
    refetchInterval: (q) => (isRunning(q.state.data?.job) ? 4000 : false),
  })
}

export function useStartAnalysis(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: { documentIds: string[]; accountId: string; model: string; guidance?: string }) =>
      api.post<AnalysisJob>(`/projects/${projectId}/syllabus/analysis`, req),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['syllabus', 'analysis', projectId] }),
  })
}

export function useCancelAnalysis(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (jobId: string) => api.post<AnalysisJob>(`/projects/${projectId}/syllabus/analysis/${jobId}/cancel`, {}),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['syllabus', 'analysis', projectId] }),
  })
}

export function useWriteByHand(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<ResourceState<Syllabus>>(`/projects/${projectId}/syllabus/manual`, {}),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['reviews', projectId] }),
  })
}

export function useUpdateTopics(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<ResourceState<Topics>>(`/projects/${projectId}/topics/from-syllabus`, {}),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['reviews', projectId] }),
  })
}
