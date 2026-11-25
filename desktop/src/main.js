'use strict'
// Leenax Studio for Windows: the Leenax server's web app in its own window.
//
// Security: the server's pages run sandboxed with no Node.js access. The only thing they get
// is the small bridge in preload.js (badge count and Windows notifications). Sign-in stays in
// the server's HttpOnly session cookie; this app stores no passwords, keys or tokens, only the
// server's address and the window's size in config.json.

const { app, BrowserWindow, Menu, Notification, Tray, ipcMain, nativeImage, net, session, shell } = require('electron')
const fs = require('node:fs')
const path = require('node:path')
const { parseServer, sameOrigin, openableOutside, looksLikeLeenax, clip } = require('./rules')

const APP_ID = 'lk.leenax.studio'
const PARTITION = 'persist:leenax'
const ASSETS = path.join(__dirname, '..', 'assets')
const ALLOWED_PERMISSIONS = new Set(['notifications', 'media', 'clipboard-sanitized-write', 'fullscreen'])

let win = null
let tray = null
let quitting = false

// ---- settings (no secrets) -------------------------------------------------------------------------

const configFile = () => path.join(app.getPath('userData'), 'config.json')
function readConfig() {
  try {
    return JSON.parse(fs.readFileSync(configFile(), 'utf8'))
  } catch {
    return {}
  }
}
function writeConfig(patch) {
  const next = { ...readConfig(), ...patch }
  fs.mkdirSync(path.dirname(configFile()), { recursive: true })
  fs.writeFileSync(configFile(), JSON.stringify(next, null, 2))
  return next
}
const serverOrigin = () => {
  const s = readConfig().server
  const p = s ? parseServer(s) : null
  return p && p.origin ? p.origin : null
}

// ---- one copy only -------------------------------------------------------------------------------------

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => showWindow())
}

app.setAppUserModelId(APP_ID) // Windows shows notifications only for apps with an id

// ---- the window ------------------------------------------------------------------------------------------

function secureWebPreferences(preload, extraArgs = []) {
  return {
    preload,
    partition: PARTITION,
    sandbox: true,
    contextIsolation: true,
    nodeIntegration: false,
    webSecurity: true,
    allowRunningInsecureContent: false,
    spellcheck: true,
    backgroundThrottling: false, // keep checking for notifications while in the tray
    additionalArguments: extraArgs,
  }
}

function createWindow(hidden = false) {
  const cfg = readConfig()
  const bounds = cfg.bounds || { width: 1360, height: 880 }
  win = new BrowserWindow({
    ...bounds,
    minWidth: 380,
    minHeight: 520,
    show: false,
    title: 'Leenax Studio',
    icon: path.join(ASSETS, 'icon.ico'),
    backgroundColor: '#f3f4f8',
    autoHideMenuBar: true,
    webPreferences: secureWebPreferences(path.join(__dirname, 'preload.js'), [`--leenax-origin=${serverOrigin() || ''}`, `--leenax-version=${app.getVersion()}`]),
  })
  const me = win
  win.on('closed', () => { if (win === me) win = null })
  if (cfg.maximized) win.maximize()
  win.once('ready-to-show', () => { if (!hidden) win.show() })

  const remember = () => {
    if (!win || win.isMinimized()) return
    writeConfig(win.isMaximized() ? { maximized: true } : { maximized: false, bounds: win.getBounds() })
  }
  win.on('resized', remember)
  win.on('moved', remember)
  win.on('maximize', remember)
  win.on('unmaximize', remember)

  win.on('close', (e) => {
    if (quitting || readConfig().keepInTray === false) return
    e.preventDefault()
    win.hide()
    if (!readConfig().trayHintShown) {
      writeConfig({ trayHintShown: true })
      notify('Leenax Studio is still running', 'It stays in the tray so notifications keep arriving. Right-click the tray icon to quit.')
    }
  })

  guardNavigation(win.webContents)
  win.webContents.on('did-fail-load', (_e, code, description, url, isMainFrame) => {
    if (!isMainFrame || code === -3) return // -3: the load was replaced by another
    if (url && url.startsWith('file:')) return
    win.loadFile(path.join(__dirname, 'pages', 'offline.html'), { query: { reason: description || String(code), server: serverOrigin() || '' } })
  })
  win.webContents.on('page-title-updated', (e) => {
    e.preventDefault()
    win.setTitle('Leenax Studio')
  })

  openStart()
}

function openStart() {
  const origin = serverOrigin()
  if (origin) win.loadURL(origin + '/')
  else win.loadFile(path.join(__dirname, 'pages', 'setup.html'))
}

/** Pages stay on the server; other links open in the default browser. */
function guardNavigation(contents) {
  contents.on('will-navigate', (e, url) => {
    if (url.startsWith('file:') && contents.getURL().startsWith('file:')) return
    if (sameOrigin(url, serverOrigin())) return
    e.preventDefault()
    if (openableOutside(url)) shell.openExternal(url)
  })
  contents.on('will-redirect', (e, url) => {
    if (!sameOrigin(url, serverOrigin()) && !url.startsWith('file:')) {
      e.preventDefault()
      if (openableOutside(url)) shell.openExternal(url)
    }
  })
  contents.setWindowOpenHandler(({ url }) => {
    if (sameOrigin(url, serverOrigin())) {
      return {
        action: 'allow',
        overrideBrowserWindowOptions: { icon: path.join(ASSETS, 'icon.ico'), autoHideMenuBar: true, webPreferences: secureWebPreferences(undefined) },
      }
    }
    if (openableOutside(url)) shell.openExternal(url)
    return { action: 'deny' }
  })
  contents.on('did-create-window', (child) => guardNavigation(child.webContents))
}

function showWindow() {
  if (!win || win.isDestroyed()) return createWindow()
  if (win.isMinimized()) win.restore()
  win.show()
  win.focus()
}

// ---- tray, badge, notifications ---------------------------------------------------------------------------

function trayMenu() {
  const cfg = readConfig()
  return Menu.buildFromTemplate([
    { label: 'Open Leenax Studio', click: showWindow },
    { label: 'Reload', click: () => { showWindow(); openStart() } },
    { label: 'Change server…', click: () => { showWindow(); win.loadFile(path.join(__dirname, 'pages', 'setup.html')) } },
    { type: 'separator' },
    { label: 'Keep running in the tray when closed', type: 'checkbox', checked: cfg.keepInTray !== false, click: (i) => { writeConfig({ keepInTray: i.checked }); tray.setContextMenu(trayMenu()) } },
    { label: 'Start when I sign in to Windows', type: 'checkbox', checked: app.getLoginItemSettings().openAtLogin, click: (i) => { app.setLoginItemSettings({ openAtLogin: i.checked, args: ['--hidden'] }); tray.setContextMenu(trayMenu()) } },
    { type: 'separator' },
    { label: `Version ${app.getVersion()}`, enabled: false },
    { label: 'Quit Leenax Studio', click: () => { quitting = true; app.quit() } },
  ])
}

function createTray() {
  tray = new Tray(nativeImage.createFromPath(path.join(ASSETS, 'tray.png')))
  tray.setToolTip('Leenax Studio')
  tray.setContextMenu(trayMenu())
  tray.on('click', showWindow)
}

function setUnread(count) {
  if (process.env.LEENAX_DESKTOP_TRACE) console.log('[unread]', count) // tests only
  tray?.setToolTip(count > 0 ? `Leenax Studio: ${count} unread` : 'Leenax Studio')
  if (!win) return
  if (process.platform === 'win32') {
    win.setOverlayIcon(count > 0 ? nativeImage.createFromPath(path.join(ASSETS, 'badge.png')) : null, count > 0 ? `${count} unread` : '')
  } else {
    app.setBadgeCount(count)
  }
}

function notify(title, body, onClick) {
  if (process.env.LEENAX_DESKTOP_TRACE) console.log('[notify]', clip(title, 120)) // tests only
  if (!Notification.isSupported()) return
  const n = new Notification({ title: clip(title, 120), body: clip(body, 240), icon: path.join(ASSETS, 'icon.png'), silent: false })
  if (onClick) n.on('click', onClick)
  n.show()
}

// ---- the bridge (preload.js) ---------------------------------------------------------------------------------

/** Only the server's own pages may use the bridge, and only with plain, short values. */
function fromServer(e) {
  const frame = e.senderFrame
  return !!frame && frame === e.sender.mainFrame && sameOrigin(frame.url, serverOrigin())
}

let recent = []
ipcMain.on('leenax:unread', (e, count) => {
  if (!fromServer(e) || !Number.isInteger(count) || count < 0 || count > 100000) return
  setUnread(count)
})
ipcMain.on('leenax:notify', (e, n) => {
  if (!fromServer(e) || !n || typeof n.id !== 'string' || typeof n.title !== 'string' || n.id.length > 64) return
  const now = Date.now()
  recent = recent.filter((t) => now - t < 60_000)
  if (recent.length >= 10) return // at most ten a minute
  recent.push(now)
  const sender = e.sender
  notify(n.title, typeof n.body === 'string' ? n.body : '', () => {
    showWindow()
    if (!sender.isDestroyed()) sender.send('leenax:open', n.id)
  })
  if (win && !win.isFocused()) win.flashFrame(true)
})

// The setup and offline pages (local files) use these.
function fromLocalPage(e) {
  return !!e.senderFrame && e.senderFrame.url.startsWith('file:')
}
ipcMain.handle('setup:current', (e) => (fromLocalPage(e) ? readConfig().server || '' : ''))
ipcMain.handle('setup:test', async (e, input) => {
  if (!fromLocalPage(e)) return { ok: false, message: 'Not allowed' }
  const p = parseServer(input)
  if (p.error) return { ok: false, message: p.error }
  try {
    const res = await net.fetch(p.origin + '/api/v1/auth/status', { headers: { Accept: 'application/json' }, cache: 'no-store', redirect: 'error' })
    let body = null
    try {
      body = await res.json()
    } catch {
      body = null
    }
    if (!looksLikeLeenax(res.status, res.headers.get('content-type'), body)) {
      return { ok: false, message: `Something answered at ${p.origin}, but it isn’t a Leenax server (status ${res.status}).` }
    }
    return { ok: true, origin: p.origin, local: p.local, setupRequired: body.setupRequired }
  } catch (err) {
    const why = String(err && err.message ? err.message : err)
    return { ok: false, message: /CERT|SSL/i.test(why) ? `The server’s certificate isn’t trusted (${why}). Fix the certificate on the server; this app won’t ignore it.` : `Couldn’t reach ${p.origin} (${why}). Is the server running?` }
  }
})
ipcMain.handle('setup:save', async (e, origin) => {
  if (!fromLocalPage(e)) return false
  const p = parseServer(origin)
  if (p.error) return false
  writeConfig({ server: p.origin })
  // the window's preload learns the address when the window is made, so make it again
  const old = win
  win = null
  createWindow()
  if (old) {
    old.removeAllListeners('close')
    old.destroy()
  }
  return true
})
ipcMain.handle('setup:retry', (e) => {
  if (!fromLocalPage(e)) return
  openStart()
})
ipcMain.handle('setup:change', (e) => {
  if (!fromLocalPage(e)) return
  win.loadFile(path.join(__dirname, 'pages', 'setup.html'))
})

// ---- start ----------------------------------------------------------------------------------------------------

app.whenReady().then(() => {
  const ses = session.fromPartition(PARTITION)
  // the server's pages may show notifications, use the microphone (voice recording) and copy;
  // nothing else (no camera-only, location, USB, serial, HID, MIDI…)
  ses.setPermissionRequestHandler((wc, permission, callback, details) => {
    let origin = null
    try { origin = new URL(details.requestingUrl).origin } catch { origin = null }
    const ok = origin === serverOrigin() && ALLOWED_PERMISSIONS.has(permission)
      && (permission !== 'media' || !(details.mediaTypes || []).includes('video'))
    callback(ok)
  })
  ses.setPermissionCheckHandler((_wc, permission, requestingOrigin) => {
    let origin = null
    try { origin = new URL(requestingOrigin).origin } catch { origin = null }
    return origin === serverOrigin() && ALLOWED_PERMISSIONS.has(permission)
  })
  ses.setDevicePermissionHandler(() => false)
  ses.on('will-download', (_e, item) => {
    item.once('done', (_ev, state) => {
      if (state === 'completed') notify('Download finished', path.basename(item.getSavePath()), () => shell.showItemInFolder(item.getSavePath()))
    })
  })

  Menu.setApplicationMenu(Menu.buildFromTemplate([
    {
      label: 'View',
      submenu: [
        { role: 'reload' }, { role: 'forceReload' },
        { type: 'separator' }, { role: 'resetZoom' }, { role: 'zoomIn' }, { role: 'zoomOut' },
        { type: 'separator' }, { role: 'togglefullscreen' },
        ...(app.isPackaged ? [] : [{ role: 'toggleDevTools' }]),
      ],
    },
    { label: 'Server', submenu: [{ label: 'Change server…', click: () => win && win.loadFile(path.join(__dirname, 'pages', 'setup.html')) }] },
    { label: 'Help', submenu: [{ label: `Leenax Studio ${app.getVersion()}`, enabled: false }] },
  ]))

  createTray()
  // started with Windows: wait in the tray
  createWindow(process.argv.includes('--hidden') && !!serverOrigin())
})

// no window → stay in the tray; quit only from the tray or the menu
app.on('window-all-closed', () => {
  if (quitting || readConfig().keepInTray === false) app.quit()
})
app.on('before-quit', () => { quitting = true })

// a certificate error is never overridden (the default refuses the page)
app.on('certificate-error', (e, _wc, _url, _err, _cert, callback) => {
  e.preventDefault()
  callback(false)
})

