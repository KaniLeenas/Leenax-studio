'use strict'
const q = new URLSearchParams(location.search)
const server = q.get('server')
if (server) document.getElementById('what').textContent = `${server} didn't answer.`
document.getElementById('why').textContent = `Details: ${q.get('reason') || 'no answer'}`
document.getElementById('retry').addEventListener('click', () => window.leenaxSetup.retry())
document.getElementById('change').addEventListener('click', () => window.leenaxSetup.change())
// try again by itself every 15 seconds while this page is open
setInterval(() => window.leenaxSetup.retry(), 15000)
