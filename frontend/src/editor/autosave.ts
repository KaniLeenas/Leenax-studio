import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { ApiError } from '../lib/api'
import { saveTimelineDocument } from '../lib/editorQueries'
import type { TimelineDoc } from '../remotion/types'

export type SaveState = 'saved' | 'pending' | 'saving' | 'error' | 'conflict'

/**
 * Saves the document 1.5 s after the last change. Sends the revision it was based on;
 * if another tab saved meanwhile the server answers 409 and we stop and ask.
 */
export function useAutosave(id: string, doc: TimelineDoc, initialRevision: number, paused: boolean) {
  const [state, setState] = useState<SaveState>('saved')
  const [error, setError] = useState<string>()
  const revision = useRef(initialRevision)
  const saved = useRef(doc)
  const latest = useRef(doc)
  const inFlight = useRef<Promise<boolean> | null>(null)
  useLayoutEffect(() => {
    latest.current = doc
  }, [doc])

  const flush = useCallback(async (): Promise<boolean> => {
    if (inFlight.current) await inFlight.current
    const toSave = latest.current
    if (toSave === saved.current) return true
    setState('saving')
    const p = saveTimelineDocument(id, revision.current, toSave)
      .then((r) => {
        revision.current = r.revision
        saved.current = toSave
        setError(undefined)
        setState(latest.current === toSave ? 'saved' : 'pending')
        return true
      })
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 409) setState('conflict')
        else setState('error')
        setError(e instanceof Error ? e.message : 'Could not save')
        return false
      })
      .finally(() => {
        inFlight.current = null
      })
    inFlight.current = p
    return p
  }, [id])

  useEffect(() => {
    if (doc === saved.current || paused || state === 'conflict') return
    setState((s) => (s === 'saving' ? s : 'pending'))
    const t = setTimeout(() => void flush(), 1500)
    return () => clearTimeout(t)
  }, [doc, paused, flush, state])

  // Last chance when the tab closes with unsaved work.
  useEffect(() => {
    const h = (e: BeforeUnloadEvent) => {
      if (latest.current !== saved.current) e.preventDefault()
    }
    window.addEventListener('beforeunload', h)
    return () => window.removeEventListener('beforeunload', h)
  }, [])

  /** After a conflict: take the server's revision and overwrite it with this tab's version. */
  const overwrite = useCallback(
    async (serverRevision: number) => {
      revision.current = serverRevision
      setState('pending')
      return flush()
    },
    [flush],
  )

  return { state, error, flush, overwrite, dirty: state !== 'saved' }
}
