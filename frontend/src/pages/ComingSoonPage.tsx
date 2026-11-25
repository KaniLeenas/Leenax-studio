import { Link } from 'react-router-dom'
import { CURRENT_PHASE, type NavItem } from '../lib/navigation'

const PHASE_NAME: Record<number, string> = {
  2: 'AI providers and video generation',
  3: 'Voice, subtitles, images and thumbnails',
  4: 'Professional video editor',
  5: 'AI automatic editing',
  6: 'Social media publishing',
  7: 'Analytics, hardening and deployment',
}

export function ComingSoonPage({ item }: { item: NavItem }) {
  const Icon = item.icon
  return (
    <div className="max-w-xl">
      <div className="inline-flex rounded-2xl bg-surface-2 p-4 text-muted mb-6">
        <Icon className="size-8" aria-hidden />
      </div>
      <h1 className="text-3xl font-semibold">{item.label}</h1>
      <p className="text-muted mt-2 text-lg">{item.summary}</p>
      <div className="mt-8 rounded-2xl border border-line bg-surface p-5">
        <p className="font-medium">Arrives in phase {item.phase}</p>
        <p className="text-muted text-sm mt-1">{PHASE_NAME[item.phase]}</p>
        <div className="mt-4 flex gap-1" aria-hidden>
          {Array.from({ length: 7 }, (_, i) => (
            <span
              key={i}
              className={
                i < CURRENT_PHASE ? 'h-1.5 flex-1 rounded-full bg-peacock-500' : i + 1 === item.phase ? 'h-1.5 flex-1 rounded-full bg-marigold-500' : 'h-1.5 flex-1 rounded-full bg-track'
              }
            />
          ))}
        </div>
        <p className="text-xs text-faint mt-2">Phases 1 to {CURRENT_PHASE} are live. This section is marked on the track.</p>
      </div>
      <p className="mt-6 text-sm text-muted">
        Meanwhile, you can collect footage in the{' '}
        <Link to="/media" className="text-ink underline underline-offset-4">
          media library
        </Link>
        .
      </p>
    </div>
  )
}
