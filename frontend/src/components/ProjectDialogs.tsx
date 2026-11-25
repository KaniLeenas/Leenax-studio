import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { LANGUAGE_LABEL, RATIO_LABEL, STATUS_LABEL } from '../lib/format'
import { useSimilarProjects } from '../lib/people'
import { useCreateProject, useUpdateProject } from '../lib/queries'
import type { AspectRatio, Language, Project, ProjectKind, ProjectStatus, ProjectSubject } from '../lib/types'
import { toast } from '../stores/ui'
import { Button, Dialog, Field, Input, Select, Textarea } from './ui'

interface FormState {
  name: string
  description: string
  aspectRatio: AspectRatio
  language: Language
  status: ProjectStatus
  kind: ProjectKind
  subject: Required<ProjectSubject>
}

const NO_SUBJECT: Required<ProjectSubject> = { subjectName: '', moduleCode: '', academicYear: '', semester: '', university: '', department: '' }

/** Only the subject fields with something in them. */
function subjectOf(f: FormState): ProjectSubject | undefined {
  if (f.kind !== 'EDUCATION') return undefined
  return Object.fromEntries(Object.entries(f.subject).map(([k, v]) => [k, v.trim() || undefined])) as ProjectSubject
}

const SUBJECT_FIELDS: { key: keyof ProjectSubject; label: string; max: number; placeholder: string }[] = [
  { key: 'subjectName', label: 'Subject', max: 200, placeholder: 'Database Management Systems' },
  { key: 'moduleCode', label: 'Module code', max: 40, placeholder: 'IT2040' },
  { key: 'academicYear', label: 'Academic year', max: 20, placeholder: '2026/27' },
  { key: 'semester', label: 'Semester', max: 20, placeholder: 'Semester 1' },
  { key: 'university', label: 'University', max: 200, placeholder: '' },
  { key: 'department', label: 'Department', max: 200, placeholder: '' },
]

function ProjectFields({ form, setForm, showStatus }: { form: FormState; setForm: (f: FormState) => void; showStatus?: boolean }) {
  return (
    <div className="flex flex-col gap-4">
      <div role="radiogroup" aria-label="Kind of project" className="grid grid-cols-2 gap-1 rounded-xl bg-surface-2 p-1 text-sm">
        {(['CREATOR', 'EDUCATION'] as const).map((k) => (
          <button
            key={k}
            type="button"
            role="radio"
            aria-checked={form.kind === k}
            onClick={() => setForm({ ...form, kind: k })}
            className={form.kind === k ? 'rounded-lg py-2 font-medium bg-surface shadow-sm text-ink' : 'rounded-lg py-2 font-medium text-muted hover:text-ink'}
          >
            {k === 'CREATOR' ? 'Creator video' : 'University subject'}
          </button>
        ))}
      </div>
      <Field label="Project name">
        {(id) => (
          <Input id={id} autoFocus value={form.name} maxLength={160} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        )}
      </Field>
      <Field label="Notes" hint="Optional. Topic, hook, anything you want to remember.">
        {(id, d) => (
          <Textarea
            id={id}
            aria-describedby={d}
            value={form.description}
            maxLength={5000}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
          />
        )}
      </Field>
      <div className="grid sm:grid-cols-2 gap-4">
        <Field label="Format">
          {(id) => (
            <Select id={id} value={form.aspectRatio} onChange={(e) => setForm({ ...form, aspectRatio: e.target.value as AspectRatio })}>
              {Object.entries(RATIO_LABEL).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Language">
          {(id) => (
            <Select id={id} value={form.language} onChange={(e) => setForm({ ...form, language: e.target.value as Language })}>
              {Object.entries(LANGUAGE_LABEL).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>
      {form.kind === 'EDUCATION' && (
        <fieldset className="grid sm:grid-cols-2 gap-4">
          <legend className="text-sm font-medium mb-1 sm:col-span-2">Subject details (optional)</legend>
          {SUBJECT_FIELDS.map((f) => (
            <Field key={f.key} label={f.label}>
              {(id) => (
                <Input
                  id={id}
                  maxLength={f.max}
                  placeholder={f.placeholder}
                  value={form.subject[f.key]}
                  onChange={(e) => setForm({ ...form, subject: { ...form.subject, [f.key]: e.target.value } })}
                />
              )}
            </Field>
          ))}
        </fieldset>
      )}
      {showStatus && (
        <Field label="Status">
          {(id) => (
            <Select id={id} value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value as ProjectStatus })}>
              {Object.entries(STATUS_LABEL).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </Select>
          )}
        </Field>
      )}
    </div>
  )
}

const EMPTY: FormState = { name: '', description: '', aspectRatio: 'LANDSCAPE', language: 'EN', status: 'DRAFT', kind: 'CREATOR', subject: NO_SUBJECT }

/** "Is this subject already here?" Same module code or same name, among the projects you can see. */
function SimilarWarning({ form, exceptId }: { form: FormState; exceptId?: string }) {
  const similar = useSimilarProjects(form.name, form.kind === 'EDUCATION' ? form.subject.moduleCode : '')
  const list = (similar.data ?? []).filter((p) => p.id !== exceptId)
  if (list.length === 0) return null
  return (
    <div role="status" className="mt-4 rounded-xl border border-marigold-500/50 bg-marigold-500/10 p-3 text-sm">
      <p className="font-medium">This may already exist</p>
      <ul className="mt-1 text-muted">
        {list.map((p) => (
          <li key={p.id}>
            <Link to={`/projects/${p.id}`} className="underline hover:text-ink">
              {p.name}
            </Link>
            {p.moduleCode ? ` (${p.moduleCode}${p.academicYear ? `, ${p.academicYear}` : ''})` : ''}: {p.reason.toLowerCase()}
          </li>
        ))}
      </ul>
      <p className="mt-1 text-muted">Add new documents to it instead, so material from one subject stays together.</p>
    </div>
  )
}

export function NewProjectDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [form, setForm] = useState<FormState>(EMPTY)
  const create = useCreateProject()
  const navigate = useNavigate()

  useEffect(() => {
    if (open) setForm(EMPTY)
  }, [open])

  function submit(e: FormEvent) {
    e.preventDefault()
    create.mutate(
      {
        name: form.name.trim(),
        description: form.description,
        aspectRatio: form.aspectRatio,
        language: form.language,
        kind: form.kind,
        subject: subjectOf(form),
      },
      {
        onSuccess: (p) => {
          toast.success(`Created ${p.name}`)
          onClose()
          navigate(`/projects/${p.id}`)
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  return (
    <Dialog open={open} title="New project" onClose={onClose}>
      <form onSubmit={submit}>
        <ProjectFields form={form} setForm={setForm} />
        <SimilarWarning form={form} />
        <div className="mt-6 flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={create.isPending} disabled={!form.name.trim()}>
            Create project
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

export function EditProjectDialog({ project, open, onClose }: { project: Project; open: boolean; onClose: () => void }) {
  const [form, setForm] = useState<FormState>(EMPTY)
  const update = useUpdateProject(project.id)

  useEffect(() => {
    if (open)
      setForm({
        name: project.name,
        description: project.description ?? '',
        aspectRatio: project.aspectRatio,
        language: project.language,
        status: project.status,
        kind: project.kind ?? 'CREATOR',
        subject: { ...NO_SUBJECT, ...Object.fromEntries(Object.entries(project.subject ?? {}).map(([k, v]) => [k, v ?? ''])) },
      })
  }, [open, project])

  function submit(e: FormEvent) {
    e.preventDefault()
    update.mutate(
      {
        name: form.name.trim(),
        description: form.description,
        aspectRatio: form.aspectRatio,
        language: form.language,
        status: form.status,
        kind: form.kind,
        subject: subjectOf(form),
      },
      {
        onSuccess: () => {
          toast.success('Project saved')
          onClose()
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  return (
    <Dialog open={open} title="Edit project" onClose={onClose}>
      <form onSubmit={submit}>
        <ProjectFields form={form} setForm={setForm} showStatus />
        <div className="mt-6 flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={update.isPending} disabled={!form.name.trim()}>
            Save project
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
