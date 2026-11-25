import { AlertTriangle, Bell, Check, CheckCheck, ExternalLink, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { cn } from '../lib/cn'
import { formatRelative } from '../lib/format'
import { isExternal, isProblem, useNotificationAction, useNotifications, useUnreadCount, type AppNotification } from '../lib/notifications'
import { Spinner } from './ui'

/**
 * The bell: how many notifications are unread (checked every minute), and a panel with the
 * latest ones. Opening one marks it read. `placement` puts the panel beside the sidebar or under
 * the phone's top bar; it's drawn on the page body so nothing in the page paints over it.
 */
export function NotificationsButton({ placement }: { placement: 'sidebar' | 'topbar' }) {
  const [open, setOpen] = useState(false)
  const unread = useUnreadCount()
  const count = unread.data ?? 0
  const panel = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false)
        button.current?.focus()
      }
    }
    const onClick = (e: MouseEvent) => {
      if (!panel.current?.contains(e.target as Node) && !button.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onClick)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onClick)
    }
  }, [open])

  return (
    <>
      <button
        ref={button}
        type="button"
        onClick={() => setOpen(!open)}
        className="relative p-2 rounded-lg text-muted hover:text-ink hover:bg-surface-2"
        aria-label={count > 0 ? `Notifications, ${count} unread` : 'Notifications'}
        aria-expanded={open}
        aria-haspopup="dialog"
      >
        <Bell className="size-[18px]" />
        {count > 0 && (
          <span aria-hidden className="absolute -top-0.5 -right-0.5 min-w-4 h-4 px-1 rounded-full bg-marigold-500 text-[#1e2240] text-[10px] font-semibold leading-4 text-center tabular">
            {count > 99 ? '99+' : count}
          </span>
        )}
      </button>
      {open && createPortal(
        <div
          ref={panel}
          role="dialog"
          aria-label="Notifications"
          className={cn(
            'fixed z-50 rounded-2xl border border-line bg-surface shadow-xl flex flex-col max-h-[min(36rem,calc(100vh-5rem))]',
            placement === 'sidebar' ? 'left-[272px] bottom-4 w-[24rem]' : 'left-2 right-2 top-16',
          )}
        >
          <Panel onClose={() => setOpen(false)} />
        </div>,
        document.body,
      )}
    </>
  )
}

function Panel({ onClose }: { onClose: () => void }) {
  const list = useNotifications(true)
  const action = useNotificationAction()
  const items = list.data?.items ?? []
  const unread = list.data?.unread ?? 0
  return (
    <>
      <div className="flex items-center gap-2 px-4 py-3 border-b border-line">
        <h2 className="font-semibold flex-1">Notifications</h2>
        {unread > 0 && (
          <button type="button" className="text-xs text-muted hover:text-ink inline-flex items-center gap-1" onClick={() => action.mutate({ action: 'readAll' })}>
            <CheckCheck className="size-3.5" aria-hidden />Mark all read
          </button>
        )}
        <button type="button" onClick={onClose} className="p-1 -m-1 text-faint hover:text-ink rounded" aria-label="Close notifications">
          <X className="size-4" />
        </button>
      </div>
      <div className="overflow-y-auto">
        {list.isPending && <div className="p-4"><Spinner label="Loading notifications" /></div>}
        {list.error && <p className="p-4 text-sm text-rose-signal">{list.error.message}</p>}
        {list.data && items.length === 0 && (
          <p className="p-6 text-sm text-muted text-center">Nothing yet. Reviews, finished jobs and published videos show up here.</p>
        )}
        <ul className="divide-y divide-line">
          {items.map((n) => (
            <Item key={n.id} n={n} onOpen={() => { if (!n.readAt) action.mutate({ action: 'read', id: n.id }); onClose() }}
              onRead={() => action.mutate({ action: 'read', id: n.id })} onDelete={() => action.mutate({ action: 'delete', id: n.id })} />
          ))}
        </ul>
      </div>
    </>
  )
}

function Item({ n, onOpen, onRead, onDelete }: { n: AppNotification; onOpen: () => void; onRead: () => void; onDelete: () => void }) {
  const content = (
    <>
      <span className="flex items-start gap-2">
        {!n.readAt && <span aria-label="Unread" className="mt-1.5 size-2 rounded-full bg-marigold-500 shrink-0" />}
        {isProblem(n.kind) && <AlertTriangle aria-hidden className="size-3.5 mt-0.5 shrink-0 text-rose-signal" />}
        <span className={cn('text-sm break-words', !n.readAt && 'font-medium')}>{n.title}</span>
        {isExternal(n.link) && <ExternalLink aria-hidden className="size-3 mt-1 shrink-0 text-faint" />}
      </span>
      {n.body && <span className="block text-xs text-muted mt-0.5 break-words">{n.body}</span>}
      <span className="block text-xs text-faint mt-0.5">{formatRelative(n.createdAt)}</span>
    </>
  )
  return (
    <li className="group relative px-4 py-3 hover:bg-surface-2/60">
      {n.link ? (
        isExternal(n.link) ? (
          <a href={n.link} target="_blank" rel="noreferrer noopener" onClick={onOpen} className="block pr-12">{content}</a>
        ) : (
          <Link to={n.link} onClick={onOpen} className="block pr-12">{content}</Link>
        )
      ) : (
        <div className="pr-12">{content}</div>
      )}
      <span className="absolute right-3 top-3 flex gap-1 opacity-0 group-hover:opacity-100 group-focus-within:opacity-100">
        {!n.readAt && (
          <button type="button" onClick={onRead} className="p-1 rounded text-faint hover:text-ink" aria-label={`Mark “${n.title}” read`}>
            <Check className="size-3.5" />
          </button>
        )}
        <button type="button" onClick={onDelete} className="p-1 rounded text-faint hover:text-ink" aria-label={`Remove “${n.title}”`}>
          <X className="size-3.5" />
        </button>
      </span>
    </li>
  )
}
