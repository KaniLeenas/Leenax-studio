import { Player, type PlayerRef } from '@remotion/player'
import { Pause, Play, SkipBack, SkipForward } from 'lucide-react'
import { forwardRef, useEffect, useImperativeHandle, useRef, type RefObject } from 'react'
import { timelineDuration, toFrames } from '../remotion/timeline'
import { TimelineVideo } from '../remotion/TimelineVideo'
import type { MediaRef, TimelineDoc } from '../remotion/types'
import { formatTime, usePlayheadTime, usePlaying, type Playhead } from './playhead'

export interface PreviewHandle {
  seek: (seconds: number) => void
  toggle: () => void
  pause: () => void
}

/** Remotion Player: the same composition the renderer exports, so the preview matches the file. */
export const Preview = forwardRef<PreviewHandle, { doc: TimelineDoc; media: Record<string, MediaRef>; playhead: Playhead }>(function Preview(
  { doc, media, playhead },
  ref,
) {
  const player = useRef<PlayerRef>(null)
  const duration = timelineDuration(doc)
  const frames = Math.max(1, toFrames(duration, doc.fps))

  useImperativeHandle(ref, () => ({
    seek: (s) => player.current?.seekTo(Math.min(frames - 1, toFrames(s, doc.fps))),
    toggle: () => player.current?.toggle(),
    pause: () => player.current?.pause(),
  }))

  useEffect(() => {
    const p = player.current
    if (!p) return
    const onFrame = (e: { detail: { frame: number } }) => playhead.set(e.detail.frame / doc.fps)
    const onPlay = () => playhead.setPlaying(true)
    const onPause = () => playhead.setPlaying(false)
    p.addEventListener('frameupdate', onFrame)
    p.addEventListener('seeked', onFrame)
    p.addEventListener('play', onPlay)
    p.addEventListener('pause', onPause)
    p.addEventListener('ended', onPause)
    return () => {
      p.removeEventListener('frameupdate', onFrame)
      p.removeEventListener('seeked', onFrame)
      p.removeEventListener('play', onPlay)
      p.removeEventListener('pause', onPause)
      p.removeEventListener('ended', onPause)
    }
  }, [playhead, doc.fps])

  return (
    <div className="flex flex-col gap-2 min-h-0 h-full">
      <div className="flex-1 min-h-0 flex items-center justify-center bg-[#0b0d18] rounded-xl overflow-hidden">
        <Player
          ref={player}
          component={TimelineVideo}
          inputProps={{ timeline: doc, media }}
          durationInFrames={frames}
          fps={doc.fps}
          compositionWidth={doc.width}
          compositionHeight={doc.height}
          style={{ width: '100%', height: '100%' }}
          controls={false}
          clickToPlay
          doubleClickToFullscreen
          spaceKeyToPlayOrPause={false}
          numberOfSharedAudioTags={12}
          acknowledgeRemotionLicense
          errorFallback={({ error }: { error: Error }) => <p className="text-white text-sm p-4">Preview error: {error.message}</p>}
        />
      </div>
      <Transport player={player} playhead={playhead} duration={duration} fps={doc.fps} />
    </div>
  )
})

function Transport({ player, playhead, duration, fps }: { player: RefObject<PlayerRef | null>; playhead: Playhead; duration: number; fps: number }) {
  const t = usePlayheadTime(playhead)
  const playing = usePlaying(playhead)
  return (
    <div className="flex items-center justify-center gap-2">
      <button type="button" aria-label="Go to start" className="p-1.5 rounded hover:bg-surface-2" onClick={() => player.current?.seekTo(0)}>
        <SkipBack className="size-4" />
      </button>
      <button
        type="button"
        aria-label={playing ? 'Pause' : 'Play'}
        className="size-10 rounded-full bg-marigold-500 text-[#1e2240] flex items-center justify-center hover:bg-marigold-400"
        onClick={() => player.current?.toggle()}
      >
        {playing ? <Pause className="size-5" /> : <Play className="size-5 ml-0.5" />}
      </button>
      <button type="button" aria-label="Go to end" className="p-1.5 rounded hover:bg-surface-2" onClick={() => player.current?.seekTo(Math.max(0, Math.round(duration * fps) - 1))}>
        <SkipForward className="size-4" />
      </button>
      <span className="tabular text-sm text-muted w-36 text-center" aria-live="off">
        {formatTime(t)} / {formatTime(duration)}
      </span>
    </div>
  )
}
