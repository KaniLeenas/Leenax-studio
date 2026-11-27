export type ProjectStatus = 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED' | 'ARCHIVED'
export type AspectRatio = 'LANDSCAPE' | 'PORTRAIT' | 'SQUARE'
export type Language = 'EN' | 'TA'
export type MediaKind = 'VIDEO' | 'IMAGE' | 'AUDIO'
export type MediaSource = 'UPLOAD' | 'AI_GENERATED' | 'RENDER' | 'RECORDING'

export type SystemRole = 'ADMIN' | 'MEMBER'
export type ProjectRole = 'OWNER' | 'EDITOR' | 'REVIEWER' | 'VIEWER'

export interface User {
  id?: string
  email: string
  displayName: string
  role?: SystemRole
  canCreateProjects?: boolean
  lastLoginAt?: string
  mfaEnabled?: boolean
  mfaEnabledAt?: string
  recoveryCodesLeft?: number
}

/** After a correct password: signed in, or waiting for the authenticator code. */
export interface LoginResult {
  mfaRequired: boolean
  user?: User
}

export interface AuthStatus {
  setupRequired: boolean
  setupEnabled: boolean
  authenticated: boolean
  user?: User
}

export interface Project {
  id: string
  name: string
  description?: string
  status: ProjectStatus
  aspectRatio: AspectRatio
  language: Language
  kind?: ProjectKind
  subject?: ProjectSubject
  reviewerNotAuthor?: boolean
  /** Your role in it; ADMIN for an administrator who isn't a member. */
  myRole?: ProjectRole | 'ADMIN'
  memberCount?: number
  mediaCount: number
  createdAt: string
  updatedAt: string
}

export type ProjectKind = 'CREATOR' | 'EDUCATION'

export interface ProjectSubject {
  subjectName?: string
  moduleCode?: string
  academicYear?: string
  semester?: string
  university?: string
  department?: string
}

export interface MediaAsset {
  id: string
  projectId?: string
  kind: MediaKind
  source: MediaSource
  filename: string
  contentType: string
  sizeBytes: number
  width?: number
  height?: number
  durationMs?: number
  createdAt: string
  url: string
  posterUrl?: string
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface DashboardSummary {
  stats: {
    totalProjects: number
    activeProjects: number
    completedProjects: number
    videos: number
    images: number
    audio: number
    storageBytes: number
  }
  recentProjects: Project[]
  recentMedia: MediaAsset[]
}

// ---- Phase 2: AI providers ------------------------------------------------

export type ProviderCode = 'RUNWAY' | 'GOOGLE_GEMINI' | 'KLING' | 'LUMA' | 'OPENAI' | 'ANTHROPIC' | 'ELEVENLABS' | 'GOOGLE_CLOUD_TTS' | 'OLLAMA' | 'FREE_LLM_API'
export type Capability = 'VIDEO' | 'TEXT' | 'IMAGE' | 'VOICE' | 'TRANSCRIPTION' | 'SOUND'
export type AccountStatus = 'ACTIVE' | 'UNVERIFIED' | 'INVALID' | 'DISABLED'
export type CreditUnit = 'CREDITS' | 'USD' | 'SECONDS' | 'UNITS'
export type GenerationMode = 'TEXT_TO_VIDEO' | 'IMAGE_TO_VIDEO'
export type JobStatus = 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface ProviderInfo {
  code: ProviderCode
  name: string
  capabilities: Capability[]
  balanceApi: boolean
  keyUrl: string
  keyHelp: string
}

export interface Balance {
  id: string
  label: string
  kind: 'FREE' | 'PAID'
  amount: number
  unit: CreditUnit
  expiresAt?: string
  source: 'MANUAL' | 'PROVIDER'
  expired: boolean
  expiringSoon: boolean
  updatedAt: string
}

export interface AiAccount {
  id: string
  provider: ProviderCode
  providerName: string
  capabilities: Capability[]
  balanceApi: boolean
  name: string
  status: AccountStatus
  isDefault: boolean
  maskedKey: string
  lowCreditThreshold?: number
  lastVerifiedAt?: string
  lastError?: string
  balanceSyncedAt?: string
  balances: Balance[]
  totals: { unit: CreditUnit; amount: number }[]
  lowCredit: boolean
  expiringSoon: boolean
  createdAt: string
}

export interface OptionItem {
  value: string
  label: string
}

export interface ModelOptions {
  modes?: GenerationMode[]
  durations?: { min?: number; max?: number; values?: number[] }
  defaultDuration?: number
  durationInPrompt?: boolean
  audio?: boolean
  publicImageUrl?: boolean
  aspectRatios?: Partial<Record<GenerationMode | 'ALL', OptionItem[]>>
  resolutions?: OptionItem[]
  // image models
  sizes?: OptionItem[]
  qualities?: OptionItem[]
  imageSizes?: OptionItem[]
  transparent?: boolean
  references?: boolean
  maxCount?: number
  // voice models
  maxChars?: number
  languages?: string[]
  cloning?: boolean
  styleControl?: 'settings' | 'prompt'
}

export interface Pricing {
  unit?: CreditUnit
  minimum?: number
  rates?: { perSecond: number; audio?: boolean; resolution?: string }[]
}

export interface AiModelInfo {
  id: string
  provider: ProviderCode
  modelCode: string
  displayName: string
  capability: 'VIDEO' | 'TEXT' | 'IMAGE' | 'VOICE'
  options: ModelOptions
  pricing: Pricing
  notes?: string
  enabled: boolean
}

export interface GenerationJob {
  id: string
  groupId: string
  variationIndex: number
  accountId?: string
  accountName: string
  provider: ProviderCode
  providerName: string
  modelId?: string
  modelCode: string
  modelName: string
  mode: GenerationMode
  prompt: string
  negativePrompt?: string
  durationSeconds?: number
  aspectRatio?: string
  resolution?: string
  audio?: boolean
  inputMediaId?: string
  projectId?: string
  status: JobStatus
  progress?: number
  attempts: number
  errorCode?: string
  errorMessage?: string
  estimatedCost?: number
  actualCost?: number
  costUnit?: CreditUnit
  output?: MediaAsset
  createdAt: string
  submittedAt?: string
  completedAt?: string
}

export interface VideoGenerationInput {
  accountId: string
  modelId: string
  mode: GenerationMode
  prompt: string
  negativePrompt?: string
  durationSeconds?: number
  aspectRatio?: string
  resolution?: string
  audio?: boolean
  imageMediaId?: string
  projectId?: string
  variations: number
}

export interface Estimate {
  perVideo?: number
  total?: number
  unit?: CreditUnit
  variations: number
  balance?: number
  balanceUnit?: CreditUnit
  balanceAfter?: number
  lowCredit: boolean
  insufficient: boolean
  warnings: string[]
}

export interface UsageEntry {
  id: string
  accountId?: string
  accountName: string
  provider: ProviderCode
  providerName: string
  modelCode: string
  capability: string
  generationJobId?: string
  outputSeconds?: number
  cost?: number
  costUnit?: CreditUnit
  costSource: 'PROVIDER_REPORTED' | 'ESTIMATED' | 'UNKNOWN'
  success: boolean
  createdAt: string
}

export interface UsageSummary {
  days: number
  generations: number
  failed: number
  seconds: number
  costByUnit: Partial<Record<CreditUnit, number>>
  byAccount: {
    accountId?: string
    accountName: string
    provider?: ProviderCode
    providerName?: string
    generations: number
    failed: number
    seconds: number
    costByUnit: Partial<Record<CreditUnit, number>>
  }[]
}

// ---- Phase 3: voice, audio, subtitles, images, thumbnails -----------------

export type Emotion = 'NEUTRAL' | 'HAPPY' | 'SAD' | 'ANGRY' | 'EXCITED' | 'STORYTELLING' | 'DOCUMENTARY' | 'PROFESSIONAL'

export interface VoiceOption {
  id: string
  name: string
  category?: string
  gender?: string
  language?: string
  accent?: string
  description?: string
  previewUrl?: string
  cloned: boolean
}

export interface VoiceStyle {
  emotion?: Emotion
  direction?: string
  speed?: number
  pitch?: number
  stability?: number
  similarity?: number
}

export interface VoiceChoice {
  profileId?: string
  accountId?: string
  voiceId?: string
  model?: string
}

export interface VoiceProfile {
  id: string
  accountId: string
  accountName?: string
  provider: ProviderCode
  providerVoiceId: string
  name: string
  kind: 'STOCK' | 'CLONED'
  language?: Language
  model?: string
  settings: VoiceStyle
  consent?: { statement: string; confirmedAt: string; ownVoice: boolean }
  sampleMediaIds: string[]
  /** Everyone may use a shared voice; only its creator changes it. */
  shared?: boolean
  createdBy?: string
  createdAt: string
}

export interface PronunciationRule {
  id: string
  term: string
  replacement: string
  language?: Language
}

export interface SpeechResult {
  media: MediaAsset
  chunks: number
  characters: number
}

export type AudioOperation = 'DENOISE' | 'ENHANCE' | 'REMOVE_SILENCE' | 'NORMALIZE'

export interface Cue {
  start: number
  end: number
  text: string
}

export type TrackSource = 'TRANSCRIBED' | 'TRANSLATED' | 'MANUAL'

export interface TrackSummary {
  id: string
  mediaId: string
  language: string
  label: string
  source: TrackSource
  cueCount: number
  updatedAt: string
}

export interface SubtitleTrack {
  id: string
  mediaId: string
  projectId?: string
  language: string
  label: string
  source: TrackSource
  cues: Cue[]
  createdAt: string
  updatedAt: string
}

export interface ImagePreset {
  id: string
  label: string
  width: number
  height: number
}

export interface ImageToolsStatus {
  backgroundRemoval: boolean
  presets: ImagePreset[]
}

export interface ThumbnailSummary {
  id: string
  name: string
  width: number
  height: number
  template: boolean
  projectId?: string
  export?: MediaAsset
  updatedAt: string
}

export interface ThumbnailDesign extends ThumbnailSummary {
  document: ThumbnailDocument
  createdAt: string
}

/** The Thumbnail Studio's own JSON format (stored as-is by the server). */
export interface ThumbnailDocument {
  version: 1
  background: string
  layers: Layer[]
}

interface LayerBase {
  id: string
  name: string
  x: number
  y: number
  rotation: number
  opacity: number
  hidden?: boolean
  locked?: boolean
}

export interface TextLayer extends LayerBase {
  type: 'text'
  text: string
  fontFamily: string
  fontSize: number
  fontStyle: 'normal' | 'bold' | 'italic' | 'bold italic'
  fill: string
  stroke: string
  strokeWidth: number
  shadow: boolean
  align: 'left' | 'center' | 'right'
  width: number
  lineHeight: number
  letterSpacing: number
  /** Coloured box behind the text (pill / label look), or empty for none. */
  background: string
  padding: number
}

export interface ImageLayer extends LayerBase {
  type: 'image'
  mediaId: string
  width: number
  height: number
  stroke: string
  strokeWidth: number
  cornerRadius: number
  shadow: boolean
}

export interface ShapeLayer extends LayerBase {
  type: 'rect' | 'circle' | 'arrow'
  width: number
  height: number
  fill: string
  stroke: string
  strokeWidth: number
  cornerRadius: number
}

export type Layer = TextLayer | ImageLayer | ShapeLayer

// ---- Phase 4: editor -------------------------------------------------------

export interface TimelineSummary {
  id: string
  name: string
  width: number
  height: number
  fps: number
  durationSeconds: number
  projectId?: string
  poster?: MediaAsset
  updatedAt: string
  createdAt: string
}

export interface TimelineResponse extends TimelineSummary {
  document: import('../remotion/types').TimelineDoc
  revision: number
  media: Record<string, MediaAsset>
}

export type RenderStatus = 'QUEUED' | 'RENDERING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
export type RenderQuality = 'STANDARD' | 'HIGH' | 'BEST'

export interface RenderJob {
  id: string
  timelineId: string
  timelineName: string
  projectId?: string
  status: RenderStatus
  progress: number
  resolution: number
  quality: RenderQuality
  outputWidth: number
  outputHeight: number
  errorMessage?: string
  output?: MediaAsset
  createdAt: string
  submittedAt?: string
  completedAt?: string
}

// ---- Phase 5: AI auto-editor -------------------------------------------------

export type AutoEditMode = 'SCRIPT_TO_VIDEO' | 'TALKING_HEAD' | 'HIGHLIGHTS'
export type VideoFormat = 'LANDSCAPE' | 'PORTRAIT' | 'SQUARE'
export type AutoEditStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface AutoEditRequest {
  mode: AutoEditMode
  name: string
  format: VideoFormat
  language: Language
  projectId?: string
  footageIds?: string[]
  script?: string
  topic?: string
  targetSeconds?: number
  style?: string
  voice?: VoiceChoice
  voiceStyle?: VoiceStyle
  describeFootage: boolean
  imageAccountId?: string
  imageModel?: string
  textAccountId?: string
  textModel?: string
  transcriptionAccountId?: string
  removeFillers: boolean
  maxPause?: number
  punchIn: boolean
  highlightCount?: number
  minSeconds?: number
  maxSeconds?: number
  captions: boolean
  musicMediaId?: string
  musicVolume?: number
}

export interface AutoEditJob {
  id: string
  mode: AutoEditMode
  name: string
  projectId?: string
  status: AutoEditStatus
  step?: string
  progress: number
  settings: AutoEditRequest
  result?: { timelineIds?: string[]; notes?: string[] }
  errorMessage?: string
  createdAt: string
  startedAt?: string
  completedAt?: string
}
