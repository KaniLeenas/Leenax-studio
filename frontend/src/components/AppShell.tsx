import { LogOut, Menu, Moon, Sun, X } from 'lucide-react'
import { NotificationsButton } from './Notifications'
import { useEffect, type ReactNode } from 'react'
import { NavLink, useLocation } from 'react-router-dom'
import { useDesktopAlerts } from '../lib/desktop'
import { isLive, navFor } from '../lib/navigation'
import { useUnreadCount } from '../lib/notifications'
import { useAuthStatus, useLogout } from '../lib/queries'
import { cn } from '../lib/cn'
import { useUi } from '../stores/ui'

export function BrandMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" className={className} aria-hidden>
      <rect width="32" height="32" rx="8" fill="#1E2240" />
      <path d="M10 8v16h12" fill="none" stroke="#E8A317" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="22" cy="10" r="3" fill="#2FB4B0" />
    </svg>
  )
}

function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  const auth = useAuthStatus()
  const nav = navFor(auth.data?.user?.role === 'ADMIN')
  const { theme, toggleTheme } = useUi()
  const { data } = useAuthStatus()
  const logout = useLogout()

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2.5 px-5 h-16 shrink-0">
        <BrandMark className="size-8" />
        <span className="font-display text-[1.2rem] font-semibold tracking-tight">Leenax Studio</span>
      </div>

      <nav aria-label="Main" className="flex-1 overflow-y-auto px-3 pb-4">
        {nav.map((group) => (
          <div key={group.label} className="mt-5 first:mt-2">
            <p className="px-3 mb-1 text-[0.8rem] font-medium text-faint">{group.label}</p>
            <ul>
              {group.items.map((item) => {
                const Icon = item.icon
                const later = !isLive(item)
                return (
                  <li key={item.path}>
                    <NavLink
                      to={item.path}
                      end={item.path === '/'}
                      onClick={onNavigate}
                      className={({ isActive }) =>
                        cn(
                          'group relative flex items-center gap-3 rounded-lg px-3 h-9 text-[0.94rem] transition-colors',
                          isActive ? 'bg-surface-2 text-ink font-medium' : 'text-muted hover:text-ink hover:bg-surface-2/60',
                        )
                      }
                    >
                      {({ isActive }) => (
                        <>
                          {isActive && (
                            <span className="absolute left-0 top-1.5 bottom-1.5 w-[3px] rounded-full bg-marigold-500" aria-hidden />
                          )}
                          <Icon className={cn('size-[18px] shrink-0', later && !isActive && 'opacity-60')} aria-hidden />
                          <span className={cn('flex-1 truncate', later && !isActive && 'opacity-75')}>{item.label}</span>
                          {later && <span className="text-[0.72rem] text-faint tabular">Phase {item.phase}</span>}
                        </>
                      )}
                    </NavLink>
                  </li>
                )
              })}
            </ul>
          </div>
        ))}
      </nav>

      <div className="border-t border-line p-3 flex items-center gap-2">
        <div className="min-w-0 flex-1 px-2">
          <p className="truncate text-sm font-medium">{data?.user?.displayName}</p>
          <p className="truncate text-xs text-faint">{data?.user?.email}</p>
        </div>
        {!onNavigate && <NotificationsButton placement="sidebar" />}
        <button
          onClick={toggleTheme}
          className="p-2 rounded-lg text-muted hover:text-ink hover:bg-surface-2"
          aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
        >
          {theme === 'dark' ? <Sun className="size-[18px]" /> : <Moon className="size-[18px]" />}
        </button>
        <button
          onClick={() => logout.mutate()}
          className="p-2 rounded-lg text-muted hover:text-ink hover:bg-surface-2"
          aria-label="Sign out"
        >
          <LogOut className="size-[18px]" />
        </button>
      </div>
    </div>
  )
}

export function AppShell({ children }: { children: ReactNode }) {
  const { navOpen, setNavOpen } = useUi()
  const location = useLocation()
  // Editors use the whole width of the window.
  const wide = /^\/(edit\/timeline|create\/thumbnails)\/[^/]+$/.test(location.pathname)

  useEffect(() => setNavOpen(false), [location.pathname, setNavOpen])
  useDesktopAlerts(useUnreadCount().data)

  return (
    <div className="min-h-full lg:grid lg:grid-cols-[264px_1fr]">
      {/* Desktop sidebar */}
      <aside className="hidden lg:block sticky top-0 h-screen border-r border-line bg-surface">
        <Sidebar />
      </aside>

      {/* Mobile top bar */}
      <div className="lg:hidden sticky top-0 z-30 flex items-center gap-3 h-14 px-4 border-b border-line bg-surface/95 backdrop-blur">
        <button onClick={() => setNavOpen(true)} className="p-2 -ml-2 rounded-lg" aria-label="Open menu">
          <Menu className="size-5" />
        </button>
        <BrandMark className="size-7" />
        <span className="font-display font-semibold flex-1">Leenax Studio</span>
        <NotificationsButton placement="topbar" />
      </div>

      {/* Mobile drawer */}
      {navOpen && (
        <div className="lg:hidden fixed inset-0 z-40">
          <div className="absolute inset-0 bg-[#0d1020]/50" onClick={() => setNavOpen(false)} />
          <aside className="absolute inset-y-0 left-0 w-[min(300px,85vw)] bg-surface border-r border-line shadow-xl">
            <button
              onClick={() => setNavOpen(false)}
              className="absolute right-3 top-4 p-2 rounded-lg text-muted"
              aria-label="Close menu"
            >
              <X className="size-5" />
            </button>
            <Sidebar onNavigate={() => setNavOpen(false)} />
          </aside>
        </div>
      )}

      <main className={cn('min-w-0', wide ? 'px-3 sm:px-4 py-4' : 'px-4 sm:px-8 lg:px-12 py-8 lg:py-10')}>
        <div className={wide ? undefined : 'max-w-[1180px]'}>{children}</div>
      </main>
    </div>
  )
}
