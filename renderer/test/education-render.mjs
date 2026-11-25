// E8 end-to-end check with real Chrome + FFmpeg: renders a lesson and a quiz timeline (scene clips,
// narration and captions, as the backend builds them) and checks the MP4s with ffprobe.
// Run: BROWSER_EXECUTABLE=/path/to/chrome node test/education-render.mjs lesson.json quiz.json
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { bundle } from '@remotion/bundler'
import { createQueue } from '../jobs.mjs'

const docs = process.argv.slice(2).map((f) => [path.basename(f, '.json'), JSON.parse(fs.readFileSync(f, 'utf8'))])
assert.ok(docs.length, 'pass timeline JSON files')
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'leenax-edu-'))
const ff = (...args) => execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args])
// A stand-in for the narration (a soft tone; the real one comes from text-to-speech), and an image.
ff('-f', 'lavfi', '-i', 'sine=f=330:d=60', '-af', 'volume=0.3', '-c:a', 'libmp3lame', path.join(dir, 'narration.mp3'))
ff('-f', 'lavfi', '-i', 'testsrc2=size=1200x800', '-frames:v', '1', path.join(dir, 'image.png'))

const server = http.createServer((req, res) => {
  const f = path.join(dir, path.basename(req.url.split('?')[0]))
  if (!fs.existsSync(f)) return res.writeHead(404).end()
  const size = fs.statSync(f).size
  const type = f.endsWith('.png') ? 'image/png' : 'audio/mpeg'
  const range = req.headers.range?.match(/bytes=(\d+)-(\d*)/)
  if (range) {
    const start = Number(range[1])
    const end = range[2] ? Number(range[2]) : size - 1
    res.writeHead(206, { 'content-type': type, 'content-range': `bytes ${start}-${end}/${size}`, 'accept-ranges': 'bytes', 'content-length': end - start + 1 })
    return fs.createReadStream(f, { start, end }).pipe(res)
  }
  res.writeHead(200, { 'content-type': type, 'content-length': size, 'accept-ranges': 'bytes' })
  fs.createReadStream(f).pipe(res)
})
await new Promise((r) => server.listen(0, '127.0.0.1', r))
const base = `http://127.0.0.1:${server.address().port}`
const NARRATION = '00000000-0000-0000-0000-00000000aaaa'
const IMAGE = '00000000-0000-0000-0000-00000000bbbb'
const media = {
  [NARRATION]: { id: NARRATION, kind: 'AUDIO', url: `${base}/narration.mp3`, durationMs: 60000 },
  [IMAGE]: { id: IMAGE, kind: 'IMAGE', url: `${base}/image.png`, width: 1200, height: 800 },
}

const entry = process.env.COMPOSITION_ENTRY ?? path.resolve('../frontend/src/remotion/entry.ts')
const serveUrl = await bundle({ entryPoint: entry, outDir: path.join(dir, 'bundle'), enableCaching: false })
const queue = createQueue({ serveUrl, outDir: path.join(dir, 'out'), browserExecutable: process.env.BROWSER_EXECUTABLE || null })
const outDir = process.env.OUT_DIR ?? '/tmp/edu'
for (const [name, timeline] of docs) {
  const half = { width: timeline.width / 2, height: timeline.height / 2 }
  const started = Date.now()
  queue.submit({ id: `edu-${name}-render`, props: { timeline, media, output: half }, crf: 23 })
  let job
  while ((job = queue.get(`edu-${name}-render`)).status === 'queued' || job.status === 'rendering') await new Promise((r) => setTimeout(r, 500))
  assert.equal(job.status, 'done', job.error)
  const out = path.join(outDir, `${name}.mp4`)
  fs.copyFileSync(queue.file(`edu-${name}-render`), out)
  const probe = JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-print_format', 'json', '-show_streams', '-show_format', out]).toString())
  const v = probe.streams.find((s) => s.codec_type === 'video')
  assert.equal(`${v.width}x${v.height}`, `${half.width}x${half.height}`)
  assert.ok(probe.streams.some((s) => s.codec_type === 'audio'), 'has narration')
  console.log(`${name}: ${v.width}x${v.height}, ${probe.format.duration} s, rendered in ${((Date.now() - started) / 1000).toFixed(0)} s`)
}
queue.stop()
server.close()
