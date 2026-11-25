'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { isLocalHost, parseServer, sameOrigin, openableOutside, looksLikeLeenax, clip } = require('../src/rules')

test('local addresses', () => {
  for (const h of ['localhost', '127.0.0.1', '10.0.0.5', '192.168.1.20', '172.16.0.1', '172.31.255.255', '[::1]', 'studio.local']) assert.equal(isLocalHost(h), true, h)
  for (const h of ['172.32.0.1', '8.8.8.8', 'studio.example.com', 'localhost.evil.com', '192.169.0.1']) assert.equal(isLocalHost(h), false, h)
})

test('what a person types becomes the server origin', () => {
  assert.deepEqual(parseServer('http://localhost:8088/projects'), { origin: 'http://localhost:8088', local: true })
  assert.deepEqual(parseServer('localhost:8088'), { origin: 'http://localhost:8088', local: true })
  assert.deepEqual(parseServer('studio.example.com'), { origin: 'https://studio.example.com', local: false })
  assert.deepEqual(parseServer(' https://Studio.Example.com/ '), { origin: 'https://studio.example.com', local: false })
  assert.equal(parseServer('http://192.168.1.20:8088').origin, 'http://192.168.1.20:8088')
})

test('plain http to the internet, other schemes and credentials in the address are refused', () => {
  assert.match(parseServer('http://studio.example.com').error, /https/)
  assert.match(parseServer('ftp://studio.example.com').error, /https/)
  assert.match(parseServer('file:///C:/x').error, /https/)
  assert.match(parseServer('https://kani:secret@studio.example.com').error, /password/)
  assert.ok(parseServer('').error)
  assert.ok(parseServer('http://').error)
})

test('pages stay on the server', () => {
  assert.equal(sameOrigin('https://studio.example.com/notes/1', 'https://studio.example.com'), true)
  assert.equal(sameOrigin('https://studio.example.com.evil.com/', 'https://studio.example.com'), false)
  assert.equal(sameOrigin('http://studio.example.com/', 'https://studio.example.com'), false)
  assert.equal(sameOrigin('not a url', 'https://studio.example.com'), false)
  assert.equal(sameOrigin('https://studio.example.com/', null), false)
})

test('only web and mail links open outside', () => {
  assert.equal(openableOutside('https://www.youtube.com/watch?v=x'), true)
  assert.equal(openableOutside('mailto:kani@example.com'), true)
  for (const u of ['file:///C:/Windows/system32/calc.exe', 'javascript:alert(1)', 'ms-settings:', 'smb://host/share', 'nonsense']) assert.equal(openableOutside(u), false, u)
})

test('a Leenax server is recognised by its status reply', () => {
  assert.equal(looksLikeLeenax(200, 'application/json', { setupRequired: false, setupEnabled: false, authenticated: false }), true)
  assert.equal(looksLikeLeenax(200, 'text/html', '<html>'), false)
  assert.equal(looksLikeLeenax(404, 'application/json', { setupRequired: false, authenticated: false }), false)
  assert.equal(looksLikeLeenax(200, 'application/json', { ok: true }), false)
})

test('notification text is one short line', () => {
  assert.equal(clip('a\n\n b', 10), 'a b')
  assert.equal(clip('x'.repeat(20), 10), 'x'.repeat(9) + '…')
  assert.equal(clip(null, 5), '')
})
