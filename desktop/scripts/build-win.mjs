// Builds the Windows installer: dist/Leenax-Studio-Setup-<version>.exe
//
// On Windows this is the plain electron-builder build. On Linux or macOS electron-builder would
// need Wine twice: to put the icon and version into LeenaxStudio.exe, and to run the half-built
// installer once to get its uninstaller. Here both are done without Wine: the icon and version
// with resedit, and the uninstaller with electron-builder's own reader (the one it uses on macOS).
//
// Signing: unsigned unless CSC_LINK (the .pfx file) and CSC_KEY_PASSWORD are set in the
// environment. Never put the certificate or its password in this folder.
import { createRequire } from 'node:module'
import fs from 'node:fs'
import path from 'node:path'

const require = createRequire(import.meta.url)
const builder = require('electron-builder')
const pkg = JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url)))
const onWindows = process.platform === 'win32'

if (!onWindows) {
  const macos = require('app-builder-lib/out/util/macosVersion')
  macos.isMacOsCatalina = () => true // makes electron-builder read the uninstaller instead of running Wine
}

async function setIconAndVersion(context) {
  if (onWindows) return
  const ResEdit = await import('resedit')
  const exe = path.join(context.appOutDir, `${pkg.build.win.executableName}.exe`)
  const file = ResEdit.NtExecutable.from(fs.readFileSync(exe))
  const res = ResEdit.NtExecutableResource.from(file)
  const ico = ResEdit.Data.IconFile.from(fs.readFileSync(new URL('../installer/icon.ico', import.meta.url)))
  ResEdit.Resource.IconGroupEntry.replaceIconsForResource(res.entries, 1, 1033, ico.icons.map((i) => i.data))
  const info = ResEdit.Resource.VersionInfo.fromEntries(res.entries)[0] ?? ResEdit.Resource.VersionInfo.createEmpty()
  const [a, b, c] = pkg.version.split('.').map(Number)
  info.setFileVersion(a, b, c, 0, 1033)
  info.setProductVersion(a, b, c, 0, 1033)
  info.setStringValues({ lang: 1033, codepage: 1200 }, {
    FileDescription: pkg.productName,
    ProductName: pkg.productName,
    CompanyName: pkg.author,
    LegalCopyright: pkg.build.copyright,
    OriginalFilename: `${pkg.build.win.executableName}.exe`,
    InternalName: pkg.build.win.executableName,
  })
  info.outputToResourceEntries(res.entries)
  res.outputResource(file)
  fs.writeFileSync(exe, Buffer.from(file.generate()))
  console.log(`  • icon and version set without Wine  file=${exe}`)
}

await builder.build({
  targets: builder.Platform.WINDOWS.createTarget('nsis', builder.Arch.x64),
  publish: 'never', // no auto-update server: a new version is installed over the old one
  config: {
    ...pkg.build,
    win: { ...pkg.build.win, signAndEditExecutable: onWindows },
    nsis: { ...pkg.build.nsis, differentialPackage: false },
    publish: null,
    afterPack: setIconAndVersion,
  },
})
