import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { cn } from '../lib/cn'
import { drawMath, drawMermaid, keepFigure, svgDataUrl } from '../lib/figures'
import { geometry, MAX_SIZE, PAGE, STYLES, type Box, type Slide, type SlideFit, type StyleKey } from '../lib/slides'

/**
 * One slide drawn in the browser with the same boxes and colours as the PowerPoint file, at the
 * sizes the document service measured. The browser has different fonts, so line breaks here can
 * differ a little; the measurement (and the file) are what count, and a box that doesn't fit is
 * outlined.
 */
export function SlideCanvas({
  slide,
  style,
  fit,
  number,
  total,
  projectId,
  className,
  label,
}: {
  slide: Slide
  style: StyleKey
  fit?: SlideFit
  number: number
  total: number
  projectId?: string
  className?: string
  label?: string
}) {
  const s = STYLES[style] ?? STYLES.LECTURE
  const boxes = geometry(slide.layout)
  const band = !!s.band && slide.layout !== 'TITLE'
  const sizeOf = (field: string) => fit?.boxes.find((b) => b.field === field)?.size ?? MAX_SIZE[field] ?? 18
  const overflows = (field: string) => fit?.boxes.some((b) => b.field === field && !b.fits) ?? false

  const colour = (field: string) => {
    if (field === 'title') return band ? '#FFFFFF' : slide.layout === 'TITLE' && s.band ? s.band : s.title
    if (field === 'subtitle') return s.band ? s.text : s.muted
    if (field === 'caption') return s.muted
    return s.text
  }

  return (
    <div
      role="img"
      aria-label={label ?? `Slide ${number}: ${slide.title ?? ''}`}
      className={cn('relative w-full overflow-hidden select-none [container-type:inline-size]', className)}
      style={{ aspectRatio: `${PAGE.w} / ${PAGE.h}`, background: s.bg, fontFamily: 'Calibri, Carlito, "Noto Sans Tamil", "Noto Sans", sans-serif' }}
    >
      {band && <Rect box={{ x: 0, y: 0, w: PAGE.w, h: 120 }} colour={s.band!} />}
      {s.band && slide.layout === 'TITLE' && <Rect box={{ x: 0, y: PAGE.h - 36, w: PAGE.w, h: 36 }} colour={s.band} />}
      {s.bar && slide.layout !== 'TITLE' && <Rect box={{ x: 43, y: 116, w: 96, h: 4 }} colour={s.accent} />}
      {style === 'ILLUSTRATED' && (
        <>
          <Rect box={{ x: PAGE.w - 70, y: -30, w: 120, h: 120 }} colour={s.codeBg} round />
          <Rect box={{ x: -40, y: PAGE.h - 60, w: 110, h: 110 }} colour={s.codeBg} round />
        </>
      )}

      {Object.entries(boxes).map(([field, box]) => {
        if (field === 'figure') return <Placed key={field} box={box}><Figure slide={slide} projectId={projectId} muted={s.muted} /></Placed>
        if (field === 'image') return <Placed key={field} box={box}><SlideImage slide={slide} muted={s.muted} /></Placed>
        const size = sizeOf(field)
        const text: CSSProperties = { fontSize: pt(size), lineHeight: 1.2, color: colour(field), fontWeight: field === 'title' || field === 'term' ? 700 : 400 }
        const bad = overflows(field)
        if (field === 'table') {
          const t = slide.table
          if (!t) return null
          return (
            <Placed key={field} box={box} bad={bad}>
              <table className="w-full border-collapse" style={{ fontSize: pt(size), lineHeight: 1.2, color: s.text }}>
                {t.headers.length > 0 && (
                  <thead>
                    <tr>{t.headers.map((h, i) => <th key={i} className="text-left font-bold align-top" style={{ background: s.headBg, color: s.headText, padding: pt(5) }}>{h}</th>)}</tr>
                  </thead>
                )}
                <tbody>
                  {t.rows.map((r, i) => (
                    <tr key={i} style={{ borderBottom: `1px solid ${s.muted}40` }}>
                      {r.map((c, j) => <td key={j} className="align-top" style={{ padding: pt(5) }}>{c}</td>)}
                    </tr>
                  ))}
                </tbody>
              </table>
            </Placed>
          )
        }
        if (field === 'bullets') {
          const items = (slide.bullets ?? []).filter((b) => b.trim())
          if (!items.length) return null
          return (
            <Placed key={field} box={box} bad={bad}>
              <ul style={text}>
                {items.map((b, i) => (
                  <li key={i} className="relative" style={{ paddingLeft: pt(22), marginTop: i ? pt(size * 0.45) : 0 }}>
                    <span className="absolute left-0" style={{ color: s.accent }} aria-hidden>•</span>
                    {b}
                  </li>
                ))}
              </ul>
            </Placed>
          )
        }
        if (field === 'code') {
          if (!slide.code?.text) return null
          return (
            <Placed key={field} box={box} bad={bad} fill={s.codeBg}>
              <pre className="whitespace-pre-wrap break-all" style={{ ...text, fontFamily: '"Courier New", "Liberation Mono", monospace', fontWeight: 400 }}>{slide.code.text}</pre>
            </Placed>
          )
        }
        const value = (slide as unknown as Record<string, unknown>)[field]
        if (typeof value !== 'string' || !value.trim()) return null
        return (
          <Placed key={field} box={box} bad={bad} bottom={box.anchor === 'bottom'}>
            <p className="whitespace-pre-line break-words" style={text}>{value}</p>
          </Placed>
        )
      })}
      <Placed box={{ x: 860, y: 508, w: 72, h: 22 }}>
        <p className="text-right" style={{ fontSize: pt(11), color: s.muted }}>{number} / {total}</p>
      </Placed>
    </div>
  )
}

/** Points on the 960-point page as a share of the slide's width, so the slide scales as a whole. */
const pt = (v: number) => `${(v * 100) / PAGE.w}cqw`
const pct = (b: Box): CSSProperties => ({ left: `${(b.x / PAGE.w) * 100}%`, top: `${(b.y / PAGE.h) * 100}%`, width: `${(b.w / PAGE.w) * 100}%`, height: `${(b.h / PAGE.h) * 100}%` })

function Rect({ box, colour, round }: { box: Box; colour: string; round?: boolean }) {
  return <span aria-hidden className={cn('absolute', round && 'rounded-full')} style={{ ...pct(box), background: colour }} />
}

function Placed({ box, children, bad, bottom, fill }: { box: Box; children: ReactNode; bad?: boolean; bottom?: boolean; fill?: string }) {
  return (
    <div className={cn('absolute flex flex-col', bottom ? 'justify-end' : 'justify-start', bad && 'outline outline-2 outline-dashed outline-rose-500')}
      style={{ ...pct(box), padding: pt(3.6), background: fill }}>
      {children}
    </div>
  )
}

function Figure({ slide, projectId, muted }: { slide: Slide; projectId?: string; muted: string }) {
  const f = slide.figure
  const [state, setState] = useState<{ svg?: string; error?: string }>({})
  useEffect(() => {
    let live = true
    setState({})
    if (!f?.source.trim()) return
    const p = f.kind === 'MATH' ? drawMath(f.source, true) : drawMermaid(f.source)
    p.then((svg) => { if (live) { setState({ svg }); keepFigure(projectId, f.kind, f.source, svg) } },
      (e) => live && setState({ error: e instanceof Error ? e.message : 'Can’t draw this' }))
    return () => { live = false }
  }, [f?.kind, f?.source, projectId])
  if (!f?.source.trim()) return <Missing muted={muted}>No diagram yet</Missing>
  if (state.error) return <Missing muted="#e11d48">{state.error}</Missing>
  if (!state.svg) return <Missing muted={muted}>Drawing…</Missing>
  return <img src={svgDataUrl(state.svg)} alt={f.source} className="w-full h-full object-contain" />
}

function SlideImage({ slide, muted }: { slide: Slide; muted: string }) {
  if (!slide.image?.assetId) return <Missing muted={muted}>No image chosen</Missing>
  return <img src={`/api/v1/media/${slide.image.assetId}/content`} alt={slide.image.alt ?? ''} className="w-full h-full object-contain" />
}

function Missing({ children, muted }: { children: ReactNode; muted: string }) {
  return <p className="m-auto text-center" style={{ fontSize: pt(18), color: muted }}>{children}</p>
}
