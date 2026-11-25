import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import type { GenerationJob, Page } from './types'

/**
 * Subscribes to live job updates over Server-Sent Events and patches the
 * cached job lists in place. Falls back to polling when the stream drops.
 */
export function useGenerationEvents() {
  const qc = useQueryClient()
  const [connected, setConnected] = useState(false)

  useEffect(() => {
    if (typeof EventSource === 'undefined') return
    let source: EventSource | undefined
    let retry: number | undefined
    let closed = false

    const connect = () => {
      source = new EventSource('/api/v1/ai/generation/events', { withCredentials: true })
      source.addEventListener('ready', () => setConnected(true))
      source.addEventListener('job', (e) => {
        const job = JSON.parse((e as MessageEvent).data) as GenerationJob
        let found = false
        qc.setQueriesData<Page<GenerationJob>>({ queryKey: ['ai', 'jobs'] }, (page) => {
          if (!page) return page
          const items = page.items.map((j) => {
            if (j.id !== job.id) return j
            found = true
            return job
          })
          return { ...page, items }
        })
        if (!found) qc.invalidateQueries({ queryKey: ['ai', 'jobs'] })
        if (job.status === 'COMPLETED' || job.status === 'FAILED') {
          qc.invalidateQueries({ queryKey: ['ai', 'accounts'] })
          qc.invalidateQueries({ queryKey: ['media'] })
          qc.invalidateQueries({ queryKey: ['dashboard'] })
        }
      })
      source.onerror = () => {
        setConnected(false)
        source?.close()
        if (!closed) retry = window.setTimeout(connect, 5000)
      }
    }
    connect()
    return () => {
      closed = true
      window.clearTimeout(retry)
      source?.close()
    }
  }, [qc])

  return connected
}
