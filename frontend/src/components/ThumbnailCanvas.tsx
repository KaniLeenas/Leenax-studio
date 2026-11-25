import type Konva from 'konva'
import { forwardRef, useEffect, useRef, useState } from 'react'
import { Arrow, Ellipse, Group, Image as KImage, Label, Layer as KLayer, Rect, Stage, Tag, Text, Transformer } from 'react-konva'
import { mediaContentUrl } from '../lib/studioQueries'
import { fontFor } from '../lib/thumbnails'
import type { Layer, ThumbnailDocument } from '../lib/types'

/** Loads library images through our own origin so the canvas can be exported. */
const imageCache = new Map<string, HTMLImageElement>()

export function loadMediaImage(mediaId: string): Promise<HTMLImageElement> {
  const cached = imageCache.get(mediaId)
  if (cached?.complete && cached.naturalWidth > 0) return Promise.resolve(cached)
  return new Promise((resolve, reject) => {
    const img = cached ?? new window.Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('Image could not be loaded'))
    if (!cached) {
      img.src = mediaContentUrl(mediaId)
      imageCache.set(mediaId, img)
    }
  })
}

function useMediaImage(mediaId: string) {
  const [img, setImg] = useState<HTMLImageElement>()
  useEffect(() => {
    let live = true
    loadMediaImage(mediaId).then((i) => live && setImg(i), () => live && setImg(undefined))
    return () => {
      live = false
    }
  }, [mediaId])
  return img
}

function ImageNode({ layer }: { layer: Extract<Layer, { type: 'image' }> }) {
  const img = useMediaImage(layer.mediaId)
  return (
    <>
      {img ? (
        <KImage
          image={img}
          width={layer.width}
          height={layer.height}
          cornerRadius={layer.cornerRadius}
          shadowEnabled={layer.shadow}
          shadowColor="#000"
          shadowBlur={Math.max(layer.width, layer.height) * 0.04}
          shadowOpacity={0.55}
          shadowOffset={{ x: 0, y: 6 }}
        />
      ) : (
        <Rect width={layer.width} height={layer.height} fill="rgba(255,255,255,0.08)" cornerRadius={layer.cornerRadius} />
      )}
      {layer.strokeWidth > 0 && (
        <Rect width={layer.width} height={layer.height} stroke={layer.stroke} strokeWidth={layer.strokeWidth} cornerRadius={layer.cornerRadius} listening={false} />
      )}
    </>
  )
}

function LayerContent({ layer }: { layer: Layer }) {
  switch (layer.type) {
    case 'text': {
      const text = (
        <Text
          text={layer.text}
          fontFamily={fontFor(layer)}
          fontSize={layer.fontSize}
          fontStyle={layer.fontStyle}
          fill={layer.fill}
          stroke={layer.strokeWidth > 0 ? layer.stroke : undefined}
          strokeWidth={layer.strokeWidth * 2}
          fillAfterStrokeEnabled
          lineJoin="round"
          align={layer.align}
          width={layer.width}
          lineHeight={layer.lineHeight}
          letterSpacing={layer.letterSpacing}
          padding={layer.background ? layer.padding : 0}
          shadowEnabled={layer.shadow}
          shadowColor="#000"
          shadowBlur={layer.fontSize * 0.25}
          shadowOpacity={0.6}
          shadowOffset={{ x: 0, y: layer.fontSize * 0.06 }}
        />
      )
      return layer.background ? (
        <Label>
          <Tag fill={layer.background} cornerRadius={layer.padding * 0.6} />
          {text}
        </Label>
      ) : (
        text
      )
    }
    case 'image':
      return <ImageNode layer={layer} />
    case 'rect':
      return <Rect width={layer.width} height={layer.height} fill={layer.fill} stroke={layer.stroke} strokeWidth={layer.strokeWidth} cornerRadius={layer.cornerRadius} />
    case 'circle':
      return (
        <Ellipse
          x={layer.width / 2}
          y={layer.height / 2}
          radiusX={layer.width / 2}
          radiusY={layer.height / 2}
          fill={layer.fill}
          stroke={layer.stroke}
          strokeWidth={layer.strokeWidth}
        />
      )
    case 'arrow':
      return (
        <Arrow
          points={[0, layer.height / 2, layer.width, layer.height / 2]}
          pointerLength={layer.height}
          pointerWidth={layer.height * 1.6}
          strokeWidth={layer.height * 0.42}
          fill={layer.fill}
          stroke={layer.fill}
          lineCap="round"
          shadowEnabled
          shadowColor="#000"
          shadowBlur={layer.height * 0.3}
          shadowOpacity={0.5}
        />
      )
  }
}

export interface CanvasProps {
  doc: ThumbnailDocument
  width: number
  height: number
  /** Display scale (stage pixels per design pixel). */
  scale: number
  selectedId?: string
  onSelect?: (id?: string) => void
  onChange?: (id: string, patch: Partial<Layer>) => void
  /** Bumped when web fonts finish loading so text is re-measured. */
  fontsVersion?: number
}

/** Renders a design. Interactive when onSelect/onChange are given; read-only otherwise (previews). */
export const ThumbnailCanvas = forwardRef<Konva.Stage, CanvasProps>(function ThumbnailCanvas(
  { doc, width, height, scale, selectedId, onSelect, onChange, fontsVersion = 0 },
  ref,
) {
  const transformer = useRef<Konva.Transformer>(null)
  const layerRef = useRef<Konva.Layer>(null)
  const interactive = !!onChange
  const selected = doc.layers.find((l) => l.id === selectedId)

  useEffect(() => {
    const tr = transformer.current
    if (!tr) return
    const node = selected && !selected.locked && !selected.hidden ? layerRef.current?.findOne(`#${selected.id}`) : undefined
    tr.nodes(node ? [node] : [])
    tr.getLayer()?.batchDraw()
  }, [selected, doc, fontsVersion])

  useEffect(() => {
    layerRef.current?.batchDraw()
  }, [fontsVersion])

  return (
    <Stage
      ref={ref}
      width={Math.round(width * scale)}
      height={Math.round(height * scale)}
      scaleX={scale}
      scaleY={scale}
      onMouseDown={(e) => {
        if (interactive && (e.target === e.target.getStage() || e.target.name() === 'background')) onSelect?.(undefined)
      }}
      onTouchStart={(e) => {
        if (interactive && (e.target === e.target.getStage() || e.target.name() === 'background')) onSelect?.(undefined)
      }}
    >
      <KLayer ref={layerRef}>
        <Rect name="background" width={width} height={height} fill={doc.background} />
        {doc.layers.map((l) =>
          l.hidden ? null : (
            <Group
              key={`${l.id}-${fontsVersion}`}
              id={l.id}
              x={l.x}
              y={l.y}
              rotation={l.rotation}
              opacity={l.opacity}
              draggable={interactive && !l.locked}
              listening={interactive && !l.locked}
              onMouseDown={() => onSelect?.(l.id)}
              onTap={() => onSelect?.(l.id)}
              onDragEnd={(e) => onChange?.(l.id, { x: Math.round(e.target.x()), y: Math.round(e.target.y()) })}
              onTransformEnd={(e) => {
                const node = e.target
                const sx = node.scaleX()
                const sy = node.scaleY()
                node.scale({ x: 1, y: 1 })
                const base = { x: Math.round(node.x()), y: Math.round(node.y()), rotation: Math.round(node.rotation() * 10) / 10 }
                if (l.type === 'text') {
                  onChange?.(l.id, { ...base, width: Math.max(40, Math.round(l.width * sx)), fontSize: Math.max(8, Math.round(l.fontSize * sy)) })
                } else {
                  onChange?.(l.id, { ...base, width: Math.max(8, Math.round(l.width * sx)), height: Math.max(8, Math.round(l.height * sy)) })
                }
              }}
            >
              <LayerContent layer={l} />
            </Group>
          ),
        )}
        {interactive && (
          <Transformer
            ref={transformer}
            rotationSnaps={[0, 90, 180, 270]}
            keepRatio={selected?.type === 'image'}
            enabledAnchors={
              selected?.type === 'text'
                ? ['top-left', 'top-right', 'bottom-left', 'bottom-right', 'middle-left', 'middle-right']
                : undefined
            }
            anchorSize={10 / Math.max(scale, 0.3)}
            borderStroke="#f5a524"
            anchorStroke="#f5a524"
            anchorFill="#ffffff"
            ignoreStroke
          />
        )}
      </KLayer>
    </Stage>
  )
})

/** Renders the design at full size and returns the image. The editor's selection is never included. */
export async function exportStage(stage: Konva.Stage, scale: number, mimeType: 'image/png' | 'image/jpeg'): Promise<Blob> {
  const tr = stage.findOne('Transformer') as Konva.Transformer | undefined
  const nodes = tr?.nodes() ?? []
  tr?.nodes([])
  try {
    const blob = (await stage.toBlob({ pixelRatio: 1 / scale, mimeType, quality: 0.92 })) as Blob | null
    if (!blob) throw new Error('The browser could not render the image')
    return blob
  } finally {
    tr?.nodes(nodes)
  }
}
