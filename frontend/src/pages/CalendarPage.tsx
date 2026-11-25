import { ChevronLeft, ChevronRight, Plus } from 'lucide-react'
import { useMemo, useState, type DragEvent } from 'react'
import { Link } from 'react-router-dom'
import { PlatformMark, PostDialog, StatusBadge } from '../components/publish'
import { Button, ErrorNotice } from '../components/ui'
import { cn } from '../lib/cn'
import {
  addDays,
  canMove,
  dayKey,
  formatTime,
  groupByDay,
  monthGrid,
  moveToDay,
  postDate,
  startOfDay,
  weekDays,
  type Post,
} from '../lib/publish'
import { useCalendar, usePosts, useReschedule } from '../lib/publishQueries'
import { toast } from '../stores/ui'

type View = 'month' | 'week' | 'day'

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

/** Left edge colour of a calendar entry, by status. */
const EDGE: Record<string, string> = {
  DRAFT: 'border-l-line',
  SCHEDULED: 'border-l-marigold-500',
  PUBLISHING: 'border-l-marigold-500',
  PUBLISHED: 'border-l-peacock-500',
  PARTIAL: 'border-l-rose-signal',
  FAILED: 'border-l-rose-signal',
  CANCELLED: 'border-l-line',
}

const DOT: Record<string, string> = {
  DRAFT: 'bg-line', SCHEDULED: 'bg-marigold-500', PUBLISHING: 'bg-marigold-500', PUBLISHED: 'bg-peacock-500',
  PARTIAL: 'bg-rose-signal', FAILED: 'bg-rose-signal', CANCELLED: 'bg-line',
}

export function CalendarPage() {
  const [view, setView] = useState<View>('month')
  const [cursor, setCursor] = useState(() => startOfDay(new Date()))
  const [open, setOpen] = useState<string>()
  const [dragOver, setDragOver] = useState<string>()

  const days = useMemo(() => {
    if (view === 'month') return monthGrid(cursor.getFullYear(), cursor.getMonth())
    if (view === 'week') return weekDays(cursor)
    return [startOfDay(cursor)]
  }, [view, cursor])
  const from = days[0]
  const to = addDays(days[days.length - 1], 1)

  const calendar = useCalendar(from, to)
  const drafts = usePosts('DRAFT')
  const reschedule = useReschedule()
  const byDay = useMemo(() => groupByDay(calendar.data ?? []), [calendar.data])
  const undated = (drafts.data ?? []).filter((p) => !p.scheduledAt)
  const all = [...(calendar.data ?? []), ...(drafts.data ?? [])]
  const current = all.find((p) => p.id === open)
  const todayKey = dayKey(new Date())

  const step = (dir: 1 | -1) => {
    if (view === 'month') setCursor(new Date(cursor.getFullYear(), cursor.getMonth() + dir, 1))
    else setCursor(addDays(cursor, dir * (view === 'week' ? 7 : 1)))
  }

  const title =
    view === 'month'
      ? cursor.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
      : view === 'week'
        ? `${days[0].toLocaleDateString(undefined, { day: 'numeric', month: 'short' })} – ${days[6].toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })}`
        : cursor.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })

  const drop = (day: Date) => (e: DragEvent) => {
    e.preventDefault()
    setDragOver(undefined)
    const id = e.dataTransfer.getData('text/x-leenax-post')
    const post = all.find((p) => p.id === id)
    if (!post) return
    const at = moveToDay(post.scheduledAt, day)
    if (post.status === 'SCHEDULED' && at.getTime() < Date.now()) {
      toast.error('That time has passed. Drop it on today or a later day.')
      return
    }
    reschedule.mutate(
      { id, scheduledAt: at.toISOString() },
      { onSuccess: () => toast.success(`Moved to ${at.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })}`), onError: (err) => toast.error(err.message) },
    )
  }

  const dropProps = (day: Date) => ({
    onDragOver: (e: DragEvent) => {
      if (e.dataTransfer.types.includes('text/x-leenax-post')) {
        e.preventDefault()
        setDragOver(dayKey(day))
      }
    },
    onDragLeave: () => setDragOver(undefined),
    onDrop: drop(day),
  })

  return (
    <>
      <header className="flex flex-wrap items-end justify-between gap-4 mb-6">
        <div>
          <h1 className="text-3xl sm:text-[2.15rem] font-semibold">Calendar</h1>
          <p className="text-muted mt-1">Drag a draft or scheduled post to another day to move it. Times are in your time zone.</p>
        </div>
        <Link to="/publish/new" className="inline-flex items-center gap-2 h-10 px-4 rounded-lg bg-marigold-500 text-[#1e2240] font-semibold hover:bg-marigold-400">
          <Plus className="size-4" aria-hidden /> New post
        </Link>
      </header>

      <div className="flex flex-wrap items-center gap-3 mb-4">
        <div className="flex items-center gap-1">
          <Button size="sm" variant="ghost" aria-label="Previous" onClick={() => step(-1)} icon={<ChevronLeft className="size-4" />} />
          <Button size="sm" variant="ghost" aria-label="Next" onClick={() => step(1)} icon={<ChevronRight className="size-4" />} />
          <Button size="sm" onClick={() => setCursor(startOfDay(new Date()))}>Today</Button>
        </div>
        <h2 className="text-xl font-semibold flex-1 min-w-48" aria-live="polite">{title}</h2>
        <div role="radiogroup" aria-label="Calendar view" className="inline-flex rounded-lg border border-line p-0.5 bg-surface">
          {(['month', 'week', 'day'] as View[]).map((v) => (
            <button key={v} role="radio" aria-checked={view === v} onClick={() => setView(v)} className={cn('px-3 h-8 rounded-md text-sm capitalize', view === v ? 'bg-surface-2 text-ink font-medium' : 'text-muted hover:text-ink')}>
              {v}
            </button>
          ))}
        </div>
      </div>

      {calendar.error && <div className="mb-4"><ErrorNotice error={calendar.error} onRetry={() => calendar.refetch()} /></div>}

      <div className="grid xl:grid-cols-[minmax(0,1fr)_16rem] gap-6 items-start">
        <div className={cn('transition-opacity', calendar.isFetching && !calendar.data && 'opacity-60')}>
          {view === 'month' && (
            <div className="rounded-2xl border border-line overflow-hidden bg-surface">
              <div className="grid grid-cols-7 border-b border-line bg-surface-2/50">
                {WEEKDAYS.map((d) => <div key={d} className="px-2 py-2 text-xs font-medium text-muted">{d}</div>)}
              </div>
              <div className="grid grid-cols-7 auto-rows-[minmax(4rem,auto)] sm:auto-rows-[minmax(6.5rem,auto)]">
                {days.map((d, i) => {
                  const key = dayKey(d)
                  const inMonth = d.getMonth() === cursor.getMonth()
                  const posts = byDay.get(key) ?? []
                  return (
                    <div
                      key={key}
                      {...dropProps(d)}
                      className={cn(
                        'p-1.5 flex flex-col gap-1 min-w-0 border-line',
                        i % 7 !== 6 && 'border-r',
                        i < 35 && 'border-b',
                        !inMonth && 'bg-surface-2/40',
                        dragOver === key && 'bg-marigold-500/10 ring-2 ring-inset ring-marigold-500',
                      )}
                    >
                      <button
                        type="button"
                        onClick={() => { setCursor(d); setView('day') }}
                        aria-label={d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' })}
                        className={cn('self-start text-xs tabular rounded-full size-7 sm:size-6 grid place-items-center hover:bg-surface-2', key === todayKey ? 'bg-marigold-500 text-[#1e2240] font-semibold hover:bg-marigold-400' : inMonth ? 'text-ink' : 'text-faint')}
                      >
                        {d.getDate()}
                      </button>
                      <div className="hidden sm:flex flex-col gap-1 min-w-0">
                        {posts.slice(0, 3).map((p) => <Entry key={p.id} post={p} onOpen={setOpen} compact />)}
                        {posts.length > 3 && (
                          <button type="button" className="text-xs text-muted hover:text-ink text-left px-1" onClick={() => { setCursor(d); setView('day') }}>
                            +{posts.length - 3} more
                          </button>
                        )}
                      </div>
                      {/* Phones: a dot per post; tapping the date opens the day. */}
                      {posts.length > 0 && (
                        <span className="sm:hidden flex flex-wrap gap-1 px-1" aria-label={`${posts.length} post${posts.length > 1 ? 's' : ''}`}>
                          {posts.map((p) => <span key={p.id} className={cn('size-2 rounded-full', DOT[p.status])} />)}
                        </span>
                      )}
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {view === 'week' && (
            <div className="grid grid-cols-1 sm:grid-cols-7 gap-2">
              {days.map((d, i) => {
                const key = dayKey(d)
                const posts = byDay.get(key) ?? []
                return (
                  <section key={key} {...dropProps(d)} aria-label={d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' })}
                    className={cn('rounded-xl border border-line bg-surface p-2 min-h-48 flex flex-col gap-1.5', dragOver === key && 'ring-2 ring-marigold-500 bg-marigold-500/8')}>
                    <h3 className={cn('text-xs font-medium flex items-baseline gap-1.5 px-1', key === todayKey ? 'text-marigold-700 dark:text-marigold-300' : 'text-muted')}>
                      {WEEKDAYS[i]} <span className="text-base text-ink tabular">{d.getDate()}</span>
                    </h3>
                    {posts.map((p) => <Entry key={p.id} post={p} onOpen={setOpen} />)}
                  </section>
                )
              })}
            </div>
          )}

          {view === 'day' && (
            <div {...dropProps(days[0])} className={cn('rounded-2xl border border-line bg-surface', dragOver && 'ring-2 ring-marigold-500')}>
              {(byDay.get(dayKey(days[0])) ?? []).length === 0 ? (
                <p className="p-8 text-center text-muted">Nothing on this day. <Link className="underline hover:text-ink" to="/publish/new">Plan a post</Link></p>
              ) : (
                <ol className="divide-y divide-line">
                  {(byDay.get(dayKey(days[0])) ?? []).map((p) => (
                    <li key={p.id}>
                      <button type="button" onClick={() => setOpen(p.id)} className="w-full text-left px-5 py-4 flex items-center gap-4 hover:bg-surface-2/60">
                        <time className="w-16 shrink-0 text-sm tabular text-muted" dateTime={postDate(p)}>{formatTime(postDate(p)!)}</time>
                        <span className="min-w-0 flex-1">
                          <span className="block font-medium truncate">{p.title}</span>
                          <span className="flex gap-1 mt-1">{p.targets.map((t) => <PlatformMark key={t.id} platform={t.platform} className="size-5 text-[0.6rem]" />)}</span>
                        </span>
                        <StatusBadge status={p.status} />
                      </button>
                    </li>
                  ))}
                </ol>
              )}
            </div>
          )}
        </div>

        <aside aria-labelledby="undated-heading" className="flex flex-col gap-3">
          <h2 id="undated-heading" className="font-semibold">Drafts without a date</h2>
          {undated.length === 0 ? (
            <p className="text-sm text-muted">None. Drafts you save without a date wait here; drag one onto a day to plan it.</p>
          ) : (
            <div className="flex flex-col gap-1.5">{undated.map((p) => <Entry key={p.id} post={p} onOpen={setOpen} />)}</div>
          )}
          <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-2 gap-y-1.5 text-xs text-muted items-center">
            <dt className="h-3 w-1 rounded-full bg-marigold-500" /><dd>Scheduled or publishing</dd>
            <dt className="h-3 w-1 rounded-full bg-peacock-500" /><dd>Published</dd>
            <dt className="h-3 w-1 rounded-full bg-rose-signal" /><dd>Failed on one or more accounts</dd>
            <dt className="h-3 w-1 rounded-full bg-line" /><dd>Draft or cancelled</dd>
          </dl>
        </aside>
      </div>

      <PostDialog post={current} onClose={() => setOpen(undefined)} />
    </>
  )
}

function Entry({ post: p, onOpen, compact }: { post: Post; onOpen: (id: string) => void; compact?: boolean }) {
  const movable = canMove(p)
  const at = postDate(p)
  return (
    <button
      type="button"
      draggable={movable}
      onDragStart={(e) => {
        e.dataTransfer.setData('text/x-leenax-post', p.id)
        e.dataTransfer.effectAllowed = 'move'
      }}
      onClick={() => onOpen(p.id)}
      title={`${p.title}${movable ? ' (drag to move)' : ''}`}
      className={cn(
        'w-full text-left rounded-md border border-line border-l-[3px] bg-surface hover:bg-surface-2 px-1.5 py-1 min-w-0',
        EDGE[p.status],
        movable && 'cursor-grab active:cursor-grabbing',
        p.status === 'CANCELLED' && 'opacity-60 line-through',
      )}
    >
      <span className="flex items-center gap-1 text-[0.7rem] text-muted tabular">
        {at && <span>{formatTime(at)}</span>}
        {p.status === 'DRAFT' && <span className="italic">draft</span>}
      </span>
      <span className={cn('block truncate font-medium', compact ? 'text-xs' : 'text-sm')}>{p.title}</span>
      {!compact && (
        <span className="flex gap-1 mt-1">{p.targets.map((t) => <PlatformMark key={t.id} platform={t.platform} className="size-4 text-[0.55rem] rounded" />)}</span>
      )}
    </button>
  )
}
