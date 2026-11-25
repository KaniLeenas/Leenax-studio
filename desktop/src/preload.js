'use strict'
// Runs before each page, sandboxed. The server's own pages get `leenaxDesktop` (badge count and
// Windows notifications); the app's local setup and offline pages get `leenaxSetup`. Any other
// page gets nothing. Neither carries credentials.

const { contextBridge, ipcRenderer } = require('electron')

const arg = (name) => {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`))
  return hit ? hit.slice(name.length + 3) : ''
}
const serverOrigin = arg('leenax-origin')

if (serverOrigin && location.origin === serverOrigin) {
  contextBridge.exposeInMainWorld('leenaxDesktop', {
    version: arg('leenax-version'),
    setUnread: (count) => ipcRenderer.send('leenax:unread', Number(count)),
    notify: (n) => ipcRenderer.send('leenax:notify', { id: String(n?.id ?? ''), title: String(n?.title ?? ''), body: n?.body == null ? '' : String(n.body) }),
    onOpen: (handler) => {
      const listener = (_e, id) => handler(String(id))
      ipcRenderer.on('leenax:open', listener)
      return () => ipcRenderer.removeListener('leenax:open', listener)
    },
  })
} else if (location.protocol === 'file:') {
  contextBridge.exposeInMainWorld('leenaxSetup', {
    current: () => ipcRenderer.invoke('setup:current'),
    test: (address) => ipcRenderer.invoke('setup:test', String(address)),
    save: (origin) => ipcRenderer.invoke('setup:save', String(origin)),
    retry: () => ipcRenderer.invoke('setup:retry'),
    change: () => ipcRenderer.invoke('setup:change'),
  })
}
