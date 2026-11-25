import { ChevronLeft, ExternalLink, History, Lightbulb, RefreshCw } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ColumnChart, InlineBar, RetentionChart } from '../components/charts'
import { Button, EmptyState, ErrorNotice, Field, Input, PageHeader, Select, Spinner } from '../components/ui'
import { compact } from '../lib/analytics'
import { useProject } from '../lib/queries'
import {
  clock,
  dailyViews,
  daysBefore,
  percent,
  RECOMMENDATION_LABEL,
  trafficRows,
  useDashboard,
  usePublishOverview,
  useReach,
  useRefreshAnalytics,
  yesterday,
  type Dashboard,
  type Recommendation,
  type VideoRow,
} from '../lib/youtube'
import { toast } from '../stores/ui'

/**
 * /projects/:id/youtube/analytics — this project's published videos on one channel: YouTube
 * Analytics numbers, impressions and click-through rate from the daily reach reports, kept as
 * snapshots, with recommendations that show the rule and numbers behind them.
 */
export function YouTubeAnalyticsPage() {
  const { id = '' } = useParams()
  const project = useProject(id)
  const overview = usePublishOverview(id)
  const [accountIdState, setAccountId] = useState<string>()
  const [to, setTo] = useState(yesterday())
  const [from, setFrom] = useState(daysBefore(yesterday(), 27))
  const [snapshotAt, setSnapshotAt] = useState<string>()
  const channels = overview.data?.channels ?? []
  const usedChannels = new Set(overview.data?.publications.map((p) => p.accountId))
  const accountId = accountIdState ?? channels.find((c) => usedChannels.has(c.id))?.id ?? channels[0]?.id
  const dash = useDashboard(id, accountId, from, to, snapshotAt)
  const refresh = useRefreshAnalytics(id)
  const reach = useReach(id)

  if (project.isPending || overview.isPending) return <Spinner label="Loading YouTube analytics" />
  if (project.error || overview.error) return <ErrorNotice error={project.error ?? overview.error} onRetry={() => overview.refetch()} />

  const preset = (days: number) => {
    const end = yesterday()
    setTo(end)
    setFrom(daysBefore(end, days - 1))
    setSnapshotAt(undefined)
  }
  const d = dash.data
  return (
    <>
      <Link to={`/projects/${id}/youtube`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Publish to YouTube
      </Link>
      <PageHeader
        title="YouTube analytics"
        description="This project's published videos, from YouTube's own reports for your channel. Other channels' analytics are private, so nothing here is about them."
      />
      {channels.length === 0 ? (
        <EmptyState title="No YouTube channel connected" body="Connect a channel and publish a video to see its numbers here."
          action={<Link className="underline underline-offset-2" to="/publish/accounts">Connect a channel</Link>} />
      ) : (
        <>
          <div className="flex flex-wrap items-end gap-3 mb-4">
            <Field label="Channel">
              {(fid) => (
                <Select id={fid} value={accountId ?? ''} onChange={(e) => { setAccountId(e.target.value); setSnapshotAt(undefined) }} className="w-56">
                  {channels.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </Select>
              )}
            </Field>
            <Field label="From">
              {(fid) => <Input id={fid} type="date" value={from} max={to} onChange={(e) => { setFrom(e.target.value); setSnapshotAt(undefined) }} className="w-40" />}
            </Field>
            <Field label="To">
              {(fid) => <Input id={fid} type="date" value={to} min={from} max={yesterday()} onChange={(e) => { setTo(e.target.value); setSnapshotAt(undefined) }} className="w-40" />}
            </Field>
            <div className="flex gap-1" role="group" aria-label="Quick periods">
              {[7, 28, 90].map((n) => (
                <Button key={n} size="sm" variant="ghost" onClick={() => preset(n)}>{n} days</Button>
              ))}
            </div>
            <Button variant="primary" icon={<RefreshCw className="size-4" />} loading={refresh.isPending} disabled={!accountId || (d ? !d.canRefresh : false)}
              title={d && !d.canRefresh ? 'Reconnect the channel to allow YouTube Analytics' : undefined}
              onClick={() => accountId && refresh.mutate({ accountId, from, to }, {
                onSuccess: () => { setSnapshotAt(undefined); toast.success('Fetched from YouTube and kept as a snapshot') },
                onError: (e) => toast.error(e.message),
              })}>
              Refresh from YouTube
            </Button>
          </div>
          {d && !d.canRefresh && (
            <p role="note" className="text-sm rounded-xl border border-marigold-500/50 px-4 py-3 mb-4">
              This channel was connected before analytics were supported. <Link to="/publish/accounts" className="underline underline-offset-2">Reconnect it</Link> and allow YouTube Analytics.
            </p>
          )}
          {dash.isPending && accountId && <Spinner label="Loading the snapshot" />}
          {dash.error && <ErrorNotice error={dash.error} onRetry={() => dash.refetch()} />}
          {d && (
            <DashboardView
              d={d}
              snapshotAt={snapshotAt}
              onSnapshot={(at) => setSnapshotAt(at)}
              reachBusy={reach.isPending}
              onReach={(action) => accountId && reach.mutate({ accountId, action }, {
                onSuccess: () => toast.success(action === 'start' ? 'Reach reports turned on. YouTube makes the first file in about two days.' : 'Reach reports imported'),
                onError: (e) => toast.error(e.message),
              })}
            />
          )}
        </>
      )}
    </>
  )
}

function DashboardView({ d, snapshotAt, onSnapshot, reachBusy, onReach }: {
  d: Dashboard; snapshotAt?: string; onSnapshot: (at?: string) => void; reachBusy: boolean; onReach: (a: 'start' | 'import') => void
}) {
  const withRetention = d.videos.filter((v) => (d.retention[v.videoId]?.length ?? 0) > 0)
  const [retentionVideo, setRetentionVideo] = useState<string>()
  const rv = withRetention.find((v) => v.videoId === retentionVideo) ?? withRetention[0]
  const t = d.totals
  const traffic = trafficRows(d.traffic)
  const maxTraffic = Math.max(0, ...traffic.map((x) => x.views))
  return (
    <div className="flex flex-col gap-8">
      <section aria-label="Snapshot" className="flex flex-wrap items-center gap-3 text-sm">
        <History className="size-4 text-muted" aria-hidden />
        {d.fetchedAt ? (
          <span>
            {snapshotAt ? 'Viewing an earlier snapshot' : 'Snapshot'} of {d.from} to {d.to}, taken {new Date(d.fetchedAt).toLocaleString()}{d.fetchedByName ? ` by ${d.fetchedByName}` : ''}.
          </span>
        ) : (
          <span className="text-muted">No snapshot of {d.from} to {d.to} yet. Refresh to fetch it from YouTube.</span>
        )}
        {d.history.length > 0 && (
          <Select aria-label="Earlier snapshots" value={snapshotAt ?? ''} onChange={(e) => onSnapshot(e.target.value || undefined)} className="w-auto">
            <option value="">The newest of the chosen period</option>
            {d.history.map((h) => (
              <option key={h.fetchedAt} value={h.fetchedAt}>{h.startDate} – {h.endDate}, taken {new Date(h.fetchedAt).toLocaleString()}</option>
            ))}
          </Select>
        )}
      </section>

      {d.notes.length > 0 && (
        <ul className="text-sm text-muted flex flex-col gap-0.5 -mt-4">{d.notes.map((n) => <li key={n}>· {n}</li>)}</ul>
      )}

      <ReachPanel d={d} busy={reachBusy} onReach={onReach} />

      {d.fetchedAt && (
        <>
          <section aria-label="Totals" className="grid grid-cols-2 lg:grid-cols-4 gap-4">
            <Tile label="Views" value={num(t.views)} />
            <Tile label="Watch time" value={t.minutesWatched == null ? '—' : `${compact(Math.round(t.minutesWatched / 60))} h`} sub={t.minutesWatched == null ? undefined : `${compact(Math.round(t.minutesWatched))} minutes`} />
            <Tile label="Average view duration" value={clock(t.averageViewDuration)} />
            <Tile label="Subscribers gained" value={num(t.subscribersGained)} sub="From these videos" />
            <Tile label="Impressions" value={num(t.impressions)} sub={t.impressions == null ? 'Needs reach reports' : 'Thumbnail impressions (reach report)'} />
            <Tile label="Click-through rate" value={percent(t.ctr)} sub={t.ctr == null ? 'Needs reach reports' : 'Weighted by impressions'} />
            <Tile label="Likes · comments" value={`${num(t.likes)} · ${num(t.comments)}`} />
            <Tile label="Shares" value={num(t.shares)} />
          </section>

          <Section title="Views per day">
            <ColumnChart rows={dailyViews(d.daily)} series={[{ key: 'views', label: 'Views', color: 'var(--series-1)' }]} label="Views per day of this project's videos" />
          </Section>

          <Section title="Videos">
            <VideoTable rows={d.videos} />
          </Section>

          <Section title="Audience retention">
            {withRetention.length === 0 ? <p className="text-sm text-muted">YouTube reported no retention for these videos in this period (it needs enough views).</p> : (
              <>
                <Select aria-label="Video" value={rv?.videoId ?? ''} onChange={(e) => setRetentionVideo(e.target.value)} className="w-auto max-w-full mb-3">
                  {withRetention.map((v) => <option key={v.videoId} value={v.videoId}>{v.title}</option>)}
                </Select>
                {rv && (
                  <RetentionChart points={d.retention[rv.videoId]} scenes={d.scenes[rv.videoId] ?? []} duration={rv.durationSeconds}
                    label={`Audience retention of ${rv.title}`} />
                )}
                <p className="text-xs text-muted mt-2">A ratio of 1 means everyone who started is still watching; above 1, people went back to watch a part again. Shaded bands are the video's scenes.</p>
              </>
            )}
          </Section>

          <div className="grid lg:grid-cols-2 gap-8">
            <Section title="Traffic sources">
              {traffic.length === 0 ? <p className="text-sm text-muted">No views in this period.</p> : (
                <table className="w-full text-sm tabular">
                  <caption className="sr-only">Views by traffic source</caption>
                  <thead className="text-xs text-muted"><tr><th scope="col" className="text-left font-medium py-1">Source</th><th scope="col" className="text-right font-medium py-1">Views</th><th scope="col" className="text-right font-medium py-1">Share</th></tr></thead>
                  <tbody>
                    {traffic.map((r) => (
                      <tr key={r.key} className="border-t border-line">
                        <th scope="row" className="text-left font-normal py-1.5 pr-3">{r.label}<InlineBar value={r.views} max={maxTraffic} color="var(--series-1)" /></th>
                        <td className="text-right py-1.5">{compact(r.views)}</td>
                        <td className="text-right py-1.5 text-muted">{percent(r.share)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Section>
            <Section title="YouTube search terms">
              {d.searchTerms.length === 0 ? <p className="text-sm text-muted">No search views in this period.</p> : (
                <table className="w-full text-sm tabular">
                  <caption className="sr-only">Search terms that brought views</caption>
                  <thead className="text-xs text-muted"><tr><th scope="col" className="text-left font-medium py-1">Search</th><th scope="col" className="text-right font-medium py-1">Views</th></tr></thead>
                  <tbody>
                    {d.searchTerms.map((r) => (
                      <tr key={String(r.insightTrafficSourceDetail)} className="border-t border-line">
                        <th scope="row" className="text-left font-normal py-1.5 pr-3 break-words">{String(r.insightTrafficSourceDetail)}</th>
                        <td className="text-right py-1.5">{typeof r.views === 'number' ? compact(r.views) : '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Section>
          </div>

          <Section title="Recommendations">
            {d.recommendations.length === 0 ? (
              <p className="text-sm text-muted">Not enough data for recommendations yet. Each rule needs a minimum number of views or impressions before it says anything.</p>
            ) : (
              <ul className="flex flex-col gap-3">{d.recommendations.map((r, i) => <RecommendationCard key={i} r={r} videos={d.videos} />)}</ul>
            )}
          </Section>
        </>
      )}
    </div>
  )
}

function ReachPanel({ d, busy, onReach }: { d: Dashboard; busy: boolean; onReach: (a: 'start' | 'import') => void }) {
  const r = d.reach
  return (
    <section aria-label="Reach reports" className="rounded-xl border border-line px-4 py-3 flex flex-wrap items-center gap-3 text-sm">
      <div className="flex-1 min-w-60">
        <p className="font-medium">Impressions and click-through rate</p>
        <p className="text-xs text-muted">
          {!r.started
            ? 'They come from YouTube’s daily reach reports, which have to be turned on for the channel first.'
            : r.lastDay
              ? `Reach reports are imported every day. Latest day: ${r.lastDay}.`
              : 'Reach reports are on. YouTube makes the first file about two days after they are turned on.'}
          {r.lastCheckedAt ? ` Last checked ${new Date(r.lastCheckedAt).toLocaleString()}.` : ''}
        </p>
        {r.lastError && <p className="text-xs text-rose-signal mt-0.5">{r.lastError}</p>}
      </div>
      {!r.started && d.canManageReach && <Button size="sm" loading={busy} onClick={() => onReach('start')} disabled={!d.canRefresh}>Turn on reach reports</Button>}
      {!r.started && !d.canManageReach && <span className="text-xs text-muted">The project owner or an administrator can turn them on.</span>}
      {r.started && <Button size="sm" variant="ghost" loading={busy} onClick={() => onReach('import')}>Import now</Button>}
    </section>
  )
}

function VideoTable({ rows }: { rows: VideoRow[] }) {
  if (rows.length === 0) return <p className="text-sm text-muted">No published videos on this channel.</p>
  return (
    <div className="overflow-x-auto rounded-lg border border-line">
      <table className="w-full text-sm tabular min-w-[56rem]">
        <caption className="sr-only">Numbers per video</caption>
        <thead className="bg-surface-2/60 text-xs text-muted">
          <tr>
            {['Video', 'Views', 'Watch time', 'Avg. duration', 'Avg. % viewed', 'Impressions', 'CTR', 'Likes', 'Comments', 'Shares', 'Subscribers'].map((h, i) => (
              <th key={h} scope="col" className={i === 0 ? 'text-left font-medium px-3 py-2' : 'text-right font-medium px-3 py-2'}>{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((v) => (
            <tr key={v.videoId} className="border-t border-line">
              <th scope="row" className="text-left font-normal px-3 py-2 max-w-72">
                <a href={v.url} target="_blank" rel="noreferrer noopener" className="inline-flex items-start gap-1 hover:underline break-words">{v.title}<ExternalLink className="size-3 shrink-0 mt-1" aria-hidden /></a>
                {v.topicTitle && <span className="block text-xs text-muted">{v.topicTitle}</span>}
              </th>
              <td className="text-right px-3 py-2">{num(v.views)}</td>
              <td className="text-right px-3 py-2">{v.minutesWatched == null ? '—' : `${compact(Math.round(v.minutesWatched))} min`}</td>
              <td className="text-right px-3 py-2">{clock(v.averageViewDuration)}</td>
              <td className="text-right px-3 py-2">{v.averageViewPercentage == null ? '—' : `${v.averageViewPercentage.toFixed(0)}%`}</td>
              <td className="text-right px-3 py-2">{num(v.impressions)}</td>
              <td className="text-right px-3 py-2">{percent(v.ctr)}</td>
              <td className="text-right px-3 py-2">{num(v.likes)}</td>
              <td className="text-right px-3 py-2">{num(v.comments)}</td>
              <td className="text-right px-3 py-2">{num(v.shares)}</td>
              <td className="text-right px-3 py-2">{num(v.subscribersGained)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function RecommendationCard({ r, videos }: { r: Recommendation; videos: VideoRow[] }) {
  const video = r.videoId ? videos.find((v) => v.videoId === r.videoId) : undefined
  return (
    <li className="rounded-xl border border-line p-4 flex flex-col gap-2">
      <p className="text-xs text-muted flex items-center gap-1.5"><Lightbulb className="size-3.5" aria-hidden />{RECOMMENDATION_LABEL[r.kind]}{video ? ` · ${video.title}` : ''}</p>
      <p className="font-medium">{r.title}</p>
      <p className="text-sm">{r.detail}</p>
      <ul className="text-xs text-muted flex flex-col gap-0.5">
        {r.evidence.map((e) => <li key={e.metric + e.value}><span className="text-ink">{e.metric}:</span> {e.value} <span className="text-faint">({e.source})</span></li>)}
      </ul>
      <p className="text-xs text-faint">Rule: {r.rule}</p>
    </li>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="flex flex-col gap-3 min-w-0">
      <h2 className="font-semibold">{title}</h2>
      {children}
    </section>
  )
}

function Tile({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <div className="rounded-2xl border border-line bg-surface p-5 min-w-0">
      <p className="text-sm text-muted">{label}</p>
      <p className="text-2xl font-semibold tabular mt-1 break-words">{value}</p>
      {sub && <p className="text-xs text-faint mt-1">{sub}</p>}
    </div>
  )
}

const num = (n?: number | null) => (n == null ? '—' : compact(n))
