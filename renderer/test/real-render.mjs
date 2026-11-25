// End-to-end check with real Chrome + FFmpeg: builds sample media, serves it over HTTP,
// renders a timeline with video, image, Tamil text, music, transitions and speed,
// then checks the MP4 with ffprobe. Run: BROWSER_EXECUTABLE=/path/to/chrome npm run test:render
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { bundle } from '@remotion/bundler'
import { createQueue } from '../jobs.mjs'

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'leenax-real-'))
const ff = (...args) => execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args])
ff('-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=30:d=4', '-f', 'lavfi', '-i', 'sine=f=440:d=4', '-shortest', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', path.join(dir, 'v.mp4'))
ff('-f', 'lavfi', '-i', 'color=c=0x1f6f8b:size=800x800', '-frames:v', '1', path.join(dir, 'i.png'))
ff('-f', 'lavfi', '-i', 'sine=f=220:d=10', '-c:a', 'libmp3lame', path.join(dir, 'm.mp3'))

const server = http.createServer((req, res) => {
  const f = path.join(dir, path.basename(req.url.split('?')[0]))
  if (!fs.existsSync(f)) return res.writeHead(404).end()
  const size = fs.statSync(f).size
  const type = f.endsWith('.mp4') ? 'video/mp4' : f.endsWith('.png') ? 'image/png' : 'audio/mpeg'
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

const filters = { brightness: 1, contrast: 1, saturation: 1, blur: 0 }
const media = (id, kind, extra) => ({
  id, type: 'media', mediaId: id, kind, trimStart: 0, speed: 1, volume: 1, fadeIn: 0, fadeOut: 0, fit: 'cover', x: 0, y: 0, scale: 1,
  rotation: 0, opacity: 1, filters, motion: 'none', transitionIn: { type: 'none', duration: 0.5 }, transitionOut: { type: 'none', duration: 0.5 }, ...extra,
})
const props = {
  timeline: {
    version: 1, width: 1280, height: 720, fps: 30, background: '#000000',
    tracks: [
      { id: 't1', kind: 'text', name: 'Text', muted: false, hidden: false, locked: false, volume: 1, clips: [
        { id: 'x1', type: 'text', start: 0.2, duration: 2.5, text: 'வணக்கம் Leenax', x: 0.5, y: 0.5, animationIn: 'pop', animationOut: 'fade',
          style: { fontFamily: 'Anek Tamil Variable', fontSize: 90, fontWeight: 800, color: '#ffffff', stroke: '#000000', strokeWidth: 4, background: '', align: 'center', width: 0.9, shadow: true } },
      ] },
      { id: 't2', kind: 'video', name: 'Video', muted: false, hidden: false, locked: false, volume: 1, clips: [
        media('v', 'VIDEO', { start: 0, duration: 2, trimStart: 1, speed: 1.5, transitionOut: { type: 'fade', duration: 0.5 } }),
        media('i', 'IMAGE', { start: 2, duration: 2, motion: 'zoom-in', transitionIn: { type: 'slide-left', duration: 0.5 }, filters: { ...filters, saturation: 1.3 } }),
      ] },
      { id: 't3', kind: 'audio', name: 'Music', muted: false, hidden: false, locked: false, volume: 0.6, clips: [media('m', 'AUDIO', { start: 0, duration: 4, fadeIn: 0.5, fadeOut: 1 })] },
    ],
  },
  media: {
    v: { id: 'v', kind: 'VIDEO', url: `${base}/v.mp4`, durationMs: 4000 },
    i: { id: 'i', kind: 'IMAGE', url: `${base}/i.png` },
    m: { id: 'm', kind: 'AUDIO', url: `${base}/m.mp3`, durationMs: 10000 },
  },
}

const entry = process.env.COMPOSITION_ENTRY ?? path.resolve('../frontend/src/remotion/entry.ts')
console.log('Bundling', entry)
const serveUrl = await bundle({ entryPoint: entry, outDir: path.join(dir, 'bundle'), enableCaching: false })
const queue = createQueue({ serveUrl, outDir: path.join(dir, 'out'), browserExecutable: process.env.BROWSER_EXECUTABLE || null })
const started = Date.now()
queue.submit({ id: 'real-render-1', props: { ...props, output: { width: 640, height: 360 } }, crf: 23 })
let job
while ((job = queue.get('real-render-1')).status === 'queued' || job.status === 'rendering') await new Promise((r) => setTimeout(r, 250))
console.log(job)
assert.equal(job.status, 'done', job.error)
const out = queue.file('real-render-1')
const probe = JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-print_format', 'json', '-show_streams', '-show_format', out]).toString())
const v = probe.streams.find((s) => s.codec_type === 'video')
const a = probe.streams.find((s) => s.codec_type === 'audio')
assert.equal(v.codec_name, 'h264')
assert.equal(`${v.width}x${v.height}`, '640x360') // laid out at 1280x720, exported at 640x360
assert.ok(a, 'has an audio track')
assert.ok(Math.abs(Number(probe.format.duration) - 4) < 0.15, `duration ${probe.format.duration}`)
// Frame at 1 s comes from the video; frame at 3 s is the (blue-green) image.
for (const [t, name] of [[1, 'f1.png'], [3, 'f3.png']]) ff('-ss', String(t), '-i', out, '-frames:v', '1', path.join(dir, name))
fs.copyFileSync(path.join(dir, 'f1.png'), '/tmp/leenax-render-frame1.png')
fs.copyFileSync(path.join(dir, 'f3.png'), '/tmp/leenax-render-frame3.png')
fs.copyFileSync(out, '/tmp/leenax-render.mp4')
console.log(`OK: ${v.width}x${v.height} ${probe.format.duration}s with audio, in ${((Date.now() - started) / 1000).toFixed(1)} s`)
queue.stop()
server.close()
