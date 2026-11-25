import { useEffect, useState } from 'react'
import { AbsoluteFill, Audio, continueRender, delayRender, Img, OffthreadVideo, Sequence, useCurrentFrame, useVideoConfig } from 'remotion'
import { lookStyle, motionTransform, textLook, transitionLook } from './effects'
import { fontStack } from './fonts'
import { SceneLayer } from './SceneLayer'
import { fadeEnvelope, toFrames } from './timeline'
import type { CompositionProps, MediaClip, MediaRef, TextClip, Track } from './types'

/** Waits for web fonts before the first frame is captured, so text is never drawn in a fallback font. */
function useFontsReady() {
  const [handle] = useState(() => delayRender('Loading fonts'))
  useEffect(() => {
    let done = false
    const finish = () => {
      if (!done) {
        done = true
        continueRender(handle)
      }
    }
    if (typeof document !== 'undefined' && document.fonts) document.fonts.ready.then(finish, finish)
    else finish()
    const t = setTimeout(finish, 8000)
    return () => clearTimeout(t)
  }, [handle])
}

export function TimelineVideo({ timeline, media }: CompositionProps) {
  useFontsReady()
  const { fps, width, height } = useVideoConfig()
  // Bottom track first so the top track is drawn in front.
  const tracks = [...timeline.tracks].reverse()
  // Lay out at the timeline's own size, then scale to the export size.
  const k = Math.min(width / timeline.width, height / timeline.height)
  return (
    <AbsoluteFill style={{ backgroundColor: timeline.background, overflow: 'hidden' }}>
      <div style={{ position: 'absolute', left: 0, top: 0, width: timeline.width, height: timeline.height, transform: k === 1 ? undefined : `scale(${k})`, transformOrigin: '0 0' }}>
      {tracks.map((track) =>
        track.clips.map((clip) => {
          const from = toFrames(clip.start, fps)
          const dur = Math.max(1, toFrames(clip.start + clip.duration, fps) - from)
          if (clip.type === 'text') {
            if (track.hidden) return null
            return (
              <Sequence key={clip.id} from={from} durationInFrames={dur} name={clip.text.slice(0, 24)}>
                <TextLayer clip={clip} width={timeline.width} height={timeline.height} />
              </Sequence>
            )
          }
          if (clip.type === 'scene') {
            if (track.hidden) return null
            return (
              <Sequence key={clip.id} from={from} durationInFrames={dur} name={clip.scene.title ?? clip.scene.kind}>
                <SceneLayer scene={clip.scene} width={timeline.width} height={timeline.height} media={media} />
              </Sequence>
            )
          }
          const ref = media[clip.mediaId]
          if (!ref) return null
          if (track.hidden && clip.kind !== 'AUDIO' && track.muted) return null
          return (
            <Sequence key={clip.id} from={from} durationInFrames={dur} premountFor={clip.kind === 'VIDEO' ? fps : 0} name={ref.filename ?? clip.kind}>
              <MediaLayer clip={clip} media={ref} track={track} />
            </Sequence>
          )
        }),
      )}
      </div>
    </AbsoluteFill>
  )
}

function MediaLayer({ clip, media, track }: { clip: MediaClip; media: MediaRef; track: Track }) {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()
  const t = frame / fps
  const volume = (f: number) => {
    const v = clip.volume * track.volume * fadeEnvelope(f / fps, clip.duration, clip.fadeIn, clip.fadeOut)
    return Math.max(0, Math.min(2, v))
  }
  const muted = track.muted || clip.volume === 0

  if (clip.kind === 'AUDIO') {
    return <Audio src={media.url} trimBefore={toFrames(clip.trimStart, fps) || undefined} playbackRate={clip.speed} volume={volume} muted={muted} />
  }

  const look = transitionLook(t, clip.duration, clip.transitionIn, clip.transitionOut)
  // Fades on pictures fade to the layers below (or the background).
  look.opacity *= fadeEnvelope(t, clip.duration, clip.fadeIn, clip.fadeOut) * clip.opacity
  const motion = motionTransform(clip.motion, clip.duration > 0 ? t / clip.duration : 0)
  const f = clip.filters
  const filter = [
    f.brightness !== 1 ? `brightness(${f.brightness})` : '',
    f.contrast !== 1 ? `contrast(${f.contrast})` : '',
    f.saturation !== 1 ? `saturate(${f.saturation})` : '',
    f.blur > 0 ? `blur(${f.blur}px)` : '',
  ]
    .filter(Boolean)
    .join(' ')
  const placement = `translate(${clip.x * 50}%, ${clip.y * 50}%) scale(${clip.scale}) rotate(${clip.rotation}deg)`
  const mediaStyle: React.CSSProperties = { width: '100%', height: '100%', objectFit: clip.fit, filter: filter || undefined }

  return (
    <AbsoluteFill style={{ ...lookStyle(look), visibility: track.hidden ? 'hidden' : undefined }}>
      <AbsoluteFill style={{ transform: placement }}>
        <AbsoluteFill style={{ transform: motion.join(' ') || undefined }}>
          {clip.kind === 'VIDEO' ? (
            <OffthreadVideo
              src={media.url}
              trimBefore={toFrames(clip.trimStart, fps) || undefined}
              playbackRate={clip.speed}
              volume={volume}
              muted={muted}
              style={mediaStyle}
              pauseWhenBuffering
            />
          ) : (
            <Img src={media.url} style={mediaStyle} />
          )}
        </AbsoluteFill>
      </AbsoluteFill>
    </AbsoluteFill>
  )
}

function TextLayer({ clip, width, height }: { clip: TextClip; width: number; height: number }) {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()
  const t = frame / fps
  const look = textLook(t, clip.duration, clip.animationIn, clip.animationOut, clip.text.length)
  const s = clip.style
  const text = look.visibleChars !== undefined ? clip.text.slice(0, look.visibleChars) : clip.text
  const boxWidth = s.width * width
  return (
    <AbsoluteFill>
      <div
        style={{
          position: 'absolute',
          left: clip.x * width - boxWidth / 2,
          top: clip.y * height,
          width: boxWidth,
          transform: `translateY(-50%) ${look.transform.join(' ')}`,
          opacity: look.opacity,
          display: 'flex',
          justifyContent: s.align === 'left' ? 'flex-start' : s.align === 'right' ? 'flex-end' : 'center',
        }}
      >
        <span
          style={{
            fontFamily: fontStack(s.fontFamily),
            fontSize: s.fontSize,
            fontWeight: s.fontWeight,
            color: s.color,
            lineHeight: 1.18,
            textAlign: s.align,
            whiteSpace: 'pre-wrap',
            WebkitTextStroke: s.strokeWidth > 0 ? `${s.strokeWidth * 2}px ${s.stroke}` : undefined,
            paintOrder: 'stroke fill',
            textShadow: s.shadow ? `0 ${s.fontSize * 0.05}px ${s.fontSize * 0.18}px rgba(0,0,0,0.6)` : undefined,
            background: s.background || undefined,
            padding: s.background ? `${s.fontSize * 0.12}px ${s.fontSize * 0.3}px` : undefined,
            borderRadius: s.background ? s.fontSize * 0.14 : undefined,
            boxDecorationBreak: 'clone',
            WebkitBoxDecorationBreak: 'clone',
          }}
        >
          {text}
        </span>
      </div>
    </AbsoluteFill>
  )
}
