import { describe, expect, it } from 'vitest'
import { builtInTemplates, duplicateLayer, fontFor, makeVariation, moveLayer, newImage, newText, resizeDocument } from './thumbnails'

describe('thumbnail helpers', () => {
  it('falls back to a Tamil font for Tamil text in English-only fonts', () => {
    expect(fontFor({ fontFamily: 'Anton', text: 'வணக்கம்' })).toContain('Anek Tamil')
    expect(fontFor({ fontFamily: 'Anton', text: 'HELLO' })).toBe('"Anton", "Hind Madurai", sans-serif')
  })

  it('builds templates with layers inside the frame', () => {
    for (const t of builtInTemplates(1280, 720)) {
      expect(t.document.layers.length).toBeGreaterThan(0)
      for (const l of t.document.layers) {
        expect(l.x).toBeGreaterThanOrEqual(0)
        expect(l.y).toBeLessThan(720)
      }
    }
  })

  it('covers the frame with a background image', () => {
    const img = newImage('m1', { width: 1000, height: 1000 }, { width: 1280, height: 720 }, true)
    expect(img.width).toBe(1280)
    expect(img.height).toBe(1280)
    expect(img.y).toBe(-280)
    expect(img.locked).toBe(true)
  })

  it('resizes a design to another format, keeping text proportional', () => {
    const t = newText(1280, 720, { fontSize: 100, width: 600 })
    const out = resizeDocument({ version: 1, background: '#000', layers: [t] }, { width: 1280, height: 720 }, { width: 1080, height: 1920 })
    const r = out.layers[0] as typeof t
    expect(r.fontSize).toBe(Math.round(100 * (1080 / 1280)))
    expect(r.y).toBe(Math.round(t.y * (1920 / 720)))
  })

  it('variations change colours but keep layout and Tamil fonts', () => {
    const tamil = newText(1280, 720, { text: 'தலைப்பு', fontFamily: 'Catamaran' })
    const doc = { version: 1 as const, background: '#000000', layers: [tamil] }
    const v = makeVariation(doc, 1)
    expect(v.background).not.toBe('#000000')
    expect(v.layers[0].x).toBe(tamil.x)
    expect((v.layers[0] as typeof tamil).fontFamily).toBe('Catamaran')
  })

  it('reorders and duplicates layers', () => {
    const a = newText(100, 100, { name: 'a' })
    const b = newText(100, 100, { name: 'b' })
    expect(moveLayer([a, b], a.id, 'up').map((l) => l.name)).toEqual(['b', 'a'])
    expect(moveLayer([a, b], b.id, 'bottom').map((l) => l.name)).toEqual(['b', 'a'])
    const d = duplicateLayer([a, b], a.id)
    expect(d).toHaveLength(3)
    expect(d[1].name).toBe('a copy')
    expect(d[1].id).not.toBe(a.id)
  })
})
