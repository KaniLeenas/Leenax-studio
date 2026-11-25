import { useQueryClient } from '@tanstack/react-query'
import { lazy, Suspense, useEffect } from 'react'
import { Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { Toasts } from './components/Toasts'
import { ErrorNotice, Spinner } from './components/ui'
import { setUnauthorizedHandler } from './lib/api'
import { ALL_NAV_ITEMS, isLive } from './lib/navigation'
import { keys, useAuthStatus } from './lib/queries'
import { LinkPage, LoginPage, SetupPage } from './pages/AuthPages'
import { AdminOnly } from './components/AdminOnly'
import { ComingSoonPage } from './pages/ComingSoonPage'
import { DashboardPage } from './pages/DashboardPage'
import { MediaLibraryPage } from './pages/MediaLibraryPage'
import { NotFoundPage, ProjectDetailPage } from './pages/ProjectDetailPage'
import { ProjectsPage } from './pages/ProjectsPage'
import { AiAccountsPage } from './pages/AiAccountsPage'
import { CreditsPage } from './pages/CreditsPage'
import { VideoGeneratorPage } from './pages/VideoGeneratorPage'
import { ImageGeneratorPage } from './pages/ImageGeneratorPage'
import { AutoEditPage } from './pages/AutoEditPage'
import { SubtitlesPage } from './pages/SubtitlesPage'
import { VoiceStudioPage } from './pages/VoiceStudioPage'
import { SocialAccountsPage } from './pages/SocialAccountsPage'
import { PublisherPage } from './pages/PublisherPage'
import { CalendarPage } from './pages/CalendarPage'

// Analytics and Settings (QR codes) load on first visit.
const AnalyticsPage = lazy(() => import('./pages/AnalyticsPage').then((m) => ({ default: m.AnalyticsPage })))
const SettingsPage = lazy(() => import('./pages/SettingsPage').then((m) => ({ default: m.SettingsPage })))

// The thumbnail editor bundles a canvas library; load it only when opened.
const ThumbnailsPage = lazy(() => import('./pages/ThumbnailsPage').then((m) => ({ default: m.ThumbnailsPage })))
const EditorPage = lazy(() => import('./pages/EditorPage').then((m) => ({ default: m.EditorPage })))
const TimelinesPage = lazy(() => import('./pages/TimelinesPage').then((m) => ({ default: m.TimelinesPage })))
// Scripts, characters and storyboards (Phase 8).
const ScriptsPage = lazy(() => import('./pages/ScriptsPage').then((m) => ({ default: m.ScriptsPage })))
const CharactersPage = lazy(() => import('./pages/CharactersPage').then((m) => ({ default: m.CharactersPage })))
const StoryboardsPage = lazy(() => import('./pages/StoryboardsPage').then((m) => ({ default: m.StoryboardsPage })))
const StoryboardEditorPage = lazy(() => import('./pages/StoryboardEditorPage').then((m) => ({ default: m.StoryboardEditorPage })))
const DocumentsPage = lazy(() => import('./pages/DocumentsPage').then((m) => ({ default: m.DocumentsPage })))
const DocumentsHomePage = lazy(() => import('./pages/DocumentsPage').then((m) => ({ default: m.DocumentsHomePage })))
const DocumentReaderPage = lazy(() => import('./pages/DocumentReaderPage').then((m) => ({ default: m.DocumentReaderPage })))
const SyllabusPage = lazy(() => import('./pages/SyllabusPage').then((m) => ({ default: m.SyllabusPage })))
const TopicsPage = lazy(() => import('./pages/TopicsPage').then((m) => ({ default: m.TopicsPage })))
const MaterialsPage = lazy(() => import('./pages/MaterialsPage').then((m) => ({ default: m.MaterialsPage })))
const NotesPage = lazy(() => import('./pages/NotesPage').then((m) => ({ default: m.NotesPage })))
const BankPage = lazy(() => import('./pages/BankPage').then((m) => ({ default: m.BankPage })))
const QuizzesPage = lazy(() => import('./pages/QuizzesPage').then((m) => ({ default: m.QuizzesPage })))
const QaPage = lazy(() => import('./pages/QaPage').then((m) => ({ default: m.QaPage })))
const TopicAnalysisProjectPage = lazy(() => import('./pages/TopicAnalysisPages').then((m) => ({ default: m.TopicAnalysisProjectPage })))
const TopicAnalysisPage = lazy(() => import('./pages/TopicAnalysisPages').then((m) => ({ default: m.TopicAnalysisPage })))
const QaReportPage = lazy(() => import('./pages/QaReportPage').then((m) => ({ default: m.QaReportPage })))
const SeoPage = lazy(() => import('./pages/SeoPage').then((m) => ({ default: m.SeoPage })))
const SeoReportPage = lazy(() => import('./pages/SeoReportPage').then((m) => ({ default: m.SeoReportPage })))
const SlidesPage = lazy(() => import('./pages/SlidesPage').then((m) => ({ default: m.SlidesPage })))
const DeckPage = lazy(() => import('./pages/DeckPage').then((m) => ({ default: m.DeckPage })))
const VideosPage = lazy(() => import('./pages/VideosPage').then((m) => ({ default: m.VideosPage })))
const VideoPage = lazy(() => import('./pages/VideoPage').then((m) => ({ default: m.VideoPage })))
const YouTubePublishPage = lazy(() => import('./pages/YouTubePublishPage').then((m) => ({ default: m.YouTubePublishPage })))
const YouTubeAnalyticsPage = lazy(() => import('./pages/YouTubeAnalyticsPage').then((m) => ({ default: m.YouTubeAnalyticsPage })))
const UsersPage = lazy(() => import('./pages/UsersPage').then((m) => ({ default: m.UsersPage })))
const ThumbnailEditorPage = lazy(() => import('./pages/ThumbnailEditorPage').then((m) => ({ default: m.ThumbnailEditorPage })))

export default function App() {
  const qc = useQueryClient()
  const auth = useAuthStatus()
  const location = useLocation()
  const navigate = useNavigate()
  const linkPage = location.pathname === '/activate' || location.pathname === '/reset-password'

  // Any 401 (e.g. session expired) sends you back to sign-in.
  useEffect(() => setUnauthorizedHandler(() => qc.invalidateQueries({ queryKey: keys.auth })), [qc])

  let content
  if (linkPage) {
    // One-time activation and reset links work without signing in.
    content = (
      <LinkPage
        token={new URLSearchParams(location.search).get('token')}
        onDone={() => {
          qc.invalidateQueries({ queryKey: keys.auth })
          navigate('/', { replace: true })
        }}
      />
    )
  } else if (auth.isPending) {
    content = <Spinner label="Opening your studio" />
  } else if (auth.error) {
    content = (
      <div className="max-w-lg mx-auto p-8">
        <ErrorNotice error={auth.error} onRetry={() => auth.refetch()} />
      </div>
    )
  } else if (auth.data.setupRequired) {
    content = <SetupPage enabled={auth.data.setupEnabled} />
  } else if (!auth.data.authenticated) {
    content = <LoginPage />
  } else {
    content = (
      <AppShell>
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/projects/:id" element={<ProjectDetailPage />} />
          <Route path="/projects/:id/documents" element={<Suspense fallback={<Spinner label="Opening documents" />}><DocumentsPage /></Suspense>} />
          <Route path="/projects/:id/syllabus" element={<Suspense fallback={<Spinner label="Opening the syllabus" />}><SyllabusPage /></Suspense>} />
          <Route path="/projects/:id/topics" element={<Suspense fallback={<Spinner label="Opening topics" />}><TopicsPage /></Suspense>} />
          <Route path="/projects/:id/materials" element={<Suspense fallback={<Spinner label="Opening notes and questions" />}><MaterialsPage /></Suspense>} />
          <Route path="/projects/:id/quizzes" element={<Suspense fallback={<Spinner label="Opening quizzes" />}><QuizzesPage /></Suspense>} />
          <Route path="/projects/:id/qa" element={<Suspense fallback={<Spinner label="Opening academic QA" />}><QaPage /></Suspense>} />
          <Route path="/projects/:id/analysis" element={<Suspense fallback={<Spinner label="Opening topic analysis" />}><TopicAnalysisProjectPage /></Suspense>} />
          <Route path="/topic-analysis/:resourceId" element={<Suspense fallback={<Spinner label="Opening the topic analysis" />}><TopicAnalysisPage /></Suspense>} />
          <Route path="/qa-report/:resourceId" element={<Suspense fallback={<Spinner label="Opening the QA report" />}><QaReportPage /></Suspense>} />
          <Route path="/projects/:id/seo" element={<Suspense fallback={<Spinner label="Opening YouTube SEO" />}><SeoPage /></Suspense>} />
          <Route path="/seo/:resourceId" element={<Suspense fallback={<Spinner label="Opening the SEO report" />}><SeoReportPage /></Suspense>} />
          <Route path="/projects/:id/slides" element={<Suspense fallback={<Spinner label="Opening presentations" />}><SlidesPage /></Suspense>} />
          <Route path="/slides/:resourceId" element={<Suspense fallback={<Spinner label="Opening the presentation" />}><DeckPage /></Suspense>} />
          <Route path="/projects/:id/videos" element={<Suspense fallback={<Spinner label="Opening videos" />}><VideosPage /></Suspense>} />
          <Route path="/videos/:resourceId" element={<Suspense fallback={<Spinner label="Opening the video" />}><VideoPage /></Suspense>} />
          <Route path="/projects/:id/youtube" element={<Suspense fallback={<Spinner label="Opening YouTube publishing" />}><YouTubePublishPage /></Suspense>} />
          <Route path="/projects/:id/youtube/analytics" element={<Suspense fallback={<Spinner label="Opening YouTube analytics" />}><YouTubeAnalyticsPage /></Suspense>} />
          <Route path="/notes/:resourceId" element={<Suspense fallback={<Spinner label="Opening the notes" />}><NotesPage /></Suspense>} />
          <Route path="/mcq/:resourceId" element={<Suspense fallback={<Spinner label="Opening the question bank" />}><BankPage /></Suspense>} />
          <Route path="/documents" element={<Suspense fallback={<Spinner label="Opening documents" />}><DocumentsHomePage /></Suspense>} />
          <Route path="/documents/:id" element={<Suspense fallback={<Spinner label="Opening document" />}><DocumentReaderPage /></Suspense>} />
          <Route path="/media" element={<MediaLibraryPage />} />
          <Route path="/settings" element={<Suspense fallback={<Spinner label="Opening settings" />}><SettingsPage /></Suspense>} />
          <Route path="/create/scripts" element={<Suspense fallback={<Spinner label="Opening scripts" />}><ScriptsPage /></Suspense>} />
          <Route path="/create/characters" element={<Suspense fallback={<Spinner label="Opening characters" />}><CharactersPage /></Suspense>} />
          <Route path="/create/storyboard" element={<Suspense fallback={<Spinner label="Opening storyboards" />}><StoryboardsPage /></Suspense>} />
          <Route path="/create/storyboard/:id" element={<Suspense fallback={<Spinner label="Opening storyboard" />}><StoryboardEditorPage /></Suspense>} />
          <Route path="/create/video" element={<VideoGeneratorPage />} />
          <Route path="/create/image" element={<ImageGeneratorPage />} />
          <Route path="/create/thumbnails" element={<Suspense fallback={<Spinner label="Opening thumbnail studio" />}><ThumbnailsPage /></Suspense>} />
          <Route path="/create/thumbnails/:id" element={<Suspense fallback={<Spinner label="Opening editor" />}><ThumbnailEditorPage /></Suspense>} />
          <Route path="/create/voice" element={<VoiceStudioPage />} />
          <Route path="/edit/subtitles" element={<SubtitlesPage />} />
          <Route path="/edit/auto" element={<AutoEditPage />} />
          <Route path="/edit/timeline" element={<Suspense fallback={<Spinner label="Opening the editor" />}><TimelinesPage /></Suspense>} />
          <Route path="/edit/timeline/:id" element={<Suspense fallback={<Spinner label="Opening the editor" />}><EditorPage /></Suspense>} />
          <Route path="/manage/users" element={<AdminOnly><Suspense fallback={<Spinner label="Opening users" />}><UsersPage /></Suspense></AdminOnly>} />
          <Route path="/manage/ai-accounts" element={<AdminOnly><AiAccountsPage /></AdminOnly>} />
          <Route path="/manage/credits" element={<AdminOnly><CreditsPage /></AdminOnly>} />
          <Route path="/publish/accounts" element={<SocialAccountsPage />} />
          <Route path="/publish/new" element={<PublisherPage />} />
          <Route path="/publish/calendar" element={<CalendarPage />} />
          <Route path="/analytics" element={<Suspense fallback={<Spinner label="Opening analytics" />}><AnalyticsPage /></Suspense>} />
          {ALL_NAV_ITEMS.filter((i) => !isLive(i)).map((i) => (
            <Route key={i.path} path={i.path} element={<ComingSoonPage item={i} />} />
          ))}
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </AppShell>
    )
  }

  return (
    <>
      {content}
      <Toasts />
    </>
  )
}
