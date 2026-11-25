import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { Layout, Slide, SlideType, StyleKey } from '../remotion/slideDesign'
import { defaultLayout, TYPE_LABEL, LAYOUT_FIELDS } from '../remotion/slideDesign'
export * from '../remotion/slideDesign'

// ---- a deck (matches Slides.java) ---------------------------------------------------------------

export interface BoxFit {
  field: string
  size: number
  fits: boolean
  lines: number
  needed: number
  room: number
  min: number
  max: number
  note?: string | null
}

export interface SlideFit {
  id: string
  layout: string
  ok: boolean
  boxes: BoxFit[]
  problems: string[]
}

export interface DeckFit {
  checked: boolean
  ok: boolean
  error?: string | null
  method?: string
  slides?: SlideFit[]
}

export interface Deck {
  topicId: string
  topicTitle?: string | null
  level: string
  notesVersionId?: string | null
  qaVersionId?: string | null
  seoVersionId?: string | null
  style: StyleKey
  title?: string | null
  slides: Slide[]
  fit?: DeckFit | null
  model?: string | null
}

export const newSlideId = () => 's' + Math.random().toString(36).slice(2, 10)

/** A new slide of a type, with empty fields for its layout. */
export function blankSlide(type: SlideType): Slide {
  const layout = defaultLayout(type)
  const s: Slide = { id: newSlideId(), type, layout, title: TYPE_LABEL[type] }
  return withLayout(s, layout)
}

/** Changes the layout, keeping what fits and starting what the new one needs. */
export function withLayout(s: Slide, layout: Layout): Slide {
  const f = LAYOUT_FIELDS[layout]
  return {
    ...s,
    layout,
    bullets: f.includes('bullets') ? (s.bullets ?? ['']) : s.bullets,
    table: f.includes('table') ? (s.table ?? { headers: ['', ''], rows: [['', '']] }) : s.table,
    code: f.includes('code') ? (s.code ?? { language: '', text: '' }) : s.code,
  }
}

/** Moves the slide at i by d (−1 up, +1 down). */
export function move<T>(list: T[], i: number, d: number): T[] {
  const j = i + d
  if (j < 0 || j >= list.length) return list
  const out = [...list]
  ;[out[i], out[j]] = [out[j], out[i]]
  return out
}

export function fitOf(fit: DeckFit | null | undefined, id: string): SlideFit | undefined {
  return fit?.slides?.find((s) => s.id === id)
}

// ---- server ------------------------------------------------------------------------------------

export interface DeckInfo {
  level: string
  resourceId?: string | null
  versionNo?: number | null
  status?: string | null
  slides: number
  running: boolean
  error?: string | null
}

export interface TopicSlides {
  topicId: string
  title: string
  depth: number
  approvedNotes: string[]
  qaApproved: boolean
  seoApproved: boolean
  decks: DeckInfo[]
}

export interface SlidesOverview {
  topicsApproved: boolean
  topics: TopicSlides[]
  canEdit: boolean
  styles: Record<string, string>
}

export function useSlidesOverview(projectId: string) {
  return useQuery({
    queryKey: ['slides', projectId],
    queryFn: () => api.get<SlidesOverview>(`/projects/${projectId}/slides`),
    enabled: !!projectId,
    refetchInterval: (q) => (q.state.data?.topics.some((t) => t.decks.some((d) => d.running)) ? 5000 : false),
  })
}

export type GenerateRequest = { topicId: string; level: string; accountId: string; model: string; style: StyleKey; slides: number; guidance?: string }

export function useGenerateDeck(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: GenerateRequest) => api.post(`/projects/${projectId}/slides`, req),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['slides', projectId] }),
  })
}

export function measureDeck(projectId: string, deck: Deck) {
  const { fit: _fit, ...rest } = deck
  return api.post<DeckFit>(`/projects/${projectId}/slides/fit`, rest)
}

export interface Proposal {
  slide: Slide
  fit: DeckFit
}

export function useRegenerateSlide(resourceId: string) {
  return useMutation({
    mutationFn: (v: { slideId: string; versionId: string; accountId: string; model: string; guidance?: string }) =>
      api.post<Proposal>(`/slides/${resourceId}/slides/${v.slideId}/regenerate`, { versionId: v.versionId, accountId: v.accountId, model: v.model, guidance: v.guidance }),
  })
}

export const slidesExportUrl = (versionId: string, format: 'pptx' | 'pdf') => `/api/v1/exports/versions/${versionId}?format=${format}`

export { LEVEL_LABEL } from './materials'
