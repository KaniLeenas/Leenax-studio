import { Composition } from 'remotion'
import './fonts'
import { defaultProps, timelineDuration, toFrames } from './timeline'
import { TimelineVideo } from './TimelineVideo'
import type { CompositionProps } from './types'

export const COMPOSITION_ID = 'leenax-timeline'

/** Renderer entry: size, fps and length come from the timeline being rendered. */
export function RemotionRoot() {
  return (
    <Composition
      id={COMPOSITION_ID}
      component={TimelineVideo}
      defaultProps={defaultProps()}
      durationInFrames={30}
      fps={30}
      width={1920}
      height={1080}
      calculateMetadata={({ props }: { props: CompositionProps }) => ({
        width: props.output?.width ?? props.timeline.width,
        height: props.output?.height ?? props.timeline.height,
        fps: props.timeline.fps,
        durationInFrames: Math.max(1, toFrames(timelineDuration(props.timeline), props.timeline.fps)),
      })}
    />
  )
}
