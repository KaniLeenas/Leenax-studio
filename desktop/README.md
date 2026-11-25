# Leenax Studio for Windows

A Windows app that opens your Leenax server in its own window. It is the same studio as in the
browser, with:

- its own window and taskbar icon, remembered size and position;
- a **tray icon**: closing the window keeps it running so notifications still arrive (turn this off in the tray menu); "Start when I sign in to Windows" is in the same menu;
- **Windows notifications** for new Leenax notifications (review requests, decisions, finished or failed AI jobs, renders, posts); clicking one opens its page. A dot on the taskbar icon shows there are unread ones;
- a **"Can't reach your Leenax server"** page that tries again by itself every 15 seconds, and "Change server…" in the menu;
- downloads (PDF, Word, PowerPoint exports) saved where you choose, with a notification when done.

The server still runs as before (Docker on your PC, or online). All five people share it.

## Install

Run `Leenax-Studio-Setup-1.0.0.exe`. It installs for the current Windows user (no administrator
needed), with Start-menu and desktop shortcuts.

The installer isn't signed yet, so Windows SmartScreen says "Windows protected your PC". Choose
**More info → Run anyway** once. Install the same version over the old one to update; your server
address and sign-in are kept.

On first start, type the server address:

- on the PC running Docker: `http://localhost:8088` (the `WEB_PORT` in `.env`);
- on your local network: `http://192.168.x.x:8088` (only if you set `WEB_BIND=0.0.0.0`);
- on the internet: `https://studio.your-domain` (plain `http://` is refused there).

The app checks that a Leenax server answers before it saves the address.

## Security

- The server's pages run sandboxed, with no access to Node.js or your files. They get only a tiny bridge for the notification count and Windows notifications, and only on your server's address.
- Sign-in uses the server's HttpOnly session cookie, kept inside the app. The app stores **no passwords, API keys or tokens**; `config.json` (in `%APPDATA%\Leenax Studio`) holds only the server address and window settings.
- Pages stay on your server. Any other link (YouTube, help pages) opens in your normal browser; `file:` and other kinds of link are blocked.
- Permissions: notifications, the microphone (voice recording) and copying to the clipboard, for your server only. Camera, location, USB and the rest are refused.
- A certificate error is never overridden: fix the certificate on the server.
- No automatic updates and no telemetry.

## Build it

Needs Node.js 22.

```
cd desktop
npm ci
npm test            # address and link rules
npm run dist:win    # → dist/Leenax-Studio-Setup-<version>.exe
```

On Windows this is a normal electron-builder build. On Linux or macOS the script sets the icon and
version and reads the uninstaller without Wine.

**Signing later:** buy a code-signing certificate, then set `CSC_LINK` (path to the .pfx) and
`CSC_KEY_PASSWORD` in the environment before `npm run dist:win`. Don't put the certificate or its
password in this folder.

To try it against a server without installing: `npm start`.
