import { BarChart3, BookOpen, Compass, ChevronLeft, Clapperboard, ClipboardList, ListChecks, ListTree, NotebookPen, Pencil, Presentation, Search, ShieldCheck, Trash2, Upload } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { MediaGrid } from '../components/MediaGrid'
import { UploadZone } from '../components/media'
import { ProjectMembersPanel } from '../components/ProjectMembers'
import { canEditProject, canOwnProject, roleLabel } from '../lib/people'
import { EditProjectDialog } from '../components/ProjectDialogs'
import { ProjectWorkflow } from '../components/ProjectWorkflow'
import { Button, Dialog, EmptyState, ErrorNotice, Spinner, StatusPill } from '../components/ui'
import { formatRelative, LANGUAGE_LABEL, RATIO_LABEL, STATUS_LABEL } from '../lib/format'
import { useDeleteProject, useMedia, useProject } from '../lib/queries'
import { toast } from '../stores/ui'

export function ProjectDetailPage() {
  const { id = '' } = useParams()
  const project = useProject(id)
  const media = useMedia({ projectId: id })
  const [editOpen, setEditOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const remove = useDeleteProject()
  const navigate = useNavigate()

  if (project.isPending) return <Spinner label="Loading project" />
  if (project.error) {
    return (
      <>
        <BackLink />
        <ErrorNotice error={project.error} onRetry={() => project.refetch()} />
      </>
    )
  }
  const p = project.data

  return (
    <>
      <BackLink />
      <header className="flex flex-wrap items-start justify-between gap-4 mb-8">
        <div className="min-w-0 max-w-2xl">
          <div className="flex items-center gap-3 mb-2">
            <StatusPill tone={p.status === 'IN_PROGRESS' ? 'progress' : p.status === 'COMPLETED' ? 'done' : 'neutral'}>
              {STATUS_LABEL[p.status]}
            </StatusPill>
            <span className="text-sm text-faint">Updated {formatRelative(p.updatedAt)}</span>
          </div>
          <h1 className="text-3xl sm:text-[2.15rem] font-semibold break-words">{p.name}</h1>
          <p className="text-muted mt-1">
            {p.kind === 'EDUCATION'
              ? [p.subject?.moduleCode, p.subject?.subjectName, p.subject?.academicYear, p.subject?.semester].filter(Boolean).join(', ') || 'University subject'
              : `${RATIO_LABEL[p.aspectRatio]}, ${LANGUAGE_LABEL[p.language]}`}
          </p>
          {p.description && <p className="mt-3 whitespace-pre-line">{p.description}</p>}
        </div>
        <div className="flex flex-col items-end gap-2">
          <div className="flex flex-wrap gap-2">
            <Link
              to={`/projects/${p.id}/documents`}
              className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors"
            >
              <BookOpen className="size-4" aria-hidden />
              Documents
            </Link>
            {p.kind === 'EDUCATION' && (
              <>
                <Link to={`/projects/${p.id}/syllabus`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors">
                  <ListTree className="size-4" aria-hidden />
                  Syllabus
                </Link>
                <Link to={`/projects/${p.id}/topics`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors">
                  <ListChecks className="size-4" aria-hidden />
                  Topics
                </Link>
                <Link to={`/projects/${p.id}/analysis`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors">
                  <Compass className="size-4" aria-hidden />
                  Topic analysis
                </Link>
                <Link to={`/projects/${p.id}/materials`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors">
                  <NotebookPen className="size-4" aria-hidden />
                  Notes and questions
                </Link>
                <Link to={`/projects/${p.id}/qa`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors">
                  <ShieldCheck className="size-4" aria-hidden />
                  Academic QA
                </Link>
                <Link to={`/projects/${p.id}/seo`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors">
                  <Search className="size-4" aria-hidden />
                  YouTube SEO
                </Link>
                <Link to={`/projects/${p.id}/slides`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors">
                  <Presentation className="size-4" aria-hidden />
                  Presentations
                </Link>
                <Link to={`/projects/${p.id}/videos`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors">
                  <Clapperboard className="size-4" aria-hidden />
                  Lesson and quiz videos
                </Link>
                <Link to={`/projects/${p.id}/youtube`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors">
                  <Upload className="size-4" aria-hidden />
                  Publish to YouTube
                </Link>
                <Link to={`/projects/${p.id}/youtube/analytics`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors">
                  <BarChart3 className="size-4" aria-hidden />
                  YouTube analytics
                </Link>
                <Link to={`/projects/${p.id}/quizzes`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors">
                  <ClipboardList className="size-4" aria-hidden />
                  Quizzes
                </Link>
              </>
            )}
            {canEditProject(p.myRole) && (
              <Button icon={<Pencil className="size-4" />} onClick={() => setEditOpen(true)}>
                Edit
              </Button>
            )}
            {canOwnProject(p.myRole) && (
              <Button variant="ghost" icon={<Trash2 className="size-4" />} onClick={() => setDeleteOpen(true)}>
                Delete
              </Button>
            )}
          </div>
          <p className="text-sm text-muted">
            Your role: <span className="text-ink font-medium">{roleLabel(p.myRole)}</span>
          </p>
        </div>
      </header>

      <section className="mb-10" aria-labelledby="workflow-heading">
        <h2 id="workflow-heading" className="text-xl font-semibold mb-4">
          Workflow
        </h2>
        <ProjectWorkflow projectId={p.id} />
      </section>

      <section className="mb-10" aria-labelledby="members-heading">
        <h2 id="members-heading" className="text-xl font-semibold mb-4">
          People
        </h2>
        <ProjectMembersPanel project={p} onLeft={() => navigate('/projects')} />
      </section>

      {canEditProject(p.myRole) && (
        <section className="mb-10">
          <h2 className="text-xl font-semibold mb-4">Add media</h2>
          <UploadZone projectId={p.id} compact />
        </section>
      )}

      <section>
        <h2 className="text-xl font-semibold mb-4">
          Project media <span className="text-faint font-normal tabular">({media.data?.totalItems ?? p.mediaCount})</span>
        </h2>
        {media.isPending && <Spinner label="Loading media" />}
        {media.error && <ErrorNotice error={media.error} onRetry={() => media.refetch()} />}
        {media.data &&
          (media.data.items.length === 0 ? (
            <EmptyState title="Nothing here yet" body="Upload footage, images or audio above, or move files in from the media library." />
          ) : (
            <MediaGrid items={media.data.items} />
          ))}
      </section>

      <EditProjectDialog project={p} open={editOpen} onClose={() => setEditOpen(false)} />
      <Dialog
        open={deleteOpen}
        title="Delete this project?"
        onClose={() => setDeleteOpen(false)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setDeleteOpen(false)}>
              Keep project
            </Button>
            <Button
              variant="danger"
              loading={remove.isPending}
              onClick={() =>
                remove.mutate(p.id, {
                  onSuccess: () => {
                    toast.success(`Deleted ${p.name}`)
                    navigate('/projects')
                  },
                  onError: (e) => toast.error(e.message),
                })
              }
            >
              Delete project
            </Button>
          </>
        }
      >
        <p className="text-muted">
          “{p.name}” will be removed. Its {p.mediaCount} {p.mediaCount === 1 ? 'file stays' : 'files stay'} in your media library.
        </p>
      </Dialog>
    </>
  )
}

function BackLink() {
  return (
    <Link to="/projects" className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
      <ChevronLeft className="size-4" aria-hidden />
      My projects
    </Link>
  )
}

export function NotFoundPage() {
  return <EmptyState title="Page not found" body="That address doesn't match anything in your studio." />
}
