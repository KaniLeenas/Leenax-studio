import { Check } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useTimelines } from '../lib/editorQueries'
import { cn } from '../lib/cn'
import { usePosts } from '../lib/publishQueries'
import { useMedia } from '../lib/queries'
import { workflowSteps } from '../lib/story'
import { useCharacters, useScripts, useStoryboards } from '../lib/storyQueries'
import { useDesigns } from '../lib/studioQueries'
import { Spinner } from './ui'

/** Where the project stands, from idea to published post, with a link to each step. */
export function ProjectWorkflow({ projectId }: { projectId: string }) {
  const scripts = useScripts(projectId)
  const boards = useStoryboards(projectId)
  const characters = useCharacters(projectId)
  const timelines = useTimelines(projectId)
  const videos = useMedia({ projectId, kind: 'VIDEO' })
  const designs = useDesigns(false)
  const posts = usePosts()

  if (scripts.isPending || boards.isPending) return <Spinner label="Checking progress" />

  const projectPosts = (posts.data ?? []).filter((p) => p.media?.projectId === projectId)
  const steps = workflowSteps(projectId, {
    scripts: scripts.data?.length ?? 0,
    storyboards: boards.data ?? [],
    characters: (characters.data ?? []).filter((c) => c.projectId === projectId).length,
    timelines: timelines.data?.totalItems ?? 0,
    renders: (videos.data?.items ?? []).filter((m) => m.source === 'RENDER').length,
    thumbnails: (designs.data ?? []).filter((d) => d.projectId === projectId).length,
    posts: projectPosts.length,
    published: projectPosts.filter((p) => p.status === 'PUBLISHED' || p.status === 'PARTIAL').length,
  })
  const next = steps.find((s) => !s.done && s.id !== 'characters')

  return (
    <ol className="grid sm:grid-cols-2 lg:grid-cols-4 2xl:grid-cols-8 gap-2">
      {steps.map((s, i) => (
        <li key={s.id}>
          <Link
            to={s.to}
            aria-current={next?.id === s.id ? 'step' : undefined}
            className={cn(
              'h-full flex flex-col gap-1 rounded-xl border p-3 hover:bg-surface-2',
              next?.id === s.id ? 'border-marigold-500 bg-marigold-500/8' : 'border-line bg-surface',
            )}
          >
            <span className="flex items-center gap-2 text-sm font-medium">
              <span
                className={cn(
                  'size-5 shrink-0 rounded-full grid place-items-center text-[11px] tabular',
                  s.done ? 'bg-peacock-500 text-white' : 'border border-line text-muted',
                )}
                aria-hidden
              >
                {s.done ? <Check className="size-3" /> : i + 1}
              </span>
              {s.label}
              <span className="sr-only">{s.done ? ' (done)' : next?.id === s.id ? ' (next)' : ''}</span>
            </span>
            <span className="text-xs text-muted">{s.detail}</span>
          </Link>
        </li>
      ))}
    </ol>
  )
}
