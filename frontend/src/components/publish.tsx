import { AlertTriangle, ExternalLink, Pencil, RotateCcw, Trash2, X } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { cn } from '../lib/cn'
import {
  canEdit,
  formatWhen,
  isPublicTarget,
  PLATFORM,
  POST_LABEL,
  postTone,
  TARGET_LABEL,
  type Post,
  type PostStatus,
  type PostTarget,
  type SocialPlatform,
  type TargetStatus,
} from '../lib/publish'
import { usePostAction } from '../lib/publishQueries'
import { toast } from '../stores/ui'
import { MediaThumb } from './media'
import { Button, Dialog } from './ui'

/** Two-letter platform mark. Platform names, not logos. */
export function PlatformMark({ platform, className }: { platform: SocialPlatform; className?: string }) {
  return (
    <span
      title={PLATFORM[platform].name}
      className={cn(
        'inline-grid place-items-center rounded-md bg-surface-2 text-[0.65rem] font-semibold tracking-wide text-muted size-6 shrink-0',
        className,
      )}
    >
      {PLATFORM[platform].short}
    </span>
  )
}

const TONE = {
  neutral: 'bg-surface-2 text-muted',
  progress: 'bg-marigold-500/15 text-marigold-700 dark:text-marigold-300',
  done: 'bg-peacock-500/15 text-peacock-600 dark:text-peacock-400',
  muted: 'bg-surface-2 text-faint',
  error: 'bg-rose-signal/12 text-rose-signal',
}

export function StatusBadge({ status, kind = 'post' }: { status: PostStatus | TargetStatus; kind?: 'post' | 'target' }) {
  const label = kind === 'post' ? POST_LABEL[status as PostStatus] : TARGET_LABEL[status as TargetStatus]
  return <span className={cn('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium whitespace-nowrap', TONE[postTone(status)])}>{label}</span>
}

/** One account's line in a post: status, progress, link, problems, retry. */
export function TargetRow({ post, target }: { post: Post; target: PostTarget }) {
  const action = usePostAction()
  const [confirming, setConfirming] = useState(false)
  const retry = (confirmPublic: boolean) =>
    action.mutate(
      { id: post.id, action: 'retry', targetId: target.id, confirmPublic },
      {
        onSuccess: () => {
          setConfirming(false)
          toast.success(`Publishing to ${target.accountName} again`)
        },
        onError: (e) => toast.error(e.message),
      },
    )
  const running = target.status === 'UPLOADING' || target.status === 'PROCESSING'
  return (
    <li className="py-3 flex flex-col gap-1.5">
      <div className="flex items-center gap-2.5">
        <PlatformMark platform={target.platform} />
        <span className="min-w-0 flex-1 truncate text-sm font-medium">{target.accountName}</span>
        <StatusBadge status={target.status} kind="target" />
      </div>
      {running && (
        <div className="pl-8.5 flex items-center gap-3">
          <div className="h-1.5 flex-1 rounded-full bg-surface-2 overflow-hidden" role="progressbar" aria-valuenow={Math.round(target.progress * 100)} aria-valuemin={0} aria-valuemax={100} aria-label={`${target.accountName} progress`}>
            <div className="h-full bg-marigold-500 transition-[width]" style={{ width: `${Math.max(4, target.progress * 100)}%` }} />
          </div>
        </div>
      )}
      {target.step && target.status !== 'PUBLISHED' && <p className="pl-8.5 text-xs text-muted">{target.step}</p>}
      {target.errorMessage && (
        <p className={cn('pl-8.5 text-xs', target.status === 'FAILED' ? 'text-rose-signal' : 'text-muted')}>
          {target.status === 'FAILED' ? target.errorMessage : `Last try: ${target.errorMessage}`}
          {target.nextAttemptAt && target.status !== 'FAILED' && <> · next try {formatWhen(target.nextAttemptAt)}</>}
        </p>
      )}
      {target.warning && (
        <p className="pl-8.5 text-xs text-marigold-700 dark:text-marigold-300 flex gap-1.5">
          <AlertTriangle className="size-3.5 shrink-0 mt-px" aria-hidden />
          <span>{target.warning}</span>
        </p>
      )}
      <div className="pl-8.5 flex flex-wrap gap-3 text-xs">
        {target.remoteUrl && (
          <a href={target.remoteUrl} target="_blank" rel="noreferrer noopener" className="inline-flex items-center gap-1 text-peacock-600 dark:text-peacock-400 hover:underline">
            Open on {PLATFORM[target.platform].name} <ExternalLink className="size-3" aria-hidden />
          </a>
        )}
        {(target.status === 'FAILED' || target.status === 'CANCELLED') && target.accountId && (
          confirming ? (
            <span className="inline-flex flex-wrap items-center gap-2">
              <span className="text-ink">Anyone will be able to see this post.</span>
              <button type="button" className="font-medium text-peacock-600 dark:text-peacock-400 hover:underline" disabled={action.isPending} onClick={() => retry(true)}>
                Post publicly again
              </button>
              <button type="button" className="text-muted hover:text-ink" onClick={() => setConfirming(false)}>Keep it stopped</button>
            </span>
          ) : (
            <button
              type="button"
              className="inline-flex items-center gap-1 text-muted hover:text-ink"
              disabled={action.isPending}
              onClick={() => (isPublicTarget(target.platform, target.settings) ? setConfirming(true) : retry(false))}
            >
              <RotateCcw className="size-3" aria-hidden /> Try again
            </button>
          )
        )}
      </div>
    </li>
  )
}

/** Everything about one post, with its actions. Used by the calendar and the publisher. */
export function PostDialog({ post, onClose }: { post?: Post; onClose: () => void }) {
  const action = usePostAction()
  if (!post) return <Dialog open={false} title="" onClose={onClose}>{null}</Dialog>
  const cancellable = post.targets.some((t) => t.status === 'SCHEDULED' || t.status === 'DRAFT')
  const deletable = !post.targets.some((t) => t.status === 'UPLOADING' || t.status === 'PROCESSING')
  const when = post.publishedAt ?? post.scheduledAt
  return (
    <Dialog
      open
      title={post.title}
      onClose={onClose}
      footer={
        <>
          {deletable && (
            <Button
              variant="ghost"
              icon={<Trash2 className="size-4" />}
              loading={action.isPending && action.variables?.action === 'delete'}
              onClick={() => {
                const published = post.targets.some((t) => t.status === 'PUBLISHED')
                if (!window.confirm(published ? 'Remove this post from the studio? It stays on the platforms where it was published.' : 'Delete this post?')) return
                action.mutate({ id: post.id, action: 'delete' }, { onSuccess: () => { toast.success('Post deleted'); onClose() }, onError: (e) => toast.error(e.message) })
              }}
            >
              Delete
            </Button>
          )}
          {cancellable && (
            <Button
              icon={<X className="size-4" />}
              loading={action.isPending && action.variables?.action === 'cancel'}
              onClick={() => action.mutate({ id: post.id, action: 'cancel' }, { onSuccess: () => toast.success('Post cancelled'), onError: (e) => toast.error(e.message) })}
            >
              Cancel post
            </Button>
          )}
          {canEdit(post) && (
            <Link to={`/publish/new?edit=${post.id}`} className="inline-flex items-center gap-2 h-10 px-4 rounded-lg bg-marigold-500 text-[#1e2240] font-semibold hover:bg-marigold-400">
              <Pencil className="size-4" aria-hidden /> Edit
            </Link>
          )}
        </>
      }
    >
      <div className="flex gap-4 items-start">
        {post.media ? (
          <MediaThumb asset={post.media} className="w-28 aspect-video rounded-lg shrink-0" />
        ) : (
          <div className="w-28 aspect-video rounded-lg bg-surface-2 grid place-items-center text-xs text-faint shrink-0">Video removed</div>
        )}
        <div className="min-w-0 flex flex-col gap-1">
          <StatusBadge status={post.status} />
          <p className="text-sm text-muted">
            {post.publishedAt ? 'Published ' : post.status === 'DRAFT' ? (when ? 'Planned for ' : 'No date yet') : 'Goes out '}
            {when && <time dateTime={when}>{formatWhen(when)}</time>}
          </p>
          {post.media && <p className="text-xs text-faint truncate">{post.media.filename}</p>}
        </div>
      </div>
      {post.targets.length > 0 ? (
        <ul className="mt-4 divide-y divide-line border-y border-line">
          {post.targets.map((t) => (
            <TargetRow key={t.id} post={post} target={t} />
          ))}
        </ul>
      ) : (
        <p className="mt-4 text-sm text-muted">No accounts chosen yet.</p>
      )}
    </Dialog>
  )
}
