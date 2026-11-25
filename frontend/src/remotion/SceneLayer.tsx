import type { CSSProperties, ReactNode } from 'react'
import { AbsoluteFill, Img, useCurrentFrame, useVideoConfig } from 'remotion'
import { appearTimes, ease } from './sceneTiming'
import { geometry, MAX_SIZE, PAGE, STYLES, type Box, type StyleColours, type StyleKey } from './slideDesign'
import type { MediaRef, SceneData, SceneQuestion } from './types'

/**
 * The lesson and quiz video templates (E8). Each scene is drawn from approved content: a slide
 * keeps the presentation's boxes, colours and measured text sizes (in fonts with the same widths
 * as PowerPoint's), and builds up while it is narrated; a question shows its options, counts
 * down, reveals the answer and shows the explanation. Times come from the narration.
 */
export function SceneLayer({ scene, width, height, media }: { scene: SceneData; width: number; height: number; media: Record<string, MediaRef> }) {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()
  const t = frame / fps
  const s = STYLES[scene.style as StyleKey] ?? STYLES.LECTURE
  const vertical = scene.aspect === '9:16'
  const band = scene.captions === false ? 0 : vertical ? 330 : 140
  const area = { w: width, h: height - band }
  const fadeIn = ease(t, 0, 0.35)
  return (
    <AbsoluteFill style={{ background: s.bg, fontFamily: SANS }}>
      {/* the background is there from the first frame; only the content fades in */}
      <AbsoluteFill style={{ opacity: fadeIn }}>
      {scene.kind === 'SLIDE' && scene.slide ? (
        <SlideScene scene={scene} s={s} t={t} area={area} vertical={vertical} media={media} />
      ) : scene.kind === 'QUESTION' && scene.question ? (
        <QuestionScene q={scene.question} scene={scene} s={s} t={t} area={area} vertical={vertical} />
      ) : (
        <TitleScene scene={scene} s={s} t={t} area={area} vertical={vertical} />
      )}
      </AbsoluteFill>
      {scene.total && scene.number ? <Progress s={s} at={(scene.number - 1 + Math.min(1, t / 3)) / scene.total} height={height} band={band} /> : null}
    </AbsoluteFill>
  )
}

const SANS = 'Carlito, "Noto Sans Tamil", sans-serif'
const MONO = 'Cousine, "Noto Sans Tamil", monospace'

function Progress({ s, at, height, band }: { s: StyleColours; at: number; height: number; band: number }) {
  return <div style={{ position: 'absolute', left: 0, top: height - band - 6, height: 6, width: `${Math.min(100, at * 100)}%`, background: s.accent, opacity: 0.8 }} />
}

// ---- a slide ------------------------------------------------------------------------------------

function SlideScene({ scene, s, t, area, vertical, media }: {
  scene: SceneData; s: StyleColours; t: number; area: { w: number; h: number }; vertical: boolean; media: Record<string, MediaRef>
}) {
  const slide = scene.slide!
  const k = Math.min(area.w / PAGE.w, area.h / PAGE.h)
  const left = (area.w - PAGE.w * k) / 2
  const top = vertical ? Math.max(0, (area.h - PAGE.h * k) / 2 - 80) : (area.h - PAGE.h * k) / 2
  const boxes = geometry(slide.layout)
  const size = (f: string) => scene.sizes?.[f] ?? MAX_SIZE[f] ?? 18
  const band = !!s.band && slide.layout !== 'TITLE'
  const start = scene.speakStart ?? 0.4
  const dur = scene.speakDuration ?? 4
  const kw = scene.keywords ?? []
  const titleColour = band ? '#FFFFFF' : s.title
  const items = (slide.bullets ?? []).filter((b) => b.trim())
  const times = appearTimes(items.length, start, dur)
  const rows = slide.table?.rows ?? []
  const rowTimes = appearTimes(rows.length, start + 0.3, dur)

  return (
    <div style={{ position: 'absolute', left, top, width: PAGE.w, height: PAGE.h, transform: `scale(${k})`, transformOrigin: '0 0', background: s.bg, overflow: 'hidden' }}>
      {band && <Rect b={{ x: 0, y: 0, w: PAGE.w, h: 120 }} c={s.band!} />}
      {s.bar && <Rect b={{ x: 43, y: 116, w: 96 * ease(t, 0.2, 0.6), h: 4 }} c={s.accent} />}
      {scene.style === 'ILLUSTRATED' && (
        <>
          <Rect b={{ x: PAGE.w - 70, y: -30, w: 120, h: 120 }} c={s.codeBg} round />
          <Rect b={{ x: -40, y: PAGE.h - 60, w: 110, h: 110 }} c={s.codeBg} round />
        </>
      )}
      {Object.entries(boxes).map(([field, b]) => {
        const text: CSSProperties = { fontSize: size(field), lineHeight: 1.2, color: field === 'caption' ? s.muted : s.text }
        if (field === 'title') {
          const a = ease(t, 0.15, 0.4)
          return (
            <Place key={field} b={b} bottom>
              <p style={{ ...text, color: titleColour, fontWeight: 700, opacity: a, transform: `translateY(${(1 - a) * 12}px)` }}>{slide.title}</p>
            </Place>
          )
        }
        if (field === 'bullets' && items.length) {
          return (
            <Place key={field} b={b}>
              <ul style={{ ...text, listStyle: 'none', margin: 0, padding: 0 }}>
                {items.map((it, i) => {
                  const a = ease(t, times[i], 0.4)
                  return (
                    <li key={i} style={{ position: 'relative', paddingLeft: 22, marginTop: i ? size(field) * 0.45 : 0, opacity: a, transform: `translateX(${(1 - a) * 18}px)` }}>
                      <span style={{ position: 'absolute', left: 0, color: s.accent }}>•</span>
                      <Highlight text={it} words={kw} at={times[i] + 0.5} t={t} s={s} />
                    </li>
                  )
                })}
              </ul>
            </Place>
          )
        }
        if (field === 'term' && slide.term) {
          const a = ease(t, start, 0.5)
          return (
            <Place key={field} b={b}>
              <p style={{ ...text, fontWeight: 700, color: s.accent, opacity: a, transform: `scale(${0.92 + 0.08 * a})`, transformOrigin: 'left center' }}>{slide.term}</p>
            </Place>
          )
        }
        if (field === 'text' && slide.text) {
          const a = ease(t, start + (slide.layout === 'DEFINITION' ? 0.8 : 0.2), 0.5)
          return (
            <Place key={field} b={b}>
              <p style={{ ...text, whiteSpace: 'pre-line', opacity: a }}><Highlight text={slide.text} words={kw} at={start + 1.2} t={t} s={s} /></p>
            </Place>
          )
        }
        if (field === 'code' && slide.code?.text) {
          const all = slide.code.text
          const shown = Math.round(all.length * ease(t, start + 0.3, Math.max(1, dur * 0.6)))
          return (
            <Place key={field} b={b} fill={s.codeBg}>
              <pre style={{ ...text, fontFamily: MONO, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                {all.slice(0, shown)}
                {shown < all.length && <span style={{ opacity: Math.round(t * 2) % 2 ? 1 : 0, color: s.accent }}>▍</span>}
              </pre>
            </Place>
          )
        }
        if (field === 'table' && slide.table) {
          const tb = slide.table
          return (
            <Place key={field} b={b}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: size('table'), lineHeight: 1.2, color: s.text }}>
                {tb.headers.length > 0 && (
                  <thead>
                    <tr style={{ opacity: ease(t, start, 0.4) }}>
                      {tb.headers.map((h, i) => <th key={i} style={{ background: s.headBg, color: s.headText, padding: 5, textAlign: 'left', verticalAlign: 'top' }}>{h}</th>)}
                    </tr>
                  </thead>
                )}
                <tbody>
                  {rows.map((r, i) => (
                    <tr key={i} style={{ borderBottom: `1px solid ${s.muted}40`, opacity: ease(t, rowTimes[i], 0.4) }}>
                      {r.map((c, j) => <td key={j} style={{ padding: 5, verticalAlign: 'top' }}>{c}</td>)}
                    </tr>
                  ))}
                </tbody>
              </table>
            </Place>
          )
        }
        if (field === 'figure' && slide.figure?.svg) {
          // an animated diagram: drawn in from the left (a flowchart reads left to right or top down)
          const a = ease(t, start, Math.max(1.2, dur * 0.5))
          const reveal = slide.figure.kind === 'MATH' ? `inset(0 0 0 0)` : `inset(0 ${(1 - a) * 100}% 0 0)`
          return (
            <Place key={field} b={b}>
              <Img src={svgUrl(slide.figure.svg)} style={{ width: '100%', height: '100%', objectFit: 'contain', clipPath: reveal,
                opacity: slide.figure.kind === 'MATH' ? a : 1, transform: slide.figure.kind === 'MATH' ? `scale(${0.9 + 0.1 * a})` : undefined }} />
            </Place>
          )
        }
        if (field === 'image' && scene.imageMediaId && media[scene.imageMediaId]) {
          const zoom = 1 + 0.04 * Math.min(1, t / Math.max(3, dur + 1))
          return (
            <Place key={field} b={b}>
              <div style={{ width: '100%', height: '100%', overflow: 'hidden' }}>
                <Img src={media[scene.imageMediaId].url} style={{ width: '100%', height: '100%', objectFit: 'contain', transform: `scale(${zoom})` }} />
              </div>
            </Place>
          )
        }
        const value = field === 'subtitle' ? slide.subtitle : field === 'caption' ? slide.caption : null
        if (!value) return null
        return (
          <Place key={field} b={b}>
            <p style={{ ...text, opacity: ease(t, start + 0.4, 0.5) }}>{value}</p>
          </Place>
        )
      })}
    </div>
  )
}

/** Text with the keywords marked; the mark sweeps in at `at`. */
export function Highlight({ text, words, at, t, s }: { text: string; words: string[]; at: number; t: number; s: StyleColours }) {
  const ws = words.filter((w) => w.trim().length >= 2)
  if (!ws.length) return <>{text}</>
  const re = new RegExp(`(${ws.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`, 'gi')
  const a = ease(t, at, 0.5)
  return (
    <>
      {text.split(re).map((part, i) =>
        i % 2 === 1 ? (
          <mark key={i} style={{ color: 'inherit', fontWeight: 700, background: `linear-gradient(${s.accent}55, ${s.accent}55) no-repeat 0 85% / ${a * 100}% 40%` }}>{part}</mark>
        ) : (
          <span key={i}>{part}</span>
        ),
      )}
    </>
  )
}

function Rect({ b, c, round }: { b: Box; c: string; round?: boolean }) {
  return <div style={{ position: 'absolute', left: b.x, top: b.y, width: b.w, height: b.h, background: c, borderRadius: round ? '50%' : 0 }} />
}

function Place({ b, children, bottom, fill }: { b: Box; children: ReactNode; bottom?: boolean; fill?: string }) {
  return (
    <div style={{ position: 'absolute', left: b.x, top: b.y, width: b.w, height: b.h, padding: 3.6, boxSizing: 'border-box', background: fill,
      display: 'flex', flexDirection: 'column', justifyContent: bottom ? 'flex-end' : 'flex-start' }}>
      {children}
    </div>
  )
}

const svgUrl = (svg: string) => `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svg)))}`

// ---- title, outro, quiz intro, summary ----------------------------------------------------------

function TitleScene({ scene, s, t, area, vertical }: { scene: SceneData; s: StyleColours; t: number; area: { w: number; h: number }; vertical: boolean }) {
  const lines = scene.lines ?? []
  const start = scene.speakStart ?? 0.4
  const times = appearTimes(lines.length, start + 0.3, scene.speakDuration ?? 3)
  const list = scene.kind === 'SUMMARY'
  const unit = vertical ? area.w / 1080 : area.h / 1080
  const a = ease(t, 0.1, 0.5)
  const ground = s.band ?? s.headBg
  return (
    <div style={{ position: 'absolute', left: 0, top: 0, width: area.w, height: area.h, display: 'flex', flexDirection: 'column',
      justifyContent: list ? 'flex-start' : 'center', padding: `${(list ? 110 : 0) * unit}px ${(vertical ? 80 : 160) * unit}px`, boxSizing: 'border-box' }}>
      {scene.kind === 'QUIZ_INTRO' && (
        <div style={{ alignSelf: 'flex-start', background: s.accent, color: s.headText, fontWeight: 700, fontSize: 40 * unit, padding: `${8 * unit}px ${24 * unit}px`,
          borderRadius: 12 * unit, marginBottom: 36 * unit, transform: `scale(${0.6 + 0.4 * ease(t, 0, 0.4)})`, transformOrigin: 'left center' }}>
          {scene.language === 'TA' ? 'வினாடி வினா' : 'Quiz'}
        </div>
      )}
      <h1 style={{ margin: 0, fontSize: (list ? 72 : vertical ? 92 : 96) * unit, lineHeight: 1.12, fontWeight: 700, color: s.band ? ground : s.title,
        opacity: a, transform: `translateY(${(1 - a) * 24 * unit}px)` }}>
        {scene.title}
      </h1>
      <div style={{ height: 8 * unit, width: 220 * unit * ease(t, 0.3, 0.7), background: s.accent, margin: `${28 * unit}px 0` }} />
      <ul style={{ listStyle: 'none', margin: 0, padding: 0, columns: list && lines.length > 8 && !vertical ? 2 : undefined }}>
        {lines.map((l, i) => (
          <li key={i} style={{ fontSize: (list ? 40 : 48) * unit, lineHeight: 1.3, color: list ? s.text : s.muted, marginBottom: 12 * unit,
            opacity: ease(t, times[i], 0.4), breakInside: 'avoid' }}>
            {l}
          </li>
        ))}
      </ul>
    </div>
  )
}

// ---- a question ---------------------------------------------------------------------------------

const LETTERS = ['A', 'B', 'C', 'D']
const RIGHT = '#16A34A'

function QuestionScene({ q, scene, s, t, area, vertical }: {
  q: SceneQuestion; scene: SceneData; s: StyleColours; t: number; area: { w: number; h: number }; vertical: boolean
}) {
  // phones: bigger type, centred in the frame
  const unit = vertical ? (area.w / 1080) * 1.3 : area.h / 1080
  const start = scene.speakStart ?? 0.4
  const cdStart = scene.countdownStart ?? start + (scene.speakDuration ?? 4) + 0.3
  const cd = scene.countdown ?? 5
  const reveal = scene.revealAt ?? cdStart + cd
  const answerAt = scene.answerStart ?? reveal + 0.6
  const optTimes = appearTimes(4, start + Math.min(2, (scene.speakDuration ?? 4) * 0.35), (scene.speakDuration ?? 4) * 0.8)
  const revealed = t >= reveal
  const pulse = ease(t, reveal, 0.35)
  const pad = (vertical ? 56 : 110) * unit
  const stemSize = (q.stem.length > 160 ? 46 : q.stem.length > 90 ? 54 : 62) * unit
  return (
    <div style={{ position: 'absolute', left: 0, top: 0, width: area.w, height: area.h, padding: pad, boxSizing: 'border-box', display: 'flex', flexDirection: 'column',
      justifyContent: vertical ? 'center' : 'flex-start', gap: 26 * unit }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span style={{ background: s.accent, color: s.headText, fontWeight: 700, fontSize: 34 * unit, padding: `${6 * unit}px ${20 * unit}px`, borderRadius: 10 * unit }}>
          {(scene.language === 'TA' ? 'கேள்வி ' : 'Question ') + q.number + (q.total ? ` / ${q.total}` : '')}
        </span>
        <Countdown t={t} start={cdStart} seconds={cd} s={s} size={vertical ? 150 * unit : 130 * unit} />
      </div>
      <p style={{ margin: 0, fontSize: stemSize, lineHeight: 1.2, fontWeight: 700, color: s.text, opacity: ease(t, 0.1, 0.4) }}>{q.stem}</p>
      {q.code?.text && (
        <pre style={{ margin: 0, fontFamily: MONO, fontSize: 28 * unit, lineHeight: 1.25, background: s.codeBg, color: s.text, padding: 16 * unit, borderRadius: 10 * unit, whiteSpace: 'pre-wrap' }}>
          {q.code.text}
        </pre>
      )}
      <div style={{ display: 'grid', gridTemplateColumns: vertical ? '1fr' : '1fr 1fr', gap: 18 * unit }}>
        {q.options.map((o, i) => {
          const right = i === q.answer
          const a = ease(t, optTimes[i], 0.4)
          const bg = revealed ? (right ? RIGHT : s.bg) : s.codeBg
          return (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 18 * unit, padding: `${16 * unit}px ${20 * unit}px`, borderRadius: 14 * unit,
              background: bg, border: `${3 * unit}px solid ${revealed && right ? RIGHT : `${s.muted}55`}`, color: revealed && right ? '#FFFFFF' : s.text,
              opacity: a * (revealed && !right ? 0.45 : 1), transform: `translateY(${(1 - a) * 16 * unit}px) scale(${revealed && right ? 1 + 0.04 * Math.sin(pulse * Math.PI) : 1})` }}>
              <span style={{ flex: 'none', display: 'grid', placeItems: 'center', width: 58 * unit, height: 58 * unit, borderRadius: '50%', fontWeight: 700, fontSize: 32 * unit,
                background: revealed && right ? '#FFFFFF' : s.accent, color: revealed && right ? RIGHT : s.headText }}>
                {revealed && right ? '✓' : LETTERS[i]}
              </span>
              <span style={{ fontSize: (o.length > 60 ? 32 : 38) * unit, lineHeight: 1.2 }}>{o}</span>
            </div>
          )
        })}
      </div>
      {q.explanation && t >= answerAt && (
        <p style={{ margin: 0, fontSize: 34 * unit, lineHeight: 1.3, color: s.text, borderLeft: `${6 * unit}px solid ${RIGHT}`, paddingLeft: 18 * unit,
          opacity: ease(t, answerAt, 0.5) }}>
          {q.explanation}
        </p>
      )}
    </div>
  )
}

/** A ring that empties over the countdown, with the seconds left in the middle. */
export function Countdown({ t, start, seconds, s, size }: { t: number; start: number; seconds: number; s: StyleColours; size: number }) {
  const running = t >= start
  const left = Math.max(0, seconds - (t - start))
  const share = running ? left / seconds : 1
  const r = 44
  const c = 2 * Math.PI * r
  const shown = running ? Math.ceil(left) : seconds
  return (
    <svg width={size} height={size} viewBox="0 0 100 100" style={{ opacity: ease(t, start - 0.5, 0.4) * 0.6 + (running ? 0.4 : 0) }}>
      <circle cx="50" cy="50" r={r} fill="none" stroke={`${s.muted}55`} strokeWidth="8" />
      <circle cx="50" cy="50" r={r} fill="none" stroke={left <= 2 && running ? '#DC2626' : s.accent} strokeWidth="8" strokeLinecap="round"
        strokeDasharray={c} strokeDashoffset={c * (1 - share)} transform="rotate(-90 50 50)" />
      <text x="50" y="50" textAnchor="middle" dominantBaseline="central" fontSize="38" fontWeight="700" fill={s.text} fontFamily={SANS}>
        {left <= 0 && running ? '✓' : shown}
      </text>
    </svg>
  )
}
