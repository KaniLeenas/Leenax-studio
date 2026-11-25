import { useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from './api'
import type { AppNotification } from './notifications'

/**
 * What the Leenax Studio desktop app (desktop/, Electron) lets the page do. Its preload script
 * puts this on window; in a normal browser it is absent and nothing here does anything.
 * It carries no credentials: the session cookie stays HttpOnly inside the app.
 */
export interface DesktopBridge {
  version: string
  /** The unread count for the taskbar badge and the tray tooltip. */
  setUnread(count: number): void
  /** A Windows notification; clicking it brings the window forward and calls onOpen. */
  notify(n: { id: string; title: string; body?: string | null }): void
  /** Called with a notification's id when its Windows notification is clicked. */
  onOpen(handler: (id: string) => void): () => void
}

export const desktop = (): DesktopBridge | undefined => (window as unknown as { leenaxDesktop?: DesktopBridge }).leenaxDesktop

/**
 * In the desktop app: keeps the badge in step with the unread count, and when new notifications
 * arrive shows each (up to three) as a Windows notification that opens its page when clicked.
 */
export function useDesktopAlerts(count: number | undefined) {
  const bridge = desktop()
  const last = useRef<number | undefined>(undefined)
  const shown = useRef(new Set<string>())
  const links = useRef(new Map<string, string>())
  const navigate = useNavigate()

  useEffect(() => {
    if (!bridge) return
    return bridge.onOpen((id) => {
      const link = links.current.get(id)
      if (!link) return
      api.post(`/notifications/${id}/read`).catch(() => {})
      if (/^https?:\/\//.test(link)) window.open(link, '_blank', 'noopener')
      else navigate(link)
    })
  }, [bridge, navigate])

  useEffect(() => {
    if (!bridge || count === undefined) return
    bridge.setUnread(count)
    const before = last.current
    last.current = count
    if (before !== undefined && count <= before) return
    if (before === undefined && count === 0) return
    api.get<{ items: AppNotification[] }>('/notifications?limit=10').then(({ items }) => {
      // the ones already unread when the app opened are remembered, not announced
      if (before === undefined) return items.forEach((n) => shown.current.add(n.id))
      items.filter((n) => !n.readAt && !shown.current.has(n.id)).slice(0, 3).reverse().forEach((n) => {
        shown.current.add(n.id)
        if (n.link) links.current.set(n.id, n.link)
        bridge.notify({ id: n.id, title: n.title, body: n.body ?? n.projectName })
      })
    }).catch(() => {})
  }, [bridge, count])
}
