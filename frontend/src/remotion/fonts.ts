// Fonts bundled with the app, used by both the preview and the renderer.
// Tamil-capable: Anek Tamil, Catamaran, Hind Madurai. English display: Anton, Bebas Neue.
import '@fontsource-variable/anek-tamil'
import '@fontsource/catamaran/400.css'
import '@fontsource/catamaran/800.css'
import '@fontsource/hind-madurai/400.css'
import '@fontsource/hind-madurai/600.css'
import '@fontsource/anton'
import '@fontsource/bebas-neue'
// Same character widths as Calibri and Courier New, so slides in lesson videos wrap as in PowerPoint.
import '@fontsource/carlito/400.css'
import '@fontsource/carlito/700.css'
import '@fontsource/cousine/400.css'
import '@fontsource/noto-sans-tamil/400.css'
import '@fontsource/noto-sans-tamil/700.css'

export const EDITOR_FONTS: { family: string; label: string; tamil: boolean }[] = [
  { family: 'Anek Tamil Variable', label: 'Anek Tamil', tamil: true },
  { family: 'Catamaran', label: 'Catamaran', tamil: true },
  { family: 'Hind Madurai', label: 'Hind Madurai', tamil: true },
  { family: 'Anton', label: 'Anton (English)', tamil: false },
  { family: 'Bebas Neue', label: 'Bebas Neue (English)', tamil: false },
]

/** English-only fonts fall back to a Tamil font for Tamil letters, so text never shows as boxes. */
export const fontStack = (family: string) => `"${family}", "Anek Tamil Variable", "Hind Madurai", sans-serif`
