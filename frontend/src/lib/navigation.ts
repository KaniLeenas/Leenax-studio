import {
  AudioLines,
  BookOpen,
  BarChart3,
  CalendarDays,
  Captions,
  Clapperboard,
  Coins,
  FileText,
  Film,
  FolderOpen,
  Frame,
  ImageIcon,
  KeyRound,
  LayoutPanelTop,
  LayoutDashboard,
  Images,
  Send,
  Settings,
  Share2,
  UserCog,
  UsersRound,
  Wand2,
  type LucideIcon,
} from 'lucide-react'

export interface NavItem {
  label: string
  path: string
  icon: LucideIcon
  /** Build phase that delivers this section. Phase 1 sections are live. */
  phase: number
  summary: string
  /** Shown to administrators only (the server refuses members anyway). */
  adminOnly?: boolean
}

export interface NavGroup {
  label: string
  items: NavItem[]
}

/** Grouped by where each tool sits in the create → edit → publish workflow. */
export const NAV: NavGroup[] = [
  {
    label: 'Studio',
    items: [
      { label: 'Dashboard', path: '/', icon: LayoutDashboard, phase: 1, summary: 'Overview of your projects and media.' },
      { label: 'My projects', path: '/projects', icon: FolderOpen, phase: 1, summary: 'Every video you are working on.' },
      { label: 'Media library', path: '/media', icon: Images, phase: 1, summary: 'All your uploaded and generated files.' },
      { label: 'Course documents', path: '/documents', icon: BookOpen, phase: 9, summary: 'Lecture notes, slides and past papers per subject, read page by page and searchable in English and Tamil.' },
    ],
  },
  {
    label: 'Create',
    items: [
      { label: 'Scripts & ideas', path: '/create/scripts', icon: FileText, phase: 8, summary: 'Video ideas and scripts in Tamil or English, rewrites, and post text for each platform.' },
      { label: 'Character studio', path: '/create/characters', icon: UsersRound, phase: 8, summary: 'People who appear in your stories, with their look, reference images and voice.' },
      { label: 'Storyboard', path: '/create/storyboard', icon: LayoutPanelTop, phase: 8, summary: 'Scene-by-scene plans with shots, images, video, narration and dialogue.' },
      { label: 'AI video generator', path: '/create/video', icon: Clapperboard, phase: 2, summary: 'Text-to-video and image-to-video with Veo, Runway, Kling and Luma, using your own provider accounts.' },
      { label: 'AI image generator', path: '/create/image', icon: ImageIcon, phase: 3, summary: 'Scene images, thumbnail backgrounds and social graphics, plus background removal and resizing.' },
      { label: 'Thumbnail studio', path: '/create/thumbnails', icon: Frame, phase: 3, summary: 'YouTube thumbnails with text overlays, templates and variations.' },
      { label: 'Voice & audio', path: '/create/voice', icon: AudioLines, phase: 3, summary: 'Tamil and English voice-overs, your own cloned voice, sound effects, music and clean-up.' },
    ],
  },
  {
    label: 'Edit',
    items: [
      { label: 'Video editor', path: '/edit/timeline', icon: Film, phase: 4, summary: 'Multi-track timeline editing with transitions, text and audio mixing.' },
      { label: 'AI auto editor', path: '/edit/auto', icon: Wand2, phase: 5, summary: 'Script to video, talking-video clean-up and Shorts from long videos, as editable first cuts.' },
      { label: 'Subtitles', path: '/edit/subtitles', icon: Captions, phase: 3, summary: 'Generate, translate and time subtitles.' },
    ],
  },
  {
    label: 'Publish',
    items: [
      { label: 'Social accounts', path: '/publish/accounts', icon: Share2, phase: 6, summary: 'Connect YouTube, Instagram, TikTok and more through their official sign-in.' },
      { label: 'Publisher', path: '/publish/new', icon: Send, phase: 6, summary: 'Prepare one video for many platforms and post or schedule it.' },
      { label: 'Calendar', path: '/publish/calendar', icon: CalendarDays, phase: 6, summary: 'Scheduled, published and draft posts by day, week and month.' },
      { label: 'Analytics', path: '/analytics', icon: BarChart3, phase: 7, summary: 'Generation costs, publishing history and platform performance.' },
    ],
  },
  {
    label: 'Manage',
    items: [
      { label: 'Users', path: '/manage/users', icon: UserCog, phase: 9, adminOnly: true, summary: 'Up to five people, their roles, activation links and sign-in history.' },
      { label: 'AI accounts & keys', path: '/manage/ai-accounts', icon: KeyRound, phase: 2, adminOnly: true, summary: 'Your provider accounts and API keys, stored encrypted on the server.' },
      { label: 'Credits & usage', path: '/manage/credits', icon: Coins, phase: 2, adminOnly: true, summary: 'Balances, costs and low-credit warnings per provider account.' },
      { label: 'Settings', path: '/settings', icon: Settings, phase: 1, summary: 'Profile, password and appearance.' },
    ],
  },
]

/** Phases built so far. Sections from later phases show a "coming in phase N" page. */
export const CURRENT_PHASE = 9

export const isLive = (item: NavItem) => item.phase <= CURRENT_PHASE

export const ALL_NAV_ITEMS = NAV.flatMap((g) => g.items)

export function findNavItem(pathname: string): NavItem | undefined {
  return ALL_NAV_ITEMS.find((i) => i.path === pathname)
}

/** The menu a person sees: administrators get everything, members don't see the Manage tools they can't use. */
export function navFor(isAdmin: boolean): NavGroup[] {
  return NAV.map((g) => ({ ...g, items: g.items.filter((i) => isAdmin || !i.adminOnly) })).filter((g) => g.items.length > 0)
}
