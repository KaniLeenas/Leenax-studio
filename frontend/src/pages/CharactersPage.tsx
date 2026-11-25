import { ImagePlus, Mic, Pencil, Plus, Trash2, UserRound, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { MediaPicker } from '../components/MediaPicker'
import { MediaThumb } from '../components/media'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, Select, Spinner, Textarea } from '../components/ui'
import { useProjects } from '../lib/queries'
import type { Character } from '../lib/story'
import { useCharacters, useDeleteCharacter, useSaveCharacter } from '../lib/storyQueries'
import { useVoiceProfiles } from '../lib/studioQueries'
import type { MediaAsset } from '../lib/types'
import { toast } from '../stores/ui'

const MAX_REFS = 8

export function CharactersPage() {
  const [params, setParams] = useSearchParams()
  const projectId = params.get('project') ?? ''
  const characters = useCharacters(projectId || undefined)
  const projects = useProjects({ page: 0 })
  const [editing, setEditing] = useState<Character | 'new'>()
  const [removing, setRemoving] = useState<Character>()
  const remove = useDeleteCharacter()
  const projectName = (id?: string) => (projects.data?.items ?? []).find((p) => p.id === id)?.name

  return (
    <>
      <header className="flex flex-wrap items-end justify-between gap-4 mb-8">
        <div className="min-w-0">
          <h1 className="text-3xl sm:text-[2.15rem] font-semibold">Character studio</h1>
          <p className="text-muted mt-1 max-w-2xl">
            Describe the people in your stories once. Their look and reference photos go into every scene image and video, and their voice speaks their lines.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor="character-project" className="sr-only">
            Show characters for
          </label>
          <div className="w-60">
          <Select
            id="character-project"
            value={projectId}
            onChange={(e) => {
              const next = new URLSearchParams(params)
              if (e.target.value) next.set('project', e.target.value)
              else next.delete('project')
              setParams(next)
            }}
          >
            <option value="">All characters</option>
            {(projects.data?.items ?? []).map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} and shared
              </option>
            ))}
          </Select>
          </div>
          <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => setEditing('new')}>
            Add character
          </Button>
        </div>
      </header>

      {characters.isPending && <Spinner label="Loading characters" />}
      {characters.error && <ErrorNotice error={characters.error} onRetry={() => characters.refetch()} />}
      {characters.data && characters.data.length === 0 && (
        <EmptyState
          title="No characters yet"
          body="Add the host, the cook, the grandmother: anyone who appears in more than one scene, so they look and sound the same each time."
          action={
            <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => setEditing('new')}>
              Add character
            </Button>
          }
        />
      )}
      {characters.data && characters.data.length > 0 && (
        <ul className="grid sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {characters.data.map((c) => (
            <li key={c.id} className="rounded-2xl border border-line bg-surface overflow-hidden flex flex-col">
              <div className="grid grid-cols-4 gap-0.5 bg-line">
                {c.references.length === 0 ? (
                  <div className="col-span-4 aspect-[4/1] bg-surface-2 grid place-items-center text-faint">
                    <UserRound className="size-8" aria-hidden />
                  </div>
                ) : (
                  c.references.slice(0, 4).map((r, i) => (
                    <MediaThumb key={r.id} asset={r} className={i === 0 && c.references.length === 1 ? 'col-span-4 aspect-[4/1]' : 'aspect-square'} />
                  ))
                )}
              </div>
              <div className="p-4 flex flex-col gap-2 flex-1">
                <div className="flex items-start justify-between gap-2">
                  <h2 className="font-semibold text-lg break-words">{c.name}</h2>
                  <span className="text-xs text-faint shrink-0 mt-1.5">{c.projectId ? projectName(c.projectId) ?? 'Project' : 'Shared'}</span>
                </div>
                {c.appearance && <p className="text-sm line-clamp-3">{c.appearance}</p>}
                {c.clothing && <p className="text-sm text-muted line-clamp-2">Outfit: {c.clothing}</p>}
                <p className="text-sm text-muted flex items-center gap-1.5 mt-auto pt-2">
                  <Mic className="size-4" aria-hidden />
                  {c.voiceName ?? 'No voice yet'}
                </p>
                <div className="flex gap-2 pt-1">
                  <Button size="sm" icon={<Pencil className="size-4" />} onClick={() => setEditing(c)}>
                    Edit
                  </Button>
                  <Button size="sm" variant="ghost" icon={<Trash2 className="size-4" />} onClick={() => setRemoving(c)}>
                    Delete
                  </Button>
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}

      {editing && <CharacterDialog character={editing === 'new' ? undefined : editing} defaultProject={projectId} onClose={() => setEditing(undefined)} />}
      <Dialog
        open={!!removing}
        title={`Delete ${removing?.name ?? 'character'}?`}
        onClose={() => setRemoving(undefined)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setRemoving(undefined)}>
              Keep
            </Button>
            <Button
              variant="danger"
              loading={remove.isPending}
              onClick={() =>
                removing &&
                remove.mutate(removing.id, {
                  onSuccess: () => {
                    toast.success(`Deleted ${removing.name}`)
                    setRemoving(undefined)
                  },
                  onError: (e) => toast.error(e.message),
                })
              }
            >
              Delete character
            </Button>
          </>
        }
      >
        <p className="text-muted">Scenes that show them keep their images and videos. Their reference photos stay in your media library.</p>
      </Dialog>
    </>
  )
}

function CharacterDialog({ character, defaultProject, onClose }: { character?: Character; defaultProject: string; onClose: () => void }) {
  const [name, setName] = useState(character?.name ?? '')
  const [description, setDescription] = useState(character?.description ?? '')
  const [appearance, setAppearance] = useState(character?.appearance ?? '')
  const [clothing, setClothing] = useState(character?.clothing ?? '')
  const [voiceProfileId, setVoiceProfileId] = useState(character?.voiceProfileId ?? '')
  const [projectId, setProjectId] = useState(character ? character.projectId ?? '' : defaultProject)
  const [refs, setRefs] = useState<MediaAsset[]>(character?.references ?? [])
  const [picking, setPicking] = useState(false)
  const profiles = useVoiceProfiles()
  const projects = useProjects({ page: 0 })
  const save = useSaveCharacter()

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) return
    save.mutate(
      {
        id: character?.id,
        name: name.trim(),
        description: description.trim() || undefined,
        appearance: appearance.trim() || undefined,
        clothing: clothing.trim() || undefined,
        voiceProfileId: voiceProfileId || undefined,
        projectId: projectId || undefined,
        referenceMediaIds: refs.map((r) => r.id),
      },
      {
        onSuccess: (c) => {
          toast.success(character ? `Saved ${c.name}` : `Added ${c.name}`)
          onClose()
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  return (
    <Dialog open title={character ? `Edit ${character.name}` : 'Add a character'} onClose={onClose} size="xl">
      <form onSubmit={submit} className="grid md:grid-cols-[minmax(0,1fr)_18rem] gap-6">
        <div className="flex flex-col gap-4">
          <div className="grid sm:grid-cols-2 gap-4">
            <Field label="Name" hint="The name the script uses, so scenes can find them.">
              {(id, d) => <Input id={id} aria-describedby={d} maxLength={120} value={name} onChange={(e) => setName(e.target.value)} required />}
            </Field>
            <Field label="Project" hint="Shared characters appear in every project.">
              {(id, d) => (
                <Select id={id} aria-describedby={d} value={projectId} onChange={(e) => setProjectId(e.target.value)}>
                  <option value="">Shared</option>
                  {(projects.data?.items ?? []).map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
          </div>
          <Field label="Appearance" hint="Face, hair, age, build: the details that must stay the same in every shot.">
            {(id, d) => <Textarea id={id} aria-describedby={d} rows={3} maxLength={2000} value={appearance} onChange={(e) => setAppearance(e.target.value)} placeholder="Woman in her 60s, silver hair in a bun, round glasses, warm smile" />}
          </Field>
          <Field label="Clothing">
            {(id) => <Input id={id} maxLength={1000} value={clothing} onChange={(e) => setClothing(e.target.value)} placeholder="Green cotton saree with a thin gold border" />}
          </Field>
          <Field label="About them (optional)" hint="Personality and role; helps the AI writer, not sent to image models.">
            {(id, d) => <Textarea id={id} aria-describedby={d} rows={2} maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} />}
          </Field>
          <Field label="Voice" hint="Speaks their dialogue lines. Voices come from My voices in Voice & audio.">
            {(id, d) =>
              profiles.isPending ? (
                <Spinner label="Loading voices" />
              ) : (
                <Select id={id} aria-describedby={d} value={voiceProfileId} onChange={(e) => setVoiceProfileId(e.target.value)}>
                  <option value="">No voice</option>
                  {(profiles.data ?? []).map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                      {p.kind === 'CLONED' ? ' (cloned)' : ''}
                    </option>
                  ))}
                </Select>
              )
            }
          </Field>
          {profiles.data?.length === 0 && (
            <p className="text-sm text-muted -mt-2">
              No saved voices yet. <Link to="/create/voice" className="underline">Add one in Voice & audio</Link>.
            </p>
          )}
        </div>
        <div className="flex flex-col gap-3">
          <div>
            <p className="text-sm font-medium">Reference images</p>
            <p className="text-xs text-faint mt-0.5">Up to {MAX_REFS}. The first is sent with every scene image they appear in, for models that accept references.</p>
          </div>
          <ul className="grid grid-cols-2 gap-2">
            {refs.map((r, i) => (
              <li key={r.id} className="relative">
                <MediaThumb asset={r} className="aspect-square rounded-lg" />
                {i === 0 && <span className="absolute left-1.5 bottom-1.5 text-[11px] rounded bg-[#1e2240]/80 text-white px-1.5">Main</span>}
                <button
                  type="button"
                  onClick={() => setRefs((rs) => rs.filter((x) => x.id !== r.id))}
                  className="absolute right-1 top-1 rounded-full bg-surface p-1 text-ink shadow-sm hover:bg-surface-2"
                  aria-label={`Remove ${r.filename}`}
                >
                  <X className="size-3.5" />
                </button>
              </li>
            ))}
            {refs.length < MAX_REFS && (
              <li>
                <button
                  type="button"
                  onClick={() => setPicking(true)}
                  className="aspect-square w-full rounded-lg border border-dashed border-line text-muted hover:text-ink hover:bg-surface-2 grid place-items-center"
                >
                  <span className="flex flex-col items-center gap-1 text-sm">
                    <ImagePlus className="size-5" aria-hidden />
                    Add image
                  </span>
                </button>
              </li>
            )}
          </ul>
          <div className="mt-auto flex justify-end gap-2 pt-4">
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" variant="primary" loading={save.isPending} disabled={!name.trim()}>
              {character ? 'Save character' : 'Add character'}
            </Button>
          </div>
        </div>
      </form>
      <MediaPicker
        open={picking}
        kind="IMAGE"
        title="Choose a reference image"
        onClose={() => setPicking(false)}
        onPick={(m) => {
          setRefs((rs) => (rs.some((r) => r.id === m.id) ? rs : [...rs, m]))
          setPicking(false)
        }}
      />
    </Dialog>
  )
}
