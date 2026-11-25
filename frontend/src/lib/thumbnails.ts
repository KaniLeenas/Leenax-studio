import type { ImageLayer, Layer, ShapeLayer, TextLayer, ThumbnailDocument } from './types'

export const SIZE_PRESETS = [
  { id: 'youtube', label: 'YouTube thumbnail', width: 1280, height: 720 },
  { id: 'square', label: 'Instagram square', width: 1080, height: 1080 },
  { id: 'portrait', label: 'Instagram portrait', width: 1080, height: 1350 },
  { id: 'story', label: 'Story, Reel or Short cover', width: 1080, height: 1920 },
  { id: 'link', label: 'Facebook / link preview', width: 1200, height: 630 },
] as const

/** Fonts bundled with the app (no outside requests). Tamil-capable ones are marked. */
export const FONTS: { family: string; label: string; tamil: boolean; weights: string }[] = [
  { family: 'Anek Tamil Variable', label: 'Anek Tamil', tamil: true, weights: 'variable' },
  { family: 'Catamaran', label: 'Catamaran', tamil: true, weights: '400 800' },
  { family: 'Hind Madurai', label: 'Hind Madurai', tamil: true, weights: '400 600' },
  { family: 'Anton', label: 'Anton (English)', tamil: false, weights: '400' },
  { family: 'Bebas Neue', label: 'Bebas Neue (English)', tamil: false, weights: '400' },
]

export const hasTamil = (s: string) => /[஀-௿]/.test(s)

/** Anton and Bebas have no Tamil letters; fall back to a Tamil font so text never shows as boxes. */
export function fontFor(layer: Pick<TextLayer, 'fontFamily' | 'text'>): string {
  const f = FONTS.find((x) => x.family === layer.fontFamily)
  if (f && !f.tamil && hasTamil(layer.text)) return `"${layer.fontFamily}", "Anek Tamil Variable", sans-serif`
  return `"${layer.fontFamily}", "Hind Madurai", sans-serif`
}

let counter = 0
export const layerId = () => `l${Date.now().toString(36)}${(counter++).toString(36)}`

export function newDocument(background = '#1e2240'): ThumbnailDocument {
  return { version: 1, background, layers: [] }
}

export function newText(w: number, h: number, patch: Partial<TextLayer> = {}): TextLayer {
  const size = Math.round(Math.min(w, h) * 0.12)
  return {
    id: layerId(),
    type: 'text',
    name: 'Text',
    x: Math.round(w * 0.06),
    y: Math.round(h * 0.1),
    rotation: 0,
    opacity: 1,
    text: 'YOUR TITLE',
    fontFamily: 'Anek Tamil Variable',
    fontSize: size,
    fontStyle: 'bold',
    fill: '#ffffff',
    stroke: '#000000',
    strokeWidth: Math.max(2, Math.round(size / 14)),
    shadow: true,
    align: 'left',
    width: Math.round(w * 0.6),
    lineHeight: 1.05,
    letterSpacing: 0,
    background: '',
    padding: Math.round(size * 0.25),
    ...patch,
  }
}

export function newImage(mediaId: string, natural: { width: number; height: number }, canvas: { width: number; height: number }, cover = false): ImageLayer {
  const scale = cover
    ? Math.max(canvas.width / natural.width, canvas.height / natural.height)
    : Math.min((canvas.width * 0.5) / natural.width, (canvas.height * 0.8) / natural.height, 1)
  const width = Math.round(natural.width * scale)
  const height = Math.round(natural.height * scale)
  return {
    id: layerId(),
    type: 'image',
    name: cover ? 'Background image' : 'Image',
    mediaId,
    x: Math.round((canvas.width - width) / 2),
    y: Math.round((canvas.height - height) / 2),
    width,
    height,
    rotation: 0,
    opacity: 1,
    stroke: '#ffffff',
    strokeWidth: 0,
    cornerRadius: 0,
    shadow: false,
    locked: cover,
  }
}

export function newShape(type: ShapeLayer['type'], w: number, h: number): ShapeLayer {
  const base = Math.round(Math.min(w, h) * 0.25)
  return {
    id: layerId(),
    type,
    name: type === 'rect' ? 'Box' : type === 'circle' ? 'Circle' : 'Arrow',
    x: Math.round(w / 2 - base / 2),
    y: Math.round(h / 2 - base / 2),
    width: type === 'arrow' ? base * 1.6 : base,
    height: type === 'arrow' ? Math.round(base * 0.5) : base,
    rotation: 0,
    opacity: 1,
    fill: type === 'arrow' ? '#ffd23f' : '#e8443a',
    stroke: type === 'circle' ? '#ffd23f' : '#000000',
    strokeWidth: type === 'circle' ? Math.round(base / 14) : 0,
    cornerRadius: type === 'rect' ? Math.round(base / 10) : 0,
  }
}

/** Built-in starting points. Saved templates (your own) are listed after these. */
export function builtInTemplates(width: number, height: number): { id: string; name: string; document: ThumbnailDocument }[] {
  const s = Math.min(width, height)
  return [
    {
      id: 'bold-headline',
      name: 'Bold headline',
      document: {
        version: 1,
        background: '#14182e',
        layers: [
          { ...newShape('rect', width, height), name: 'Accent bar', x: 0, y: Math.round(height * 0.72), width, height: Math.round(height * 0.28), fill: '#f5a524', cornerRadius: 0 },
          newText(width, height, { text: 'இது தான் ரகசியம்!', y: Math.round(height * 0.1), fontSize: Math.round(s * 0.16), width: Math.round(width * 0.88) }),
          newText(width, height, { name: 'Subtitle', text: 'FULL RECIPE INSIDE', fontFamily: 'Bebas Neue', fontStyle: 'normal', fill: '#14182e', stroke: '#000000', strokeWidth: 0, shadow: false, y: Math.round(height * 0.77), fontSize: Math.round(s * 0.12), width: Math.round(width * 0.9) }),
        ],
      },
    },
    {
      id: 'split',
      name: 'Photo on the right',
      document: {
        version: 1,
        background: '#0f5257',
        layers: [
          { ...newShape('rect', width, height), name: 'Photo area', x: Math.round(width * 0.52), y: 0, width: Math.round(width * 0.48), height, fill: '#0b3c40', cornerRadius: 0 },
          newText(width, height, { text: '5 TIPS\nநீங்கள் அறியாதது', width: Math.round(width * 0.46), fontSize: Math.round(s * 0.13), y: Math.round(height * 0.18) }),
          { ...newShape('arrow', width, height), x: Math.round(width * 0.38), y: Math.round(height * 0.7) },
        ],
      },
    },
    {
      id: 'label',
      name: 'Label and title',
      document: {
        version: 1,
        background: '#2b1a3d',
        layers: [
          newText(width, height, { name: 'Label', text: 'NEW', fontFamily: 'Anton', fontStyle: 'normal', fontSize: Math.round(s * 0.07), fill: '#14182e', stroke: '#000000', strokeWidth: 0, shadow: false, background: '#ffd23f', padding: Math.round(s * 0.02), width: Math.round(s * 0.24), align: 'center', y: Math.round(height * 0.08) }),
          newText(width, height, { text: 'Madurai Street Food Tour', fontFamily: 'Anton', fontStyle: 'normal', fontSize: Math.round(s * 0.14), y: Math.round(height * 0.24), width: Math.round(width * 0.85) }),
        ],
      },
    },
  ]
}

/** Rescales a design to another size (e.g. YouTube → Instagram square), keeping layout proportions. */
export function resizeDocument(doc: ThumbnailDocument, from: { width: number; height: number }, to: { width: number; height: number }): ThumbnailDocument {
  const sx = to.width / from.width
  const sy = to.height / from.height
  const s = Math.min(sx, sy)
  return {
    ...doc,
    layers: doc.layers.map((l) => {
      const cx = (l.x + ('width' in l ? l.width / 2 : 0)) * sx
      const cy = l.y * sy
      if (l.type === 'text') {
        const width = Math.round(l.width * s)
        return { ...l, x: Math.round(cx - width / 2), y: Math.round(cy), width, fontSize: Math.round(l.fontSize * s), strokeWidth: Math.round(l.strokeWidth * s), padding: Math.round(l.padding * s) }
      }
      if (l.type === 'image' && l.locked && l.x <= 0 && l.y <= 0) {
        // full-bleed background: cover the new frame
        const k = Math.max(to.width / l.width, to.height / l.height)
        const width = Math.round(l.width * k)
        const height = Math.round(l.height * k)
        return { ...l, width, height, x: Math.round((to.width - width) / 2), y: Math.round((to.height - height) / 2) }
      }
      const width = Math.round(l.width * s)
      const height = Math.round(l.height * s)
      return { ...l, x: Math.round(cx - width / 2), y: Math.round(cy), width, height }
    }),
  }
}

/** Colour schemes used for quick variations: [background, text, outline, accent]. */
export const PALETTES: [string, string, string, string][] = [
  ['#14182e', '#ffffff', '#000000', '#f5a524'],
  ['#ffd23f', '#14182e', '#ffffff', '#e8443a'],
  ['#e8443a', '#ffffff', '#14182e', '#ffd23f'],
  ['#0f5257', '#ffffff', '#062b2e', '#ffb627'],
  ['#ffffff', '#e8443a', '#14182e', '#14182e'],
  ['#2b1a3d', '#ffd23f', '#000000', '#5ad1c4'],
]

const DISPLAY_FONTS = ['Anek Tamil Variable', 'Catamaran', 'Anton', 'Bebas Neue']

/**
 * Variation n of a design: another palette, and for English-only text another display font.
 * Images and layout stay the same, so you can A/B test colour and type.
 */
export function makeVariation(doc: ThumbnailDocument, n: number): ThumbnailDocument {
  const [bg, text, outline, accent] = PALETTES[n % PALETTES.length]
  let textIndex = 0
  return {
    ...doc,
    background: bg,
    layers: doc.layers.map((l) => {
      if (l.type === 'text') {
        const first = textIndex++ === 0
        const font = hasTamil(l.text) ? l.fontFamily : DISPLAY_FONTS[(DISPLAY_FONTS.indexOf(l.fontFamily) + n) % DISPLAY_FONTS.length]
        return l.background
          ? { ...l, background: accent, fill: bg, fontFamily: font }
          : { ...l, fill: first ? text : accent, stroke: outline, fontFamily: font }
      }
      if (l.type === 'rect' || l.type === 'circle' || l.type === 'arrow') return { ...l, fill: l.type === 'circle' ? 'transparent' : accent, stroke: l.type === 'circle' ? accent : l.stroke }
      return l
    }),
  }
}

export function moveLayer(layers: Layer[], id: string, dir: 'up' | 'down' | 'top' | 'bottom'): Layer[] {
  const i = layers.findIndex((l) => l.id === id)
  if (i < 0) return layers
  const out = [...layers]
  const [l] = out.splice(i, 1)
  const to = dir === 'top' ? out.length : dir === 'bottom' ? 0 : Math.max(0, Math.min(out.length, i + (dir === 'up' ? 1 : -1)))
  out.splice(to, 0, l)
  return out
}

export function duplicateLayer(layers: Layer[], id: string): Layer[] {
  const i = layers.findIndex((l) => l.id === id)
  if (i < 0) return layers
  const copy = { ...layers[i], id: layerId(), name: `${layers[i].name} copy`, x: layers[i].x + 24, y: layers[i].y + 24, locked: false }
  const out = [...layers]
  out.splice(i + 1, 0, copy)
  return out
}

/** Media ids used by a design (to preload images). */
export const mediaIdsOf = (doc: ThumbnailDocument) => doc.layers.filter((l): l is ImageLayer => l.type === 'image').map((l) => l.mediaId)
