# Leenax AI Video Studio

Your private studio for AI video, voice, editing, thumbnails and social publishing. It is built for one owner, not as a commercial SaaS.

This folder contains **all seven phases**: the foundation; AI provider accounts and video generation; voice, audio, subtitles, images and thumbnails; the multi-track video editor with MP4 export; the AI auto-editor; social publishing with a calendar; and analytics, two-step sign-in, health monitoring and automatic backups.

| Phase | Scope | Status |
|---|---|---|
| **1** | Foundation, owner sign-in, dashboard, database, projects, media uploads | **Built** |
| **2** | AI provider accounts, encrypted API keys, credits, video generation | **Built** |
| **3** | Voice (Tamil/English), audio tools, subtitles, image generation, thumbnails | **Built** |
| **4** | Multi-track video editor | **Built** |
| **5** | AI automatic editor, script-to-video | **Built** |
| **6** | Social accounts (OAuth), multi-platform publishing, calendar | **Built** |
| **7** | Analytics, MFA, monitoring, hardening | **Built** |

All 17 sections in the sidebar are live.

## What's in Phase 1

- **Owner account.** It is created once with a setup token from the server. After that, nobody else can register. BCrypt password hashing, a server-side session in Redis, an HttpOnly + SameSite=Strict cookie, CSRF protection, and a login lockout after 5 failed attempts.
- **Dashboard.** Your latest media laid out on a timeline, totals, and recent projects.
- **Projects.** Create, edit, filter, search, archive and delete. Each project records its format (16:9, 9:16, 1:1) and language (English or Tamil).
- **Media library.** Drag-and-drop uploads with progress. The real file type is checked from the file's bytes. FFmpeg reads the duration and resolution and makes a poster frame. Files go into private storage, and the browser only gets signed links that expire after 30 minutes. You can preview, rename, move between projects, download and delete files.
- **Settings.** Profile, password change, and light/dark theme.
- **Audit log** of sign-ins, password changes, uploads and deletions (`audit_logs` table).
- Responsive layout for desktop, tablet and phone.

## What's in Phase 2

- **AI accounts & keys.** You can connect several accounts per provider: Runway, Google Gemini, Kling AI and Luma for video, and OpenAI, Anthropic and Gemini for prompt writing.
  - Each key is checked with the provider before it is saved.
  - Keys are encrypted with AES-256-GCM using your `LEENAX_MASTER_KEY`, and they are only ever shown masked (`••••1234`).
  - Each provider has one default account. You can switch accounts off, replace keys, and re-check them.
- **Credits & usage.**
  - Balances are read automatically where the provider has an official balance API (Runway, Kling, Luma). Otherwise you enter them by hand.
  - Track free and paid credits separately, with expiry dates, and set a low-credit warning level per account.
  - The cost of every generation is logged, marked as reported by the provider or estimated, along with 30-day totals.
  - Model prices are editable, because providers change them often.
- **AI video generator.**
  - Generate from text or from an image in your library, and pick the account and model for each run.
  - Length, format, resolution and sound settings follow what each model actually supports.
  - "Improve prompt" rewrites your idea with a text model, with Tamil-aware instructions.
  - Generate 1 to 4 takes at once, with a cost estimate and an optional confirmation before anything is billed.
  - Live progress, cancel and regenerate, "use these settings" to iterate on a take, and side-by-side comparison of up to 4 takes.
  - Finished videos are saved to the media library (and to a project, if you choose one).
- **Background worker** that survives restarts, retries rate limits and outages with backoff, and protects against double-clicks with idempotency keys.

Models seeded from the official docs (November 2026):
- Runway: Gen-4.5, Gen-4 Turbo, plus Veo 3.1, Veo 3.1 Fast and Kling 3.0 through Runway
- Google: Gemini Omni Flash 1.1, which replaced the Veo 3.1 preview models that shut down on 22 October 2026
- Kling: 4.0 and 4.0 Flash
- Luma: Ray 2 and Ray Flash 2

## What's in Phase 3

- **Voice & audio** (`/create/voice`), with ElevenLabs or Google Gemini accounts:
  - Tamil and English voice-overs from any provider voice or a saved voice. Long scripts are split at paragraph and sentence boundaries, made in parts with the neighbouring text for smooth joins, and joined into one MP3.
  - Delivery styles (natural, storytelling, documentary, professional, happy, excited, sad, angry), speed (0.5–2×) and pitch (±6 semitones). ElevenLabs is steered with voice settings, Gemini with a written direction. Speed outside ElevenLabs' 0.7–1.2 range and all pitch changes are done with FFmpeg.
  - Pauses: type `[pause 1s]` anywhere. They become the provider's own pause syntax.
  - **Pronunciation rules** ("say Leenax as Lee-nax"), applied to every script before it is sent.
  - **Dialogue**: one line per turn (`Kani: …`), one saved voice per speaker (up to 6), joined with a gap you choose.
  - **My voices**: saved voices with their style, speed and pitch.
  - **Clone your own voice** (ElevenLabs instant cloning). It needs recordings (uploaded or recorded with the microphone in the browser), the "this is my own voice" box ticked, and the consent sentence typed exactly. The consent, the time and the SHA-256 of each recording are stored with the voice. Deleting the voice also deletes it from your ElevenLabs account.
  - **Sound effects** from a description (ElevenLabs, 0.5–30 s, optional seamless loop).
  - **Audio tools**, which run on your server with FFmpeg and use no credits: noise removal, trimming long pauses, voice enhancement, loudness to −14 LUFS, speed and pitch, background music that loops, fades and automatically dips under speech, and extracting audio from a video. Originals are never changed.
  - A short "Preview first lines" before you spend credits on a long script.
- **Subtitles** (`/edit/subtitles`), with ElevenLabs Scribe or OpenAI Whisper:
  - Word-timed transcription becomes readable subtitles: at most two lines of 42 characters (28 for Shorts and Reels), at most 6 seconds each, split at sentence ends and natural pauses.
  - Edit text and timing next to the video, with the subtitles shown live on the preview, "now" buttons to set times from playback, a nudge for all subtitles, and warnings for overlaps.
  - Translate into 14 languages with any text model. Timing is copied exactly, and only the words change.
  - Download as SRT or VTT.
- **AI image generator** (`/create/image`) with OpenAI GPT Image 2.5 or Google Nano Banana: sizes and shapes per model, 1–4 images, transparent PNGs (OpenAI), and up to 4 reference images from your library.
  - **Background removal** runs on your own server (the `imagetools` service, rembg). It costs nothing and sends nothing to a third party.
  - **Resize** to YouTube, Instagram, Story, Facebook or X sizes, or a custom size, by filling and cropping or by fitting with coloured or transparent bars.
- **Thumbnail studio** (`/create/thumbnails`): a layered canvas editor.
  - Layers: text with outline, shadow and a label box; images from your library; full-bleed backgrounds; boxes, circles and arrows.
  - Bundled fonts with Tamil letters (Anek Tamil, Catamaran, Hind Madurai) plus Anton and Bebas Neue. Tamil text in an English-only font falls back to a Tamil font automatically.
  - Undo and redo, keyboard nudging, and lock or hide per layer.
  - Three built-in templates, and you can save your own designs as templates.
  - **Variations**: the same layout in 4 other colour and type schemes, saved as separate designs for A/B tests.
  - **Other sizes**: copy a design to Instagram, Story or link-preview sizes, scaled proportionally.
  - Export PNG or JPG at full resolution to the media library.
- Microphone recordings (WebM/Opus or MP4/AAC) are stored as audio, not as video.
- Every voice, image, transcription and sound call is written to `usage_logs`.

Models seeded (November 2026):
- ElevenLabs: Eleven v3, Multilingual v2 and Flash v2.5, all of which support Tamil
- Gemini TTS: 3.8 Flash and Flash-Lite
- OpenAI: GPT Image 2.5 Flare and Sunburst (`gpt-image-1` shut down on 23 October 2026)
- Google: Nano Banana 2.1, Nano Banana 2 Lite and Nano Banana Pro

OpenAI's text-to-speech is deliberately not offered: its TTS models are announced to shut down on 6 January 2027.

## What's in Phase 4

- **Video editor** (`/edit/timeline`): timelines for YouTube (16:9), Shorts/Reels/TikTok (9:16), square and 4:5, at 24–60 fps.
  - **Tracks:** any number of video, audio and text tracks. The top track is drawn in front. Each track can be muted, hidden, locked, renamed and given its own volume.
  - **Clips:** drag files from the media bin (or press "Add" to place one at the playhead). Clips can be moved between tracks, trimmed from either edge, split at the playhead (S), duplicated (Ctrl+D) and deleted. Shift+Delete closes the gap.
  - Clips never overlap on a track. Snapping to clip edges and the playhead can be turned off.
  - **Undo and redo** for every edit (a whole drag counts as one step). **Autosave** runs 1.5 s after each change. If another tab saved meanwhile, you choose which version to keep, so nothing is silently overwritten.
  - **Per clip:**
    - Sound: volume up to 200%, fade in and out.
    - Speed: 0.5× to 2× (0.25× to 4× allowed), keeping the same footage.
    - Picture: fit or fill, position, size, rotation and opacity.
    - Colour: brightness, contrast, saturation and blur.
    - Movement: slow zoom or pan (Ken Burns).
    - Transitions in and out: fade, slide, zoom, wipe and blur.
  - **Text:** title, lower-third and caption presets, using the same Tamil and English fonts as the Thumbnail studio. Outline, box, shadow, position and width are adjustable, with in and out animations (fade, rise, pop, typewriter).
  - **Captions:** add any subtitle track made in Subtitles as caption clips, lined up with where the video sits on the timeline. "Use this style for all captions" restyles them all at once.
  - **Preview:** the Remotion Player plays the timeline with sound, using the same composition the server renders, so the export matches the preview.
  - **Shortcuts:** Space, S, Del, Shift+Del, Ctrl+Z/Y, Ctrl+D, ←/→ (one frame; with Shift, one second), Home/End, +/−.
- **Export:** MP4 (H.264 + AAC) at 720p, 1080p, 1440p or 4K. Quality is Standard, High or Best (a slower encode). Text is laid out at the timeline's size and drawn sharp at the export size.
  - Exports run in the background on your own server, with progress, cancel, retries if the render service restarts, and a 3-hour limit.
  - The finished video goes into the media library (and the timeline's project). It also becomes the timeline's cover picture.
- **Render service** (`renderer/`): Node + Remotion + headless Chrome in its own container, reachable only by the backend, which uses a shared token. It renders one export at a time and reads media through short-lived internal links. It never sees your API keys.
  - Remotion is free for individuals and companies of up to 3 people, which covers this personal studio. A larger company would need Remotion's company license.

## What's in Phase 5

The **AI auto editor** (`/edit/auto`) makes an editable first cut. The result is an ordinary timeline in the video editor, so you can change anything before exporting. It runs in the background; you can leave the page.

- **Script to video:**
  - **Script:** paste your script, or give a topic, length and style and let your AI text model (OpenAI, Claude or Gemini) write it in Tamil or English. It is split into scenes, each with what to show and optional on-screen words.
  - **Voice-over:** any voice from My voices, including your cloned voice, recorded scene by scene. Each scene lasts exactly as long as its narration.
  - **Your footage:** the AI can look at 3 small frames from each clip to learn what it shows (remembered per file, so each clip is described once). It then picks shots for each scene from your videos and images. The app makes sure every shot really exists in the source, has a usable length, and that each scene is filled exactly.
  - **No footage:** your image account can make one picture per scene (up to 12) with slow zoom and pan. Otherwise each scene becomes a title card.
  - **Finishing:** captions timed to the narration, a title, on-screen words as lower thirds, B-roll sound muted, fades at the start and end, and music under the voice.
- **Clean up a talking video:**
  - Transcribes your clips once (ElevenLabs or OpenAI) and keeps only the parts where you speak.
  - Pauses longer than a set length are cut (0.45 s by default), with a little air left around each phrase. Filler sounds like um, uh and ம்ம் are removed too.
  - Optional zoom on every other cut so jump cuts look deliberate, plus captions re-timed to the new edit and background music.
- **Shorts from a long video:**
  - Your AI text model reads the timestamped transcript and picks up to 5 self-contained moments of your chosen length, each with a title.
  - Each moment is snapped to whole words and sentence boundaries, and pauses inside it are tightened.
  - Each Short becomes its own vertical timeline with the title, large captions and optional music.
- **Cost control:**
  - Transcripts and footage descriptions are cached in `media_analyses`, so a file is never transcribed or described twice, even if you run an edit again.
  - Every AI call is written to `usage_logs`.
  - At most 3 automatic edits run at a time, one after another.
- The text adapters can now send pictures, and replies from Claude can be longer (8,192 tokens instead of 1,024). This also helps long subtitle translations.

## What's in Phase 6

Publishing goes through each platform's **official API and official sign-in page** only. There is no scraping, no browser automation and no password handling.

- **Social accounts** (`/publish/accounts`):
  - **YouTube:** sign in with Google (OAuth with PKCE). Connects your channel.
  - **Facebook Pages and Instagram:** one sign-in with Meta (Facebook Login for Business). Connects every Page you choose, plus the Instagram professional account linked to each Page.
  - **TikTok:** sign in with TikTok (Login Kit).
  - Tokens are encrypted with `LEENAX_MASTER_KEY` (AES-256-GCM, bound to the account and token type) and never reach the browser. Google and TikTok tokens are refreshed automatically before they expire. If a platform revokes access, the account is marked **Reconnect** and its posts stop with a clear message.
  - A platform without credentials in `.env` shows the exact redirect address to register and what to set.
- **Publisher** (`/publish/new`):
  - Pick a video (and optionally a thumbnail for YouTube), tick the accounts, write one caption with hashtags and copy it to every platform, then adjust each platform's own fields.
  - **YouTube:** title, description, tags, category, visibility, the required "made for kids" answer, and the altered/synthetic content disclosure. Resumable upload, so a dropped connection carries on where it stopped.
  - **Facebook Page:** posts as a Reel (3–90 s).
  - **Instagram:** posts as a Reel (3 s to 15 min, up to 300 MB), with cover frame and "show in grid". Checks the account's 100-posts-per-day allowance first.
  - **TikTok:** follows TikTok's required posting screen. It shows your TikTok name, makes you choose who can see the post (no default), keeps comments/duet/stitch off unless you allow them (and greys out what you turned off in TikTok), has the commercial-content disclosure and the Music Usage Confirmation. You can also **send the video to the TikTok app** and finish it there.
  - **Post now, schedule, or save a draft** (a draft can have a planned date).
  - Problems the platform would reject (missing title, Reel too long, too many hashtags…) are shown before you post.
- **Calendar** (`/publish/calendar`): month, week and day views. Drag a draft or scheduled post to another day to move it (the time of day is kept). Undated drafts sit beside the calendar. Click any post for each account's status, links, warnings and **Try again**. On phones, the month view shows a dot per post.
- **Publishing worker:** posts are leased from Postgres (like exports), two at a time. Each account is one target with its own status: Scheduled → Uploading → Processing → Published (or Failed). Upload progress is saved after every step that can't be undone, so a restart or retry carries on and never posts twice. Rate limits and server errors are retried with back-off (up to 6 tries, honouring `Retry-After`). Daily caps wait hours rather than hammering the API.
- **Honest results:** if YouTube keeps an upload private because your Google project isn't audited, or TikTok posts it privately, the post says so instead of pretending it's public.

### Social publishing set-up

You need your own developer app on each platform. For personal use, the apps can stay in development/testing mode with you as the only user.

1. **Your address.** Set `PUBLIC_BASE_URL` in `.env` to the address you open the studio at (`http://localhost:8088` locally). The callback addresses are shown on the Social accounts page.
2. **YouTube (Google Cloud Console):**
   - Create a project, enable **YouTube Data API v3**, and for E9 also **YouTube Analytics API** and **YouTube Reporting API**.
   - OAuth consent screen: External; add yourself as a test user; scopes `youtube.upload`, `youtube.readonly`, `youtube.force-ssl` (playlists and captions) and `yt-analytics.readonly` (analytics and reach reports).
   - A channel connected before E9 has only the first two; the Publish to YouTube page asks you to reconnect it.
   - Credentials → OAuth client ID → **Web application**; authorised redirect URI `<PUBLIC_BASE_URL>/api/v1/social/oauth/google/callback`.
   - Put the client ID and secret in `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`.
   - While the consent screen is in **Testing**, Google expires the sign-in after 7 days, so you'd reconnect weekly. Setting it to **In production** (you can stay unverified for your own use; you'll see a warning screen) avoids that.
   - Uploads from a project that hasn't passed **YouTube's API audit** are locked to private. You can apply for the audit from the API's quota page.
3. **Facebook and Instagram (developers.facebook.com):**
   - Create a **Business** app; add **Facebook Login for Business**.
   - Valid OAuth redirect URI: `<PUBLIC_BASE_URL>/api/v1/social/oauth/meta/callback`.
   - Permissions used: `pages_show_list`, `pages_read_engagement`, `pages_manage_posts`, `instagram_basic`, `instagram_content_publish`, `business_management`. In development mode, they work for people with a role on the app (you).
   - Your Instagram account must be a **professional** account linked to a Facebook Page. If Meta asks for **Page Publishing Authorization**, complete it on the Page.
   - Set `META_APP_ID` and `META_APP_SECRET` (and `META_GRAPH_VERSION` if you want a version other than v26.0).
4. **TikTok (developers.tiktok.com):**
   - Create an app; add **Login Kit** and **Content Posting API**, and turn on **Direct Post**.
   - Redirect URI: `<PUBLIC_BASE_URL>/api/v1/social/oauth/tiktok/callback`. TikTok requires **https**, so TikTok works once the studio is online (see "Put it on the internet").
   - Scopes: `user.info.basic`, `video.publish`, `video.upload`.
   - Until the app passes TikTok's audit, your TikTok account must be **private** and posts are visible only to you.
   - Set `TIKTOK_CLIENT_KEY` and `TIKTOK_CLIENT_SECRET`.
5. Restart: `docker compose up -d backend`.

Scheduled posts go out only while the studio is running. If the PC was off at the scheduled time, the post goes out as soon as it's back on.

## What's in Phase 7

- **Analytics** (`/analytics`), for the last 7, 30 or 90 days in your time zone:
  - **Publishing:** posts published per day by platform (hover a day for the numbers, or switch to the table), totals and failures per platform, and the latest failures with their reasons.
  - **How posts are doing:** views, likes, comments and shares per platform and per post, read from each platform's official API every 6 hours for posts from the last 90 days, plus a "Refresh numbers" button (at most every 10 minutes). Each read is kept as a snapshot.
    - **YouTube:** `videos.list` statistics. Hidden like counts stay blank.
    - **Facebook Reels:** `video_insights` (plays, reactions, comments, shares), or like and comment totals when insights aren't allowed.
    - **Instagram:** media insights (views, reach, likes, comments, shares, saves), or like and comment counts without the insights permission.
    - **TikTok:** the Display API video query. It works only for public posts.
    - A number the platform didn't give is shown as "—", never as 0. A post with no numbers says why.
  - **AI costs:** spend per day for each currency or credit type separately (credits and dollars are never added together), cost by provider and use, failed calls, and calls with no reported cost.
- **Two-step sign-in** (Settings → Security), optional:
  - Works with any authenticator app (RFC 6238 codes, set up from a QR code). The secret is encrypted with `LEENAX_MASTER_KEY`.
  - Gives you 10 single-use recovery codes, stored only as hashes. Each code can be used once, and an app code can't be reused within its 30 seconds.
  - Sign-in becomes password → code. The code step allows 5 tries in 5 minutes, and failures count towards the sign-in lockout.
  - Turning it off or making new recovery codes needs both your password and a code.
- **Recent activity** (Settings → Security): sign-ins (including failed ones, with the address they came from), security changes, account connections, publishing and deletions, with filters.
- **System health** (Settings → System):
  - Whether the database, Redis, media storage, export service and background-removal service are working.
  - Background queues (waiting, running, failed in 24 hours), storage use, uptime, and the last backup with free disk space.
  - Plain warnings, for example "the last good backup is more than a day old" or "1 social account needs reconnecting".
- **Request limit:** at most 600 API requests per minute from one address (`API_REQUESTS_PER_MINUTE`); live event streams aren't counted. Sign-in keeps its own stricter lockout.
- **Automatic backups:** the new `backup` service (see "Backups").
- **Faster first load:** Analytics and Settings load only when opened.

### Backups

The `backup` service runs inside Docker Compose. Every day at `BACKUP_TIME` (03:15 by default, in `TZ`), it writes `backups/<date-time>/`:
- `leenax.dump`: the whole database. Each run checks that the dump can be read back.
- `storage.tar.gz`: every media file.

It keeps the newest `BACKUP_KEEP` (14), and catches up on start if the last backup is more than a day old (for example, when the PC was off at 03:15). The result shows on Settings → System. You can still run `scripts\backup.ps1` / `scripts/backup.sh` by hand.

**Copy the `backups` folder somewhere else** (another drive, or cloud storage) and keep a copy of your `.env`. Saved API keys, sign-in tokens and the two-step secret in the database are encrypted with `LEENAX_MASTER_KEY`, which is not in the backup on purpose. A backup can't be read without that key.

To restore (this replaces everything):
```
powershell -ExecutionPolicy Bypass -File .\scripts\restore.ps1 .\backups\20261119-031500
./scripts/restore.sh backups/20261119-031500          # Linux / macOS / WSL
```

### Production checklist

Already done in the code and setup:
- **Sign-in and sessions:** single owner; BCrypt passwords; optional two-step sign-in; sign-in lockout; HttpOnly SameSite=Strict session cookie; CSRF on every change.
- **Secrets:** API keys, OAuth tokens and the MFA secret are encrypted, and no secret ever reaches the browser.
- **Media:** private storage with expiring links; uploads checked by content.
- **Limits and logs:** a per-address request limit and an audit trail.
- **Network:** the database, Redis and storage ports are bound to 127.0.0.1.
- **Recovery:** daily verified backups and a restore script.

When you put it online:
1. In `.env`, set `COOKIE_SECURE=true`, `PUBLIC_BASE_URL=https://studio.your-domain`, `MEDIA_PUBLIC_URL=https://media.your-domain`, and long random values for every password and token.
2. Use `deploy/nginx-https.example.conf` with Certbot. It already forces HTTPS.
3. Turn on two-step sign-in straight after your first sign-in, and remove `SETUP_TOKEN` from `.env`.
4. Allow only ports 80 and 443 in the server firewall. Everything else listens on 127.0.0.1.
5. Copy `backups/` off the server automatically (rclone, rsync or your cloud provider's backup), and test a restore once.
6. Check Settings → System after updates. Each sign-in, connection and post is listed under Security → Recent activity.

## Running it on a cloud server (Oracle Cloud or AWS)

`deploy/cloud/` puts the whole studio on one Ubuntu server with automatic HTTPS, so you and your users can reach it from anywhere, in the browser or the desktop app. Install and update it from the server's own terminal (or with one command from Ubuntu/WSL/macOS, or PowerShell on Windows); another command copies its nightly backups to your computer.

- **Oracle Cloud Always Free** (ARM, 2 OCPUs, 12 GB): free with no time limit. `deploy/cloud/README-oracle.md`
- **Automatic deploys from GitHub:** every push to `main` that passes the checks is deployed to the server. `deploy/cloud/README-github.md`
- **AWS Free plan** (EC2 m7i-flex.large, 2 vCPU, 8 GB): free while the sign-up credits last (a few weeks to about 2 months running all day), then the account closes unless you upgrade and pay. `deploy/cloud/README-aws.md`

## Leenax Studio for Windows (desktop app)

`desktop/` builds **Leenax-Studio-Setup-1.0.0.exe**, a Windows app that opens your Leenax server in its own window, with a tray icon, Windows notifications for new Leenax notifications, and a page that waits for the server when it's down. The server still runs as before and all five people share it; the app keeps only the server's address. See `desktop/README.md` for installing (it's unsigned for now: "More info → Run anyway"), security and building.

## What's in E11: topic analysis and automatic follow-on work (education increment 11)

- **Topic analysis** (`/projects/{id}/analysis`, opened from the project page and from Notes and questions). Each teaching unit in the approved topic list is analysed by the AI (Prompt 1 of the prompts document) before anything is written for it:
  - a summary and difficulty, prerequisites, learning objectives with Bloom levels and module outcomes, key concepts, common misconceptions, examples, exam relevance;
  - a **question plan** (questions per objective, with difficulty and styles) that adds up to the number of questions chosen;
  - a **lesson video plan**: an opening hook and up to 12 scenes with their goal, what each shows and how long it runs;
  - gaps in the course documents and questions for the lecturer.
  - Every quote it cites is checked against the page it names; citations of pages it wasn't given are removed and counted. Items not from the documents are marked "general knowledge".
  - A teaching unit is a selected topic under a chapter, or a selected chapter with no selected topics.
- **Review before anything follows.** The analysis is a draft you can edit (objectives, concepts, misconceptions, the plans, the hook and scenes) and send for review. Approval is blocked unless the topic is still in the approved topic list, there's at least one objective, the question plan adds up and the video plan has scenes. Quotes not found on their page, a video plan more than a quarter off the length asked for, and open gaps are warnings. The database refuses to approve an analysis without an approved topic list (`LX024`).
- **Automation, per project, off until you turn it on** (owners and editors, with the AI account and model to use, the notes level, questions per topic and video length). With it on:
  - approving the **topic list** queues an analysis for each teaching unit that doesn't have one;
  - approving an **analysis** queues the **notes** at the chosen level;
  - approving those **notes** queues the **questions**, following the question plan (objective by objective, using the misconceptions as wrong options); each question's subtopic starts with its objective ("O2: …"), and the job summary says how the plan was covered;
  - once the notes, the **academic QA report** and the **SEO report** are approved, the **presentation** is queued, following the video plan: scenes in order, speaker notes sized to each scene at about 150 words a minute (120 for Tamil). The lesson video is then made from the approved presentation on the videos page, as before.
  - Each step is a draft a person reviews; QA checks and SEO research still need a person to start them. Nothing that already exists is replaced. A job runs as the person whose approval started it, is marked "started automatically", and that person is told when it finishes or fails. The settings dialog shows how many AI requests this can make; they're charged to the chosen account.
- The automatic start is done by the database (V19) when a version becomes approved, including a reviewer's approval with edits, so it happens whoever approves and however.
- **The dashboard** counts topic analyses and approved ones; notifications and the task list link to them.

### E11 limits

- The AI prompts were not run against a live provider here (no keys were used). The reply reader, the tidying and the checks are unit-tested with sample replies.
- Maven Central isn't reachable here: the backend was type-checked against stubs and its unit tests run with a JUnit shim, not with `mvn verify`.

## What's in E10: production readiness (education increment 10)

- **The dashboard (module 02)** now starts with your university work, counted in the database for the projects you can see:
  - subject projects, course documents, analysed modules (approved syllabus reports), approved topics, notes, MCQs, academic QA reports, pending approvals, approved SEO reports, presentations, videos, published videos and running jobs;
  - **Waiting for you:** versions to review (never your own), changes asked for on your work, approved videos ready to publish (owners and administrators), and your AI jobs that failed in the last week and weren't tried again;
  - **Running now** (AI jobs and renders, with progress) and **Recent decisions**.
- **Notifications.** A bell in the sidebar (top bar on a phone) with the unread count, checked every minute. The database writes them, so no code path can forget one:
  - a version sent for review → the project's owners and reviewers, except its author;
  - approved, sent back (with the comment), rejected or made outdated → its author;
  - an AI job, a render or a post finished or failed → the person who started it, with the reason;
  - never for what you did yourself, and never to deactivated people. Each person sees, marks read and removes only their own.
- **The whole workflow in one test** (`backend/src/test/sql/e10.py`): one subject goes syllabus → topics → notes, questions, QA and SEO → presentation → lesson video with a checked render → authorised YouTube publication → analytics snapshot, by an owner, an editor and a reviewer, with every gate refusing before its step is approved, and the notifications and dashboard checked for each person.
- **The SQL suites are in the repository** (`backend/src/test/sql`, `run_all.sh`). They need `LEENAX_TEST_DSN` pointing at a throwaway database, because they create and delete people.
- **CI** (`.github/workflows/ci.yml`): Maven build and tests; every migration on an empty PostgreSQL followed by all SQL suites; frontend type check, tests, build and `npm audit`; renderer and document-service tests; a scan for committed keys. It runs if you push the folder to GitHub.
- **Documents:** `docs/ARCHITECTURE.md` (the pieces, who sees what, module dependencies, the approval workflow and its gates, the main tables, folders, why these choices), `docs/ADMIN-GUIDE.md`, `docs/USER-GUIDE.md`, `docs/ACCEPTANCE.md` (each acceptance criterion with its evidence, and what hasn't been tried live) and `docs/PROGRESS.md`.
- **Tighter grants:** the app's database role can no longer change or delete authorisation records (the trigger already refused it).

## What's in E9: YouTube publishing and analytics (education increment 9)

- **Nothing uploads without an authorisation record.**
  - Posting or scheduling records who authorised exactly which file with exactly which settings (a hash of them) for each account, with a statement and the time. The record can't be changed.
  - The database refuses to move a post into uploading, processing, waiting or published unless such a record matches the post's current file and settings (`LX020`). Changing the video or a setting afterwards needs a new authorisation; the publisher marks an unmatched post failed with "Not authorised" instead of uploading.
  - Only a signed-in person can authorise, only their own post; background jobs can't.
  - **No public post without confirmation** (`LX021`): YouTube public, or private with a time YouTube makes it public; TikTok other than "Only me"; every Facebook and Instagram post. The creator publisher has a confirmation box, and so does "Try again" on a failed public post.
  - Posts scheduled before E9 have no record: they fail with "Not authorised" and are authorised by trying them again.
- **Publish to YouTube** (`/projects/{id}/youtube`, module 22):
  - Channels with their details (subscribers unless hidden, videos), and which of them need reconnecting for playlists, captions or analytics.
  - **Ready to publish:** approved lesson and quiz videos whose render passed its file checks. The file uploaded is that render.
  - **Approved metadata only:** a title chosen from the titles of the topic's approved SEO report (its choice first), the approved description and the approved tags, shown as sent. Hashtags are added to the description only if you tick it. Text that breaks YouTube's limits isn't shortened; the page says to edit the SEO report.
  - **Thumbnail, playlists** (choose up to 5, or create one; the SEO report's playlist ideas fill in the name), **captions** (an SRT file made from the checked render's own caption timing, so the words and times are the approved video's), **visibility** (private, unlisted, public, or scheduled: private until YouTube makes it public), upload now or at a set time, "made for kids" and the altered or synthetic content disclosure (both must be answered), notify subscribers, category Education.
  - **The authorisation step:** a summary sentence of what will happen, "I hold the rights" (required) and the public confirmation. Only the project **Owner** or an **administrator** can authorise (`LX022`), only for an approved video version and its checked render. A newer approved version means reviewing again.
  - **Quota:** the page shows the YouTube API units the publication uses (upload 1,600, thumbnail 50, each playlist 50, captions 400, of 10,000 a day by default). A quota error or expired Google sign-in is reported and the step is tried again later; the video is never uploaded twice.
  - **History:** every publication with its status, video address, errors, and who authorised it with what they confirmed. Project members see it even though posts are personal.
- **YouTube analytics** (`/projects/{id}/youtube/analytics`, module 23):
  - **From the YouTube Analytics API** for the project's published videos on a channel, for a chosen period (7, 28, 90 days or any dates): views, watch time, average view duration, average percentage viewed, subscribers gained, likes, comments, shares, views per day, audience retention per video, traffic sources and YouTube search terms.
  - **Impressions and click-through rate from the Reporting API's reach report** (`channel_reach_basic_a1`): the owner turns it on for the channel, YouTube makes a file a day (the first about two days later), and Leenax imports new files every day at 14:20 UTC (`YOUTUBE_REACH_IMPORT_CRON`) or on **Import now**. A corrected file for a day replaces that day. Click-through rate is weighted by impressions.
  - **Snapshots:** every refresh is stored with its period, time and person; the newest of a period is shown, and earlier ones can be reopened as they were. Nothing is fetched without **Refresh**.
  - **Retention** is drawn per video with the video's scenes (from the checked render) as bands, a hover read-out and a table.
  - **Recommendations** (best subjects, underperforming videos, drop-off points, strong sections, title and thumbnail, future topics, strategy) are rules applied to your own numbers. Each shows its rule and the numbers with their source, and says nothing until there are enough views or impressions. Drop-offs and strong parts are named by time and scene. Title suggestions are the other titles approved in the SEO report. No competitor's private analytics are used or claimed.

## What's in E8: lesson and quiz videos (education increment 8)

- **Videos** (**Lesson and quiz videos** on a university project, `/projects/{id}/videos`).
- **After approval only:**
  - A **lesson video** is made from an approved presentation (per topic and notes level).
  - A **quiz video** is made from the topic's approved MCQ set, once its SEO report is approved.
  - The rule is checked when the video is planned, before it can be approved, and in the database (`LX018`).
  - Changing the presentation, the MCQ set or the SEO report marks the video outdated.
- **No AI writing.**
  - A lesson video has one scene per slide, narrated from the slide's approved speaker notes. A slide without notes is read as written.
  - A quiz video reads each approved question with its four options, counts down, reveals the answer and reads the explanation, then shows the answers to count a score.
  - The linking sentences (introduction, ending, "the answer is …") are fixed wording in English or Tamil.
  - You can edit any narration before it's spoken. Slides and questions are shown exactly as approved; a check blocks approval if they differ.
- **Voice:**
  - You choose a voice account (ElevenLabs, Google Gemini or the new **Google Cloud Text-to-Speech**), a voice and a speed.
  - **Make the narration** speaks every part in the background.
  - Changing the words or the voice drops the old narration, so it can't be out of step.
- **Google Cloud Text-to-Speech** (new provider, for its Tamil voices):
  - **Sign-in:** the administrator adds either a **service account key file** (JSON; Leenax signs its own short-lived tokens) or an **API key**. Google documents service accounts for this API, so an API key may be refused; Leenax says so if it is. Either one is stored encrypted like other keys and checked by listing voices.
  - **Voices:** Tamil (ta-IN) and English (India, UK, US, Australia) voices are listed with their type and Google's list price.
  - **Pauses:** made with SSML. Chirp 3: HD voices don't take SSML, so for them a pause becomes a comma or full stop.
  - **No OpenAI text-to-speech:** OpenAI shuts down all its speech models on 6 January 2027, and its replacement doesn't use the speech endpoint, so no OpenAI voice was added.
- **The templates** (Remotion, the same ones the editor previews):
  - **Slides** keep the presentation's boxes, colours and measured text sizes. They use fonts with PowerPoint's character widths (Carlito, Cousine and Noto Sans Tamil, now bundled), so text that fit the slide fits the video.
  - **Building up:** bullets appear as they're narrated, keywords from the approved SEO report are highlighted, diagrams draw in, equations scale in, code types out, table rows reveal and images zoom slowly.
  - **Quiz:** a question card, options appearing in turn, a countdown ring, the right answer turning green and the explanation.
  - **Also:** captions burned in (can be turned off), a progress bar, and 16:9 or 9:16 (Shorts) with bigger type for phones.
- **Rendering** goes through the render service as an editor timeline (720p, 1080p or 4K).
- **Every render is now checked with ffprobe before it counts as done.** This covers the editor's exports too.
  - The checks: length, picture size, frame rate and frame count match the timeline; the picture is H.264 in 4:2:0; and when there's narration or audio, there is an AAC track of the same length that isn't silent (ffmpeg's volume meter).
  - A render that fails is marked failed with the reasons, and the file isn't kept.
- **"A rendered quiz video passes validation before it can be approved":** a video version can be approved only while a render of exactly its content finished and passed the checks.
  - This is enforced in the app and the database (`LX019`).
  - Approving with reviewer edits isn't offered for videos, because edits would need a new render.

## What's in E7: the presentation studio (education increment 7)

- **Presentations** (**Presentations** on a university project, `/projects/{id}/slides`): a slide deck per topic and notes level, edited in the app and downloaded as PowerPoint or PDF.
- **After approval only.** "No presentation before academic and SEO approval":
  - A deck is made only from **approved notes** at one level, once the topic's **academic QA report** and **SEO report** are approved.
  - The rule is checked when the deck is made, again before it can be approved, and once more in the database (`LX017`).
  - Changing the notes, the QA report or the SEO report marks the deck outdated.
- **Making a deck:** choose the notes level, one of the 7 styles, about how many slides (5 to 40), your AI account and model.
  - The AI works from the approved notes only. It reuses the notes' own diagrams and uses the SEO report's chosen title and keywords where they fit.
  - It writes speaker notes for every teaching slide.
  - It never writes references: Leenax adds a references slide with the notes' own references.
  - The result is a draft to edit and approve.
- **The 15 slide types** in the brief: title, learning objectives, introduction, topic overview, definitions, concept explanations, technical diagrams, flowcharts, practical examples, comparison tables, applications, exam tips, summary, revision questions and references.
- **8 layouts:** title, bullet points, bullets and image, term and definition, table, diagram, example with code, and image.
- **The 7 styles:** university lecture, modern minimalist, dark technology, animated infographic, whiteboard, corporate education and illustrated presentation.
  - "Animated infographic" is a static design. The PowerPoint file has no animations.
- **Editing** (the deck page, `/slides/{id}`):
  - Slides are data, not pictures. You can change the text, type and layout, reorder, add and delete slides, and edit tables.
  - You can draw a diagram (Mermaid) or an equation (LaTeX), and replace or remove an image from the project's media.
  - Speaker notes go into the PowerPoint notes.
  - **Redo with AI** rewrites one slide. You see it beside the current one and choose; nothing changes until you save.
  - Version history, review and approval work as for the other stages.
- **Text that doesn't fit is caught, not shrunk away:**
  - Every text box has a fixed size. The document service measures the text with fonts that have the same character widths as PowerPoint's: Carlito for Calibri, Liberation Mono for Courier New. Tamil is measured with Noto Sans Tamil plus a 12% margin, because Windows shows it in Nirmala UI.
  - The PowerPoint file uses those fonts, exact line spacing and no autofit, so the lines measured are the lines drawn.
  - Text is set at the largest size that fits, down to a readable minimum: 24 pt for titles, 16 pt for body text and 12 pt for code.
  - Anything that still doesn't fit is outlined on the slide, named in the checks, and blocks approval.
  - Every download is drawn again by LibreOffice before it is sent. If any character falls outside its box, the download is refused rather than sent.
- **The checks** (the brief's factual accuracy, source alignment, readability and layout overflow):
  - **Must pass:**
    - The text fits.
    - Every slide has what its layout shows.
    - The notes, QA report and SEO report are approved.
    - Every diagram is drawn.
    - Images are in the project's media.
    - References come only from the notes.
  - **Warnings:**
    - A slide whose content isn't found in the approved notes.
    - A number the notes don't contain.
    - Dense slides: more than 6 bullets, or a bullet over 25 words.
    - Text shrunk to the minimum size.
    - A missing title slide, references slide or speaker notes.
  - These are word checks against the notes, not a judgement of truth. The notes they compare against have already passed academic QA and approval.
- **Downloads:** PowerPoint (.pptx, 16:9) and PDF, of any saved version.

## What's in E6: YouTube SEO (education increment 6)

- **YouTube SEO** (**YouTube SEO** on a university project, `/projects/{id}/seo`): research per approved topic, a draft SEO report per topic to edit and approve.
- **Where the data comes from, and what isn't used:**
  - **YouTube Data API.** `search.list` gives the top 20 videos for a keyword; `videos.list` gives their public numbers (views, likes and comments where public, length, description, tags). Research uses a YouTube account the administrator connected for publishing, read-only.
  - **Google Trends, by hand.** On trends.google.com, compare terms, choose YouTube Search, download "Interest over time" and import the CSV. Values are relative (0 to 100 within that download) and are used only as a demand signal. The official Trends API is for approved testers, so it isn't connected.
  - **Your AI account** suggests keywords (labelled "AI suggestion") and writes titles, the description, tags and plans. It is told never to state figures.
  - **Not used** (specification, section 2): YouTube autocomplete, an unofficial endpoint; Google Ads Keyword Planner, which is for Google Ads campaigns and measures Google Search, not YouTube. No API gives YouTube search volumes, so none are shown.
- **The daily search budget.** `search.list` has a small daily allowance shared by everyone on the install, so every call is recorded.
  - The SEO page shows how many searches are left today. YouTube resets at midnight Pacific time; the time is shown in your own time zone.
  - A run can't ask for more searches than are left.
  - The same search within 7 days is reused from the cache, free.
  - If YouTube's quota runs out mid-run, the research stops searching, is marked partial, and says so.
  - The budget is a setting (`SEO_DAILY_SEARCHES`, default 100).
- **What a research run does:**
  1. The AI suggests 20 to 40 keywords in English and Tamil: primary, secondary, long-tail, question, beginner, exam, practical and regional.
  2. The best ones are searched on YouTube, up to 10 a run (default 5), taking turns between languages.
  3. Each keyword gets the documented opportunity score.
  4. Competitor videos are listed with their public numbers and the subtopics their titles and descriptions cover. Subtopics few of them cover are flagged as content gaps.
  5. The AI writes 12 to 15 titles across the eight styles, each scored 1 to 5 on the nine criteria in the brief, plus a recommendation and its reason. If its pick is clickbait or too long, Leenax picks the usable title with the highest score and says so.
  6. It also writes the description, tags, hashtags, thumbnail text and prompt, chapters from the notes headings, playlists, next videos (the next topics in the approved list and the prerequisites come first), and separate plans for lessons, MCQ videos, exam preparation, revision and Shorts.
- **The opportunity score** (specification, section 8):
  - The formula is `R × (wD·D + wC·(1 − C) + wG·G)`, each input 0 to 1 and shown beside the score:
    - R is relevance: the share of the keyword's words found in the approved topic, its subtopics and notes.
    - D is demand: the mean of the last 12 Trends points ÷ 100.
    - C is competition: among the top results, how many are recent (≤ 365 days), long (≥ 8 minutes) and highly viewed (≥ 100,000 views).
    - G is the content gap: the share of top results whose title and description don't cover the keyword.
  - Without a Trends value, D is dropped, C and G weigh 0.5 each, and the score is marked partial.
  - The weights are settings. Every score is labelled estimated.
- **Every number has a source or an "estimated" label.** Each number is stored with where it came from (YouTube's API and the date, a Trends file, a count Leenax made, a setting), or as estimated with how. A number that isn't public, such as hidden likes, has no value and says why. The report can't be approved while any number lacks a label; that check runs on the report and on the research.
- **The SEO report:**
  - **What you edit:** choose the title to use (10 to 15 options), edit or add titles, the description, keywords, tags, hashtags, thumbnail text and prompt, chapters, playlists, next videos and the five plans.
  - **Checked as you type:** YouTube's limits (100-character titles, with a warning past about 70; 5,000-character descriptions; 500 characters of tags; 15 hashtags) and clickbait wording.
  - **Scores and edits:** editing a title clears its AI score, so a score always belongs to the words it was given for.
  - **The research can't be edited.** It is kept as it was found, and the report refers to it.
  - **Approval:** the report is the "YouTube SEO" approval stage. Editing the topic list marks it outdated. It warns while the topic's academic QA report isn't approved (slides will need both, in E7).
  - **Downloads:** the 23-part report as PDF or Word; Excel with Keywords, Competitors, Titles and Sources sheets, with formula-safe cells.

## What's in E5: academic QA and QA reports (education increment 5)

- **Academic QA** (the **Academic QA** section under each set of notes and each question bank, and **Academic QA** on a university project):
  - **Running a check:** choose one of your AI text accounts. Leenax suggests a different model from the one that wrote the content; a check by the same model is allowed but marked "not independent". You can add a second model to confirm the findings; it also answers every question.
  - **Notes** are checked claim by claim against the course document pages for the topic: accuracy, maths, code, consistency, the subtopics in the approved topic list, explanations, examples and grammar. Each claim gets a basis: a course document page (its quoted words checked against the stored page), an outside link the AI gave (recorded, not opened), the checker's own explanation, an estimate, or unverified.
  - **Questions** are answered by the checker without seeing their keys. If its answer differs from the key, that's a wrong answer key; if more than one option can be defended, the question is ambiguous. A second call checks explanations, facts and difficulty with the keys shown.
  - **Rules without AI:** references whose words weren't on their page, subtopics the notes never mention, every question rule, and repeated questions in this bank and other banks.
- **Issues:** each one records where it is, what was found, why, its basis and evidence, a suggestion, and a second model's view if one ran.
  - **Severity:** Critical, Major or Minor. A factual error, a wrong answer key, an ambiguous question, a missing core syllabus topic and an unresolved contradiction are always critical (specification, section 6); nothing else can be. The checker can't talk a wrong key down to minor.
  - **Corrections are proposals.** Content is never changed by QA. An editor accepts a correction, which goes into the draft (or a new draft) with a note in the version history. The issue then waits for a recheck.
  - **Recheck:** checks every open or corrected issue again on the current version. Rules are run again and keys are answered blind again. A fixed issue is resolved; one that isn't goes back to open.
  - **Dismiss:** owners and reviewers can dismiss an issue they judge wrong, with a reason, and reopen it later. With "reviewer must not be the author" on, you can't dismiss a critical issue on work you wrote.
  - **The blocking rule:** notes or a bank can't be approved while a critical issue is open on it, including one that is corrected but not rechecked yet. The Approve button is disabled and the checks say why. PostgreSQL enforces this too (`V13`).
- **QA reports** (one per topic, made from **Academic QA** on the project):
  - **What's covered:** the topic's notes at every level and its question bank. The report covers the approved version of each where there is one.
  - **Three views:** notes, questions, and combined.
  - **Counts first:** items checked, passed, failed, corrected and still open, overall and per category (accuracy, syllabus coverage, explanations, references, MCQ keys, difficulty, duplicates). It also shows claims by basis, difficulty spread, syllabus coverage per subtopic, every issue, the correction history, recommendations and the limits of the check.
  - **Scoring:** passed ÷ checked, explained on the report. The headline is **Blocked** while any critical issue is open, whatever the score. Otherwise it is **Not checked** if anything covered hasn't been checked as it is now, **Needs attention** if a major issue is open, and **Passed** if none of those apply.
  - **Approval:** a report is the "Academic quality reports" approval stage. It can be approved only when everything it covers is approved and checked as it is now, and no critical issue is open. Leenax makes the report; nobody edits it. Editing the notes or bank marks the report outdated, so you make it again.
  - **Downloads:** each view as PDF or Word.
- **One more change:** question exports no longer print the old "Not yet checked by QA" line per question. A question's QA state lives in the issue log, and a stored field would go stale.

## What's in E4: notes, question banks and quizzes (education increment 4)

- **Notes and questions** (`/projects/{id}/materials`, or **Notes and questions** on a university project). Every topic in the approved topic list gets:
  - **Notes at five levels:** beginner, intermediate, advanced, university and exam-focused. Each level is its own document with its own versions and approval.
  - **One question bank.**
  - Nothing can be written until the topic list is approved.
  - A coloured dot on each level shows its status, with a key above the list.
- **Writing notes:**
  - **With AI:** your own AI text account writes the notes. It follows the 23-section structure in the original brief, in its own words, at the chosen level.
  - **Sources:** the AI reads the pages the syllabus cites for the topic first, then the best matches from your course documents (at most 14 pages, 40,000 characters). Every source it names is checked against the stored page: ✓ means the quoted words were found there, ? means they weren't.
  - **By hand:** start from a blank page and write the notes yourself.
- **The notes editor:**
  - Headings, lists, tables, code blocks, links, sub- and superscript.
  - Images from your media library.
  - Equations in LaTeX: inline, or on their own line.
  - Mermaid diagrams.
  - Equations and diagrams are drawn in your browser, shown as plain pictures (no scripts), and the drawing is kept for exports.
- **Comments:**
  - Owners, editors and reviewers can select a passage and comment on it.
  - Click a comment's quote to find the passage in the notes.
  - Comments never change a version. Resolve them once you've acted on them; you can delete your own while they're open.
- **Question banks:**
  - **Eight kinds of question:** beginner, intermediate, advanced, application, scenario, code, calculation and exam-focused, with a difficulty for each.
  - **What every question needs:** four different options, exactly one marked correct, and an explanation. Optionally, a note on why each wrong option is wrong.
  - **"All/none of the above":** off by default; a bank can allow it. These rules are checked as you type, and again on the server before review.
  - **Repeats:** questions that repeat others in the project are flagged.
  - **Writing questions:** the AI writes them from a topic's approved notes, 1 to 30 at a time, with the kinds and difficulties you choose. Ones that fail the checks are left out and counted.
  - **Rewriting one question:** with AI, you can rewrite a single question in your draft. Nothing is saved until you save the draft.
- **Outdated:** changing the approved topic list marks the notes built on it outdated, and those notes mark their question bank outdated. Each has a button to tie it to the newly approved version once you've checked it.
- **Quizzes** (`/projects/{id}/quizzes`):
  - Draw questions from approved banks, filtered by difficulty and kind, with the options shuffled if you like.
  - The seed is recorded, so the same seed and choices give the same quiz.
  - A quiz keeps its own copy of the questions, so later edits to a bank don't change it.
- **Downloads:**
  - Notes: PDF and Word.
  - Question banks: PDF, Word, Excel and CSV, with answers and explanations.
  - Quizzes: the same four formats, either for students (no answers) or with an answer key at the end.
  - Word files carry each drawing as a vector image with a PNG copy for older Word. CSV and Excel cells that start with `=`, `+`, `-` or `@` are made safe, so a question can't run as a formula.

## What's in E3: approvals, the syllabus and topics (education increment 3)

- **Approvals.** A project's syllabus and topic list have numbered versions, each with a status: draft, in review, changes requested, approved, rejected, outdated or archived.
  - Owners and editors write drafts and **send them for review**, once the checks pass. They can **withdraw** a version from review to change it.
  - Owners, reviewers and administrators **approve**, **approve with edits**, **ask for changes** or **reject**. Asking for changes and rejecting need a comment.
  - **Approve with edits:** the reviewer edits the version in review, and the edits are saved as a new, approved version. The version under review is kept, and the record lists what changed.
  - **The record:** every decision names one exact version, with who, when, the status before and after, and the comment. Nobody can edit or delete it.
  - With the project's "reviewer must not be the author" setting on (the default), you can't decide on a version you wrote, not even as an administrator.
  - Background jobs, such as the AI analysis, can write drafts but can never approve anything.
  - A version's content can't change after it leaves draft. Editing an approved version makes a new draft, and the approved one stays approved until a newer one is.
  - Editing the syllabus once it has an approved version marks the topic list **outdated**.
  - PostgreSQL enforces all of this too (`V11`), not only the app.
- **Syllabus** (`/projects/{id}/syllabus`, or the **Syllabus** button on a university project):
  - **Analyse documents:** choose course documents that have been read, and your own AI text account (OpenAI, Anthropic or Gemini) drafts the syllabus. The draft covers the structure (chapters, topics, subtopics), learning outcomes, assessments with weights, and contradictions between documents.
  - **Citations are checked:** every item cites a page and copies a few words from it. Leenax checks each quote against the stored page text: ✓ means the words were found on that page; ? means they weren't, so check it. Citations to pages that don't exist are removed and counted. Each one links to the page in the reader.
  - **Before approval:** every contradiction needs a written resolution, and there must be at least one chapter or topic. Missing sources and weights that don't add up to 100% are shown as warnings.
  - **By hand:** you can write the syllabus yourself, or edit the AI's draft: summary, structure, outcomes, assessments, resolutions.
  - **Limits:** at most 150,000 characters of text per analysis (`SYLLABUS_MAX_SOURCE_CHARS`), and one analysis at a time per project. The AI call is logged under Credits & usage like any other.
- **Topics** (`/projects/{id}/topics`):
  - **Built from the approved syllabus only:** the button appears once the syllabus is approved.
  - **Editing:** include or leave out topics; add, rename, reorder, move between levels, merge, split; set difficulty (introductory, intermediate, advanced) and prerequisites. Prerequisite loops are shown as you make them.
  - **Updating:** when a newer syllabus is approved, "Update from syllabus version N" adds its new items. It keeps your names, order, merges, choices, difficulty and prerequisites, and flags topics whose syllabus item is gone.
  - **Before approval:** the list must be built from the currently approved syllabus. It needs at least one topic included, no prerequisite loops, no included topic depending on one that's left out, and no included topic that is no longer in the syllabus.

## What's in E2: course documents (education increment 2)

- **Course documents per subject** (`/projects/{id}/documents`, or **Course documents** in the menu, or the **Documents** button on a project):
  - Add PDF, Word (DOCX, DOC), PowerPoint (PPTX, PPT), OpenDocument (ODT, ODP), plain text, or photos and scans of pages (JPG, PNG, TIFF, WebP), up to 100 MB each. Drag several at once.
  - Files are checked by their contents, not their names, and kept in private storage like media.
  - The same file can't be added twice to a subject; you're told where it already is.
  - **Versions:** upload a newer file to an existing document. Older versions are kept and can be opened and downloaded.
- **Reading, page by page,** by a new private service (`docservice`: Python, Tesseract, LibreOffice) that runs on your server. No provider account, no credits, nothing leaves the server.
  - PDFs: the text inside the PDF is used. Pages without usable text are read by OCR in English and Tamil: scans, photos, and Tamil typed in older fonts such as Bamini, which come out as Latin gibberish.
  - Word, PowerPoint and OpenDocument files are converted to PDF first, so page numbers match the printed pages.
  - **Read again** re-reads a version; **Read with OCR** reads it from the page images even where the PDF has text (for garbled PDFs the automatic check missed).
  - Reading happens in the background, one document at a time; the list updates by itself. If the service is busy or down, it tries again after 1 and then 5 minutes, then marks the version as not read, with the reason.
  - Up to 500 pages per document are read (change `MAX_DOCUMENT_PAGES`); the rest is noted.
- **Search** across a subject's documents, in English and Tamil:
  - Results show the document, the page number and a snippet with the words marked. Clicking opens the reader at that page.
  - Matches the beginnings of words, so "normal" finds "normalisation" and "கணினி" finds "கணினியின்". Every word you type must be in the same passage.
  - Searches the newest version of each document that was read successfully.
- **Subject check:** module codes on the first pages (like CSC2041) are compared with the project's module code, with a warning when a document seems to belong to another subject. The language is detected too: English, Tamil, or both.
- **Who can do what:** follows the project. Every member can read, search and download; owners, editors and administrators can add, rename, re-read and delete. PostgreSQL enforces it, as in E1; only the background reader can write pages and passages.
- Database: `V10__course_documents.sql`. New service: `docservice/`.

## What's in E1: multi-user accounts (education increment 1)

The first step of the merged specification ("Leenax Studio: merged master specification"). Leenax is no longer a one-person studio.

- **People** (`/manage/users`, administrators only):
  - Invite someone by name and email. There's no email server: you get a one-time link to send them (it works once, for 7 days) and they choose their own password.
  - Roles: Administrator or Member. Members can be allowed or not to create projects.
  - Deactivate and reactivate people. A deactivated person is signed out on their next click; their work and history stay.
  - Password reset links (24 hours), and a sign-in history with address and device.
- **Account count:** no fixed application or database cap. The Users directory is paginated, searchable by name or email prefix, and filterable by account status. The database still refuses to lose the last administrator.
- **Projects have members:** Owner, Editor, Reviewer or Viewer. Owners add people and change roles. A project always keeps an owner. Anyone can leave a project.
- **University subjects:** a project can be a university subject with a module code, year, semester, university and department. Creating one warns when the same module code or name already exists.
- **What each person sees** is enforced by PostgreSQL itself (row-level security), not only by the app:
  - An administrator sees everything.
  - A member sees the projects they belong to, and anything they made outside a project.
  - Only owners and editors can change a project's content; reviewers and viewers can read it.
  - Background jobs (video generation, renders, auto-edits, posting) run as the person who started them, so what they make belongs to that person.
- **Shared voices:** a saved voice can be shared with the team (a narrator everyone uses); only its creator changes it.
- **Administrator-only:** Users, AI accounts and keys, credits and usage, connecting or disconnecting social channels, and system health. Members see their own activity; administrators see everyone's.
- The owner from Phases 1 to 8 becomes the first administrator, and everything already made stays theirs.
- Database: `V9__users_roles_project_access.sql`.

## What's in Phase 8 (Increment 8 of the consolidated checklist)

The create side of the workflow, from idea to an editable first cut. See `docs/CHECKLIST.md` for everything still to do.

- **Scripts & ideas** (`/create/scripts`):
  - Ideas for a topic and audience, then a full script in Tamil or English: introduction, scenes with visual notes, and an outro, for 10 video types and a target length.
  - Paste your own script instead. Spoken length is estimated as you type.
  - Rewrite with your own instruction or a quick action. Every rewrite is a new version and the earlier one is kept.
  - Post text (title, description, caption, hashtags) for up to 10 platforms at a time, saved with the script, with a copy button.
- **Character studio** (`/create/characters`): people who appear in more than one scene, shared or kept to one project, with appearance, clothing, up to 8 reference images and a voice from My voices.
- **Storyboard studio** (`/create/storyboard`):
  - The AI writer splits a script into scenes (shots, camera moves, lighting, narration, dialogue) and links the characters it names. You can also start blank.
  - Edit, insert, duplicate, delete and reorder scenes. Each scene shows the image and video prompts the studio would write; you can replace them.
  - **Scene image:** made with your image account. The scene's own reference images and the first reference of each character in it go to models that accept references. Or choose one from the library.
  - **Scene video:** image-to-video from the still where the model allows, otherwise text-to-video, rounded to a length the model accepts. It runs in the background and is linked to the scene when it finishes.
  - "Make missing images" and "Start missing videos" do every scene without one, after a confirmation, since each is billed by the provider.
  - **Voice:** one narration for the whole board (each scene keeps its part), and dialogue spoken in each character's voice.
  - **Send to editor:** a new editable timeline each time, with scene videos (muted under speech), stills with slow movement where there's no video or it's too short, title cards for empty scenes, narration, dialogue, optional captions from the narration and optional music.
- **Project page:** a workflow strip from script to publish shows what's done for the project and links to the next step.
- Database: `V8__scripts_storyboards_characters.sql` adds `scripts`, `storyboard_characters`, `storyboard_projects`, `storyboard_scenes` and `storyboard_references`.

## Stack

Java 21 · Spring Boot 3.5 · Spring Security · Spring Data JPA · Flyway · PostgreSQL 17 · Redis 7 · S3-compatible storage (SeaweedFS locally, AWS S3 in the cloud) · FFmpeg · React 19 · TypeScript · Vite · Tailwind CSS 4 · TanStack Query · Zustand · Nginx · Docker Compose

```
leenax-studio/
├── backend/            Spring Boot API (modules: auth, projects, media, documents, approvals, syllabus, dashboard, audit, common, security)
├── docservice/         Document reading and English/Tamil OCR (Python, Tesseract, LibreOffice)
├── frontend/           React app + Nginx config
├── deploy/             HTTPS Nginx example, storage entrypoint
├── scripts/            backup.sh / backup.ps1, restore.sh / restore.ps1
├── docs/API.md         Endpoint reference
└── docker-compose.yml
```

## Run it on your PC (Windows)

You need **Docker Desktop**. You don't need Java or Node, because Docker builds everything.

1. Open PowerShell in this folder (`E:\Leenax Studio`).
2. Copy the settings file and edit it:
   ```powershell
   copy .env.example .env
   notepad .env
   ```
   Replace every `change-me` with a long random value. `SETUP_TOKEN` is a one-time code you'll type into the setup screen.
   Set `LEENAX_MASTER_KEY` to 32 random bytes in Base64. In PowerShell:
   ```powershell
   $b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
   ```
   **Back this value up.** It encrypts your provider API keys; if it's lost, the keys must be entered again.
   For AI Agent workflows, set `AGENT_SERVICE_TOKEN` to a separate random value in `.env` (for example `openssl rand -hex 32` from Git Bash). The backend and document service must share this token; do not leave it blank or reuse a provider key.
   For **free agent text generation**, use local Ollama (no API key) or the bundled private FreeLLMAPI router. `AGENT_ALLOW_PAID_MODELS=false` is the default; agents can use these two accounts, while direct paid provider accounts stay blocked. FreeLLMAPI needs free-tier API keys from providers you sign up for, and those providers set the quotas and terms. Do not add paid/custom endpoints to its fallback chain if you need to keep agent requests free. Its dashboard is available only on this PC at `http://localhost:3001`; add free-tier provider keys there, then copy the unified key from its Keys page into Leenax → AI Accounts & Keys → FreeLLMAPI. The database and encrypted keys persist in the `freellmapi-data` volume; `.freellmapi.env` contains its encryption key and must be backed up securely. FreeLLMAPI describes itself as a single-user personal experimentation tool, so don't expose its dashboard or API to the public internet. For remote server administration, use a private tunnel.
   Ollama runs without a provider key. Leenax defaults to `http://host.docker.internal:11434` in Compose; Ollama must accept connections from the backend container. Set `OLLAMA_NO_CLOUD=1` in Ollama's environment to prevent its cloud-model use. Ollama binds to `127.0.0.1` by default; if you change it to listen on another interface for Docker, restrict access with your firewall because the local API does not require a key. For a backend running directly on Windows, set `OLLAMA_BASE_URL=http://localhost:11434` instead. A local Ollama call has no per-call provider fee, but uses your own computer/server CPU/GPU, memory, storage and electricity.
3. If you don't already have `.freellmapi.env`, create FreeLLMAPI's encryption key (keep this file safe and backed up):
   ```powershell
   $k = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
   "ENCRYPTION_KEY=$k" | Set-Content -Encoding ascii .freellmapi.env
   ```
   Start the stack:
   ```powershell
   docker compose up -d --build
   ```
   The first build takes a few minutes because Maven and npm download dependencies.
   The first build also downloads the background-removal model (about 180 MB) into the `imagetools` image and a headless Chrome (about 100 MB) into the `renderer` image. That happens once.
   Set `RENDERER_TOKEN` in `.env` to any long random string first.
4. Open **http://localhost:8088**, enter the setup token, and create your account.
5. After setup, delete the `SETUP_TOKEN` line from `.env` and run `docker compose up -d`. Setup is then switched off completely.
6. Open **http://localhost:3001** on this PC, create the FreeLLMAPI dashboard login, and add provider API keys for free tiers you are eligible to use. Copy its unified API key. In Leenax, open **AI Accounts & Keys**, connect **FreeLLMAPI** using that key, and select one of its listed models for an agent. Free-tier limits are controlled by each provider; the router does not create unlimited tokens.

Useful commands:

```powershell
docker compose logs -f backend     # watch the backend
docker compose ps                  # health of every service
docker compose down                # stop (data is kept in Docker volumes)
.\scripts\backup.ps1               # back up database + media into .\backups (also automatic, daily)
.\scripts\restore.ps1 <folder>     # restore a backup (replaces everything)
```

## Develop without Docker for the app itself

Run only the infrastructure in Docker: `docker compose up -d postgres redis storage` (add `imagetools` and set `IMAGETOOLS_URL=http://localhost:7000` with a `ports` entry if you want background removal while developing; likewise `docservice` with `DOCSERVICE_URL=http://localhost:7200` to have documents read)

Backend (JDK 21 + Maven):

```powershell
cd backend
$env:DB_PASSWORD="<POSTGRES_PASSWORD>"; $env:REDIS_PASSWORD="<REDIS_PASSWORD>"
$env:S3_ENDPOINT="http://localhost:8333"; $env:S3_REGION="us-east-1"
$env:S3_ACCESS_KEY="<S3_ACCESS_KEY>"; $env:S3_SECRET_KEY="<S3_SECRET_KEY>"
$env:SETUP_TOKEN="<SETUP_TOKEN>"
mvn spring-boot:run
```

Frontend (Node 22+):

```powershell
cd frontend
npm install
npm run dev        # http://localhost:5173, proxies /api to :8080
npm test           # unit + component tests
```

Install FFmpeg locally (`winget install Gyan.FFmpeg`) to get durations and poster frames outside Docker.

## Put it on the internet

1. Get a small VPS (2 vCPU / 4 GB RAM or more) with Docker, and point two DNS names at it, for example `studio.example.com` and `media.example.com`.
2. Copy this folder to the server and create `.env` as above, plus:
   ```
   COOKIE_SECURE=true
   MEDIA_PUBLIC_URL=https://media.example.com
   ```
3. `docker compose up -d --build`. The app listens only on `127.0.0.1:8088`, and storage only on `127.0.0.1:8333`.
4. Install Nginx + Certbot on the host and use `deploy/nginx-https.example.conf` (replace the domain names).
5. Backups run automatically (the `backup` service); copy the `backups/` folder off the server regularly. See "Production checklist".

To use **AWS S3** instead of SeaweedFS: create a private bucket and point `S3_ENDPOINT` at it (blank for AWS), along with `S3_PUBLIC_ENDPOINT` (blank), `S3_REGION`, `S3_BUCKET`, `S3_PATH_STYLE=false` and `S3_CREATE_BUCKET=false`. Use an IAM user limited to that bucket, then set `MEDIA_PUBLIC_URL=https://<bucket>.s3.<region>.amazonaws.com` so the browser's content policy allows it.

## Security notes

- Microphone recording works on `localhost` and over HTTPS only (a browser rule).

- Nothing secret is stored in the browser: there are no tokens in localStorage, and the session cookie is HttpOnly.
- At most 5 active or invited people (a setting). The database enforces it, keeps at least one administrator, and never deletes a person.
- What each person can see and change is enforced by PostgreSQL row-level security. The app's database login is a superuser in Docker, and superusers skip those rules, so every connection made for a person or a job first switches to the restricted `leenax_app` role (created by V9) and says who it acts for. A connection that skipped that step sees nothing.
- Activation and reset links are 256-bit random tokens; only their SHA-256 is stored, each works once, and making a new one retires the old.
- Uploads are checked by content. SVG/HTML are refused, filenames are cleaned, and the extension is forced to match the real type.
- Media is never public. Every link is signed and expires.
- Error responses never echo request bodies, so passwords can't leak through errors.
- Postgres, Redis and storage ports are bound to `127.0.0.1` only.
- Two-step sign-in (TOTP) is optional; recovery codes are stored as SHA-256 hashes, app codes can't be replayed, and the code step has its own attempt limit.
- Every API call is limited per address (600 per minute by default).
- Social sign-in: tokens are encrypted at rest and never sent to the browser. The sign-in return address is the only page reachable without the session cookie (the cookie is SameSite=Strict, so it isn't sent when Google, Meta or TikTok redirect back). It is protected by a single-use random `state` value whose hash is kept for 10 minutes, plus PKCE for Google. Graph API calls carry an `appsecret_proof`.
- Account avatars from the platforms aren't loaded, so the page's strict image policy stays as it is.

## Known limits of this phase

- **E10 checks:**
  - **On real PostgreSQL 16:** all 16 SQL suites (539 checks) pass on an empty database with the 18 migrations, run from the repository the way CI runs them. The new whole-workflow suite has 39 checks.
  - **Frontend:** 194 tests (5 new): the bell, opening and marking notifications, Escape, the dashboard's tasks, counts, running work and links.
  - **Backend:** 293 unit tests still pass.
  - **Checked in Chrome against a mock API**, including phone width and dark mode.
  - **Not run here:** Maven can't reach its repository from this environment, so the backend has still never been compiled by Maven or started in a real Spring server; CI or `docker compose up --build` is the first time. The CI workflow itself hasn't run (no GitHub repository here).
- **E10 limits:**
  - Notifications are in the app only (no email) and are kept until removed.
  - The dashboard counts what you can see, so two people may see different numbers.
  - "Waiting for you" lists failed jobs for 7 days.

- **E9 checks:**
  - **The specification's check, "nothing uploads without an authorisation record"**, on real PostgreSQL, 59 end-to-end checks (`e9.py`), among them:
    - A scheduled post with no record can't start uploading, be marked published or be inserted as uploading. With the record it can.
    - A record matches only its file and settings: changing the title or the file afterwards blocks the upload.
    - Background jobs and other people can't authorise; a record can't be edited; a public post, or a private one YouTube makes public later, needs its confirmation.
    - Education publications: refused without the rights confirmation, for an editor, for a draft version, for a render that failed its checks and for a passing render of other content; allowed for the owner and an administrator.
    - Project viewers see publication status though they can't see the post; outsiders see nothing. Deleting a project keeps its authorisation records.
    - Snapshot sets, date filtering, reach backfills replacing their day, and click-through rate weighting.
  - **The same rule in the publisher:** a target without a matching record is marked failed instead of uploading.
  - **Backend:** 293 tests (19 new), against stand-ins of YouTube's APIs: playlists and captions after the upload (multipart caption upload, done once, a refusal becomes a warning, a quota error retries without uploading again), the scheduled publish time, playlist listing and creation, hidden subscriber counts, Analytics API queries (`ids=channel==MINE`), Reporting API jobs, reports and downloads (only Google's report addresses are fetched), the reach CSV read by column name, SRT files from the render's captions, and the recommendation rules.
  - **Frontend:** 189 tests (9 new): the authorisation step sends exactly what's shown and can't be given without the rights and public confirmations; only owners and administrators see Publish; analytics refresh, date filters and reach reports.
  - All earlier SQL suites still pass, and all 17 migrations apply to an empty database.
  - **Checked in Chrome against a mock API**, including phone width and dark mode.
  - **Not run here:** no real Google sign-in, upload, caption, playlist, Analytics or Reporting call was made (no credentials). These integrations are built to Google's documented APIs and are **not verified live**. The Spring wiring and the Maven build weren't run either.
- **E9 limits:**
  - **Click-through rate scale:** Google documents `video_thumbnail_impressions_ctr` as a percentage, so 4.5 is read as 4.5%. Check the first real reach file; a value above 100 is refused as unreadable.
  - **Reach data starts when you turn it on:** YouTube doesn't make reach files for days before the job existed.
  - **Unverified Google Cloud projects:** YouTube locks their uploads to private. Scheduled and public videos stay private until the project passes YouTube's API audit (the publication then shows YouTube's warning).
  - **Retention** is fetched for the 12 most viewed videos of the period. Relative retention isn't offered for every video; the watch ratio is used alone then.
  - **Recommendations** are rules, not predictions, and say nothing until a video has 100 views or 1,000 impressions.
  - Uploading to several channels at once, editing a video's metadata after upload, and end screens are not included.

- **E8 checks:**
  - **The specification's check, "a rendered quiz video passes validation before it can be approved":**
    - In the database (real PostgreSQL), 24 end-to-end checks:
      - A video version can't be approved with no render, a render that failed its checks, a passing render of other content, or a passing render without its file. It can be approved with a passing render of exactly its content.
      - Approving with edits makes new content with no render, so it is refused.
      - Notes and other stages are unaffected.
    - The same rule is a blocking check in the app.
  - **Real renders:**
    - A lesson video (slides with a diagram, a Tamil definition, a table, code and an image) and a quiz video (9:16) were built by the Java code and rendered with Remotion and Chrome.
    - Both passed the Java ffprobe checks: length, size, 30 fps, 1,263 and 1,503 frames, H.264 4:2:0, AAC sound at −36 dB.
    - Frames were inspected by eye.
  - **The ffprobe checks**, tested on real files made with ffmpeg: a good file; silence; the wrong size, length and frame rate; no sound; the wrong picture format; and a file that isn't a video.
    - Rendering Remotion's own output exposed that Remotion writes yuvj420p rather than yuv420p, so both are accepted.
  - **Backend:** 274 tests (21 new), covering:
    - **Google Cloud TTS adapter**, against a stand-in Google API: the service account JWT is verified with the key's public key, the token is cached, the API key header is sent, bad key files are refused without calling Google (including a key file that points somewhere other than Google's token endpoint), voices are filtered, SSML is escaped with breaks, Chirp gets plain text, and errors don't contain the key.
    - **Plans:** lesson and quiz plans, narration dropped when words or voice change, and refused plans.
    - **Source checks:** slides and questions as approved.
    - **Timeline timing:** narration, countdown, reveal and answer, plus caption breaks.
  - **Frontend:** 180 tests (12 new).
  - **Renderer:** all 5 tests, and the earlier real-render test, still pass.
  - **Checked in Chrome against a mock API**, including phone width, with the preview playing the real quiz timeline.
  - **Not run here:**
    - A real Google Cloud, ElevenLabs or Gemini voice call. No credentials were used, so the Google adapter is tested against Google's documented API only.
    - The Spring wiring and the Maven build.
    - A Docker build of the renderer with the new fonts.
- **E8 limits:**
  - **No sound effects or music** in the templates.
  - **Captions are burned in.** E9 uploads them to YouTube as a caption file too.
  - **Rendering takes time:** a minute of video took about 1.5 minutes to render at half size here.
  - **Tamil slides** use Noto Sans Tamil in the video, the font the fit check measured with, so they fit. In PowerPoint the same slides use Nirmala UI.

- **E7 checks:**
  - **The specification's check, "an exported PPTX opens in PowerPoint without overflowing text":**
    - The PPTX files are drawn by LibreOffice, and the position of every character is read back from the PDF and compared with its text box.
    - This was run for all 7 styles, and for each layout filled to the longest text the fitter accepts, with Tamil and wide letters mixed in: no character fell outside its box.
    - The check was also shown to catch real overflow: with the fitter turned off, it found over 1,000 characters outside the box.
    - What isn't tested here is PowerPoint itself (see below).
  - **Database (real PostgreSQL):** 32 end-to-end checks with the exact SQL in the Java code:
    - One deck per topic and level.
    - A deck can't be approved until its notes level, the QA report and the SEO report are each approved. Notes at another level or another topic's SEO report don't count.
    - The lookups behind the checks: drawings by source, and images only from this project's media.
    - All earlier database checks still pass, and all 15 migrations apply in order to an empty database.
  - **Backend:** 253 tests (13 new), covering:
    - Cleaning: fields kept per layout, too-long text refused rather than cut, unknown types and styles.
    - Overflow and an unmeasured fit blocking approval.
    - The source, number and reference checks.
    - Reading the AI's slides: its references are dropped, and diagrams are reused from the notes.
    - The version diff.
    - The Java measuring call was also run against the live document service.
  - **Document service:** 41 tests (17 new), including the render check above.
  - **Frontend:** 174 tests (6 new), covering:
    - Approvals gating "Make slides".
    - Outlining a slide that doesn't fit.
    - Editing, reordering and adding slides, measured as you edit.
    - Redo with AI as a proposal.
    - The read-only review view.
  - **Checked in Chrome against a mock API**, including phone width, with the real document service measuring the slides.
  - **Not run here:**
    - **PowerPoint itself, on Windows.** Calibri and Carlito have identical character widths, but open one exported file in PowerPoint and look at the densest slide. That is your live test of the specification's check.
    - A real AI call.
    - The Spring wiring and the Maven build.
- **E7 limits:**
  - **Tamil in PowerPoint** uses Nirmala UI, which is measured only approximately (the 12% margin). Very dense Tamil slides are the ones to look at.
  - **The browser preview** uses the same boxes, colours and sizes, but your browser's fonts. Its line breaks can differ slightly from the file; the measurement and the file are what count.
  - **No animations or transitions** in the PowerPoint file.
  - **Images come from the project's media only.** An image in another project, or not in a project, is flagged until it is added to this one.

- **E6 checks:**
  - **The specification's check, "no number without a source or an estimated label", is tested three ways:**
    - A planted unlabelled number (`searchVolume: 12000`) blocks the report's approval.
    - Saving rebuilds every number, so one can't be slipped in through an edit.
    - The research is checked for labels before it is stored.
  - **YouTube calls** were tested against a stand-in for YouTube's API (the same one the publishing tests use): the search parameters, results in order, hidden likes kept unknown (not 0), durations, and a `quotaExceeded` answer reported as a quota error.
  - **Database (real PostgreSQL):** 32 end-to-end checks with the exact SQL in the Java code:
    - The day's quota counts every project's searches, though members can't see other projects' calls.
    - Failed calls count too.
    - The cache honours its 7 days and stays per project.
    - Trends files and research are kept as found.
    - One SEO report per topic.
    - The lookups behind the review checks.
    - All 353 earlier database checks still pass, and all 14 migrations apply in order to an empty database.
  - **Backend:** 240 tests (16 new), including the opportunity score with and without Trends, competition and gap counts, both Trends CSV layouts and bad files, title rules, reading the AI's keywords and plan, tags within 500 characters, and picking searches by language.
  - **Document service:** 24 tests (2 new). They cover all 23 parts in the PDF, numbers with their sources, the Word file, and the Excel sheets with formula-safe cells.
  - **Frontend:** 168 tests (6 new), including the budget shown and enforced, research without a YouTube account, editing and choosing titles with live clickbait warnings, the tag counter, the research view's labels, and a blocked Approve button.
  - **Checked in Chrome against a mock API**, including phone width. A source label that pushed the phone layout sideways was found this way and fixed.
  - **Not run here:**
    - A real YouTube search: no Google credentials here.
    - A real AI call.
    - The Spring wiring and the Maven build.
    - Your first research run is the live test. It costs up to 5 searches from the day's allowance, plus 2 AI calls.
- **E6 limits:**
  - **No search volumes, by design.** YouTube doesn't publish them. The opportunity score is a relative estimate; with no Trends file, every score is partial.
  - **The quota is small and shared.** About 100 searches a day for the whole install covers roughly 20 topics a day at 5 searches each. More needs Google's quota audit; then raise `SEO_DAILY_SEARCHES`.
  - **Two runs at the same moment** can each pass the budget check and together go a search or two over. YouTube's own quota still stops the calls, and the research says so.
  - **Competitors are judged on public numbers only.** Their impressions, click-through and retention aren't public. Content coverage is read from titles and descriptions, not the videos.
  - **Relevance and coverage are word checks.** A keyword or subtopic phrased differently can score low.
  - **Trends files** must be the "Interest over time" CSV. Values are rescaled per download, so two files can't be compared with each other.
- **E5 checks:**
  - **Database (real PostgreSQL, as a superuser like Docker):**
    - 39 checks on V13's rules, including the specification's own: **a planted wrong answer key blocks approval**. A correction that isn't rechecked still blocks; only a recheck resolves an issue. Findings and proposals can't be rewritten. Who may accept, dismiss and reopen is enforced. A report is blocked by issues on what it covers.
    - 36 end-to-end checks with the exact SQL in the Java code, through the whole path: planted key → blocked → withdraw → correction accepted → still blocked → recheck → approved. They also cover dismissing and reopening, the report gate, and that a check of a draft no longer counts once the draft changes.
    - All 278 earlier database checks still pass, and all 13 migrations apply in order to an empty database.
  - **Backend:** 224 tests (14 new). They cover the fixed critical kinds, the blind key check on a planted wrong key, ambiguous questions, quotes checked against pages, reading the checker's findings, exact-or-refused corrections, rule findings on notes and banks (Tamil included), not recording an open issue twice, and the report's scoring. The headline test is "a 99% score still says Blocked".
  - **Document service:** 22 tests (2 new). They cover the QA report as PDF and Word, each view showing only its own issues, and text from the AI escaped.
  - **Frontend:** 162 tests (7 new), on the issue log, accepting a correction, a second model, dismissing with a reason, the blocked Approve button, the report and the project QA page.
  - **Checked in Chrome against a mock API**, including a phone-sized screen. A report from the mock was also rendered to PDF and Word by the real export code and looked over.
  - **A bug the tests found and I fixed:** a check of a draft kept counting after the draft was edited. A run now records the exact content it checked.
  - **Not run here:**
    - A real QA call to an AI model. The prompts and the reading of the answers are tested, not a live model. Your first QA run is that test.
    - The Spring wiring and the Maven build.
- **E5 limits:**
  - **QA is AI, so it can be wrong both ways.** It can miss errors and report problems that aren't real. That's why every finding shows its basis, a second model can confirm, a person decides on each one, and no report claims more than "no critical or major issues open".
  - **Outside sources aren't checked.** Leenax can't browse the web, so a link the AI gives is recorded as given and labelled "not opened". Claims are checked against your course documents and the checker's own knowledge.
  - **Coverage of subtopics is a word check.** A subtopic covered under another name is flagged as a major (not critical) issue, so a reviewer can dismiss it. The checker can still raise a missing core topic as critical.
  - **Cost:** a notes check is one AI call; a bank check is two, plus one per model added; a recheck is one or two. All are logged under Credits & usage.
  - **Size:** the first 80 questions of a bank are checked by the AI; the rules check them all. Notes sent to the checker are capped at 30,000 characters for question checks.
  - **Corrections by find-and-replace** apply only when the words sit in one piece of text, exactly once. Otherwise Leenax says so and you make the change by hand.
- **E4 checks:**
  - **Database (real PostgreSQL, as a superuser like Docker):**
    - 32 checks on V12's rules: one resource per topic and level, comments nobody can rewrite, figures and quizzes that can't be changed in place, and who may add each.
    - 47 end-to-end checks with the exact SQL in the Java code: notes and banks per topic, a job per notes level, the AI unable to approve, comments by reviewers only, kept drawings, quiz snapshots, and the outdated cascade from topics to notes to bank.
    - All 199 earlier database checks still pass.
  - **Backend:** 210 tests (16 new). They cover the notes clean-up and plain text, every question rule in English and Tamil, near-duplicate detection, reading the AI's notes and questions, the quiz draw (the same seed gives the same quiz, and the key follows its shuffled option), and export file names.
  - **Document service (real LibreOffice and openpyxl):** 20 tests (8 new). They cover notes as PDF and Word with tables, code, Tamil, equations and diagrams; question banks and quizzes in all four formats with and without answers; formula-safe cells; and stripping scripts and outside links from drawings.
  - **Frontend:** 155 tests (13 new), on the question checks and the four new pages.
  - **Checked in Chrome against a mock API**, including phone-sized screens. MathJax and Mermaid drew a real equation and diagram in the browser. Those exact drawings were then turned into a PDF and a Word file by the export code, and they came out right.
  - **npm audit:** no known vulnerabilities. Mermaid 11 brings an older KaTeX with a low-severity advisory (GHSA-238p-pmpm-9mq7), so `package.json` pins KaTeX to 0.18.2 or later; diagrams were re-checked in Chrome after that.
  - **Not run here:**
    - A real AI call for notes or questions.
    - Opening the files in real Microsoft Word and Excel. They were opened with LibreOffice, python-docx and openpyxl.
- **E4 limits:**
  - **Equations use MathJax, not KaTeX** as the specification says. Exports need a vector drawing, which MathJax makes and KaTeX doesn't. The LaTeX you write is the same.
  - **Drawings are made in the browser.** An equation or diagram is drawn the first time an owner, editor or reviewer opens the notes. One nobody has opened yet is exported as its source text, with a note saying so.
  - **Repeats are found by shared wording, not meaning.** The same question asked in different words won't be caught.
  - **The AI can be wrong.** That's why each source is checked, each question is checked against the rules, and nothing reaches a quiz until a person approves the bank. "Checked by QA" stays "not yet checked" until E6's QA step exists.
- **E3 checks:**
  - **Database (real PostgreSQL, as a superuser like Docker):**
    - 33 checks on V11's rules: frozen content, one approved version, only owners and reviewers decide, never a background job or the system, reviewer-not-author including administrators and approve-with-edits, and a record nobody can edit or delete.
    - 24 end-to-end checks with the exact SQL in the Java code: document picker, analysis queue, AI draft attributed to the person, submit, approve, topics depending on the syllabus, the outdated cascade, approve with edits, and the history.
    - All 142 earlier database checks still pass.
  - **Backend:** 194 tests (15 new). These cover syllabus and topic rules, citation checking in English and Tamil, reading the AI's answer, updating topics while keeping edits, prerequisite loops, and stable hashing.
  - **Frontend:** 142 tests (15 new), covering the tree operations and the syllabus and topics pages (analysis, send for review, ask for changes, approve with edits, the author rule, outdated topics). The screens were checked in Chrome against a mock API, including a phone-sized screen.
  - **Not run here:** a real AI call. The analysis prompt and the reading of its answer are tested, but not against a live model. Your first analysis is that test.
- **E3 limits:**
  - **The AI can miss or misplace things.** That's why every item links to its page, quotes are checked, and nothing counts until a person approves it. A ? next to a source means the quoted words weren't found on that page.
  - **Big inputs:** very long documents won't fit in one analysis. Choose the module descriptor and handbook, not every lecture.
  - **Missed changes:** "Approve with edits" lists renamed, added and removed items, and changed outcomes, assessments and resolutions, but not every changed word.
  - **Not here yet:** a dashboard of everything waiting for you to review (E10).
- **E2 checks:**
  - **Document service (real Tesseract and LibreOffice):** 12 tests on one two-page lecture in English and Tamil, as a Word file, a PDF with text, a scanned PDF and a photo. The scan was read at about 95% confidence, and the Tamil came out word for word. Also checked: Word page numbers, unsupported and damaged files refused, the legacy-font detector.
  - **Database (real PostgreSQL, as a superuser like Docker):** 29 checks on V10's access rules, and 19 end-to-end checks that store and search those real documents with the exact SQL in the Java code. The end-to-end checks cover uploading as an editor, the worker leasing and saving as the uploader, Tamil and English prefix search, outsiders seeing nothing, falling back to the last good version, and re-reading. The 104 earlier database checks still pass.
  - **Backend:** 179 tests (19 new), with the same stand-ins as before. The Spring wiring is still untested in a real server.
  - **Frontend:** 127 tests (9 new). The document list, search, reader and viewer view were checked in Chrome against a mock API, including on a phone-sized screen.
  - **Not run here:** the `docservice` Docker image itself (its Debian packages), and the full path upload → SeaweedFS → worker → docservice → database. Your first upload is that test.
- **E2 limits:**
  - **OCR quality depends on the scan.** A clean printed page reads well; handwriting, phone photos at an angle and faint photocopies won't. Each OCR page shows its confidence.
  - **Tamil search has no stemming:** prefix matching finds words with endings added, but not changed stems. Searching "படி" finds "படித்தல்" but a form that changes the root letter won't match.
  - **No meaning-based search yet.** "Find passages about database consistency" only finds those words, not paraphrases. That needs an embedding model: a paid service (your OpenAI or Google key, per use) or a local model (roughly 0.5 to 2 GB of memory, slower on a CPU). I haven't picked one; it's your call on cost and hardware, and E3 can add it.
  - Documents are read one at a time. A 300-page scanned book can take 20 to 40 minutes on a typical PC.
  - Excel files, images inside Word files and maths formulas aren't read as text. Formulas in a scan come out as approximate characters.
- **E1 checks:**
  - **Database:** the updated account suite creates 100 additional active accounts and an invitation without a configured cap, then checks last-administrator protection, no deletes, and each project role. The updated V20 suite has not yet been run here.
  - **Backend:** 160 tests (11 new for E1) with the stand-ins. The Spring wiring (the connection wrapper around the pool, the account filter, the security rules) has **not** run in a real Spring server: Maven still can't be downloaded here. If the app starts but every page is empty, that wiring is the place to look; it fails closed by design.
  - **Frontend:** 118 tests (12 new). Users, one-time links, project members as owner and as viewer, and the new-project dialog were checked in Chrome against a mock API.
- **Phase 8 checks:**
  - **Database:** all eight migrations were applied to a real PostgreSQL 16, and 9 checks on the new tables passed: versions, project plus shared characters, the reference rule (a character or a scene, not both), cascades, and what survives a delete.
  - **Backend:** the script, storyboard and character code was type-checked against stand-ins. 11 new tests cover script parsing and rendering, spoken-length maths, post-text clean-up, scene planning, camera-word clean-up, character matching, prompts, duplicate rules and the timeline that "Send to editor" builds (checked with the editor's own validator). The services have not run inside a real Spring server, and no AI provider has been called from them.
  - **Frontend:** 13 new tests; the four new screens and the project page were checked in Chrome against a mock API, including a phone-sized screen.
  - **Character likeness:** reference images help, but no provider guarantees the same face in every scene.
- **Phase 7 checks:**
  - **Database:** all seven migrations were applied to a real PostgreSQL 16 (yours runs 17). Every new Phase 7 query was then run against seeded data: analytics, the metrics collector, the audit list and system health. So were the queue-claim queries of every background worker from Phases 2–6, which had never touched a real database before. All 28 checks passed.
  - **Backups:** the backup routine ran for real against that database (dump, read-back check, media archive, `latest.json`, keeping only the newest). The dump then restored cleanly into a fresh database. The Docker service wrapper itself hasn't run here.
  - **Two-step sign-in:** codes match the official RFC 6238 test values. The sign-in flow was tested in the browser against a mock API, and the backend was type-checked. It hasn't run inside a real Spring server.
  - **Post numbers:** reading them from YouTube, Meta and TikTok is tested against a mock of each API only, like Phase 6.
    - **Reconnect once:** the insights permissions (`read_insights`, `instagram_manage_insights`, TikTok `video.list`) are new. Accounts connected in Phase 6 must be reconnected once before Facebook, Instagram and TikTok numbers appear.
    - **Meta's App Review:** Meta and TikTok may require it for those permissions outside development mode.
- **Analytics limits:**
  - Numbers are as fresh as the last read (every 6 hours, or "Refresh numbers").
  - YouTube views can lag behind YouTube Studio.
  - TikTok numbers exist only for public posts.
- **Social publishing has not been tested against the real YouTube, Meta or TikTok APIs.** I don't have developer apps or accounts for them, so each integration is built from the current official documentation (checked November 2026) and tested against a local mock of each API. Treat it as unconfirmed until your first real post on each platform. Points to watch:
  - **Instagram tokens:** Instagram posts use the linked Page's token. Meta's documentation mentions both user and Page tokens for this; if Instagram rejects it with a permission error, tell me and I'll switch it to the user token (which expires every 60 days).
  - **Meta permissions:** `business_management` is requested so Pages in a business portfolio show up. If your Page isn't listed after signing in, check you ticked it on Meta's screen.
  - **TikTok:** before audit, only a private TikTok account can post, and only you can see the posts. TikTok's daily cap is about 15 posts per account.
  - **YouTube:** uploads stay private until your Google project passes the audit; the post shows a warning when this happens. Custom thumbnails need a verified channel; if it fails, the video still publishes, with a note.
  - **Facebook Reels** must be 3–90 seconds and vertical. Longer Page videos (non-Reel) are not supported in this phase.
- **Not supported (by design or by the APIs):** personal Facebook profiles and groups, Instagram Stories and carousels, TikTok photo posts, editing or deleting a post on the platform after it's published (do that in the platform's app), and X/LinkedIn (not part of this phase).
- **The backend has still not been through a real Maven build.** Maven Central isn't reachable from my sandbox.
  - 274 backend tests were run locally with JUnit-compatible stand-ins. E8 adds 21, E7 added 13, E6 added 16, E5 added 14, E4 added 16, E3 added 15, E2 added 19, E1 added 11 and Phase 8 added 11 (listed above); Phase 7 added 16 (TOTP against the RFC vectors, recovery codes, the request limiter, backup status, analytics date ranges, and reading post numbers from each platform including the fallbacks). Phase 6 added 34: every platform's sign-in and token requests, error classification, PKCE, token encryption binding, YouTube resume after a dropped upload, Meta upload headers and `appsecret_proof`, TikTok chunk maths and chunk ranges, each publisher's full flow (upload → processing → live) against a mock of the platform's API, the posting rules, and the post status roll-up.
  - All other backend code was type-checked against stand-ins for the Spring, JPA and AWS types.
  - Your first `docker compose up --build` is the real compile. If it fails, send me the build output.
- **What was run for real:**
  - **Exports:** the renderer with real Chrome, FFmpeg and Remotion. A timeline with video (trimmed and sped up), an image with zoom and a slide transition, Tamil text with a pop animation, and music with fades was rendered and checked with ffprobe. Size, length, audio and the expected frames were all correct.
  - **Backend to renderer:** the backend's own renderer client drove a real render of a vertical 1080×1920 timeline, exported at 720×1280. That run caught and fixed a rounding bug in export sizes.
  - **Editor:** checked in Chrome against a mock API: playback with sound, dragging, undo, autosave and the export dialog.
  - **Frontend:** type-checked, built and tested (142 tests). The Phase 6, 7 and 8 pages were checked in Chrome against a mock API, in light and dark mode and on a phone-sized screen.
- **Not run yet:**
  - The `renderer` Docker image itself, with Remotion's Chrome download and Linux libraries.
  - The full path from Postgres through the render worker to SeaweedFS.
  - Your first export is the real test.
- **Phase 2 and 3 providers** are still unconfirmed until your first real calls. The points to watch are listed in the Phase 2 and 3 sections of `docs/API.md`.
- **Auto-editor:**
  - Each mode's building blocks were checked for real. A talking-head timeline built by the backend code (jump cuts, filler removal, zoom on alternate cuts, Shorts captions, looping music) was exported by the real renderer: a 10-second clip became 4.3 seconds, with the expected frames and captions.
  - The full job run (database → AI providers → timeline) has not run here, because it needs real accounts.
  - Captions for generated narration are timed by each phrase's share of the scene. They follow speech closely but not to the word.
  - If the server restarts during an edit, it starts again from the top. Transcripts and footage descriptions are reused, but a voice-over would be recorded again.
- **Editor limits:**
  - There is no keyframe animation yet, only the fixed movements and transitions.
  - A cross-dissolve is made by overlapping two video tracks.
  - Clips don't show audio waveforms.
- **Browser playback:** the preview plays what your browser can decode. Exports use the original files, so very large 4K sources may stutter in the preview but export fine.
- Long voice-overs and transcriptions still run inside the request (up to 10 minutes).

## API

See [docs/API.md](docs/API.md).
