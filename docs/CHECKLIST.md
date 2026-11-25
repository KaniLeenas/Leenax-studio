# Leenax AI Video Studio: consolidated implementation checklist

This checklist puts every Leenax requirement in one place: the original architecture document, the master development prompt, Phases 1–7 and the final consolidated specification (20 November 2026). It records what is built, what is partly built and what is still to do, so work continues from one list inside one application.

**Legend**
- ✅ Built and tested locally.
- 🟡 Partly built; the gap is described.
- ⬜ To build (the increment is given).
- 🔒 Can't be done through an official API (an honest workaround is given).
- 🔑 Needs your own key, permission or approval before it can run for real.

"Tested locally" means unit and integration tests, tests against mock APIs, and checks against a real PostgreSQL 16 and in Chrome. Nothing has been verified against a live provider. The real Maven build can't be downloaded in this environment, so it still has to be run on your PC.

## Shared architecture

| Item | Status | Where |
|---|---|---|
| One Spring Boot backend, one PostgreSQL, one Redis, one private S3 store, one React app | ✅ | `backend/`, `frontend/`, `docker-compose.yml` |
| Projects shared by every module (`project_id` on media, jobs, timelines, voice, subtitles, thumbnails, posts…) | ✅ | V1–V7 migrations |
| Provider adapter interfaces (Video/Image/Text/Speech/Transcription/Sound providers, ProviderRegistry) | ✅ | `ai/provider` |
| Background work leased from Postgres (`FOR UPDATE SKIP LOCKED`) with retries and live progress over SSE | ✅ | generation, render, auto-edit, publish workers |
| Redis for sessions and the sign-in lockout | ✅ | Spring Session |
| Notifications (in-app list and unread count) | ⬜ Increment 10 | |
| Table naming in the specification vs the code | ✅ documented | `publish_jobs` = `posts` + `post_targets`; `publishing_history` = target status + `audit_logs`; `editing_projects` = `timelines`; `storyboard_projects` = `storyboard_projects`, with `storyboard_scenes`, `storyboard_characters` and `storyboard_references` (Increment 8, `V8__scripts_storyboards_characters.sql`) |

## Modules

### A. Dashboard
- ✅ Project statistics, recent projects, recent media, quick actions.
- ⬜ Increment 10: AI generation activity, rendering status, publishing status, upcoming scheduled posts, AI account status, credit and cost overview, storage use. The data exists already (analytics, system status, credits); it needs adding to the dashboard.

### B. AI idea and script generator
- ✅ **Increment 8** (`/create/scripts`): ideas for a topic and audience, full scripts with an introduction, scenes (with visual notes) and an outro, Tamil and English, 10 video types and a target length.
- ✅ Rewrite with an instruction or a quick action (shorter, stronger hook, more casual, call to action, translate). Each rewrite is a new version; the earlier one is kept.
- ✅ Titles, descriptions, captions and hashtags for up to 10 of the 12 platforms at a time, saved on the script with a copy button.
- ✅ Paste your own script. Spoken length is estimated live (150 words a minute in English, 110 in Tamil).
- 🟡 The publisher doesn't fill in post text from a script yet: copy it for now (⬜ Increment 9).

### C. AI storyboard studio
- ✅ **Increment 8** (`/create/storyboard`): script → scenes with the AI writer (up to 60), or a blank board.
- ✅ Per scene: what we see, narration, dialogue, length, shot and camera move, lighting, framing, sound notes, characters, up to 4 reference images, and your own image or video prompt (the generated prompt is shown as a suggestion).
- ✅ Add, insert, duplicate, delete and reorder scenes.
- ✅ A storyboard image per scene, made or remade with your image account, or chosen from the library. "Make missing images" does every scene without one, after a confirmation.
- ✅ Narration for the whole board in one voice-over; each scene keeps its part and stretches to fit it.
- ✅ Send to the editor: a new editable timeline each time, with scene video, the still with slow movement where video is shorter or missing, a title card where there's neither, narration, dialogue, optional captions from the narration and optional music.

### D. Character studio
- ✅ **Increment 8** (`/create/characters`): characters shared across projects or kept to one, with up to 8 reference images, appearance, clothing, a description and a voice from My voices.
- ✅ Dialogue lines are voiced with each character's own voice (up to 6 speakers a scene). The studio asks for a voice first when a speaker has none.
- Character references are sent to the image provider with every scene that uses them. The studio does not promise identical looks across scenes: providers don't guarantee it.

### E. AI video generator
- ✅ Google Veo (Gemini API), Runway, Kling and Luma through adapters.
- ✅ Text-to-video, image-to-video, prompt enhancement, variations, regenerate, model and account choice, history, progress and errors.
- 🟡 Reference-image workflows: image-to-video from a still is built. Multi-reference video depends on each provider's model and is passed through where the model allows it.
- 🔒 Video-to-video isn't offered: none of the integrated official endpoints were confirmed to support it. It will be added per provider once confirmed.
- ✅ **Increment 8:** a storyboard scene's video is made straight from the scene: image-to-video from its still when the model allows, otherwise text-to-video. The length is rounded to one the model accepts, and the finished file is linked back to the scene.
- 🔑 Each provider needs your API key.

### F. AI image generator
- ✅ Text-to-image, variations (count), reference images, editing with references (OpenAI and Gemini), background removal (rembg sidecar), resize, saved to the library and the project.
- 🟡 "Image enhancement" is limited to resize and fit, with no AI upscaling. An upscaler can be added as an adapter when you choose a provider.

### G. Thumbnail studio
- ✅ YouTube and social cover sizes, templates, text overlays, background removal, variations (duplicate), resize, reusable designs.

### H. Voice and audio studio
- ✅ ElevenLabs and Gemini text-to-speech, male and female voices, Tamil and English.
- ✅ Personal voice cloning with a spoken consent check, microphone recording, uploads, private voice profiles.
- ✅ Multi-speaker dialogue, emotional styles, speed and pitch, pronunciation rules.
- ✅ Speech-to-text (OpenAI, ElevenLabs), noise reduction, silence removal, enhancement, music and sound effects (ElevenLabs).
- ✅ Insert into the editor (from the media bin).
- 🟡 Google **Cloud** Text-to-Speech: Google's voices come from the Gemini API. A Cloud TTS adapter (separate API key) is ⬜ Increment 10.
- 🟡 OpenAI speech: OpenAI is used for transcription. An OpenAI TTS adapter is ⬜ Increment 10.

### I. Professional video editor
- ✅ Video, audio, image, text and subtitle tracks; drag-and-drop timeline; trim, split and merge; crop and resize; rotate; speed; transitions and effects; text animations; colour, brightness and contrast; mixing, volume and fades.
- ✅ Preview, undo and redo, autosave with a conflict check, reopening, and real MP4 export with Remotion.

### J. AI automatic editor
- ✅ Analyse footage (vision), detect silence, cut pauses, choose highlights, place clips, transitions, subtitles, narration, music, and audio synced to scenes.
- ✅ The result is an editable timeline.
- 🟡 Scene-change detection: highlights use transcript and vision. An FFmpeg scene-cut detector is ⬜ Increment 10.

### K. Subtitles studio
- ✅ Transcription, Tamil and English, translation, timing, styling, animated captions, SRT and VTT export, adding to the timeline, burn-in (FFmpeg).

### L. AI accounts and credit manager
- ✅ Several accounts per provider, custom names, several keys, encrypted storage, active and per-generation choice, usage history, free and paid credits with expiry, cost estimates, low-credit warnings, cost per account.
- ✅ Live balances only where the provider has an official balance API; otherwise entered by hand.

### M. Media library
- ✅ Upload video, images and audio; organise, search and filter, preview, rename, delete; link to projects; storage tracking; secure downloads.

### N. Social media account manager

| Platform | Status | How |
|---|---|---|
| YouTube | ✅ 🔑 | Google OAuth + PKCE, Data API v3 |
| Facebook Pages | ✅ 🔑 | Facebook Login for Business, Graph API |
| Instagram (professional) | ✅ 🔑 | Via the linked Page, Graph API |
| TikTok | ✅ 🔑 | Login Kit, Content Posting API |
| LinkedIn | ⬜ Increment 9 🔑 | OAuth 2.0, Posts + Videos API (member or company page) |
| X (Twitter) | ⬜ Increment 9 🔑 | OAuth 2.0 + PKCE, v2 media upload + posts. Video upload needs a paid X API plan. |
| Pinterest | ⬜ Increment 9 🔑 | OAuth, API v5 video pins |
| Threads | ⬜ Increment 9 🔑 | Threads API (Meta), video posts |
| Reddit | ⬜ Increment 9 🔑 / 🔒 | OAuth link and text posts. Native video upload isn't in Reddit's documented API, so video is shared as a link or prepared for manual upload. |
| Snapchat | 🔒 Increment 9 | No public posting API for personal accounts: export a ready file plus caption and open Snapchat |
| Telegram | ⬜ Increment 9 🔑 | Bot API `sendVideo` to your channel (bot token, bot made admin) |
| WhatsApp Channels | 🔒 Increment 9 | No Channels posting API: export plus caption copy |

- ✅ Several accounts per platform, connect and disconnect, account choice, token refresh, permissions shown, publishing history (YouTube, Facebook, Instagram, TikTok).

### O. Social media publisher
- ✅ Several platforms at once, captions, titles and descriptions, hashtags, YouTube thumbnail, Instagram and TikTok cover frame, drafts, post now, scheduling, status, retry, published links, history.
- ✅ TikTok "send to the app" (draft) mode.
- ⬜ Increment 9:
  - Confirm before anything goes public: a summary of every public post, ticked to proceed.
  - Video shaped per platform: an automatic 9:16, 1:1 or 16:9 version through FFmpeg or Remotion, linked to the post.
  - Manual-export packages (file plus caption) for platforms without an API.

### P. Publishing calendar
- ✅ Day, week and month views; scheduled, published, draft and failed posts; rescheduling by drag.
- ⬜ Increment 9: account and platform filters.

### Q. Analytics and usage
- ✅ Published content, AI usage, provider costs, views and engagement where the APIs expose them, per-platform and per-post reports.
- ⬜ Increment 10: generation and rendering history charts, storage over time, per-account performance view.

### R. Settings and security
- ✅ Single owner, password change, optional two-step sign-in (TOTP plus recovery codes), API key and OAuth management, theme, system health, activity log, rate limiting, automatic backups.
- ⬜ Increment 10: storage and backup settings shown in Settings (read-only from `.env`, with guidance) and an application configuration overview.

## End-to-end workflow

Each step lists what it uses and its status.

1. **Create project:** Projects. ✅
2. **Generate idea:** Script generator. ✅
3. **Generate script:** Script generator. ✅
4. **Create storyboard:** Storyboard studio. ✅
5. **Create characters:** Character studio. ✅
6. **Generate storyboard images:** Storyboard → image generator, using character references. ✅
7. **Generate AI video scenes:** Storyboard → video generator, linked to each scene. ✅
8. **Tamil or English voiceover:** Storyboard → voice studio (narration per scene). ✅
9. **Background music:** Music in the storyboard export, or the editor. ✅ in the export and the editor
10. **Subtitles:** Storyboard export captions, or the subtitles studio. ✅ in the export and the studio
11. **Open the editor:** Storyboard → timeline (editable). ✅
12. **Edit and arrange:** Editor. ✅
13. **Render final video:** Export (Remotion MP4). ✅
14. **Thumbnail:** Thumbnail studio. ✅
15. **Preview:** Media library and editor preview. ✅
16. **Choose accounts:** Publisher. ✅
17. **Customise per platform:** Publisher. ✅
18. **Publish or schedule:** Publisher, worker and calendar. ✅
19. **Track results:** Post status, links and analytics. ✅

The **project page** shows these steps for that project, with what's done and a link to the next (✅ Increment 8). Steps are counted from what the API can see per project: scripts, storyboards, characters, timelines, rendered videos, thumbnail designs, and posts of the project's files.

## Final tests in the specification

Steps 1, 9, 10, 11 and 16 can be run without any outside account. The others need your keys or approvals.

| # | Test | Can run here | Needs |
|---|---|---|---|
| 1 | Create a project | ✅ | — |
| 2–7 | Script, storyboard, characters, images, video scenes, voice-over | Mock APIs only | 🔑 text, image, video and voice keys |
| 8 | Subtitles | Mock APIs only | 🔑 OpenAI or ElevenLabs |
| 9–10 | Timeline edit and a real MP4 | ✅ (real Remotion render was run) | — |
| 11 | Thumbnail | ✅ | — |
| 12–15 | Connect an account, prepare, publish, save the link | Mock APIs only | 🔑 platform apps |
| 16 | Restart, data still there | ✅ by design (Postgres + S3); confirm on your PC | — |

Failure cases (invalid keys, API failures, upload failures, render errors, retries, expired tokens) are covered by the tests against mock APIs for every adapter.

## Increments

The merged specification (23 November 2026) puts the education increments E1 to E10 before the old Increment 9.

- ✅ **E1 (done, 23 November 2026):** up to five people with invitations and one-time links, administrator and member roles, a seat limit enforced by the database, project members with Owner, Editor, Reviewer and Viewer roles, access enforced by PostgreSQL row-level security, sign-in history, university subject fields and duplicate warnings, shared voices.
- ✅ **E2 (done, 23 November 2026):** course documents per subject with versions; a private document service reads PDFs, Word, PowerPoint, OpenDocument, text and photos or scans, with English and Tamil OCR; pages and page-bounded passages; search in English and Tamil with page numbers and highlighted snippets; language and module-code detection with a wrong-subject warning; duplicate refusal; read again (optionally from the page images).
  - **Not in E2, needs a decision first:** meaning-based ("semantic") search with pgvector. It needs an embedding model, which is either a paid provider or a heavy local model (see README, E2 limits).
- ✅ **E3 (done, 24 November 2026):** the approval engine (versions; draft, in review, changes requested, approved, rejected, outdated, archived; approve, approve with edits, ask for changes, reject; a permanent record of every decision; "reviewer must not be the author"; nothing decided by a background job; editing an approved syllabus marks the topics outdated), the AI syllabus report with page-checked citations and contradictions between documents, and the topic list editor (select, add, rename, reorder, move between levels, merge, split, difficulty, prerequisites).
- ✅ **E4 (done, 24 November 2026):** educational notes at five levels in a rich editor (tables, equations, code, Mermaid diagrams, images, comments), with checked sources, versions and approval; question banks with eight kinds of question, the four-options-one-key rule, repeat detection and single-question rewrites; quizzes drawn with a recorded seed; downloads as PDF and Word (notes) and PDF, Word, Excel and CSV (banks and quizzes).
- ✅ **E5 (done, 24 November 2026):** academic QA: notes checked claim by claim against the course documents, every claim's basis labelled, questions answered without seeing their keys, an optional second model, an issue log with critical, major and minor issues, corrections proposed and accepted or rejected by a person, rechecks, dismissal with a reason, and the blocking rule (a planted wrong answer key blocks approval, in the app and in PostgreSQL). Also a QA report per topic with notes, questions and combined views, counts per category, a transparent score that never hides a critical issue, its own approval stage, and PDF and Word downloads.
- ✅ **E6 (done, 24 November 2026):** YouTube SEO. Keyword research through YouTube's Data API within a daily search budget shared by the install, counted per Pacific day, with a 7-day cache (autocomplete and Keyword Planner excluded, as specified). Also: Google Trends CSV import, the documented opportunity score (partial without Trends), competitor analysis from public numbers only, content gaps, 12 to 15 titles scored on the nine criteria, and an approvable SEO report per topic with the 23 parts and five plans, as PDF, Word and Excel. Every number carries a source or an "estimated" label, and approval is blocked otherwise.
- ✅ **E7 (done, 24 November 2026):** presentation studio. Decks are made from approved notes only after the topic's academic QA and SEO reports are approved, which is also enforced in the database. They have the 15 slide types, 8 layouts and 7 styles, and are edited as data: text, layouts, tables, diagrams, images, reordering and speaker notes, with single-slide redo as a proposal and version history. They export to PowerPoint and PDF. Text is measured with metric-compatible fonts; overflow blocks approval, and every export is rendered and checked character by character.
- ✅ **E8 (done, 24 November 2026):** lesson and quiz videos.
  - **Sources:** lesson videos follow approved presentations; quiz videos use approved MCQ sets once the SEO report is approved. Both are enforced in the database.
  - **Narration:** taken from approved speaker notes and questions with no AI writing, edited before it's spoken, and spoken with a chosen voice. Google Cloud Text-to-Speech was added (service account or API key) for Tamil voices; OpenAI TTS was left out, since OpenAI shuts down its speech models on 6 January 2027.
  - **Templates:** animated slides, diagrams, code, equations, keyword highlights, a quiz countdown and answer reveal, captions, and 16:9 or 9:16.
  - **File checks:** every render is checked with ffprobe and ffmpeg before it counts. A video can be approved only with a passing render of exactly its content.
- ✅ **E9 (done, 24 November 2026):** YouTube publishing and analytics.
  - **Authorisation records:** nothing uploads without a record of who authorised exactly which file and settings, enforced in PostgreSQL; public posts need a confirmation; only a project Owner or an administrator authorises an education video, and only its approved version's checked render, with the rights confirmed.
  - **Publishing:** approved SEO title, description and tags; thumbnail; playlists (choose or create); captions from the render's timing; private, unlisted, public or scheduled; upload now or later; quota shown; retries on quota errors and expired sign-ins; history with video address and the authorisation.
  - **Analytics:** YouTube Analytics API numbers per period (views, watch time, durations, retention, subscribers, traffic sources, search terms, likes, comments, shares), impressions and click-through rate from the Reporting API reach report imported daily, stored snapshots, and rule-based recommendations that show their numbers and sources.
  - **Not verified live:** no Google credentials were available; built and tested against Google's documented APIs.
- ✅ **E10 (done, 24 November 2026):** production readiness.
  - **Dashboard (module 02):** counts from the database for the projects you can see (subject projects, documents, analysed modules, approved topics, notes, MCQs, QA reports, pending approvals, SEO reports, presentations, videos, published videos, active jobs), "Waiting for you" (reviews, changes asked for, videos ready to publish, failed jobs), running work and recent decisions.
  - **Notifications:** written by the database for review requests, decisions, finished or failed AI jobs, renders and posts; a bell with an unread count.
  - **Tests:** the SQL suites moved into the repository (`backend/src/test/sql`), a whole-workflow test from syllabus to an authorised YouTube publication, and a CI workflow (backend, database, frontend, renderer, document service, secret scan).
  - **Documents:** architecture with diagrams and the main tables, administrator guide, user guide, acceptance criteria with evidence, progress.
  - **Not done here:** a real Maven build and a run in a real Spring server (Maven Central isn't reachable here); live provider calls.
- ✅ **E11 (done, 25 November 2026):** topic analysis and automatic follow-on work.
  - **Topic analysis** per teaching unit (objectives, concepts, misconceptions, question plan, video plan, gaps), reviewed and approved before anything follows; quotes checked against their pages; gate `LX024`.
  - **Automation (opt-in per project):** topic list approved → analyses; analysis approved → notes; notes approved → questions following the plan; notes, QA and SEO approved → presentation following the video plan. Drafts only, nothing replaced, run as the approver, who is notified.
  - **Not verified live:** no AI provider was called; no Maven build.
- ✅ **Windows desktop app (25 November 2026):** `desktop/` (Electron). Opens your server in its own window; tray, Windows notifications, offline page, change server. Sandboxed pages, no stored secrets, links outside the server open in the browser. Unsigned for now.
- **Education increments E1 to E11 are complete.** Next, from the older plan: Increment 9 (more social platforms) and the rest of Increment 10 below, or anything you change.

- ✅ **Increment 8 (done, 20 November 2026):** script generator, storyboard studio, character studio, scene → image, video and voice links, storyboard → editor export, project workspace.
- **Increment 9:** publisher fills post text from a script; LinkedIn, X, Pinterest, Threads, Reddit and Telegram adapters; Snapchat and WhatsApp Channels export packages; publish confirmation; per-platform video shape; calendar filters.
- **Increment 10:** extra analytics, FFmpeg scene detection and settings overviews. (Notifications, the fuller dashboard and the full-workflow test were done in E10; Google Cloud TTS in E8; OpenAI TTS was left out, see E8.)
