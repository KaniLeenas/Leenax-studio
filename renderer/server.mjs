// Leenax render service. Internal only: reachable from the backend inside the Docker network.
import fs from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import { timingSafeEqual } from 'node:crypto'
import { createQueue } from './jobs.mjs'

const PORT = Number(process.env.PORT ?? 7100)
const TOKEN = process.env.RENDERER_TOKEN ?? ''
const MAX_BODY = 5 * 1024 * 1024

export function createServer(queue, token = TOKEN) {
  const authorised = (req) => {
    if (!token) return true
    const got = Buffer.from(String(req.headers['x-renderer-token'] ?? ''))
    const want = Buffer.from(token)
    return got.length === want.length && timingSafeEqual(got, want)
  }
  const json = (res, status, body) => {
    res.writeHead(status, { 'content-type': 'application/json' })
    res.end(JSON.stringify(body))
  }

  return http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://renderer')
    if (url.pathname === '/health') return json(res, 200, { status: 'UP' })
    if (!authorised(req)) return json(res, 401, { error: 'Unauthorized' })

    const m = url.pathname.match(/^\/renders(?:\/([A-Za-z0-9-]+))?(\/file)?$/)
    if (!m) return json(res, 404, { error: 'Not found' })
    const [, id, isFile] = m
    try {
      if (req.method === 'POST' && !id) {
        const body = await readBody(req)
        return json(res, 202, queue.submit(JSON.parse(body)))
      }
      if (req.method === 'GET' && id && !isFile) {
        const job = queue.get(id)
        return job ? json(res, 200, job) : json(res, 404, { error: 'Unknown render' })
      }
      if (req.method === 'GET' && id && isFile) {
        const f = queue.file(id)
        if (!f) return json(res, 404, { error: 'Not ready' })
        res.writeHead(200, { 'content-type': 'video/mp4', 'content-length': fs.statSync(f).size })
        return fs.createReadStream(f).pipe(res)
      }
      if (req.method === 'DELETE' && id) {
        return queue.cancel(id) ? json(res, 200, { id, cancelled: true }) : json(res, 404, { error: 'Unknown render' })
      }
      return json(res, 405, { error: 'Method not allowed' })
    } catch (e) {
      return json(res, 400, { error: String(e.message ?? e).slice(0, 300) })
    }
  })
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    req.on('data', (c) => {
      size += c.length
      if (size > MAX_BODY) {
        reject(new Error('Request too large'))
        req.destroy()
      } else chunks.push(c)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const queue = createQueue({
    serveUrl: path.resolve(process.env.BUNDLE_DIR ?? './bundle'),
    outDir: process.env.OUTPUT_DIR ?? '/tmp/leenax-renders',
    browserExecutable: process.env.BROWSER_EXECUTABLE || null,
  })
  createServer(queue).listen(PORT, () => console.log(`Leenax renderer listening on ${PORT}${TOKEN ? '' : ' (no token set!)'}`))
}
