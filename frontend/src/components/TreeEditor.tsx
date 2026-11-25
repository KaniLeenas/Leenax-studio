import { AlertTriangle, CheckCircle2, CircleHelp, MoreHorizontal, Plus } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { cn } from '../lib/cn'
import {
  addChild,
  addSibling,
  canIndent,
  canOutdent,
  DIFFICULTY_LABEL,
  dropMissingPrerequisites,
  flatten,
  indent,
  LEVEL_NAME,
  MAX_DEPTH,
  mergeWithNext,
  move,
  newId,
  outdent,
  remove,
  rename,
  sourceHref,
  sourceLabel,
  split,
  update,
  type Difficulty,
  type DocumentRef,
  type Source,
  type Topic,
  type TreeItem,
} from '../lib/syllabus'
import { Button, Dialog, Select } from './ui'

type Item = TreeItem & Partial<Topic> & { sources?: Source[] }

/**
 * An outline of chapters, topics and subtopics (three levels). With {@code topics}, each
 * row also has "include", difficulty and prerequisites. Every change goes through onChange;
 * nothing is saved here.
 */
export function TreeEditor<T extends Item>({
  items,
  onChange,
  editable,
  topics,
  docs,
  emptyText,
}: {
  items: T[]
  onChange: (items: T[]) => void
  editable: boolean
  topics?: boolean
  docs: DocumentRef[]
  emptyText: string
}) {
  const rows = flatten(items)
  const [menu, setMenu] = useState<string | null>(null)
  const [prereqFor, setPrereqFor] = useState<string | null>(null)
  const set = (next: T[]) => onChange(topics ? (dropMissingPrerequisites(next as Topic[]) as T[]) : next)
  const fresh = (title: string) => ({ id: newId(), title, children: [], ...(topics ? { selected: true, prerequisites: [], syllabusItemIds: [] } : { sources: [] }) }) as unknown as T
  const titleOf = new Map(rows.map((r) => [r.item.id, r.item.title]))

  if (rows.length === 0 && !editable) return <p className="text-muted">{emptyText}</p>

  return (
    <div>
      {rows.length === 0 && <p className="text-muted mb-3">{emptyText}</p>}
      <ul className="divide-y divide-line border-y border-line">
        {rows.map(({ item, depth }) => {
          const level = LEVEL_NAME[depth - 1]
          const excluded = topics && item.selected === false
          return (
            <li key={item.id} className={cn('py-2 flex flex-wrap items-start gap-x-3 gap-y-1', excluded && 'opacity-60')} style={{ paddingLeft: `${(depth - 1) * 1.75}rem` }}>
              {topics && (
                <input
                  type="checkbox"
                  className="mt-2.5 size-4 accent-marigold-600"
                  checked={item.selected !== false}
                  disabled={!editable}
                  aria-label={`Include ${item.title}`}
                  onChange={(e) => set(update(items, item.id, (n) => ({ ...n, selected: e.target.checked })))}
                />
              )}
              <div className="min-w-0 flex-1 basis-60">
                {editable ? (
                  <input
                    value={item.title}
                    aria-label={`${level} name`}
                    maxLength={300}
                    onChange={(e) => set(rename(items, item.id, e.target.value))}
                    className={cn(
                      'w-full bg-transparent rounded-md px-2 py-1.5 -mx-2 border border-transparent hover:border-line focus:border-marigold-500 focus:outline-none',
                      depth === 1 && 'font-semibold',
                    )}
                  />
                ) : (
                  <p className={cn('py-1.5', depth === 1 && 'font-semibold')}>{item.title}</p>
                )}
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  {item.notInSyllabus && (
                    <span className="inline-flex items-center gap-1 text-sm text-marigold-700 dark:text-marigold-300">
                      <AlertTriangle className="size-3.5" aria-hidden />
                      No longer in the syllabus
                    </span>
                  )}
                  {(item.sources ?? []).map((s, i) => (
                    <SourceChip key={i} source={s} docs={docs} />
                  ))}
                  {topics && (item.prerequisites ?? []).length > 0 && (
                    <span className="text-sm text-muted">Needs {(item.prerequisites ?? []).map((p) => titleOf.get(p) ?? '?').join(', ')}</span>
                  )}
                </div>
              </div>
              {topics && (
                <div className="flex items-center gap-2">
                  <Select
                    aria-label={`Difficulty of ${item.title}`}
                    className="h-9 w-40"
                    value={item.difficulty ?? ''}
                    disabled={!editable}
                    onChange={(e) => set(update(items, item.id, (n) => ({ ...n, difficulty: (e.target.value || null) as Difficulty | null })))}
                  >
                    <option value="">Difficulty</option>
                    {Object.entries(DIFFICULTY_LABEL).map(([k, v]) => (
                      <option key={k} value={k}>
                        {v}
                      </option>
                    ))}
                  </Select>
                  {editable && (
                    <Button size="sm" variant="ghost" onClick={() => setPrereqFor(item.id)}>
                      Prerequisites
                    </Button>
                  )}
                </div>
              )}
              {editable && (
                <RowMenu
                  label={item.title}
                  open={menu === item.id}
                  setOpen={(o) => setMenu(o ? item.id : null)}
                  actions={[
                    { label: `Add ${level.toLowerCase()} below`, run: () => set(addSibling(items, item.id, fresh(`New ${level.toLowerCase()}`))) },
                    ...(depth < MAX_DEPTH ? [{ label: `Add ${LEVEL_NAME[depth].toLowerCase()} inside`, run: () => set(addChild(items, item.id, fresh(`New ${LEVEL_NAME[depth].toLowerCase()}`))) }] : []),
                    { label: 'Move up', run: () => set(move(items, item.id, -1)) },
                    { label: 'Move down', run: () => set(move(items, item.id, 1)) },
                    ...(canIndent(items, item.id) ? [{ label: 'Move into the item above', run: () => set(indent(items, item.id)) }] : []),
                    ...(canOutdent(items, item.id) ? [{ label: 'Move out a level', run: () => set(outdent(items, item.id)) }] : []),
                    { label: 'Merge with the next', run: () => set(mergeWithNext(items, item.id)) },
                    { label: 'Split in two', run: () => set(split(items, item.id)) },
                    { label: 'Remove', run: () => set(remove(items, item.id)), danger: true },
                  ]}
                />
              )}
            </li>
          )
        })}
      </ul>
      {editable && (
        <Button className="mt-3" size="sm" variant="ghost" icon={<Plus className="size-4" />} onClick={() => set(addSibling(items, null, fresh(topics ? 'New topic' : 'New chapter')))}>
          {topics ? 'Add a topic' : 'Add a chapter'}
        </Button>
      )}
      {prereqFor && (
        <PrerequisiteDialog
          topics={items as Topic[]}
          id={prereqFor}
          onClose={() => setPrereqFor(null)}
          onSave={(ids) => {
            set(update(items, prereqFor, (n) => ({ ...n, prerequisites: ids })))
            setPrereqFor(null)
          }}
        />
      )}
    </div>
  )
}

export function SourceChip({ source, docs }: { source: Source; docs: DocumentRef[] }) {
  const found = source.found === true
  return (
    <Link
      to={sourceHref(source)}
      title={source.quote ? `“${source.quote}”${found ? '' : ' (these words weren’t found on that page)'}` : undefined}
      className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink underline-offset-2 hover:underline"
    >
      {found ? (
        <CheckCircle2 className="size-3.5 text-emerald-600 dark:text-emerald-400" aria-label="Words found on the page" />
      ) : (
        <CircleHelp className="size-3.5 text-marigold-600 dark:text-marigold-400" aria-label="Words not found on the page" />
      )}
      {sourceLabel(source, docs)}
    </Link>
  )
}

function RowMenu({ label, open, setOpen, actions }: { label: string; open: boolean; setOpen: (o: boolean) => void; actions: { label: string; run: () => void; danger?: boolean }[] }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent | KeyboardEvent) => {
      if (e instanceof KeyboardEvent ? e.key === 'Escape' : !ref.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', close)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', close)
    }
  }, [open, setOpen])
  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        aria-label={`Actions for ${label}`}
        aria-expanded={open}
        aria-haspopup="menu"
        onClick={() => setOpen(!open)}
        className="mt-1 rounded-md p-1.5 text-faint hover:text-ink hover:bg-surface-2"
      >
        <MoreHorizontal className="size-4" />
      </button>
      {open && (
        <ul role="menu" className="absolute right-0 z-20 mt-1 w-60 rounded-xl border border-line bg-surface shadow-lg py-1">
          {actions.map((a) => (
            <li key={a.label} role="none">
              <button
                type="button"
                role="menuitem"
                className={cn('w-full text-left px-3 py-1.5 text-sm hover:bg-surface-2', a.danger && 'text-rose-signal')}
                onClick={() => {
                  a.run()
                  setOpen(false)
                }}
              >
                {a.label}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function PrerequisiteDialog({ topics, id, onClose, onSave }: { topics: Topic[]; id: string; onClose: () => void; onSave: (ids: string[]) => void }) {
  const all = flatten(topics)
  const me = all.find((r) => r.item.id === id)?.item
  const [chosen, setChosen] = useState<string[]>(me?.prerequisites ?? [])
  if (!me) return null
  return (
    <Dialog
      open
      title={`What should come before “${me.title}”?`}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" onClick={() => onSave(chosen)}>
            Save prerequisites
          </Button>
        </>
      }
    >
      <ul className="max-h-80 overflow-y-auto space-y-1">
        {all
          .filter((r) => r.item.id !== id)
          .map(({ item, depth }) => (
            <li key={item.id} style={{ paddingLeft: `${(depth - 1) * 1.25}rem` }}>
              <label className="flex items-center gap-2 py-1">
                <input
                  type="checkbox"
                  className="size-4 accent-marigold-600"
                  checked={chosen.includes(item.id)}
                  onChange={(e) => setChosen((c) => (e.target.checked ? [...c, item.id] : c.filter((x) => x !== item.id)))}
                />
                <span>{item.title}</span>
              </label>
            </li>
          ))}
      </ul>
    </Dialog>
  )
}
