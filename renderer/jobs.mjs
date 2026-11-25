// Render queue: one render at a time (a render already uses every CPU core).
import { makeCancelSignal, renderMedia, selectComposition } from '@remotion/renderer'
import fs from 'node:fs'
import path from 'node:path'

export const COMPOSITION_ID = 'leenax-timeline'
const ID = /^[A-Za-z0-9-]{8,64}$/

export function createQueue({ serveUrl, outDir, browserExecutable, render = renderWithRemotion, keepMs = 6 * 3600_000 }) {
  fs.mkdirSync(outDir, { recursive: true })
  const jobs = new Map()
  const waiting = []
  let running = null

  function file(id) {
    return path.join(outDir, `${id}.mp4`)
  }

  function view(job) {
    const { id, status, progress, error, sizeBytes, createdAt, finishedAt } = job
    return { id, status, progress, error, sizeBytes, createdAt, finishedAt }
  }

  async function next() {
    if (running || waiting.length === 0) return
    const job = waiting.shift()
    if (job.status !== 'queued') return next()
    running = job
    job.status = 'rendering'
    const { cancelSignal, cancel } = makeCancelSignal()
    job.cancel = cancel
    try {
      await render({
        serveUrl,
        browserExecutable,
        request: job.request,
        output: file(job.id),
        cancelSignal,
        onProgress: (p) => {
          job.progress = Math.round(p * 1000) / 1000
        },
      })
      if (job.status === 'cancelled') {
        fs.rmSync(file(job.id), { force: true })
      } else {
        job.status = 'done'
        job.progress = 1
        job.sizeBytes = fs.statSync(file(job.id)).size
      }
    } catch (e) {
      if (job.status !== 'cancelled') {
        job.status = 'failed'
        job.error = cleanError(e)
      }
      fs.rmSync(file(job.id), { force: true })
    } finally {
      job.finishedAt = Date.now()
      job.cancel = undefined
      running = null
      next()
    }
  }

  function sweep() {
    const now = Date.now()
    for (const [id, job] of jobs) {
      if (job.finishedAt && now - job.finishedAt > keepMs) {
        fs.rmSync(file(id), { force: true })
        jobs.delete(id)
      }
    }
  }
  const timer = setInterval(sweep, 10 * 60_000)
  timer.unref()

  return {
    submit(request) {
      if (!request || typeof request.id !== 'string' || !ID.test(request.id)) throw new Error('A valid id is required')
      const p = request.props
      if (!p?.timeline?.tracks || typeof p.media !== 'object') throw new Error('props.timeline and props.media are required')
      const existing = jobs.get(request.id)
      if (existing && existing.status !== 'failed' && existing.status !== 'cancelled') return view(existing) // idempotent
      const job = { id: request.id, status: 'queued', progress: 0, request, createdAt: Date.now() }
      jobs.set(job.id, job)
      waiting.push(job)
      queueMicrotask(next)
      return view(job)
    },
    get(id) {
      const job = jobs.get(id)
      return job ? view(job) : undefined
    },
    file(id) {
      const job = jobs.get(id)
      return job?.status === 'done' ? file(id) : undefined
    },
    cancel(id) {
      const job = jobs.get(id)
      if (!job) return false
      if (job.status === 'queued' || job.status === 'rendering') {
        job.status = 'cancelled'
        job.cancel?.()
      }
      if (job.status !== 'rendering') {
        fs.rmSync(file(id), { force: true })
        if (!job.cancel) jobs.delete(id)
      }
      return true
    },
    stop() {
      clearInterval(timer)
      running?.cancel?.()
    },
  }
}

/** Remotion error messages can be long stack dumps; keep the useful first line. */
export function cleanError(e) {
  const msg = String(e?.message ?? e)
  const line = msg.split('\n').find((l) => l.trim()) ?? 'Render failed'
  return line.length > 300 ? line.slice(0, 300) : line
}

async function renderWithRemotion({ serveUrl, browserExecutable, request, output, cancelSignal, onProgress }) {
  const inputProps = request.props
  const composition = await selectComposition({
    serveUrl,
    id: COMPOSITION_ID,
    inputProps,
    browserExecutable,
    timeoutInMilliseconds: 120_000,
  })
  await renderMedia({
    composition,
    serveUrl,
    inputProps,
    codec: 'h264',
    audioCodec: 'aac',
    outputLocation: output,
    scale: clamp(Number(request.scale ?? 1), 0.25, 2),
    crf: clamp(Math.round(Number(request.crf ?? 20)), 14, 32),
    x264Preset: ['ultrafast', 'superfast', 'veryfast', 'faster', 'fast', 'medium', 'slow'].includes(request.x264Preset) ? request.x264Preset : 'veryfast',
    pixelFormat: 'yuv420p',
    browserExecutable,
    cancelSignal,
    timeoutInMilliseconds: 120_000,
    onProgress: ({ progress }) => onProgress(progress),
    chromiumOptions: { gl: 'swangle' },
    logLevel: 'warn',
  })
}

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, Number.isFinite(v) ? v : lo))
