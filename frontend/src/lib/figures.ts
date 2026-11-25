import { api } from './api'

/**
 * Equations (LaTeX, drawn by MathJax) and diagrams (Mermaid), drawn in the browser as SVG.
 * They're shown as <img src="data:…"> so nothing in a drawing can run. The drawing is also
 * saved per project for exports (Word and PDF can't run MathJax or Mermaid). Mermaid's colours
 * come from a <style> block that LibreOffice ignores, so they're copied onto each element first.
 */

export type FigureKind = 'MATH' | 'MERMAID'

const cache = new Map<string, Promise<string>>()

export function drawMath(latex: string, display: boolean): Promise<string> {
  const key = `MATH:${display ? 'D' : 'I'}:${latex}`
  if (!cache.has(key)) cache.set(key, mathSvg(latex, display))
  return cache.get(key)!
}

export function drawMermaid(code: string): Promise<string> {
  const key = `MERMAID:${code}`
  if (!cache.has(key)) {
    const p = mermaidSvg(code)
    p.catch(() => cache.delete(key))
    cache.set(key, p)
  }
  return cache.get(key)!
}

export const svgDataUrl = (svg: string) => `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svg)))}`

// ---- MathJax ------------------------------------------------------------------------------------

type MathDoc = { convert: (tex: string, o: { display: boolean }) => unknown }
let mathjax: Promise<{ doc: MathDoc; adaptor: { innerHTML: (n: unknown) => string } }> | null = null

/** No "html" package: \href, \class and \style can't put links or attributes into a drawing. */
const TEX_PACKAGES = ['base', 'ams', 'newcommand', 'noundefined', 'boldsymbol', 'cancel', 'color', 'mhchem']

function loadMathJax() {
  mathjax ??= (async () => {
    const [{ mathjax: mj }, { TeX }, { SVG }, { liteAdaptor }, { RegisterHTMLHandler }] = await Promise.all([
      import('mathjax-full/js/mathjax.js'),
      import('mathjax-full/js/input/tex.js'),
      import('mathjax-full/js/output/svg.js'),
      import('mathjax-full/js/adaptors/liteAdaptor.js'),
      import('mathjax-full/js/handlers/html.js'),
      import('mathjax-full/js/input/tex/ams/AmsConfiguration.js'),
      import('mathjax-full/js/input/tex/newcommand/NewcommandConfiguration.js'),
      import('mathjax-full/js/input/tex/noundefined/NoUndefinedConfiguration.js'),
      import('mathjax-full/js/input/tex/boldsymbol/BoldsymbolConfiguration.js'),
      import('mathjax-full/js/input/tex/cancel/CancelConfiguration.js'),
      import('mathjax-full/js/input/tex/color/ColorConfiguration.js'),
      import('mathjax-full/js/input/tex/mhchem/MhchemConfiguration.js'),
    ])
    const adaptor = liteAdaptor()
    RegisterHTMLHandler(adaptor)
    const doc = mj.document('', { InputJax: new TeX({ packages: TEX_PACKAGES }), OutputJax: new SVG({ fontCache: 'none' }) }) as MathDoc
    return { doc, adaptor: adaptor as unknown as { innerHTML: (n: unknown) => string } }
  })()
  return mathjax
}

async function mathSvg(latex: string, display: boolean): Promise<string> {
  const { doc, adaptor } = await loadMathJax()
  const svg = adaptor.innerHTML(doc.convert(latex, { display }))
  if (/data-mjx-error|merror/.test(svg)) {
    const msg = svg.match(/data-mjx-error="([^"]*)"/)?.[1]
    throw new Error(msg ? `Equation error: ${msg}` : 'This equation has an error')
  }
  // MathJax draws in currentColor; an <img> has no colour to inherit, so use black.
  return svg.replace(/currentColor/g, '#111111')
}

// ---- Mermaid ------------------------------------------------------------------------------------

let mermaidLib: Promise<typeof import('mermaid').default> | null = null
let mermaidCount = 0

function loadMermaid() {
  mermaidLib ??= import('mermaid').then(({ default: m }) => {
    m.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: 'neutral',
      // Plain SVG text instead of HTML labels, so Word and PDF can show them.
      htmlLabels: false,
      flowchart: { htmlLabels: false },
      fontFamily: 'Noto Sans Tamil, Noto Sans, Arial, sans-serif',
    })
    return m
  })
  return mermaidLib
}

async function mermaidSvg(code: string): Promise<string> {
  const m = await loadMermaid()
  await m.parse(code) // throws a readable error for bad syntax
  const id = `lx-mermaid-${++mermaidCount}`
  const { svg } = await m.render(id, code)
  return inlineStyles(svg)
}

const STYLE_PROPS = ['fill', 'stroke', 'stroke-width', 'stroke-dasharray', 'opacity', 'fill-opacity', 'stroke-opacity', 'font-family', 'font-size', 'font-weight', 'text-anchor']

/** Copies each element's computed colours and fonts onto it, so the drawing needs no <style>. */
export function inlineStyles(svg: string): string {
  if (typeof document === 'undefined' || typeof getComputedStyle === 'undefined') return svg
  const holder = document.createElement('div')
  holder.style.cssText = 'position:absolute;left:-10000px;top:0;visibility:hidden'
  const parsed = new DOMParser().parseFromString(svg, 'image/svg+xml').documentElement
  if (parsed.nodeName.toLowerCase() !== 'svg') return svg
  holder.appendChild(document.importNode(parsed, true))
  document.body.appendChild(holder)
  try {
    const root = holder.querySelector('svg')!
    for (const el of Array.from(root.querySelectorAll('*'))) {
      if (el.closest('style, defs > style, title, desc')) continue
      const cs = getComputedStyle(el)
      for (const p of STYLE_PROPS) {
        const v = cs.getPropertyValue(p)
        if (v && v !== 'normal' && !el.getAttribute(p)) el.setAttribute(p, v)
      }
    }
    // A fixed size: Mermaid writes width="100%", which an <img> or Word can't place.
    const box = root.getAttribute('viewBox')?.split(/[\s,]+/).map(Number)
    if (box?.length === 4 && box.every(Number.isFinite)) {
      root.setAttribute('width', String(Math.round(box[2])))
      root.setAttribute('height', String(Math.round(box[3])))
      root.style.removeProperty('max-width')
    }
    return new XMLSerializer().serializeToString(root)
  } finally {
    holder.remove()
  }
}

// ---- saving drawings for exports -----------------------------------------------------------------

const sent = new Set<string>()

/** Keeps the drawing for exports (once per source per session). Failures don't matter here. */
export function keepFigure(projectId: string | undefined, kind: FigureKind, source: string, svg: string) {
  if (!projectId || !source.trim()) return
  const key = `${projectId}:${kind}:${source.trim()}`
  if (sent.has(key)) return
  sent.add(key)
  api.post(`/projects/${projectId}/figures`, { kind, source: source.trim(), svg }).catch(() => sent.delete(key))
}
