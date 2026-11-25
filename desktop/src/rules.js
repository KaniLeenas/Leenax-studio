'use strict'
// Decisions the desktop app makes about addresses, kept free of Electron so they can be tested.

/** True for addresses that never leave this PC or the local network. */
function isLocalHost(hostname) {
  const h = hostname.replace(/^\[|\]$/g, '').toLowerCase()
  if (h === 'localhost' || h.endsWith('.localhost') || h === '::1' || h.endsWith('.local')) return true
  const m = h.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/)
  if (!m) return false
  const [a, b] = [Number(m[1]), Number(m[2])]
  return a === 127 || a === 10 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31)
}

/**
 * Turns what a person typed into the server's origin, or says why it can't be used.
 * Plain http is accepted only on this PC or the local network: anywhere else the password and
 * the session cookie would cross the internet unencrypted.
 */
function parseServer(input) {
  let text = String(input || '').trim()
  if (!text) return { error: 'Type the address of your Leenax server.' }
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(text)) text = (/^(localhost|127\.|10\.|192\.168\.|\[?::1)/i.test(text) ? 'http://' : 'https://') + text
  let url
  try {
    url = new URL(text)
  } catch {
    return { error: 'That isn’t a web address.' }
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return { error: 'The address must start with https:// (or http:// on this PC).' }
  if (url.username || url.password) return { error: 'Don’t put a name or password in the address; you sign in inside the app.' }
  if (url.protocol === 'http:' && !isLocalHost(url.hostname)) {
    return { error: 'Use https:// for a server on the internet. Plain http:// is only allowed on this PC or your local network.' }
  }
  return { origin: url.origin, local: isLocalHost(url.hostname) }
}

/** Whether a page address belongs to the configured server. */
function sameOrigin(address, origin) {
  try {
    return new URL(address).origin === origin
  } catch {
    return false
  }
}

/** Addresses the app may hand to the default browser or mail program. */
function openableOutside(address) {
  try {
    const u = new URL(address)
    return u.protocol === 'https:' || u.protocol === 'http:' || u.protocol === 'mailto:'
  } catch {
    return false
  }
}

/** Whether a reply from /api/v1/auth/status looks like a Leenax server. */
function looksLikeLeenax(status, contentType, body) {
  if (status !== 200 || !/application\/json/.test(contentType || '')) return false
  return body !== null && typeof body === 'object' && typeof body.setupRequired === 'boolean' && typeof body.authenticated === 'boolean'
}

/** Short, single-line text for a Windows notification. */
function clip(text, max) {
  const s = String(text ?? '').replace(/\s+/g, ' ').trim()
  return s.length > max ? s.slice(0, max - 1) + '…' : s
}

module.exports = { isLocalHost, parseServer, sameOrigin, openableOutside, looksLikeLeenax, clip }
