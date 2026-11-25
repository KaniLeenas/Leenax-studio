/**
 * Slide data, styles and boxes, shared by the app (lib/slides.ts) and the video templates.
 * The colours and boxes are the document service's (docservice/slides.py), so slides look
 * the same in the app, the PowerPoint file and the videos.
 */
export const SLIDE_TYPES = ['TITLE', 'OBJECTIVES', 'INTRODUCTION', 'OVERVIEW', 'DEFINITION', 'CONCEPT', 'DIAGRAM', 'FLOWCHART', 'EXAMPLE',
  'COMPARISON', 'APPLICATIONS', 'EXAM_TIPS', 'SUMMARY', 'REVISION', 'REFERENCES'] as const
export type SlideType = (typeof SLIDE_TYPES)[number]

export const TYPE_LABEL: Record<SlideType, string> = {
  TITLE: 'Title', OBJECTIVES: 'Learning objectives', INTRODUCTION: 'Introduction', OVERVIEW: 'Topic overview', DEFINITION: 'Definition',
  CONCEPT: 'Concept explanation', DIAGRAM: 'Technical diagram', FLOWCHART: 'Flowchart', EXAMPLE: 'Practical example', COMPARISON: 'Comparison table',
  APPLICATIONS: 'Applications', EXAM_TIPS: 'Exam tips', SUMMARY: 'Summary', REVISION: 'Revision questions', REFERENCES: 'References',
}

export const LAYOUTS = ['TITLE', 'BULLETS', 'BULLETS_IMAGE', 'DEFINITION', 'TABLE', 'DIAGRAM', 'EXAMPLE', 'IMAGE'] as const
export type Layout = (typeof LAYOUTS)[number]

export const LAYOUT_LABEL: Record<Layout, string> = {
  TITLE: 'Title', BULLETS: 'Bullet points', BULLETS_IMAGE: 'Bullets and image', DEFINITION: 'Term and definition', TABLE: 'Table',
  DIAGRAM: 'Diagram', EXAMPLE: 'Example with code', IMAGE: 'Image',
}

/** The fields each layout shows; the others are dropped when saved. */
export const LAYOUT_FIELDS: Record<Layout, string[]> = {
  TITLE: ['title', 'subtitle'],
  BULLETS: ['title', 'bullets'],
  BULLETS_IMAGE: ['title', 'bullets', 'image', 'caption'],
  DEFINITION: ['title', 'term', 'text'],
  TABLE: ['title', 'table'],
  DIAGRAM: ['title', 'figure', 'caption'],
  EXAMPLE: ['title', 'text', 'code'],
  IMAGE: ['title', 'image', 'caption'],
}

export function defaultLayout(t: SlideType): Layout {
  if (t === 'TITLE') return 'TITLE'
  if (t === 'DEFINITION') return 'DEFINITION'
  if (t === 'DIAGRAM' || t === 'FLOWCHART') return 'DIAGRAM'
  if (t === 'EXAMPLE') return 'EXAMPLE'
  if (t === 'COMPARISON') return 'TABLE'
  return 'BULLETS'
}

export interface Slide {
  id: string
  type: SlideType
  layout: Layout
  title?: string | null
  subtitle?: string | null
  bullets?: string[] | null
  term?: string | null
  text?: string | null
  table?: { headers: string[]; rows: string[][] } | null
  figure?: { kind: 'MERMAID' | 'MATH'; source: string } | null
  image?: { assetId: string; alt?: string | null } | null
  caption?: string | null
  code?: { language?: string | null; text: string } | null
  notes?: string | null
  sources?: string[] | null
}

// ---- styles: the same colours the document service uses ----------------------------------------

export type StyleKey = 'LECTURE' | 'MINIMAL' | 'DARK_TECH' | 'INFOGRAPHIC' | 'WHITEBOARD' | 'CORPORATE' | 'ILLUSTRATED'

export interface StyleColours {
  name: string
  bg: string
  title: string
  text: string
  accent: string
  muted: string
  codeBg: string
  headBg: string
  headText: string
  band?: string
  bar?: boolean
}

export const STYLES: Record<StyleKey, StyleColours> = {
  LECTURE: { name: 'University lecture', bg: '#FFFFFF', title: '#1F3864', text: '#222222', accent: '#2E75B6', muted: '#6B7280', codeBg: '#F2F4F7', headBg: '#1F3864', headText: '#FFFFFF', bar: true },
  MINIMAL: { name: 'Modern minimalist', bg: '#FAFAF7', title: '#111111', text: '#333333', accent: '#0F766E', muted: '#8A8A85', codeBg: '#F0F0EB', headBg: '#E7E5E0', headText: '#111111' },
  DARK_TECH: { name: 'Dark technology', bg: '#0E1726', title: '#7DD3FC', text: '#E5ECF5', accent: '#22D3EE', muted: '#94A3B8', codeBg: '#1B2A41', headBg: '#1E3A5F', headText: '#E5ECF5', bar: true },
  INFOGRAPHIC: { name: 'Animated infographic', bg: '#FFFFFF', title: '#FFFFFF', text: '#1E293B', accent: '#F59E0B', muted: '#64748B', codeBg: '#FEF3C7', headBg: '#F59E0B', headText: '#1E293B', band: '#0F766E' },
  WHITEBOARD: { name: 'Whiteboard', bg: '#FBFAF5', title: '#1D3557', text: '#2B2D42', accent: '#E63946', muted: '#6C757D', codeBg: '#EEF1F4', headBg: '#1D3557', headText: '#FFFFFF' },
  CORPORATE: { name: 'Corporate education', bg: '#FFFFFF', title: '#FFFFFF', text: '#1F2937', accent: '#1D4ED8', muted: '#6B7280', codeBg: '#F3F4F6', headBg: '#1E3A8A', headText: '#FFFFFF', band: '#1E3A8A' },
  ILLUSTRATED: { name: 'Illustrated presentation', bg: '#FFF8F0', title: '#7C2D12', text: '#3F3F46', accent: '#EA580C', muted: '#78716C', codeBg: '#FDEBD8', headBg: '#EA580C', headText: '#FFFFFF', bar: true },
}

export const STYLE_KEYS = Object.keys(STYLES) as StyleKey[]

// ---- geometry: the same boxes as the document service (points on a 960 × 540 page) --------------

export interface Box {
  x: number
  y: number
  w: number
  h: number
  anchor?: 'top' | 'bottom'
}

export const PAGE = { w: 960, h: 540 }

export function geometry(layout: Layout): Record<string, Box> {
  const title: Box = { x: 43, y: 32, w: 874, h: 80, anchor: 'bottom' }
  const y = 128
  const h = 372
  switch (layout) {
    case 'TITLE':
      return { title: { x: 72, y: 150, w: 816, h: 150, anchor: 'bottom' }, subtitle: { x: 72, y: 316, w: 816, h: 96 } }
    case 'BULLETS':
      return { title, bullets: { x: 43, y, w: 874, h } }
    case 'BULLETS_IMAGE':
      return { title, bullets: { x: 43, y, w: 500, h }, image: { x: 563, y, w: 354, h: 330 }, caption: { x: 563, y: y + 336, w: 354, h: 36 } }
    case 'DEFINITION':
      return { title, term: { x: 43, y: 140, w: 874, h: 70 }, text: { x: 43, y: 222, w: 874, h: 278 } }
    case 'TABLE':
      return { title, table: { x: 43, y, w: 874, h } }
    case 'DIAGRAM':
      return { title, figure: { x: 43, y, w: 874, h: 318 }, caption: { x: 43, y: y + 326, w: 874, h: 46 } }
    case 'EXAMPLE':
      return { title, text: { x: 43, y, w: 874, h: 112 }, code: { x: 43, y: y + 124, w: 874, h: h - 124 } }
    case 'IMAGE':
      return { title, image: { x: 43, y, w: 874, h: 318 }, caption: { x: 43, y: y + 326, w: 874, h: 46 } }
  }
}

/** The largest sizes, used before the measurement comes back. */
export const MAX_SIZE: Record<string, number> = { title: 36, subtitle: 24, bullets: 28, term: 32, text: 26, table: 20, caption: 18, code: 18 }

