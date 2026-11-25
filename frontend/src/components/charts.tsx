import { useLayoutEffect, useRef, useState } from 'react'
import { cn } from '../lib/cn'
import { compact, niceTicks, shortDay } from '../lib/analytics'

export interface Series<K extends string> {
  key: K
  label: string
  color: string
}

interface Props<K extends string> {
  /** One row per day, values per series key. */
  rows: { date: string; values: Record<K, number> }[]
  series: Series<K>[]
  /** Accessible name and table caption. */
  label: string
  format?: (v: number) => string
  height?: number
}

function useWidth() {
  const ref = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(640)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const measure = () => setWidth(Math.max(240, el.clientWidth))
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  return { ref, width }
}

/**
 * Stacked daily columns: thin bars (≤ 24px) with 4px rounded tops, a 2px surface
 * gap between segments, one hairline grid, a legend for two or more series, a
 * hover tooltip per day, and a table view with every number.
 */
export function ColumnChart<K extends string>({ rows, series, label, format = compact, height = 220 }: Props<K>) {
  const { ref, width } = useWidth()
  const [hover, setHover] = useState<number>()
  const [table, setTable] = useState(false)

  const totals = rows.map((r) => series.reduce((s, x) => s + (r.values[x.key] ?? 0), 0))
  const ticks = niceTicks(Math.max(0, ...totals))
  const top = ticks[ticks.length - 1]
  const pad = { l: 44, r: 8, t: 8, b: 24 }
  const plotW = width - pad.l - pad.r
  const plotH = height - pad.t - pad.b
  const band = plotW / Math.max(1, rows.length)
  const barW = Math.max(2, Math.min(24, band * 0.62))
  const y = (v: number) => pad.t + plotH - (v / top) * plotH
  const labelEvery = Math.ceil(rows.length / Math.max(2, Math.floor(plotW / 64)))
  const empty = totals.every((t) => t === 0)

  return (
    <figure className="flex flex-col gap-2 m-0">
      <div className="flex flex-wrap items-center justify-between gap-3 min-h-6">
        {series.length > 1 ? (
          <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted" aria-label="Legend">
            {series.map((s) => (
              <li key={s.key} className="flex items-center gap-1.5">
                <span aria-hidden className="size-2.5 rounded-sm" style={{ background: s.color }} />
                {s.label}
              </li>
            ))}
          </ul>
        ) : (
          <span />
        )}
        <button type="button" className="text-xs text-muted hover:text-ink underline-offset-2 hover:underline" onClick={() => setTable(!table)} aria-pressed={table}>
          {table ? 'Show chart' : 'Show table'}
        </button>
      </div>

      {table ? (
        <div className="overflow-x-auto max-h-72 rounded-lg border border-line">
          <table className="w-full text-sm tabular">
            <caption className="sr-only">{label}</caption>
            <thead className="bg-surface-2/60 text-muted text-xs sticky top-0">
              <tr>
                <th scope="col" className="text-left font-medium px-3 py-2">Day</th>
                {series.map((s) => <th key={s.key} scope="col" className="text-right font-medium px-3 py-2">{s.label}</th>)}
                {series.length > 1 && <th scope="col" className="text-right font-medium px-3 py-2">Total</th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={r.date} className="border-t border-line">
                  <th scope="row" className="text-left font-normal px-3 py-1.5">{shortDay(r.date)}</th>
                  {series.map((s) => <td key={s.key} className="text-right px-3 py-1.5">{format(r.values[s.key] ?? 0)}</td>)}
                  {series.length > 1 && <td className="text-right px-3 py-1.5">{format(totals[i])}</td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div ref={ref} className="relative" onMouseLeave={() => setHover(undefined)}>
          <svg width={width} height={height} role="img" aria-label={label} className="block overflow-visible">
            {ticks.map((t) => (
              <g key={t}>
                <line x1={pad.l} x2={width - pad.r} y1={y(t)} y2={y(t)} stroke="var(--grid)" strokeWidth={1} />
                <text x={pad.l - 8} y={y(t)} dy="0.32em" textAnchor="end" className="fill-[var(--text-faint)] text-[11px] tabular">{format(t)}</text>
              </g>
            ))}
            {rows.map((r, i) => {
              const cx = pad.l + band * i + band / 2
              let acc = 0
              const visible = series.filter((s) => (r.values[s.key] ?? 0) > 0)
              return (
                <g key={r.date} opacity={hover === undefined || hover === i ? 1 : 0.45}>
                  {visible.map((s, j) => {
                    const v = r.values[s.key]
                    const y0 = y(acc)
                    acc += v
                    const y1 = y(acc)
                    const isTop = j === visible.length - 1
                    const gap = j > 0 ? 2 : 0                      // surface gap between stacked segments
                    const h = Math.max(1, y0 - y1 - gap)
                    const x = cx - barW / 2
                    const yy = y1
                    const rad = isTop ? Math.min(4, h, barW / 2) : 0
                    const d = `M${x},${yy + h} V${yy + rad} Q${x},${yy} ${x + rad},${yy} H${x + barW - rad} Q${x + barW},${yy} ${x + barW},${yy + rad} V${yy + h} Z`
                    return <path key={s.key} d={d} fill={s.color} />
                  })}
                  {i % labelEvery === 0 && (
                    <text x={cx} y={height - 6} textAnchor="middle" className="fill-[var(--text-faint)] text-[11px]">{shortDay(r.date)}</text>
                  )}
                  <rect
                    x={pad.l + band * i}
                    y={pad.t}
                    width={band}
                    height={plotH}
                    fill="transparent"
                    onMouseEnter={() => setHover(i)}
                    onFocus={() => setHover(i)}
                    onBlur={() => setHover(undefined)}
                    tabIndex={-1}
                  />
                </g>
              )
            })}
            <line x1={pad.l} x2={width - pad.r} y1={y(0)} y2={y(0)} stroke="var(--line)" strokeWidth={1} />
          </svg>
          {empty && <p className="absolute inset-0 grid place-items-center text-sm text-faint pointer-events-none">Nothing in this period</p>}
          {hover !== undefined && !empty && (
            <div
              role="tooltip"
              className="pointer-events-none absolute z-10 rounded-lg border border-line bg-surface shadow-lg px-3 py-2 text-xs min-w-36"
              style={{
                left: Math.min(width - 160, Math.max(0, pad.l + band * hover + band / 2 - 72)),
                top: 0,
              }}
            >
              <p className="font-medium text-ink mb-1">{shortDay(rows[hover].date)}</p>
              {series.map((s) => (
                <p key={s.key} className="flex items-center gap-2 text-muted">
                  <span aria-hidden className="size-2 rounded-sm" style={{ background: s.color }} />
                  <span className="flex-1">{s.label}</span>
                  <span className="tabular text-ink">{format(rows[hover].values[s.key] ?? 0)}</span>
                </p>
              ))}
              {series.length > 1 && (
                <p className={cn('flex justify-between gap-2 border-t border-line mt-1 pt-1 text-muted')}>
                  Total <span className="tabular text-ink">{format(totals[hover])}</span>
                </p>
              )}
            </div>
          )}
        </div>
      )}
    </figure>
  )
}

/** Horizontal share bars inside a table cell: a thin bar sized to the largest value. */
export function InlineBar({ value, max, color }: { value?: number; max: number; color: string }) {
  if (value == null) return null
  return (
    <span aria-hidden className="block h-1.5 rounded-full bg-[var(--grid)] overflow-hidden mt-1">
      <span className="block h-full rounded-full" style={{ width: `${max > 0 ? Math.max(2, (value / max) * 100) : 0}%`, background: color }} />
    </span>
  )
}

export interface RetentionPointLike {
  ratio: number
  audienceWatchRatio: number
  relativeRetentionPerformance?: number | null
}

/**
 * Audience retention: the share of the audience watching at each point of a video (YouTube's
 * audience watch ratio; above 1 where people rewatch). One 2px line on a single axis, scenes
 * as faint bands, a crosshair with a tooltip, and a table view with every point.
 */
export function RetentionChart({
  points,
  scenes = [],
  duration,
  label,
  height = 240,
}: {
  points: RetentionPointLike[]
  scenes?: { start: number; end: number; title?: string | null }[]
  duration?: number | null
  label: string
  height?: number
}) {
  const { ref, width } = useWidth()
  const [hover, setHover] = useState<number>()
  const [table, setTable] = useState(false)
  const pad = { l: 44, r: 12, t: 10, b: 26 }
  const plotW = width - pad.l - pad.r
  const plotH = height - pad.t - pad.b
  const ticks = niceTicks(Math.max(1, ...points.map((p) => p.audienceWatchRatio)))
  const top = ticks[ticks.length - 1]
  const x = (r: number) => pad.l + r * plotW
  const y = (v: number) => pad.t + plotH - (v / top) * plotH
  const path = points.map((p, i) => `${i ? 'L' : 'M'}${x(p.ratio).toFixed(1)},${y(p.audienceWatchRatio).toFixed(1)}`).join(' ')
  const at = (r: number) => (duration ? clockOf(r * duration) : `${Math.round(r * 100)}%`)
  const sceneAt = (r: number) => (duration ? scenes.find((s) => r * duration >= s.start && r * duration < s.end)?.title : undefined)
  const pick = (clientX: number, left: number) => {
    const r = Math.min(1, Math.max(0, (clientX - left - pad.l) / plotW))
    let best = 0
    for (let i = 1; i < points.length; i++) if (Math.abs(points[i].ratio - r) < Math.abs(points[best].ratio - r)) best = i
    setHover(best)
  }
  const h = hover === undefined ? undefined : points[hover]
  return (
    <figure className="flex flex-col gap-2 m-0">
      <div className="flex items-center justify-between gap-3 min-h-6">
        <span className="text-xs text-muted">{duration ? 'Time in the video' : 'Share of the video'} → · audience watching ↑</span>
        <button type="button" className="text-xs text-muted hover:text-ink underline-offset-2 hover:underline" onClick={() => setTable(!table)} aria-pressed={table}>
          {table ? 'Show chart' : 'Show table'}
        </button>
      </div>
      {table ? (
        <div className="overflow-x-auto max-h-72 rounded-lg border border-line">
          <table className="w-full text-sm tabular">
            <caption className="sr-only">{label}</caption>
            <thead className="bg-surface-2/60 text-muted text-xs sticky top-0">
              <tr>
                <th scope="col" className="text-left font-medium px-3 py-2">{duration ? 'Time' : 'Point'}</th>
                {scenes.length > 0 && <th scope="col" className="text-left font-medium px-3 py-2">Scene</th>}
                <th scope="col" className="text-right font-medium px-3 py-2">Audience watch ratio</th>
                <th scope="col" className="text-right font-medium px-3 py-2">Relative retention</th>
              </tr>
            </thead>
            <tbody>
              {points.map((p) => (
                <tr key={p.ratio} className="border-t border-line">
                  <th scope="row" className="text-left font-normal px-3 py-1.5">{at(p.ratio)}</th>
                  {scenes.length > 0 && <td className="px-3 py-1.5 text-muted">{sceneAt(p.ratio) ?? ''}</td>}
                  <td className="text-right px-3 py-1.5">{p.audienceWatchRatio.toFixed(2)}</td>
                  <td className="text-right px-3 py-1.5">{p.relativeRetentionPerformance == null ? '—' : p.relativeRetentionPerformance.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div
          ref={ref}
          className="relative"
          onMouseMove={(e) => pick(e.clientX, e.currentTarget.getBoundingClientRect().left)}
          onMouseLeave={() => setHover(undefined)}
        >
          <svg width={width} height={height} role="img" aria-label={label} className="block overflow-visible">
            {duration
              ? scenes.map((s, i) =>
                  i % 2 === 1 ? (
                    <rect key={i} x={x(s.start / duration)} y={pad.t} width={Math.max(0, x(Math.min(1, s.end / duration)) - x(s.start / duration))} height={plotH} fill="var(--grid)" opacity={0.55} />
                  ) : null,
                )
              : null}
            {ticks.map((t) => (
              <g key={t}>
                <line x1={pad.l} x2={width - pad.r} y1={y(t)} y2={y(t)} stroke="var(--grid)" strokeWidth={1} />
                <text x={pad.l - 8} y={y(t)} dy="0.32em" textAnchor="end" className="fill-[var(--text-faint)] text-[11px] tabular">{t.toFixed(t % 1 ? 2 : 0)}</text>
              </g>
            ))}
            {[0, 0.25, 0.5, 0.75, 1].map((r) => (
              <text key={r} x={x(r)} y={height - 6} textAnchor={r === 0 ? 'start' : r === 1 ? 'end' : 'middle'} className="fill-[var(--text-faint)] text-[11px] tabular">{at(r)}</text>
            ))}
            <path d={path} fill="none" stroke="var(--series-1)" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
            <line x1={pad.l} x2={width - pad.r} y1={y(0)} y2={y(0)} stroke="var(--line)" strokeWidth={1} />
            {h && (
              <g>
                <line x1={x(h.ratio)} x2={x(h.ratio)} y1={pad.t} y2={pad.t + plotH} stroke="var(--text-faint)" strokeWidth={1} strokeDasharray="3 3" />
                <circle cx={x(h.ratio)} cy={y(h.audienceWatchRatio)} r={4} fill="var(--series-1)" stroke="var(--surface)" strokeWidth={2} />
              </g>
            )}
          </svg>
          {points.length === 0 && <p className="absolute inset-0 grid place-items-center text-sm text-faint pointer-events-none">No retention data for this period</p>}
          {h && (
            <div
              role="tooltip"
              className="pointer-events-none absolute z-10 rounded-lg border border-line bg-surface shadow-lg px-3 py-2 text-xs min-w-40"
              style={{ left: Math.min(width - 176, Math.max(0, x(h.ratio) - 80)), top: 0 }}
            >
              <p className="font-medium text-ink">{at(h.ratio)}{sceneAt(h.ratio) ? ` · ${sceneAt(h.ratio)}` : ''}</p>
              <p className="flex justify-between gap-3 text-muted mt-1">Audience watch ratio <span className="tabular text-ink">{h.audienceWatchRatio.toFixed(2)}</span></p>
              {h.relativeRetentionPerformance != null && (
                <p className="flex justify-between gap-3 text-muted">Relative retention <span className="tabular text-ink">{h.relativeRetentionPerformance.toFixed(2)}</span></p>
              )}
            </div>
          )}
        </div>
      )}
    </figure>
  )
}

function clockOf(seconds: number): string {
  const s = Math.round(seconds)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}
