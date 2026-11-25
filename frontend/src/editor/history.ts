import { useCallback, useRef, useState } from 'react'

export interface History<T> {
  present: T
  /** A normal edit: one undo step. */
  set: (next: T | ((cur: T) => T)) => void
  /** Live changes during a drag: shown immediately, recorded once by `commit`. */
  preview: (next: T | ((cur: T) => T)) => void
  /** Ends a drag: records one undo step back to the state before it started. */
  commit: () => void
  undo: () => void
  redo: () => void
  reset: (value: T) => void
  canUndo: boolean
  canRedo: boolean
}

const LIMIT = 150

export function useHistory<T>(initial: T): History<T> {
  const [state, setState] = useState({ past: [] as T[], present: initial, future: [] as T[] })
  const dragStart = useRef<T | null>(null)

  const set = useCallback((next: T | ((cur: T) => T)) => {
    setState((s) => {
      const value = typeof next === 'function' ? (next as (c: T) => T)(s.present) : next
      if (value === s.present) return s
      return { past: [...s.past, s.present].slice(-LIMIT), present: value, future: [] }
    })
  }, [])

  const preview = useCallback((next: T | ((cur: T) => T)) => {
    setState((s) => {
      if (dragStart.current === null) dragStart.current = s.present
      const value = typeof next === 'function' ? (next as (c: T) => T)(s.present) : next
      return value === s.present ? s : { ...s, present: value }
    })
  }, [])

  const commit = useCallback(() => {
    const before = dragStart.current
    dragStart.current = null
    if (before === null) return
    setState((s) => (before === s.present ? s : { past: [...s.past, before].slice(-LIMIT), present: s.present, future: [] }))
  }, [])

  const undo = useCallback(
    () => setState((s) => (s.past.length ? { past: s.past.slice(0, -1), present: s.past[s.past.length - 1], future: [s.present, ...s.future] } : s)),
    [],
  )
  const redo = useCallback(
    () => setState((s) => (s.future.length ? { past: [...s.past, s.present], present: s.future[0], future: s.future.slice(1) } : s)),
    [],
  )
  const reset = useCallback((value: T) => setState({ past: [], present: value, future: [] }), [])

  return { present: state.present, set, preview, commit, undo, redo, reset, canUndo: state.past.length > 0, canRedo: state.future.length > 0 }
}
