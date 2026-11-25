import { Circle, Mic, Square } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { uploadRecording } from '../lib/studioQueries'
import type { MediaAsset } from '../lib/types'
import { toast } from '../stores/ui'
import { Button } from './ui'

/** Picks a format the browser can record: Opus in WebM (Chrome, Firefox, Edge) or AAC in MP4 (Safari). */
export function recorderMimeType(isSupported: (t: string) => boolean = (t) => MediaRecorder.isTypeSupported(t)): string | undefined {
  return ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4;codecs=mp4a.40.2', 'audio/mp4'].find(isSupported)
}

/**
 * Records from the microphone and saves the recording to the media library
 * (only after you choose to keep it). Needs HTTPS or localhost.
 */
export function MicRecorder({ onSaved, label = 'Record with microphone' }: { onSaved: (m: MediaAsset) => void; label?: string }) {
  const [state, setState] = useState<'idle' | 'recording' | 'review' | 'saving'>('idle')
  const [seconds, setSeconds] = useState(0)
  const [blobUrl, setBlobUrl] = useState<string>()
  const recorder = useRef<MediaRecorder | null>(null)
  const chunks = useRef<Blob[]>([])
  const blob = useRef<Blob | null>(null)
  const stream = useRef<MediaStream | null>(null)

  useEffect(() => {
    if (state !== 'recording') return
    const t = setInterval(() => setSeconds((s) => s + 1), 1000)
    return () => clearInterval(t)
  }, [state])

  useEffect(
    () => () => {
      stream.current?.getTracks().forEach((t) => t.stop())
      if (blobUrl) URL.revokeObjectURL(blobUrl)
    },
    [blobUrl],
  )

  async function start() {
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      toast.error('This browser cannot record audio. Upload a recording instead.')
      return
    }
    const mimeType = recorderMimeType()
    try {
      stream.current = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: true },
      })
    } catch {
      toast.error('Microphone access was blocked. Allow it in the browser’s site settings and try again.')
      return
    }
    chunks.current = []
    const r = new MediaRecorder(stream.current, mimeType ? { mimeType, audioBitsPerSecond: 128_000 } : undefined)
    r.ondataavailable = (e) => e.data.size > 0 && chunks.current.push(e.data)
    r.onstop = () => {
      stream.current?.getTracks().forEach((t) => t.stop())
      blob.current = new Blob(chunks.current, { type: r.mimeType || mimeType || 'audio/webm' })
      setBlobUrl(URL.createObjectURL(blob.current))
      setState('review')
    }
    recorder.current = r
    r.start(1000)
    setSeconds(0)
    setState('recording')
  }

  function stop() {
    recorder.current?.stop()
  }

  function discard() {
    if (blobUrl) URL.revokeObjectURL(blobUrl)
    setBlobUrl(undefined)
    blob.current = null
    setState('idle')
  }

  async function save() {
    if (!blob.current) return
    setState('saving')
    const ext = blob.current.type.includes('mp4') ? 'm4a' : 'webm'
    const name = `Recording ${new Date().toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }).replace(/[/:]/g, '-')}.${ext}`
    try {
      const media = await uploadRecording(new File([blob.current], name, { type: blob.current.type }), () => {})
      toast.success('Recording saved')
      onSaved(media)
      discard()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Could not save the recording')
      setState('review')
    }
  }

  const mmss = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`

  if (state === 'recording') {
    return (
      <div className="flex items-center gap-3 rounded-lg border border-rose-signal/50 bg-rose-signal/6 px-3 py-2" role="status">
        <Circle className="size-3 fill-rose-signal text-rose-signal animate-pulse" aria-hidden />
        <span className="tabular text-sm">Recording {mmss}</span>
        <Button type="button" size="sm" className="ml-auto" onClick={stop} icon={<Square className="size-3.5" />}>
          Stop
        </Button>
      </div>
    )
  }
  if (state === 'review' || state === 'saving') {
    return (
      <div className="flex flex-col gap-2 rounded-lg border border-line p-3">
        <audio controls src={blobUrl} className="w-full" />
        <div className="flex gap-2 justify-end">
          <Button type="button" size="sm" variant="ghost" onClick={discard} disabled={state === 'saving'}>
            Discard
          </Button>
          <Button type="button" size="sm" variant="primary" onClick={save} loading={state === 'saving'}>
            Keep recording
          </Button>
        </div>
      </div>
    )
  }
  return (
    <Button type="button" onClick={start} icon={<Mic className="size-4" />}>
      {label}
    </Button>
  )
}
