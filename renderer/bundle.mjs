// Bundles the shared timeline composition once (at image build time) so renders start fast.
import { bundle } from '@remotion/bundler'
import path from 'node:path'

const entry = path.resolve(process.env.COMPOSITION_ENTRY ?? './composition/entry.ts')
const outDir = path.resolve(process.env.BUNDLE_DIR ?? './bundle')
const serveUrl = await bundle({ entryPoint: entry, outDir, enableCaching: false })
console.log(`Bundled ${entry} → ${serveUrl}`)
