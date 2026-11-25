import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { test } from 'node:test'
import { createQueue } from '../jobs.mjs'
import { createServer } from '../server.mjs'

const props = { timeline: { version: 1, width: 64, height: 64, fps: 30, background: '#000', tracks: [] }, media: {} }

async function withServer(render, fn) {
  const outDir = fs.mkdtempSync(path.join(os.tmpdir(), 'renders-'))
  const queue = createQueue({ serveUrl: 'x', outDir, render })
  const server = createServer(queue, 'secret')
  await new Promise((r) => server.listen(0, r))
  const base = `http://127.0.0.1:${server.address().port}`
  const call = (method, p, body, token = 'secret') =>
    fetch(base + p, { method, headers: { 'x-renderer-token': token, 'content-type': 'application/json' }, body: body && JSON.stringify(body) })
  try {
    await fn(call)
  } finally {
    queue.stop()
    server.close()
  }
}

const waitFor = async (call, id, status) => {
  for (let i = 0; i < 100; i++) {
    const j = await (await call('GET', `/renders/${id}`)).json()
    if (j.status === status) return j
    await new Promise((r) => setTimeout(r, 20))
  }
  throw new Error('timeout waiting for ' + status)
}

test('requires the shared token', async () => {
  await withServer(async () => {}, async (call) => {
    assert.equal((await call('GET', '/renders/abcdefgh', undefined, 'wrong')).status, 401)
    assert.equal((await call('GET', '/health', undefined, 'wrong')).status, 200)
  })
})

test('renders, reports progress and serves the file', async () => {
  const render = async ({ output, onProgress }) => {
    onProgress(0.5)
    fs.writeFileSync(output, 'MP4DATA')
  }
  await withServer(render, async (call) => {
    const res = await call('POST', '/renders', { id: 'job-12345678', props })
    assert.equal(res.status, 202)
    const done = await waitFor(call, 'job-12345678', 'done')
    assert.equal(done.progress, 1)
    assert.equal(done.sizeBytes, 7)
    const file = await call('GET', '/renders/job-12345678/file')
    assert.equal(await file.text(), 'MP4DATA')
    // submitting the same id again does not render twice
    assert.equal((await (await call('POST', '/renders', { id: 'job-12345678', props })).json()).status, 'done')
  })
})

test('reports failures with a short message', async () => {
  const render = async () => {
    throw new Error('Could not fetch media\n    at stack line')
  }
  await withServer(render, async (call) => {
    await call('POST', '/renders', { id: 'job-failing1', props })
    const j = await waitFor(call, 'job-failing1', 'failed')
    assert.equal(j.error, 'Could not fetch media')
    assert.equal((await call('GET', '/renders/job-failing1/file')).status, 404)
  })
})

test('cancels a running render', async () => {
  let release
  const render = ({ cancelSignal }) =>
    new Promise((resolve, reject) => {
      release = resolve
      cancelSignal(() => reject(new Error('cancelled')))
    })
  await withServer(render, async (call) => {
    await call('POST', '/renders', { id: 'job-cancel01', props })
    await waitFor(call, 'job-cancel01', 'rendering')
    assert.equal((await call('DELETE', '/renders/job-cancel01')).status, 200)
    await waitFor(call, 'job-cancel01', 'cancelled')
    release?.()
  })
})

test('rejects bad requests', async () => {
  await withServer(async () => {}, async (call) => {
    assert.equal((await call('POST', '/renders', { id: 'x', props })).status, 400)
    assert.equal((await call('POST', '/renders', { id: 'job-noprops1' })).status, 400)
  })
})
