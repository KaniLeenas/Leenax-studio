import { mergeAttributes, Node, type Editor, type JSONContent } from '@tiptap/core'
import { EditorContent, NodeViewWrapper, ReactNodeViewRenderer, useEditor, type NodeViewProps } from '@tiptap/react'
import { Subscript } from '@tiptap/extension-subscript'
import { Superscript } from '@tiptap/extension-superscript'
import { TableKit } from '@tiptap/extension-table'
import StarterKit from '@tiptap/starter-kit'
import {
  Bold, Code, Code2, Heading2, Heading3, Heading4, ImagePlus, Italic, Link2, List, ListOrdered, MessageSquarePlus, Minus, Network,
  Quote, Redo2, Sigma, Strikethrough, Subscript as SubIcon, Superscript as SupIcon, Table2, Underline, Undo2,
} from 'lucide-react'
import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { uploadFile } from '../lib/api'
import { cn } from '../lib/cn'
import { drawMath, drawMermaid, keepFigure, svgDataUrl, type FigureKind } from '../lib/figures'
import type { MediaAsset } from '../lib/types'
import { toast } from '../stores/ui'
import { Button, Dialog, Field, Input, Textarea } from './ui'

/** What the drawings in the editor need: which project to keep them for, and whether this person may. */
const FigureContext = createContext<{ projectId?: string; keep: boolean }>({ keep: false })

// ---- custom blocks ------------------------------------------------------------------------------

function figureNode(name: string, attr: 'latex' | 'code', inline: boolean, kind: FigureKind) {
  return Node.create({
    name,
    group: inline ? 'inline' : 'block',
    inline,
    atom: true,
    selectable: true,
    draggable: !inline,
    addAttributes() {
      return { [attr]: { default: '' } }
    },
    parseHTML() {
      return [{ tag: `${inline ? 'span' : 'div'}[data-${name}]` }]
    },
    renderHTML({ HTMLAttributes }) {
      return [inline ? 'span' : 'div', mergeAttributes(HTMLAttributes, { [`data-${name}`]: '' })]
    },
    addNodeView() {
      return ReactNodeViewRenderer((props: NodeViewProps) => <FigureView {...props} attr={attr} inline={inline} kind={kind} />)
    },
  })
}

const MathBlock = figureNode('mathBlock', 'latex', false, 'MATH')
const MathInline = figureNode('mathInline', 'latex', true, 'MATH')
const Mermaid = figureNode('mermaid', 'code', false, 'MERMAID')

const MediaImage = Node.create({
  name: 'mediaImage',
  group: 'block',
  atom: true,
  draggable: true,
  addAttributes() {
    return { assetId: { default: null }, alt: { default: '' } }
  },
  parseHTML() {
    return [{ tag: 'figure[data-media-image]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['figure', mergeAttributes(HTMLAttributes, { 'data-media-image': '' })]
  },
  addNodeView() {
    return ReactNodeViewRenderer(ImageView)
  },
})

function useDrawing(kind: FigureKind, source: string, display: boolean) {
  const [state, setState] = useState<{ svg?: string; error?: string }>({})
  useEffect(() => {
    let live = true
    if (!source.trim()) {
      setState({})
      return
    }
    const p = kind === 'MATH' ? drawMath(source, display) : drawMermaid(source)
    p.then((svg) => live && setState({ svg }), (e) => live && setState({ error: e instanceof Error ? e.message : 'Can’t draw this' }))
    return () => {
      live = false
    }
  }, [kind, source, display])
  return state
}

function Drawing({ kind, source, inline }: { kind: FigureKind; source: string; inline: boolean }) {
  const { projectId, keep } = useContext(FigureContext)
  const { svg, error } = useDrawing(kind, source, !inline)
  useEffect(() => {
    if (svg && keep) keepFigure(projectId, kind, source, svg)
  }, [svg, keep, projectId, kind, source])
  if (!source.trim()) return <span className="text-faint">{kind === 'MATH' ? 'Empty equation' : 'Empty diagram'}</span>
  if (error) return <span className="text-rose-signal text-sm">{error}</span>
  if (!svg) return <span className="text-faint text-sm">Drawing…</span>
  return <img src={svgDataUrl(svg)} alt={source} className={inline ? 'inline align-middle max-h-[2.6em]' : 'mx-auto max-w-full'} />
}

function FigureView({ node, updateAttributes, editor, selected, attr, inline, kind }: NodeViewProps & { attr: 'latex' | 'code'; inline: boolean; kind: FigureKind }) {
  const source = String(node.attrs[attr] ?? '')
  const [open, setOpen] = useState(false)
  const Tag = inline ? 'span' : 'div'
  return (
    <NodeViewWrapper as={Tag} className={cn(inline ? 'inline-block px-0.5' : 'my-4 text-center', selected && 'ring-2 ring-marigold-500 rounded')}>
      <Tag
        role={editor.isEditable ? 'button' : undefined}
        tabIndex={editor.isEditable ? 0 : undefined}
        aria-label={editor.isEditable ? `Edit ${kind === 'MATH' ? 'equation' : 'diagram'}` : undefined}
        onDoubleClick={() => editor.isEditable && setOpen(true)}
        onKeyDown={(e) => editor.isEditable && e.key === 'Enter' && setOpen(true)}
        className={editor.isEditable ? 'cursor-pointer' : undefined}
      >
        <Drawing kind={kind} source={source} inline={inline} />
      </Tag>
      {open && (
        <FigureDialog
          kind={kind}
          inline={inline}
          initial={source}
          onClose={() => setOpen(false)}
          onSave={(v) => {
            updateAttributes({ [attr]: v })
            setOpen(false)
          }}
        />
      )}
    </NodeViewWrapper>
  )
}

function FigureDialog({ kind, inline, initial, onClose, onSave }: { kind: FigureKind; inline: boolean; initial: string; onClose: () => void; onSave: (v: string) => void }) {
  const [value, setValue] = useState(initial)
  const [preview, setPreview] = useState(initial)
  useEffect(() => {
    const t = setTimeout(() => setPreview(value), 400)
    return () => clearTimeout(t)
  }, [value])
  const math = kind === 'MATH'
  return (
    <Dialog
      open
      title={math ? 'Equation' : 'Diagram'}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" disabled={!value.trim()} onClick={() => onSave(value.trim())}>
            {math ? 'Save equation' : 'Save diagram'}
          </Button>
        </>
      }
    >
      <Field label={math ? 'LaTeX' : 'Mermaid'} hint={math ? 'For example: \\frac{a}{b} or E = mc^2' : 'For example: flowchart TD; A[Table] --> B[1NF]'}>
        {(id, hid) => (
          <Textarea id={id} aria-describedby={hid} rows={math ? 3 : 8} className="font-mono text-sm" value={value} onChange={(e) => setValue(e.target.value)} spellCheck={false} />
        )}
      </Field>
      <div className="mt-4 rounded-xl border border-line px-3 py-4 text-center bg-white min-h-16" aria-live="polite">
        <Drawing kind={kind} source={preview} inline={inline} />
      </div>
    </Dialog>
  )
}

function ImageView({ node, updateAttributes, editor, selected }: NodeViewProps) {
  const id = String(node.attrs.assetId ?? '')
  const alt = String(node.attrs.alt ?? '')
  return (
    <NodeViewWrapper as="figure" className={cn('my-4 text-center', selected && 'ring-2 ring-marigold-500 rounded')}>
      <img src={`/api/v1/media/${id}/content`} alt={alt} className="mx-auto max-w-full max-h-[28rem] rounded" />
      {editor.isEditable ? (
        <input
          aria-label="Image description"
          className="mt-1 w-full text-center text-sm text-muted bg-transparent border border-transparent hover:border-line rounded px-2 py-1"
          placeholder="Describe the image"
          value={alt}
          maxLength={300}
          onChange={(e) => updateAttributes({ alt: e.target.value })}
        />
      ) : (
        alt && <figcaption className="text-sm text-muted mt-1">{alt}</figcaption>
      )}
    </NodeViewWrapper>
  )
}

// ---- the editor ---------------------------------------------------------------------------------

export const NOTE_EXTENSIONS = [
  StarterKit.configure({
    heading: { levels: [1, 2, 3, 4] },
    link: { openOnClick: false, autolink: true, protocols: ['http', 'https', 'mailto'], isAllowedUri: (url) => /^(https?:\/\/|mailto:)/i.test(url) },
  }),
  TableKit.configure({ table: { resizable: false } }),
  Subscript,
  Superscript,
  MathBlock,
  MathInline,
  Mermaid,
  MediaImage,
]

export function NotesEditor({
  content,
  editable,
  onChange,
  projectId,
  keepFigures,
  onComment,
  onReady,
}: {
  content: JSONContent
  editable: boolean
  onChange: (doc: JSONContent) => void
  projectId?: string
  keepFigures: boolean
  /** Shown when set: comment on the selected text. */
  onComment?: (quote: string) => void
  onReady?: (editor: Editor) => void
}) {
  const changed = useRef(onChange)
  changed.current = onChange
  const editor = useEditor({
    extensions: NOTE_EXTENSIONS,
    content,
    editable,
    immediatelyRender: true,
    editorProps: { attributes: { class: 'notes-prose focus:outline-none min-h-[24rem] px-6 py-5', 'aria-label': 'Notes' } },
    onUpdate: ({ editor: e, transaction }) => {
      if (transaction.docChanged) changed.current(e.getJSON())
    },
  })
  useEffect(() => {
    editor?.setEditable(editable)
  }, [editor, editable])
  useEffect(() => {
    if (editor && onReady) onReady(editor)
  }, [editor, onReady])

  return (
    <FigureContext.Provider value={{ projectId, keep: keepFigures }}>
      <div className="rounded-2xl border border-line bg-surface">
        {editor && (editable || onComment) && <Toolbar editor={editor} editable={editable} projectId={projectId} onComment={onComment} />}
        <EditorContent editor={editor} />
      </div>
    </FigureContext.Provider>
  )
}

/** Puts the cursor on the first place the quoted words appear, if they still do. */
export function findQuote(editor: Editor | null, quote: string) {
  if (!editor || !quote) return false
  const needle = quote.slice(0, 60)
  let found = -1
  editor.state.doc.descendants((n, pos) => {
    if (found >= 0 || !n.isText || !n.text) return found < 0
    const i = n.text.indexOf(needle)
    if (i >= 0) found = pos + i
    return found < 0
  })
  if (found < 0) return false
  editor.chain().focus().setTextSelection({ from: found, to: found + needle.length }).scrollIntoView().run()
  return true
}

function Toolbar({ editor, editable, projectId, onComment }: { editor: Editor; editable: boolean; projectId?: string; onComment?: (q: string) => void }) {
  const [, force] = useState(0)
  useEffect(() => {
    const update = () => force((n) => n + 1)
    editor.on('selectionUpdate', update)
    editor.on('transaction', update)
    return () => {
      editor.off('selectionUpdate', update)
      editor.off('transaction', update)
    }
  }, [editor])
  const [figure, setFigure] = useState<null | 'mathBlock' | 'mathInline' | 'mermaid'>(null)
  const [linkOpen, setLinkOpen] = useState(false)
  const file = useRef<HTMLInputElement>(null)
  const c = () => editor.chain().focus()
  const inTable = editor.isActive('table')

  async function addImage(f: File) {
    if (!f.type.startsWith('image/')) return toast.error('Choose an image (PNG, JPG, GIF or WebP)')
    try {
      const asset = await uploadFile<MediaAsset>(projectId ? `/media?projectId=${encodeURIComponent(projectId)}` : '/media', f, () => {})
      c().insertContent({ type: 'mediaImage', attrs: { assetId: asset.id, alt: f.name.replace(/\.[^.]+$/, '') } }).run()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Upload failed')
    }
  }

  const selected = editor.state.doc.textBetween(editor.state.selection.from, editor.state.selection.to, ' ').trim()

  return (
    <div role="toolbar" aria-label="Formatting" className="sticky top-0 z-10 flex flex-wrap items-center gap-0.5 border-b border-line bg-surface/95 backdrop-blur px-2 py-1.5 rounded-t-2xl">
      {editable && (
        <>
          <Tool label="Undo" icon={<Undo2 />} onClick={() => c().undo().run()} disabled={!editor.can().undo()} />
          <Tool label="Redo" icon={<Redo2 />} onClick={() => c().redo().run()} disabled={!editor.can().redo()} />
          <Sep />
          <Tool label="Section heading" icon={<Heading2 />} active={editor.isActive('heading', { level: 2 })} onClick={() => c().toggleHeading({ level: 2 }).run()} />
          <Tool label="Subheading" icon={<Heading3 />} active={editor.isActive('heading', { level: 3 })} onClick={() => c().toggleHeading({ level: 3 }).run()} />
          <Tool label="Small heading" icon={<Heading4 />} active={editor.isActive('heading', { level: 4 })} onClick={() => c().toggleHeading({ level: 4 }).run()} />
          <Sep />
          <Tool label="Bold" icon={<Bold />} active={editor.isActive('bold')} onClick={() => c().toggleBold().run()} />
          <Tool label="Italic" icon={<Italic />} active={editor.isActive('italic')} onClick={() => c().toggleItalic().run()} />
          <Tool label="Underline" icon={<Underline />} active={editor.isActive('underline')} onClick={() => c().toggleUnderline().run()} />
          <Tool label="Strikethrough" icon={<Strikethrough />} active={editor.isActive('strike')} onClick={() => c().toggleStrike().run()} />
          <Tool label="Inline code" icon={<Code />} active={editor.isActive('code')} onClick={() => c().toggleCode().run()} />
          <Tool label="Subscript" icon={<SubIcon />} active={editor.isActive('subscript')} onClick={() => c().toggleSubscript().run()} />
          <Tool label="Superscript" icon={<SupIcon />} active={editor.isActive('superscript')} onClick={() => c().toggleSuperscript().run()} />
          <Tool label="Link" icon={<Link2 />} active={editor.isActive('link')} onClick={() => setLinkOpen(true)} />
          <Sep />
          <Tool label="Bulleted list" icon={<List />} active={editor.isActive('bulletList')} onClick={() => c().toggleBulletList().run()} />
          <Tool label="Numbered list" icon={<ListOrdered />} active={editor.isActive('orderedList')} onClick={() => c().toggleOrderedList().run()} />
          <Tool label="Quote" icon={<Quote />} active={editor.isActive('blockquote')} onClick={() => c().toggleBlockquote().run()} />
          <Tool label="Code block" icon={<Code2 />} active={editor.isActive('codeBlock')} onClick={() => c().toggleCodeBlock().run()} />
          <Tool label="Divider" icon={<Minus />} onClick={() => c().setHorizontalRule().run()} />
          <Sep />
          <Tool label="Table" icon={<Table2 />} active={inTable} onClick={() => c().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()} disabled={inTable} />
          <Tool label="Equation" icon={<Sigma />} onClick={() => setFigure('mathBlock')} />
          <Tool label="Inline equation" icon={<span className="text-[0.8rem] font-semibold italic">x²</span>} onClick={() => setFigure('mathInline')} />
          <Tool label="Diagram" icon={<Network />} onClick={() => setFigure('mermaid')} />
          <Tool label="Image" icon={<ImagePlus />} onClick={() => file.current?.click()} />
          <input
            ref={file}
            type="file"
            accept="image/png,image/jpeg,image/gif,image/webp"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0]
              if (f) addImage(f)
              e.target.value = ''
            }}
          />
          {inTable && (
            <>
              <Sep />
              <TextTool onClick={() => c().addRowAfter().run()}>Row +</TextTool>
              <TextTool onClick={() => c().deleteRow().run()}>Row −</TextTool>
              <TextTool onClick={() => c().addColumnAfter().run()}>Column +</TextTool>
              <TextTool onClick={() => c().deleteColumn().run()}>Column −</TextTool>
              <TextTool onClick={() => c().deleteTable().run()}>Delete table</TextTool>
            </>
          )}
        </>
      )}
      {onComment && (
        <>
          {editable && <Sep />}
          <Button size="sm" variant="ghost" icon={<MessageSquarePlus className="size-4" />} disabled={!selected} onClick={() => onComment(selected.slice(0, 500))}>
            Comment on selection
          </Button>
        </>
      )}
      {figure && (
        <FigureDialog
          kind={figure === 'mermaid' ? 'MERMAID' : 'MATH'}
          inline={figure === 'mathInline'}
          initial=""
          onClose={() => setFigure(null)}
          onSave={(v) => {
            c().insertContent({ type: figure, attrs: figure === 'mermaid' ? { code: v } : { latex: v } }).run()
            setFigure(null)
          }}
        />
      )}
      {linkOpen && <LinkDialog editor={editor} onClose={() => setLinkOpen(false)} />}
    </div>
  )
}

function LinkDialog({ editor, onClose }: { editor: Editor; onClose: () => void }) {
  const [href, setHref] = useState(String(editor.getAttributes('link').href ?? 'https://'))
  const ok = /^(https?:\/\/[^\s]+|mailto:[^\s]+)$/i.test(href.trim())
  return (
    <Dialog
      open
      title="Link"
      onClose={onClose}
      footer={
        <>
          {editor.isActive('link') && (
            <Button variant="ghost" onClick={() => { editor.chain().focus().unsetLink().run(); onClose() }}>
              Remove link
            </Button>
          )}
          <Button variant="primary" disabled={!ok} onClick={() => { editor.chain().focus().extendMarkRange('link').setLink({ href: href.trim() }).run(); onClose() }}>
            Save link
          </Button>
        </>
      }
    >
      <Field label="Web or email address" hint="Starts with https:// or mailto:">
        {(id, hid) => <Input id={id} aria-describedby={hid} value={href} onChange={(e) => setHref(e.target.value)} />}
      </Field>
    </Dialog>
  )
}

function Tool({ label, icon, onClick, active, disabled }: { label: string; icon: ReactNode; onClick: () => void; active?: boolean; disabled?: boolean }) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      aria-pressed={active}
      disabled={disabled}
      onMouseDown={(e) => e.preventDefault()}
      onClick={onClick}
      className={cn(
        'grid place-items-center size-8 rounded-md text-muted hover:text-ink hover:bg-surface-2 disabled:opacity-40 [&_svg]:size-4',
        active && 'bg-surface-2 text-ink',
      )}
    >
      {icon}
    </button>
  )
}

function TextTool({ children, onClick }: { children: string; onClick: () => void }) {
  return (
    <button type="button" onMouseDown={(e) => e.preventDefault()} onClick={onClick} className="px-2 h-8 rounded-md text-sm text-muted hover:text-ink hover:bg-surface-2">
      {children}
    </button>
  )
}

function Sep() {
  return <span className="mx-1 h-5 w-px bg-line" aria-hidden />
}
