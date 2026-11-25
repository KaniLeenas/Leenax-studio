import { AlertTriangle, ExternalLink, RefreshCw } from 'lucide-react'
import { useMemo, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ColumnChart, InlineBar } from '../components/charts'
import { PlatformMark } from '../components/publish'
import { Button, ErrorNotice, Spinner } from '../components/ui'
import { cn } from '../lib/cn'
import { compact, dayRange, formatMoney, PLATFORM_SERIES, pivot, shortDay, UNIT_NAME, type Overview } from '../lib/analytics'
import { useOverview, useRefreshNumbers } from '../lib/analyticsQueries'
import { formatRelative } from '../lib/format'
import { PLATFORM, PLATFORM_ORDER, type SocialPlatform } from '../lib/publish'
import { toast } from '../stores/ui'

const RANGES = [7, 30, 90] as const

const PROVIDER_NAME: Record<string, string> = {
  RUNWAY: 'Runway', GOOGLE_GEMINI: 'Google Gemini', KLING: 'Kling AI', LUMA: 'Luma AI',
  OPENAI: 'OpenAI', ANTHROPIC: 'Anthropic', ELEVENLABS: 'ElevenLabs', GOOGLE_CLOUD_TTS: 'Google Cloud TTS',
}

export function AnalyticsPage() {
  const [days, setDays] = useState<number>(30)
  const overview = useOverview(days)
  const refresh = useRefreshNumbers()
  const data = overview.data

  return (
    <>
      <header className="flex flex-wrap items-end justify-between gap-4 mb-8">
        <div>
          <h1 className="text-3xl sm:text-[2.15rem] font-semibold">Analytics</h1>
          <p className="text-muted mt-1 max-w-2xl">What you published, how it is doing on each platform, and what the AI work cost.</p>
        </div>
        <div className="flex items-center gap-2">
          <div role="radiogroup" aria-label="Period" className="inline-flex rounded-lg border border-line p-0.5 bg-surface">
            {RANGES.map((r) => (
              <button key={r} role="radio" aria-checked={days === r} onClick={() => setDays(r)}
                className={cn('px-3 h-8 rounded-md text-sm', days === r ? 'bg-surface-2 text-ink font-medium' : 'text-muted hover:text-ink')}>
                {r} days
              </button>
            ))}
          </div>
        </div>
      </header>

      {overview.isPending && <Spinner label="Loading analytics" />}
      {overview.error && <ErrorNotice error={overview.error} onRetry={() => overview.refetch()} />}
      {data && (
        <div className={cn('flex flex-col gap-10 transition-opacity', overview.isFetching && overview.isPlaceholderData && 'opacity-60')}>
          <Headline data={data} />
          <PublishingSection data={data} />
          <PerformanceSection
            data={data}
            refreshing={refresh.isPending}
            onRefresh={() =>
              refresh.mutate(undefined, {
                onSuccess: (r) => toast.success(r.updated === 1 ? 'Numbers updated for 1 post' : `Numbers updated for ${r.updated} posts`),
                onError: (e) => toast.error(e.message),
              })
            }
          />
          <CostsSection data={data} />
        </div>
      )}
    </>
  )
}

// ---- headline numbers ----------------------------------------------------------------------------

function Tile({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <div className="rounded-2xl border border-line bg-surface p-5 min-w-0">
      <p className="text-sm text-muted">{label}</p>
      <p className="text-2xl sm:text-3xl font-semibold tabular mt-1 break-words">{value}</p>
      {sub && <p className="text-xs text-faint mt-1">{sub}</p>}
    </div>
  )
}

function Headline({ data }: { data: Overview }) {
  const p = data.publishing
  const known = data.performance.byPlatform.filter((x) => x.views != null)
  const views = known.reduce((s, x) => s + (x.views ?? 0), 0)
  const spend = data.costs.totals
  return (
    <section aria-label="Summary" className="grid grid-cols-2 lg:grid-cols-4 gap-4">
      <Tile label="Published" value={p.published} sub={p.failed > 0 ? `${p.failed} failed` : 'No failures'} />
      <Tile label="Scheduled" value={p.upcoming} sub="Waiting to go out" />
      <Tile label="Views" value={known.length ? compact(views) : '—'} sub={known.length ? 'Where the platform reports views' : 'No numbers yet'} />
      <Tile
        label="AI spend"
        value={spend.length === 0 ? '—' : formatMoney(spend[0].amount, spend[0].unit)}
        sub={spend.length > 1 ? spend.slice(1).map((s) => formatMoney(s.amount, s.unit)).join(' · ') : `${data.costs.calls} AI calls`}
      />
    </section>
  )
}

// ---- publishing ---------------------------------------------------------------------------------------

function PublishingSection({ data }: { data: Overview }) {
  const days = useMemo(() => dayRange(data.from, data.to, data.timeZone), [data.from, data.to, data.timeZone])
  const active = PLATFORM_ORDER.filter((pl) => data.publishing.byPlatform.some((b) => b.platform === pl && (b.published > 0 || b.failed > 0)))
  const series = (active.length ? active : PLATFORM_ORDER).map((pl) => ({ key: pl, label: PLATFORM[pl].name, color: PLATFORM_SERIES[pl] }))
  const rows = pivot(days, data.publishing.daily.map((d) => ({ date: d.date, key: d.platform, value: d.count })), series.map((s) => s.key))
  return (
    <section aria-labelledby="pub-h" className="grid xl:grid-cols-[minmax(0,1fr)_20rem] gap-6 items-start">
      <div className="rounded-2xl border border-line bg-surface p-5 min-w-0">
        <h2 id="pub-h" className="font-semibold">Posts published per day</h2>
        <p className="text-sm text-muted mb-3">Each account counts once.</p>
        <ColumnChart rows={rows} series={series} label="Posts published per day, by platform" />
      </div>
      <div className="flex flex-col gap-4">
        <div className="rounded-2xl border border-line bg-surface p-5">
          <h3 className="font-semibold mb-3">By platform</h3>
          <table className="w-full text-sm">
            <thead className="text-xs text-muted">
              <tr><th className="text-left font-medium pb-2">Platform</th><th className="text-right font-medium pb-2">Published</th><th className="text-right font-medium pb-2">Failed</th></tr>
            </thead>
            <tbody>
              {PLATFORM_ORDER.map((pl) => {
                const b = data.publishing.byPlatform.find((x) => x.platform === pl)
                return (
                  <tr key={pl} className="border-t border-line">
                    <td className="py-2"><span className="flex items-center gap-2"><span aria-hidden className="size-2.5 rounded-sm" style={{ background: PLATFORM_SERIES[pl] }} />{PLATFORM[pl].name}</span></td>
                    <td className="py-2 text-right tabular">{b?.published ?? 0}</td>
                    <td className={cn('py-2 text-right tabular', (b?.failed ?? 0) > 0 && 'text-rose-signal')}>{b?.failed ?? 0}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        {data.publishing.recentFailures.length > 0 && (
          <div className="rounded-2xl border border-line bg-surface p-5">
            <h3 className="font-semibold mb-2">Recent failures</h3>
            <ul className="flex flex-col gap-3 text-sm">
              {data.publishing.recentFailures.map((f) => (
                <li key={f.postId + f.platform + f.at} className="flex gap-2.5">
                  <PlatformMark platform={f.platform} className="size-5 text-[0.6rem] mt-0.5" />
                  <span className="min-w-0">
                    <Link to="/publish/calendar" className="font-medium hover:underline block truncate">{f.title}</Link>
                    <span className="text-xs text-muted block">{f.error ?? 'Failed'} · {formatRelative(f.at)}</span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </section>
  )
}

// ---- performance -------------------------------------------------------------------------------------

const dash = (v?: number) => (v == null ? '—' : compact(v))

function PerformanceSection({ data, onRefresh, refreshing }: { data: Overview; onRefresh: () => void; refreshing: boolean }) {
  const perf = data.performance
  const maxViews = Math.max(0, ...perf.posts.map((p) => p.views ?? 0))
  const missing = perf.byPlatform.filter((b) => b.posts > b.withNumbers)
  return (
    <section aria-labelledby="perf-h" className="rounded-2xl border border-line bg-surface p-5 flex flex-col gap-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="perf-h" className="font-semibold">How your posts are doing</h2>
          <p className="text-sm text-muted">
            Read from each platform every 6 hours{perf.lastCollectedAt ? `; last read ${formatRelative(perf.lastCollectedAt)}` : ''}. A dash means the platform didn't report that number.
          </p>
        </div>
        <Button size="sm" icon={<RefreshCw className="size-3.5" />} loading={refreshing} onClick={onRefresh}>Refresh numbers</Button>
      </div>

      {perf.byPlatform.length === 0 ? (
        <p className="text-sm text-muted">Nothing published in this period. <Link to="/publish/new" className="underline hover:text-ink">Make a post</Link></p>
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="w-full text-sm tabular min-w-[34rem]">
              <caption className="sr-only">Totals by platform</caption>
              <thead className="text-xs text-muted">
                <tr>
                  <th className="text-left font-medium pb-2">Platform</th>
                  <th className="text-right font-medium pb-2">Posts</th>
                  <th className="text-right font-medium pb-2">Views</th>
                  <th className="text-right font-medium pb-2">Likes</th>
                  <th className="text-right font-medium pb-2">Comments</th>
                  <th className="text-right font-medium pb-2">Shares</th>
                </tr>
              </thead>
              <tbody>
                {perf.byPlatform.map((b) => (
                  <tr key={b.platform} className="border-t border-line">
                    <td className="py-2"><span className="flex items-center gap-2"><PlatformMark platform={b.platform} className="size-5 text-[0.6rem]" />{PLATFORM[b.platform].name}</span></td>
                    <td className="py-2 text-right">{b.posts}{b.withNumbers < b.posts && <span className="text-faint"> ({b.withNumbers} with numbers)</span>}</td>
                    <td className="py-2 text-right">{dash(b.views)}</td>
                    <td className="py-2 text-right">{dash(b.likes)}</td>
                    <td className="py-2 text-right">{dash(b.comments)}</td>
                    <td className="py-2 text-right">{dash(b.shares)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {missing.length > 0 && (
            <p className="text-xs text-muted flex gap-1.5">
              <AlertTriangle className="size-3.5 shrink-0 mt-px text-marigold-600" aria-hidden />
              Accounts connected before this update need reconnecting once (Social accounts → Reconnect) to allow reading insights. Private TikTok posts have no numbers.
            </p>
          )}

          <div className="overflow-x-auto">
            <table className="w-full text-sm min-w-[40rem]">
              <caption className="text-left font-semibold mb-2">Posts, most viewed first</caption>
              <thead className="text-xs text-muted">
                <tr>
                  <th className="text-left font-medium pb-2">Post</th>
                  <th className="text-right font-medium pb-2 w-40">Views</th>
                  <th className="text-right font-medium pb-2">Likes</th>
                  <th className="text-right font-medium pb-2">Comments</th>
                  <th className="text-right font-medium pb-2">Shares</th>
                </tr>
              </thead>
              <tbody>
                {perf.posts.map((p) => (
                  <tr key={p.targetId} className="border-t border-line align-top">
                    <td className="py-2.5 pr-3">
                      <span className="flex items-start gap-2">
                        <PlatformMark platform={p.platform as SocialPlatform} className="size-5 text-[0.6rem] mt-0.5" />
                        <span className="min-w-0">
                          {p.url ? (
                            <a href={p.url} target="_blank" rel="noreferrer noopener" className="font-medium hover:underline inline-flex items-center gap-1">
                              {p.title} <ExternalLink className="size-3 text-faint" aria-hidden />
                            </a>
                          ) : <span className="font-medium">{p.title}</span>}
                          <span className="block text-xs text-faint">{p.accountName}{p.publishedAt ? ` · ${shortDay(p.publishedAt.slice(0, 10))}` : ''}</span>
                          {p.note && p.views == null && p.likes == null && <span className="block text-xs text-muted mt-0.5">{p.note}</span>}
                        </span>
                      </span>
                    </td>
                    <td className="py-2.5 text-right tabular">
                      {dash(p.views)}
                      <InlineBar value={p.views} max={maxViews} color={PLATFORM_SERIES[p.platform as SocialPlatform]} />
                    </td>
                    <td className="py-2.5 text-right tabular">{dash(p.likes)}</td>
                    <td className="py-2.5 text-right tabular">{dash(p.comments)}</td>
                    <td className="py-2.5 text-right tabular">{dash(p.shares)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  )
}

// ---- costs -----------------------------------------------------------------------------------------------

function CostsSection({ data }: { data: Overview }) {
  const days = useMemo(() => dayRange(data.from, data.to, data.timeZone), [data.from, data.to, data.timeZone])
  const c = data.costs
  return (
    <section aria-labelledby="cost-h" className="flex flex-col gap-4">
      <div>
        <h2 id="cost-h" className="font-semibold text-lg">AI costs</h2>
        <p className="text-sm text-muted">
          Each currency or credit type is shown on its own: credits and dollars can't be added up.
          {c.unknownCost > 0 && ` ${c.unknownCost} successful call${c.unknownCost === 1 ? '' : 's'} had no reported cost.`}
          {c.failed > 0 && ` ${c.failed} call${c.failed === 1 ? '' : 's'} failed.`}
        </p>
      </div>
      {c.totals.length === 0 ? (
        <p className="text-sm text-muted rounded-2xl border border-dashed border-line p-6 text-center">No costs recorded in this period.</p>
      ) : (
        <div className="grid lg:grid-cols-2 gap-4">
          {c.totals.map((t) => {
            const rows = pivot(days, c.daily.filter((d) => d.unit === t.unit).map((d) => ({ date: d.date, key: 'amount' as const, value: d.amount })), ['amount'])
            return (
              <div key={t.unit} className="rounded-2xl border border-line bg-surface p-5 min-w-0">
                <p className="text-sm text-muted">Spent in {UNIT_NAME[t.unit] ?? t.unit.toLowerCase()}</p>
                <p className="text-2xl font-semibold tabular mb-2">{formatMoney(t.amount, t.unit)}</p>
                <ColumnChart
                  rows={rows}
                  series={[{ key: 'amount', label: UNIT_NAME[t.unit] ?? t.unit, color: 'var(--series-1)' }]}
                  label={`Spend per day in ${UNIT_NAME[t.unit] ?? t.unit}`}
                  format={(v) => (t.unit === 'USD' ? `$${compact(v)}` : compact(v))}
                  height={160}
                />
              </div>
            )
          })}
        </div>
      )}
      {c.byProvider.length > 0 && (
        <div className="rounded-2xl border border-line bg-surface p-5 overflow-x-auto">
          <table className="w-full text-sm tabular min-w-[30rem]">
            <caption className="text-left font-semibold mb-2">By provider</caption>
            <thead className="text-xs text-muted">
              <tr>
                <th className="text-left font-medium pb-2">Provider</th>
                <th className="text-left font-medium pb-2">Used for</th>
                <th className="text-right font-medium pb-2">Calls</th>
                <th className="text-right font-medium pb-2">Cost</th>
              </tr>
            </thead>
            <tbody>
              {c.byProvider.map((p) => (
                <tr key={p.provider + p.capability + (p.unit ?? '')} className="border-t border-line">
                  <td className="py-2">{PROVIDER_NAME[p.provider] ?? p.provider}</td>
                  <td className="py-2 text-muted">{p.capability.toLowerCase()}</td>
                  <td className="py-2 text-right">{p.calls}</td>
                  <td className="py-2 text-right">{p.unit ? formatMoney(p.amount, p.unit) : <span className="text-faint">not reported</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
