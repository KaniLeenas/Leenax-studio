'use strict'
const $ = (id) => document.getElementById(id)
const bridge = window.leenaxSetup

async function start() {
  const current = await bridge.current()
  $('address').value = current || 'http://localhost:8088'
  if (current) {
    $('cancel').hidden = false
    $('cancel').addEventListener('click', () => bridge.retry())
  }
  $('address').focus()
  $('address').select()
}

$('form').addEventListener('submit', async (e) => {
  e.preventDefault()
  const out = $('result')
  out.className = ''
  out.textContent = 'Checking…'
  $('connect').disabled = true
  try {
    const r = await bridge.test($('address').value)
    if (!r.ok) {
      out.className = 'bad'
      out.textContent = r.message
      return
    }
    out.className = 'good'
    out.textContent = r.setupRequired ? `Found Leenax at ${r.origin}. It needs its first account; you'll create it next.` : `Found Leenax at ${r.origin}. Opening…`
    await bridge.save(r.origin)
  } finally {
    $('connect').disabled = false
  }
})

start()
