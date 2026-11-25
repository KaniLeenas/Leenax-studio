import { create } from 'zustand'

type Theme = 'light' | 'dark'

function initialTheme(): Theme {
  if (typeof document === 'undefined') return 'light'
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light'
}

interface UiState {
  theme: Theme
  navOpen: boolean
  setTheme: (t: Theme) => void
  toggleTheme: () => void
  setNavOpen: (open: boolean) => void
}

export const useUi = create<UiState>((set, get) => ({
  theme: initialTheme(),
  navOpen: false,
  setTheme: (theme) => {
    document.documentElement.classList.toggle('dark', theme === 'dark')
    try {
      // Only a display preference — never anything sensitive in browser storage.
      localStorage.setItem('leenax-theme', theme)
    } catch {
      /* storage unavailable */
    }
    set({ theme })
  },
  toggleTheme: () => get().setTheme(get().theme === 'dark' ? 'light' : 'dark'),
  setNavOpen: (navOpen) => set({ navOpen }),
}))

export interface Toast {
  id: number
  tone: 'success' | 'error' | 'info'
  message: string
}

interface ToastState {
  toasts: Toast[]
  push: (tone: Toast['tone'], message: string) => void
  dismiss: (id: number) => void
}

let nextId = 1

export const useToasts = create<ToastState>((set, get) => ({
  toasts: [],
  push: (tone, message) => {
    const id = nextId++
    set({ toasts: [...get().toasts, { id, tone, message }] })
    window.setTimeout(() => get().dismiss(id), tone === 'error' ? 7000 : 4000)
  },
  dismiss: (id) => set({ toasts: get().toasts.filter((t) => t.id !== id) }),
}))

export const toast = {
  success: (m: string) => useToasts.getState().push('success', m),
  error: (m: string) => useToasts.getState().push('error', m),
  info: (m: string) => useToasts.getState().push('info', m),
}
