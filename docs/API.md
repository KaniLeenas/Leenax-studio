# Leenax Studio API — Phases 1–5

Base path: `/api/v1`. JSON in and out. Timestamps are UTC ISO-8601.

**Authentication.** Sign-in creates an HttpOnly session cookie (`LEENAX_SESSION`). Every request except the three public auth endpoints needs it.

**CSRF.** The server sets a readable `XSRF-TOKEN` cookie. Every `POST`, `PUT`, `PATCH` and `DELETE` must send it back in the `X-XSRF-TOKEN` header. Call `GET /auth/status` first to receive the cookie.

**Errors** always look like this:

```json
{ "code": "NOT_FOUND", "message": "Project not found", "timestamp": "2026-11-19T06:00:00Z" }
```

Codes: `INVALID_REQUEST` 400, `UNAUTHORIZED` / `INVALID_LOGIN` 401, `FORBIDDEN` 403, `NOT_FOUND` 404, `CONFLICT` 409, `PAYLOAD_TOO_LARGE` 413, `UNSUPPORTED_MEDIA_TYPE` 415, `RATE_LIMIT_EXCEEDED` 429, `STORAGE_ERROR` 502, `INTERNAL_ERROR` 500. Provider errors use the provider error kind as the code (`INVALID_CREDENTIALS` 422, `INSUFFICIENT_CREDITS` 402, `RATE_LIMIT_EXCEEDED` 429, `PROVIDER_UNAVAILABLE` 502, …). FFmpeg failures return `MEDIA_PROCESSING_FAILED` 422.

## Auth

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/auth/status` | public | `{ setupRequired, setupEnabled, authenticated, user? }` |
| POST | `/auth/setup` | public | Create the owner account once: `{ setupToken, email, displayName, password }`. Signs you in. |
| POST | `/auth/login` | public | `{ email, password }`. 5 failures from one IP lock it out for 15 minutes. |
| POST | `/auth/logout` | session | Ends the session. 204. |
| GET | `/auth/me` | session | Current user. |
| PATCH | `/auth/me` | session | `{ displayName }` |
| POST | `/auth/change-password` | session | `{ currentPassword, newPassword }`. 204. |

Passwords: 12 characters minimum, 72 bytes maximum (BCrypt limit), hashed with BCrypt cost 12.

## Dashboard

| Method | Path | Purpose |
|---|---|---|
| GET | `/dashboard` | `{ stats, recentProjects[5], recentMedia[8] }` |

## Projects

| Method | Path | Purpose |
|---|---|---|
| GET | `/projects?status=&search=&includeArchived=false&page=0&size=24` | Paged list, most recently updated first |
| POST | `/projects` | `{ name, description?, aspectRatio?, language? }` → 201 |
| GET | `/projects/{id}` | One project, with `mediaCount` |
| PATCH | `/projects/{id}` | Any of `name, description, status, aspectRatio, language` |
| DELETE | `/projects/{id}` | 204. Its media stays in the library, unassigned. |

Enums: `status` DRAFT, IN_PROGRESS, COMPLETED, ARCHIVED · `aspectRatio` LANDSCAPE, PORTRAIT, SQUARE · `language` EN, TA.

## Media

| Method | Path | Purpose |
|---|---|---|
| POST | `/media?projectId=` | `multipart/form-data` with a `file` part → 201 |
| GET | `/media?kind=&projectId=&unassigned=false&search=&page=0&size=30` | Paged list, newest first |
| GET | `/media/{id}` | One file |
| GET | `/media/{id}/download-url` | `{ url, expiresAt }` — signed URL that downloads with the original name |
| PATCH | `/media/{id}` | `{ filename?, projectId? }` or `{ unassign: true }` |
| DELETE | `/media/{id}` | Deletes the file from storage and the database. 204. |

Upload rules:

- The type is detected from the file's bytes, not its name. Accepted: MP4, MOV, WebM, MKV, JPEG, PNG, WebP, GIF, MP3, WAV, M4A, AAC, FLAC, OGG. SVG, HTML and everything else is rejected with 415.
- Default size limit is 2 GB (`MAX_UPLOAD_SIZE`).
- FFprobe reads width, height and duration; FFmpeg saves a poster frame for videos.
- Each response includes `url` and `posterUrl`, signed for 30 minutes. The storage bucket itself is private.

## Health

`GET /actuator/health` → `{ "status": "UP" }` (public, no details).

---

# Phase 2: AI providers, accounts, credits, video generation

All paths below are under `/api/v1/ai` and need a signed-in session (plus the CSRF header on writes).

Provider errors use the normalised codes `INVALID_CREDENTIALS` (422), `INSUFFICIENT_CREDITS` (402), `RATE_LIMIT_EXCEEDED` (429), `PROVIDER_UNAVAILABLE` (502), `INVALID_REQUEST` / `CONTENT_POLICY_REJECTED` / `UNSUPPORTED_CAPABILITY` (422), `GENERATION_TIMEOUT` (504).

## Catalogue

| Method | Path | Purpose |
|---|---|---|
| GET | `/providers` | Supported providers, their capabilities, whether a balance API exists, and where to get a key |
| GET | `/models` | Video and text models with options (modes, durations, ratios, resolutions, sound) and pricing |
| PATCH | `/models/{id}` | `{ enabled?, displayName?, notes?, pricing? }`. Pricing shape: `{"unit":"CREDITS","minimum":29,"rates":[{"perSecond":20,"audio":false},{"perSecond":40,"audio":true},{"perSecond":12.56,"resolution":"720p"}]}` |

## Accounts and keys

| Method | Path | Purpose |
|---|---|---|
| GET | `/accounts` | Accounts with masked key, status, balances, totals, `lowCredit`, `expiringSoon` |
| POST | `/accounts` | `{ provider, name, apiKey, makeDefault }`. The key is checked with the provider first and a rejected key is never stored. If the provider is down, the account is saved as `UNVERIFIED`. |
| PATCH | `/accounts/{id}` | `{ name?, lowCreditThreshold?, clearLowCreditThreshold?, enabled? }` |
| PUT | `/accounts/{id}/key` | `{ apiKey }`. Replaces the key only if the provider accepts the new one. |
| POST | `/accounts/{id}/verify` | Re-checks the key and refreshes balances |
| POST | `/accounts/{id}/sync-balance` | Reads the balance from the provider (Runway, Kling, Luma) |
| POST | `/accounts/{id}/default` | Makes it the default for its provider |
| DELETE | `/accounts/{id}` | Deletes the account and its key. Usage history is kept. |
| GET | `/accounts/{id}/models` | Text model ids for that key (OpenAI, Anthropic, Gemini), used for prompt writing |

Keys are encrypted with AES-256-GCM using `LEENAX_MASTER_KEY`, bound to the account id, and never returned by any endpoint.

## Credits and usage

| Method | Path | Purpose |
|---|---|---|
| POST | `/accounts/{id}/balances` | Add a manual balance: `{ label, kind: FREE\|PAID, amount, unit: CREDITS\|USD\|SECONDS\|UNITS, expiresAt? }` |
| PATCH | `/balances/{id}` | Edit a manual balance (`clearExpiry: true` removes the expiry) |
| DELETE | `/balances/{id}` | Remove a manual balance (provider-synced ones are refreshed by Sync instead) |
| GET | `/usage?accountId=&page=&size=` | Generation history with cost and whether it was reported by the provider or estimated |
| GET | `/usage/summary?days=30` | Totals: generations, failures, seconds, spend per unit, per account |

When a generation finishes, its cost is subtracted from manual balances in the same unit (free credits first, then soonest expiry). Accounts with a balance API are re-synced instead.

## Video generation

| Method | Path | Purpose |
|---|---|---|
| POST | `/generation/video/estimate` | Same body as below. Returns `{ perVideo, total, unit, balance, balanceAfter, lowCredit, insufficient, warnings[] }` |
| POST | `/generation/video` | Queues 1–4 takes. Returns `202 { jobs[], estimate }`. Send an `Idempotency-Key` header so a double submit can't start (and bill) twice. |
| GET | `/generation/jobs?active=false&page=` | Takes, newest first. Completed ones include `output` (the media item with signed URLs). |
| GET | `/generation/jobs/{id}` | One take |
| POST | `/generation/jobs/{id}/cancel` | Cancels at the provider where supported (Runway, Luma), then marks it cancelled |
| POST | `/generation/jobs/{id}/regenerate` | Runs the same settings again as a new take |
| DELETE | `/generation/jobs/{id}` | Removes a finished take from history. The video stays in the library. |
| POST | `/generation/enhance-prompt` | `{ accountId, model, prompt, language?, targetModel? }` → `{ prompt }` |
| GET | `/generation/events` | Server-Sent Events: a `job` event with the full job each time one changes |

Request body:

```json
{
  "accountId": "…", "modelId": "…", "mode": "TEXT_TO_VIDEO",
  "prompt": "A slow dolly shot through a Madurai flower market at dawn",
  "negativePrompt": "text, logos", "durationSeconds": 8, "aspectRatio": "1920:1080",
  "resolution": null, "audio": true, "imageMediaId": null, "projectId": null, "variations": 2
}
```

`aspectRatio` and `resolution` use the model's own values from `GET /models`. Everything is validated against the model before anything is sent to the provider.

**How jobs run.** A background worker leases due jobs from PostgreSQL (`FOR UPDATE SKIP LOCKED`, 15-minute lease), so a restart loses nothing. Each job goes submit → poll → download → saved to the media library, with these rules:

- Rate-limit and outage errors retry with backoff (15 s doubling to 5 min, up to 5 attempts).
- A rejected key marks the account `INVALID`.
- Jobs still running after 75 minutes are cancelled and marked `GENERATION_TIMEOUT`.

**Provider notes (checked against the official docs in November 2026):**

| Provider | API used | Image-to-video input | Cost reporting |
|---|---|---|---|
| Runway | `api.dev.runwayml.com`, `X-Runway-Version: 2024-11-06` | Data URI | Exact credits per task |
| Google Gemini | Gemini Omni Flash, Interactions API, URI delivery | Inline Base64 | None. Length is guided in the prompt. |
| Kling AI | API-key standard, `POST /text-to-video/{model}` | Raw Base64 | Resource-pack balance, with a 12-hour delay |
| Luma | Dream Machine `/generations/video` | Public HTTPS link only, so it needs the studio online | Balance in USD |

# Phase 3: voice, audio, subtitles, images, thumbnails

Every endpoint below saves its results as ordinary media (`MediaResponse`, with signed URLs), and voice, image, transcription and sound calls are written to `usage_logs`. Accounts are chosen by id. Keys never leave the server.

Media also gained:

| Method | Path | Purpose |
|---|---|---|
| GET | `/media/{id}/content` | Streams the file from our own origin. The thumbnail canvas uses it, because a canvas with images from another origin can't be exported. `Cache-Control: private`. |

## Voice

| Method | Path | Purpose |
|---|---|---|
| GET | `/voice/voices?accountId=` | Provider voices (ElevenLabs: stock voices plus your clones; Gemini: 30 prebuilt voices) |
| POST | `/voice/narrations` | Voice-over → `201 { media, chunks, characters }` |
| POST | `/voice/preview` | Same as above with `text` ≤ 300 characters. Returns `audio/mpeg` and is not saved. |
| POST | `/voice/dialogues` | `{ script, speakers:[{name, profileId}], language?, gapSeconds?, title?, projectId? }` → `{ media, … }` |
| GET / POST | `/voice/profiles` | Saved voices. POST `{ accountId, voiceId, name, language?, model?, style? }` |
| PATCH / DELETE | `/voice/profiles/{id}` | Edit name, language, model or style. Deleting a cloned voice also deletes it at ElevenLabs. |
| GET | `/voice/clone/consent` | `{ statement }`: the sentence to type |
| POST | `/voice/clone` | `{ accountId, name, description?, language?, sampleMediaIds[], consentOwnVoice: true, consentStatement, removeBackgroundNoise }` |
| GET / POST | `/voice/pronunciations` | `{ term, replacement, language? }` (EN, TA or null for all) |
| PUT / DELETE | `/voice/pronunciations/{id}` | |

Narration body:

```json
{
  "voice": { "profileId": null, "accountId": "…", "voiceId": "JBFqnCBsd6RMkjVDRZzb", "model": "eleven_v3" },
  "text": "வணக்கம் நண்பர்களே! [pause 1s] இன்று…",
  "language": "TA",
  "style": { "emotion": "STORYTELLING", "direction": null, "speed": 1.1, "pitch": 0, "stability": null, "similarity": null },
  "title": "Episode 12 intro", "projectId": null
}
```

- `voice` is either `{ profileId }` (the profile's saved style fills any gaps) or `{ accountId, voiceId }`.
- `emotion` is one of `NEUTRAL HAPPY SAD ANGRY EXCITED STORYTELLING DOCUMENTARY PROFESSIONAL`.
- `speed` is 0.5–2 and `pitch` is −12 to 12 semitones.
- Text can be up to 50,000 characters. Scripts are split into parts below each model's limit.

**Cloning consent.** The request is refused unless `consentOwnVoice` is true and `consentStatement` matches the sentence, ignoring case and spacing. The profile stores `consent = { ownVoice, statement, confirmedAt, sampleChecksums[], removeBackgroundNoise }`. Samples must be audio, at most 10 MB each and 25 files in total.

## Audio tools (FFmpeg, no provider)

| Method | Path | Body |
|---|---|---|
| POST | `/audio/recordings` | multipart `file` (+ `projectId`). Saves a microphone recording; audio-only WebM/MP4 is stored as AUDIO. |
| POST | `/audio/cleanup` | `{ mediaId, operations: ["DENOISE","REMOVE_SILENCE","ENHANCE","NORMALIZE"] }`, always applied in that order |
| POST | `/audio/speed-pitch` | `{ mediaId, speed: 0.5–2, semitones: −12–12 }` |
| POST | `/audio/mix` | `{ voiceMediaId, musicMediaId, musicVolumeDb: −40–6, duck, fadeSeconds: 0–10, title? }` |
| POST | `/audio/extract` | `{ videoMediaId }` |
| POST | `/audio/sound-effects` | `{ accountId, prompt, durationSeconds?: 0.5–30, loop, projectId? }` (ElevenLabs) |

All return `201 { media }` as a new MP3. The original is never changed.

## Subtitles

| Method | Path | Purpose |
|---|---|---|
| GET | `/subtitles?mediaId=` | Tracks for a file `[{ id, language, label, source, cueCount, updatedAt }]` |
| POST | `/subtitles/transcribe` | `{ mediaId, accountId, language?: "ta", maxLineChars?: 16–60 }` → the track with `cues` |
| POST | `/subtitles` | Manual track `{ mediaId, language, label?, cues? }` |
| GET / PATCH / DELETE | `/subtitles/{id}` | PATCH `{ label?, language?, cues? }` (sorted and cleaned on save) |
| POST | `/subtitles/{id}/translate` | `{ accountId, model, targetLanguage }`. A new `TRANSLATED` track with the same timing. |
| GET | `/subtitles/{id}/export?format=srt\|vtt&inline=false` | File download |

A cue is `{ "start": 2.9, "end": 5.8, "text": "line one\nline two" }`, in seconds.

Transcription extracts a 16 kHz mono MP3 first. OpenAI Whisper accepts up to 25 MB (about 70 minutes). ElevenLabs Scribe accepts much longer files.

## Images

| Method | Path | Purpose |
|---|---|---|
| POST | `/images/generate` | `{ accountId, model, prompt, size?, aspectRatio?, imageSize?, count?: 1–4, quality?, transparent, references?: [mediaId…≤4], projectId? }` → `{ images: [media] }` |
| GET | `/images/tools` | `{ backgroundRemoval: bool, presets: [{ id, label, width, height }] }` |
| POST | `/images/remove-background` | `{ mediaId }` → transparent PNG (imagetools service) |
| POST | `/images/resize` | `{ mediaId, width, height, fit: COVER\|CONTAIN, background?: "#rrggbb", format: png\|jpg }` |

- `size` is for OpenAI: `WxH`, both multiples of 16, ratio at most 3:1, at most 3840×2160.
- `aspectRatio` and `imageSize` (1K/2K/4K) are for Gemini.

## Thumbnails

| Method | Path | Purpose |
|---|---|---|
| GET | `/thumbnails?templates=false` | Designs (or templates) with their last export |
| POST | `/thumbnails` | `{ name, width, height, document, template, projectId? }` |
| GET / PATCH / DELETE | `/thumbnails/{id}` | |
| POST | `/thumbnails/{id}/duplicate` | `{ name?, template? }`: use a template or copy a design |
| POST | `/thumbnails/{id}/export` | multipart `file`: the PNG/JPG rendered by the editor, saved as media (`RENDER`), at most 25 MB |

`document` is the editor's JSON (`{ version: 1, background, layers[] }`, at most 512 KB). Image layers refer to media by id; image bytes are never embedded.

## Background removal service

`imagetools/` is a small FastAPI app using rembg (u2net, downloaded when the image is built). It is reachable only inside the Docker network (`IMAGETOOLS_URL=http://imagetools:7000`) and has no published port. Remove the service to switch the feature off; everything else keeps working.

# Phase 4: video editor and exports

## Timelines

| Method | Path | Purpose |
|---|---|---|
| GET | `/editor/timelines?projectId=&page=` | Newest first: `{ id, name, width, height, fps, durationSeconds, projectId, poster, updatedAt }` |
| POST | `/editor/timelines` | `{ name, document, projectId? }` |
| GET | `/editor/timelines/{id}` | The timeline with `document`, `revision` and `media`, a map of every file it uses (with signed links) |
| PATCH | `/editor/timelines/{id}` | `{ name?, projectId?, unassign? }` |
| PUT | `/editor/timelines/{id}/document` | Autosave: `{ revision, document }` → `{ revision, durationSeconds, updatedAt }`. Returns **409** if the revision is not the latest (another tab saved). |
| POST | `/editor/timelines/{id}/duplicate` | Copy |
| DELETE | `/editor/timelines/{id}` | Removes the edit. Media and exports stay. |

`document` is the format in `frontend/src/remotion/types.ts` (version 1):

```json
{
  "version": 1, "width": 1920, "height": 1080, "fps": 30, "background": "#000000",
  "tracks": [
    { "id": "t1", "kind": "text", "name": "Text 1", "muted": false, "hidden": false, "locked": false, "volume": 1,
      "clips": [{ "id": "c1", "type": "text", "start": 0.5, "duration": 4, "text": "வணக்கம்", "x": 0.5, "y": 0.45,
                  "animationIn": "pop", "animationOut": "fade", "style": { "fontFamily": "Anek Tamil Variable", "fontSize": 120, … } }] },
    { "id": "t2", "kind": "video", …, "clips": [{ "id": "c2", "type": "media", "mediaId": "…", "kind": "VIDEO",
                  "start": 0, "duration": 12, "trimStart": 3, "speed": 1, "volume": 1, "fadeIn": 0, "fadeOut": 0.5,
                  "fit": "cover", "x": 0, "y": 0, "scale": 1, "rotation": 0, "opacity": 1,
                  "filters": { "brightness": 1, "contrast": 1, "saturation": 1, "blur": 0 }, "motion": "none",
                  "transitionIn": { "type": "none", "duration": 0.5 }, "transitionOut": { "type": "fade", "duration": 0.6 } }] },
    { "id": "t3", "kind": "audio", … }
  ]
}
```

Times are in seconds. `duration` is the length on the timeline, so a 10 s source at speed 2 lasts 5 s. `trimStart` is how far into the source the clip starts.

The server checks every save and refuses anything outside these limits:
- version 1; even width and height, 64–4096; fps 24/25/30/50/60
- at most 30 tracks and 3,000 clips, and at most 3 hours long
- clips on the right kind of track; unique ids
- speed 0.25–4; volume 0–2
- `mediaId` must be a real UUID of a file that still exists
- at most 2 MB in total

## Exports

| Method | Path | Purpose |
|---|---|---|
| GET | `/editor/renders/status` | `{ available }`: whether a render service is configured |
| POST | `/editor/timelines/{id}/renders` | `{ resolution: 720\|1080\|1440\|2160, quality: STANDARD\|HIGH\|BEST }` → `202` export job. The saved timeline is copied at this moment. |
| GET | `/editor/renders?timelineId=` | Recent exports `{ id, status, progress, outputWidth, outputHeight, errorMessage, output (media), … }` |
| GET | `/editor/renders/{id}` | One export |
| POST | `/editor/renders/{id}/cancel` | Stops it |
| DELETE | `/editor/renders/{id}` | Removes a finished export from the list. The video stays in the library. |

`resolution` is the short side of the output, with the timeline's shape kept. H.264 settings: STANDARD is CRF 23 with x264 `veryfast`, HIGH is CRF 19 `veryfast`, BEST is CRF 18 `slow`. Audio is AAC.

**How exports run.** A worker leases due jobs from PostgreSQL (the same `FOR UPDATE SKIP LOCKED` pattern as generation):
- It sends the timeline, internal signed links to its files (valid for 6 hours) and the output size to the render service.
- It checks progress every 3 seconds. When the export is done it downloads the MP4 and saves it to the media library (source `RENDER`).
- If the render service is unreachable, it retries with backoff, up to 6 attempts. If the service restarted and lost the export, it starts again (up to 3 times).
- Exports still running after 3 hours are stopped.
- At most 5 exports can wait at a time.

## Render service (internal)

`renderer/server.mjs` listens on port 7100 inside the Docker network only. Every call except `/health` needs the header `X-Renderer-Token: $RENDERER_TOKEN`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/renders` | `{ id, props: { timeline, media, output: { width, height } }, crf, x264Preset }` → 202. Idempotent per id. |
| GET | `/renders/{id}` | `{ status: queued\|rendering\|done\|failed\|cancelled, progress, error }` |
| GET | `/renders/{id}/file` | The MP4 |
| DELETE | `/renders/{id}` | Cancel, or delete the file |

The service renders one export at a time and deletes finished files after 6 hours. Tests:
- `npm test`: the API, with a fake renderer.
- `BROWSER_EXECUTABLE=/path/to/chrome npm run test:render`: a real render, checked with ffprobe.

# Phase 5: AI auto-editor

| Method | Path | Purpose |
|---|---|---|
| GET | `/auto-edit/jobs` | Latest 30 automatic edits with `status`, `step`, `progress` and `result.timelineIds` / `result.notes` |
| POST | `/auto-edit/jobs` | Start one (body below) → `202` |
| GET | `/auto-edit/jobs/{id}` | One job |
| POST | `/auto-edit/jobs/{id}/cancel` | Stop it. Noticed between steps. |
| POST | `/auto-edit/jobs/{id}/rerun` | Same settings again. Cached transcripts and descriptions are reused. |
| DELETE | `/auto-edit/jobs/{id}` | Remove a finished job from the list. Its timelines stay. |

Body (one shape for all modes; each mode uses its own fields):

```json
{
  "mode": "SCRIPT_TO_VIDEO | TALKING_HEAD | HIGHLIGHTS",
  "name": "Madurai food tour", "format": "LANDSCAPE | PORTRAIT | SQUARE", "language": "TA | EN", "projectId": null,
  "footageIds": ["…"],
  "script": "…", "topic": null, "targetSeconds": 60, "style": "energetic food vlog",
  "voice": { "profileId": "…" }, "voiceStyle": { "emotion": "STORYTELLING" },
  "describeFootage": true, "imageAccountId": null, "imageModel": null,
  "textAccountId": "…", "textModel": "…", "transcriptionAccountId": "…",
  "removeFillers": true, "maxPause": 0.45, "punchIn": true,
  "highlightCount": 3, "minSeconds": 20, "maxSeconds": 60,
  "captions": true, "musicMediaId": null, "musicVolume": 0.15
}
```

What each mode needs:

| Mode | Needs |
|---|---|
| SCRIPT_TO_VIDEO | `script`, or `topic` with a text model. Footage (videos and images) is optional. `voice` is optional; without it, scene lengths are estimated from reading speed. `describeFootage` needs a text model that accepts images. |
| TALKING_HEAD | One or more videos, in order, and `transcriptionAccountId` (ElevenLabs or OpenAI) |
| HIGHLIGHTS | Exactly one video, `transcriptionAccountId`, a text model, and `minSeconds` < `maxSeconds` |

Steps the job reports in `step`:
- Script to video: "Writing the script" or "Splitting your script into scenes", "Recording the voice-over", "Looking at <file>", "Creating image n of m", "Choosing shots for each scene", "Building the timeline"
- Talking video: "Listening to <file>", "Building the timeline"
- Shorts: "Listening to <file>", "Finding the best moments", "Building short n of m"

How the AI is used (prompts in `autoedit/Prompts.java`; every reply must be JSON):
- **Script writing or splitting** returns `{title, scenes:[{narration, visual, onScreenText}]}`. When splitting your own script, the narration must stay word for word.
- **Shot matching** gets the scenes with their lengths and a catalogue (F1, F2… with length, file name and AI description), and returns `{scenes:[{scene, shots:[{f, start, end}]}]}`. `ShotPlanner` then repairs it: it keeps times inside the source, makes shots at least 1.2 s, fills each scene exactly, and fills any gap with footage not yet used.
- **Highlights** get the transcript as `[12.3] sentence` lines and return `{highlights:[{start, end, title, hook}]}`. `HighlightPicker` snaps them to words and sentences, keeps them within the chosen lengths, and drops any that overlap.
- **Footage descriptions** send 3 JPEG frames of 512 px (at 15%, 50% and 85% of the clip) as OpenAI `image_url` data links, Claude base64 image blocks, or Gemini `inline_data`.
- A reply that can't be read is retried once. If script splitting fails, the script is split by paragraph. If shot matching fails, footage is placed in order. Each fallback is listed in `result.notes`.

Cached analysis (`media_analyses`): one row per file holds the transcript (words with timings) and the description, with the model that made it. Deleting the file deletes its analysis.

# Phase 6: social accounts, publishing and the calendar

All endpoints need the owner session and (for changes) the `X-XSRF-TOKEN` header, except the sign-in return address.

## Social accounts

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/social/platforms` | Each platform: `platform`, `name`, `signIn` (GOOGLE, META, TIKTOK), `configured`, `redirectUri`, `setupHint`, `limits[]` |
| GET | `/api/v1/social/accounts` | Connected accounts: `id`, `platform`, `displayName`, `username`, `profileUrl`, `status` (CONNECTED, NEEDS_RECONNECT), `lastError`, `accessExpiresAt`, `connectedAt`. Never tokens. |
| POST | `/api/v1/social/oauth/{google\|meta\|tiktok}/start` | Returns `{authorizationUrl}`. The browser goes there. 409 when the platform has no credentials. |
| GET | `/api/v1/social/oauth/{provider}/callback` | **No session needed** (see Security notes). Checks the single-use `state`, swaps the code for tokens, saves the account(s) and redirects (302) to `/publish/accounts?connected=N` or `?error=<message>`. |
| POST | `/api/v1/social/accounts/{id}/check` | Re-reads the profile with the saved sign-in (refreshing it if needed). Marks the account NEEDS_RECONNECT if the platform refuses. |
| GET | `/api/v1/social/accounts/{id}/tiktok-creator` | TikTok creator info: `nickname`, `username`, `privacyOptions[]`, `commentDisabled`, `duetDisabled`, `stitchDisabled`, `maxVideoSeconds`. TikTok requires showing this on the post screen. |
| DELETE | `/api/v1/social/accounts/{id}` | Revokes the Google/TikTok grant (best effort), deletes the tokens, and fails its scheduled posts. Meta grants are shared by all Pages, so remove the app in Facebook settings to revoke them. |

What one sign-in creates:
- **Google:** one YOUTUBE account (the channel from `channels.list?mine=true`). Access tokens last about an hour and are refreshed with the refresh token.
- **Meta:** the code becomes a long-lived user token, which lists your Pages (`/me/accounts`). Each Page becomes a FACEBOOK account; a linked Instagram professional account becomes an INSTAGRAM account. Both use the Page token, which doesn't expire.
- **TikTok:** one TIKTOK account (`open_id`). Access tokens last 24 hours; refresh tokens a year.

## Posts

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/publish/posts?status=` | Latest 50 posts (or up to 100 with one status) |
| GET | `/api/v1/publish/calendar?from=&to=` | Posts scheduled or published in the range (ISO instants, at most 62 days) |
| POST | `/api/v1/publish/posts` | Create (201) |
| GET | `/api/v1/publish/posts/{id}` | One post |
| PUT | `/api/v1/publish/posts/{id}` | Replace it. 409 once any account has started uploading. |
| POST | `/api/v1/publish/posts/{id}/reschedule` | `{scheduledAt}`. Moves a draft's planned date or a scheduled post's time (calendar drag). |
| POST | `/api/v1/publish/posts/{id}/cancel` | Cancels the accounts that haven't started |
| POST | `/api/v1/publish/posts/{id}/targets/{targetId}/retry` | Publishes a failed or cancelled account again, from the start |
| DELETE | `/api/v1/publish/posts/{id}` | Removes it from the studio (not from the platforms). 409 while uploading. |

Create / update body:

```json
{
  "title": "Jigarthanda in 60 seconds",
  "mediaId": "…video…",
  "thumbnailMediaId": "…image, optional, YouTube only…",
  "action": "SCHEDULE",
  "scheduledAt": "2026-11-21T13:30:00Z",
  "targets": [
    { "accountId": "…", "settings": { "title": "…", "description": "…", "tags": ["madurai"], "categoryId": "26",
                                      "privacy": "public", "madeForKids": false, "aiGenerated": false } },
    { "accountId": "…", "settings": { "description": "…" } },
    { "accountId": "…", "settings": { "caption": "…", "shareToFeed": true, "coverSeconds": 1.5 } },
    { "accountId": "…", "settings": { "mode": "DIRECT", "caption": "…", "privacy": "SELF_ONLY",
                                      "allowComment": false, "allowDuet": false, "allowStitch": false,
                                      "brandContent": false, "brandOrganic": false, "aiGenerated": true, "musicConsent": true } }
  ]
}
```

`action`: `DRAFT` (nothing is posted; `scheduledAt` is an optional planned date; settings may be incomplete), `SCHEDULE` (needs a future `scheduledAt`, at most a year ahead), `NOW`.

Settings checked per platform (400 with every problem listed, otherwise unknown keys are dropped):

| Platform | Rules |
|---|---|
| YOUTUBE | `title` 1–100 characters, no `<` `>`; `description` ≤ 5000 bytes; tags ≤ 500 characters in total; `privacy` public/unlisted/private; `madeForKids` must be given |
| FACEBOOK | Reels: 3–90 s, at least 540×960; `description` ≤ 5000 |
| INSTAGRAM | Reels: 3 s–15 min, ≤ 300 MB; `caption` ≤ 2200 characters and ≤ 30 hashtags |
| TIKTOK | `mode` DIRECT or INBOX. DIRECT: `privacy` must be chosen (re-checked against the creator's options just before posting), `musicConsent` true, branded content can't be SELF_ONLY, video length ≤ the creator's limit. `caption` ≤ 2200 |

Response: `id`, `title`, `media`, `thumbnailMediaId`, `status`, `scheduledAt`, `publishedAt`, `createdAt`, `updatedAt`, `version`, `targets[]`.
Each target: `id`, `accountId`, `platform`, `accountName`, `status`, `settings`, `step`, `progress`, `remoteUrl`, `errorMessage`, `warning`, `attempts`, `nextAttemptAt`, `publishedAt`.

Target status: `DRAFT`, `SCHEDULED`, `UPLOADING`, `PROCESSING`, `AWAITING_USER` (TikTok inbox: finish in the app), `PUBLISHED`, `FAILED`, `CANCELLED`.
Post status (from its targets): `DRAFT`, `SCHEDULED`, `PUBLISHING`, `PUBLISHED`, `PARTIAL` (some failed), `FAILED`, `CANCELLED`.

## How each platform is called

| Platform | Upload | Then |
|---|---|---|
| YouTube | `POST upload/youtube/v3/videos?uploadType=resumable&part=snippet,status` → `PUT` the file to the session URL. On a dropped connection, `PUT` with `Content-Range: bytes */size` and resume from the `Range` YouTube reports. Optional `thumbnails/set`. | `videos.list?part=status,processingDetails` every minute until `processed` (or `rejected`/`failed`). Warns if the video came back private when you asked for public or unlisted. |
| Facebook Page | `POST /{page}/video_reels` `upload_phase=start` → `POST rupload.facebook.com/video-upload/…` with `offset`/`file_size` headers → `upload_phase=finish&video_state=PUBLISHED` | `GET /{video}?fields=status,permalink_url` until `publishing_phase.status=complete` |
| Instagram | Checks `content_publishing_limit` → `POST /{ig}/media` `media_type=REELS&upload_type=resumable` → `POST rupload.facebook.com/ig-api-upload/…` | `GET /{container}?fields=status_code` until `FINISHED` → `POST /{ig}/media_publish` → permalink. An `EXPIRED` container starts over. |
| TikTok | `creator_info/query` → `video/init` (or `inbox/video/init`) with `FILE_UPLOAD` → `PUT` chunks of 10 MB (`Content-Range`), the last chunk taking the remainder | `status/fetch` until `PUBLISH_COMPLETE` (or `SEND_TO_USER_INBOX` → AWAITING_USER, polled for 24 h) |

Errors from the platforms are sorted into: **reconnect** (expired or revoked sign-in, missing permission — the account is flagged), **rate limited** (quota, daily caps; waits `Retry-After` or hours), **temporary** (network, 5xx; retried with back-off from 30 s up to 1 h, 6 tries), and **rejected** (the platform refused the post; not retried). Messages come from the platform's response, never from our request, so tokens can't leak into them.

Database (`V6__phase6_social_publishing.sql`): `social_accounts`, `social_tokens` (encrypted), `oauth_states` (hashed, 10 minutes), `posts`, `post_targets` (with `state` holding upload progress, and `run_at`/`locked_until` for the worker).

Environment: `PUBLIC_BASE_URL`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `META_APP_ID`, `META_APP_SECRET`, `META_GRAPH_VERSION` (default v26.0), `TIKTOK_CLIENT_KEY`, `TIKTOK_CLIENT_SECRET`, `PUBLISH_MAX_CONCURRENT` (default 2).

# Phase 7: analytics, two-step sign-in, activity, health

## Analytics

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/analytics/overview?days=30&tz=Asia/Colombo` | Everything on the Analytics page for 1–365 days. Days start at midnight in `tz` (IANA name; UTC if left out). |
| POST | `/api/v1/analytics/refresh` | Reads the latest numbers from the platforms now. 429 if run less than 10 minutes ago. Returns `{updated}`. |
| GET | `/api/v1/analytics/targets/{targetId}/metrics` | Every snapshot of one post on one account: `collectedAt`, `views`, `likes`, `comments`, `shares` |

`overview` returns:
- `costs`:
  - `totals[]` and `daily[]` are per `unit`. Amounts in different units are never added together.
  - `byProvider[]` gives `provider`, `capability`, `unit` (null when not reported), `amount` and `calls`.
  - Also `calls`, `failed`, and `unknownCost` (successful calls without a cost).
- `publishing`:
  - `posts` (created in the period), `published` and `failed` (account-level), `upcoming` (scheduled for later).
  - `byPlatform[]`, `daily[]` (`date`, `platform`, `count`), and `recentFailures[]` (8 newest).
- `performance`:
  - `byPlatform[]`: `posts`, `withNumbers`, and the sums of `views`, `likes`, `comments`, `shares` from each post's latest snapshot. Null means no post reported that number.
  - `posts[]`: up to 50, most viewed first, with `url` and `note` (why there are no numbers).
  - `lastCollectedAt`.

Numbers collection (`MetricsCollector`): every 30 minutes it looks for published posts from the last 90 days not read in 6 hours, groups them by account and stores one `post_metrics` row each.

| Platform | Call | Permission | Fallback |
|---|---|---|---|
| YouTube | `GET youtube/v3/videos?part=statistics&id=…` (50 per call) | `youtube.readonly` | — |
| Facebook Reel | `GET /{video}/video_insights` (all metrics; `blue_reels_play_count`, `post_video_likes_by_reaction_type`, `post_video_social_actions`) | `read_insights` | `GET /{video}?fields=likes.summary(true),comments.summary(true)` |
| Instagram | `GET /{media}/insights?metric=views,reach,likes,comments,shares,saved` | `instagram_manage_insights` | `GET /{media}?fields=like_count,comments_count` |
| TikTok | `POST /v2/video/query/?fields=id,view_count,like_count,comment_count,share_count` (20 per call) | `video.list` | — (public posts only) |

## Two-step sign-in

| Method | Path | Body | What it does |
|---|---|---|---|
| POST | `/api/v1/auth/login` | `{email, password}` | Now returns `{mfaRequired, user}`. With two-step on, `mfaRequired` is true and the session is **not** signed in yet. |
| POST | `/api/v1/auth/mfa/login` | `{code}` | **No session login needed.** A 6-digit app code or a recovery code. 5 tries within 5 minutes of the password step; wrong codes count towards the sign-in lockout. |
| POST | `/api/v1/auth/mfa/setup` | `{password}` | Returns `{secret, otpauthUri}` for the QR code. Nothing changes until confirmed. |
| POST | `/api/v1/auth/mfa/enable` | `{code}` | Turns it on; returns `{recoveryCodes[10]}`, shown once |
| POST | `/api/v1/auth/mfa/recovery-codes` | `{password, code}` | Replaces the recovery codes |
| POST | `/api/v1/auth/mfa/disable` | `{password, code}` | Turns it off (204) |

`GET /api/v1/auth/me` now includes `mfaEnabled`, `mfaEnabledAt` and `recoveryCodesLeft`.

Codes are RFC 6238 (HMAC-SHA1, 6 digits, 30 s), accepting one step of clock drift either way. The last used step is stored, so a code can't be replayed. Recovery codes look like `abcde-fghjk` (no 0/o/1/l/i), are compared without case, spaces or dashes, and are stored as SHA-256 hashes.

## Activity and health

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/audit?group=security&page=0&size=50` | Audit trail, newest first. `group`: `security`, `accounts`, `publishing`, `files`, `all`. Items: `action`, `entityType`, `entityId`, `ipAddress`, `details`, `createdAt`. |
| GET | `/api/v1/system/status` | `services[]` (`name`, `state` OK/WARNING/DOWN/OFF, `detail`), `queues[]` (`waiting`, `running`, `failedToday`), `mediaFiles`, `mediaBytes`, `databaseBytes`, `lastBackup`, `backupDisk`, `warnings[]`, `uptime`, `schemaVersion` |

Request limit: more than `API_REQUESTS_PER_MINUTE` (600) calls to `/api/…` from one address within a minute get 429 `RATE_LIMIT_EXCEEDED` with `Retry-After: 60`. Event streams are not counted.

Database (`V7__phase7_analytics_security.sql`): `post_metrics`, `post_targets.metrics_checked_at` and `metrics_note`, `users.mfa_*`, `mfa_recovery_codes`, plus indexes for the audit filter and published posts.

Environment: `API_REQUESTS_PER_MINUTE`, `BACKUP_DIR` (backend, read-only mount), `TZ`, `BACKUP_TIME`, `BACKUP_KEEP` (backup service).

## Scripts (Phase 8)

All writing calls take `writer: {accountId, model}`: an OpenAI, Anthropic or Gemini account and one of its text models. Language is `EN` or `TA`. `videoType` is one of `YOUTUBE_LONG`, `YOUTUBE_SHORT`, `REEL`, `TIKTOK`, `TUTORIAL`, `VLOG`, `DOCUMENTARY`, `CINEMATIC`, `ENTERTAINMENT`, `OTHER`.

| Method | Path | Body | What it does |
|---|---|---|---|
| POST | `/api/v1/scripts/ideas` | `{writer, topic, videoType, language, count 1–10, audience?}` | `{ideas: [{title, hook, angle}]}`; nothing is saved |
| POST | `/api/v1/scripts/write` | `{writer, projectId?, title?, idea, videoType, language, minutes 1–60, tone?, notes?}` | Writes and saves a script |
| POST | `/api/v1/scripts` | `{projectId?, title, videoType, language, idea?, body}` | Saves your own script |
| GET | `/api/v1/scripts?projectId=` | | Newest 100 |
| GET/PUT/DELETE | `/api/v1/scripts/{id}` | PUT: `{title, idea?, body, language, videoType, projectId?}` | Delete keeps later versions (they lose their parent link) |
| POST | `/api/v1/scripts/{id}/rewrite` | `{writer, instruction}` | A new version (`parentId`, `versionNo + 1`) |
| POST | `/api/v1/scripts/{id}/metadata` | `{writer, platforms[≤10]}` | Fills `metadata.{PLATFORM}` with `title`, `description`, `caption`, `hashtags` (no `#`) |

A script response includes `wordCount` and `spokenMinutes` (spoken words only; 150 a minute in English, 110 in Tamil).

## Characters (Phase 8)

| Method | Path | Body | What it does |
|---|---|---|---|
| GET | `/api/v1/characters?projectId=` | | With a project: its characters plus shared ones |
| POST / PUT | `/api/v1/characters`, `/api/v1/characters/{id}` | `{name, description?, appearance?, clothing?, voiceProfileId?, projectId?, referenceMediaIds[≤8]}` | References must be images; the first is the main one |
| DELETE | `/api/v1/characters/{id}` | | Reference images stay in the library |

## Storyboards (Phase 8)

`aspectRatio` is `LANDSCAPE`, `PORTRAIT` or `SQUARE`.

| Method | Path | Body | What it does |
|---|---|---|---|
| GET | `/api/v1/storyboards?projectId=` | | Summaries with scene, image and video counts and a cover |
| POST | `/api/v1/storyboards` | `{title, aspectRatio, language, style?, projectId?}` | A blank board |
| POST | `/api/v1/storyboards/from-script` | `{scriptId, writer, aspectRatio, style?, title?}` | The AI plans up to 60 scenes and links characters by name |
| GET / PUT / DELETE | `/api/v1/storyboards/{id}` | PUT: `{title, aspectRatio, language, style?, projectId?}` | GET also links scene videos whose generation has finished |
| POST | `/api/v1/storyboards/{id}/scenes?at=` | scene fields (optional) | Adds a scene at a position (default: the end) |
| PUT | `/api/v1/storyboards/{id}/scenes/order` | `{sceneIds}` | Every scene exactly once |
| PUT / DELETE | `/api/v1/storyboards/scenes/{sceneId}` | Only fields sent change | Fields: `title`, `description`, `narration`, `dialogue[{character, line}]`, `durationSeconds 1–120`, `cameraAngle`, `cameraMovement`, `lighting`, `composition`, `soundNotes`, `characterIds[≤8]`, `imagePrompt`, `videoPrompt`, `referenceMediaIds[≤4]`, `imageMediaId`, `videoMediaId` |
| POST | `/api/v1/storyboards/scenes/{sceneId}/duplicate` | | Keeps the still, not the video |
| POST | `/api/v1/storyboards/scenes/{sceneId}/image` | `{accountId, model}` | Generates the still with the scene's and characters' references (max 4) |
| POST | `/api/v1/storyboards/scenes/{sceneId}/video` | `{accountId, modelId, mode, durationSeconds?, resolution?, audio?}` | Starts a generation job; `IMAGE_TO_VIDEO` needs the still |
| POST | `/api/v1/storyboards/scenes/{sceneId}/dialogue-voice` | | Every speaker must be a character with a voice (max 6) |
| POST | `/api/v1/storyboards/{id}/narration` | `{voice, style?}` | One voice-over; `narrationParts[{sceneId, start, length}]` |
| POST | `/api/v1/storyboards/{id}/export` | `{name?, captions, musicMediaId?, musicVolume?}` | Creates a timeline; returns `{timelineId, totalSeconds, notes[]}` |

Every scene and storyboard call returns the whole storyboard. Each scene includes `suggestedImagePrompt` and `suggestedVideoPrompt` (what the studio would send without your own prompt) and `videoJob` (`status`, `progress` 0–1, `errorMessage`).

## Topic analysis and automation (E11)

| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | `/projects/{id}/topic-analysis` | project members | `{ topicsApproved, topicsVersionNo, settings, topics: [{ topicId, title, path, resourceId, status, versionNo, approvedNo, job, jobError, jobAuto, jobAt, next: [Step] }], canEdit, canReview, counts }`. `next` is shown once the analysis is approved: notes, questions, QA report, SEO report, presentation and lesson video, each `{ kind, label, status, resourceId, job, jobError, auto }`. |
| PUT | `/projects/{id}/topic-analysis/settings` | owner, editor | `{ enabled, accountId, model, notesLevel, mcqCount (4–30), videoMinutes (3–20), autoNotes, autoMcq, autoSlides }`. Turning it on needs a working AI account and a model. |
| POST | `/projects/{id}/topic-analysis/run` | owner, editor | `{ topicIds?, accountId?, model?, guidance? }` → 202 `{ queued, skipped, notes }`. Without `topicIds`, every teaching unit with no analysis. A topic whose analysis is in review is skipped; one already running is skipped. 409 until the topic list is approved. |
| GET | `/topic-analysis/{resourceId}/next` | project members | `{ projectId, topicId, topicTitle, notesLevel, steps, settings }` |

An analysis is read, edited, sent for review and approved through the approval routes (`/reviews/resources/{id}`, kind `TOPIC_ANALYSIS`). Approving one without an approved topic list returns 409 (`LX024`). Jobs started by the automation appear in the same job lists with `auto` set.

## Dashboard and notifications (E10)

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/dashboard/education` | `{counts {subjectProjects, documents, analysedModules, approvedTopics, notes, approvedNotes, questions, qaReports, approvedQaReports, pendingApprovals, approvedSeoReports, presentations, approvedPresentations, videos, approvedVideos, publishedVideos, activeJobs}, tasks[] {kind: REVIEW, CHANGES, PUBLISH or FAILED_JOB, projectId, projectName, resourceId, resourceKind, title, versionNo, since, byName}, active[] {kind: AI or RENDER, projectId, projectName, what, status, progress, startedAt, target}, activity[] {at, action, resourceKind, resourceId, title, projectId, projectName, byName, versionNo, comment}}`. Everything is counted for the projects the asker can see |
| GET | `/api/v1/notifications?unreadOnly=&limit=` | `{items[] {id, projectId, projectName, kind, title, body, link, createdAt, readAt}, unread}`, newest first (limit 1 to 100, default 30). `link` is a path in the app or, for a published post, the video's address |
| GET | `/api/v1/notifications/unread` | The number of unread notifications |
| POST | `/api/v1/notifications/{id}/read` | 204. Marks one read |
| POST | `/api/v1/notifications/read` | 204. Marks all read |
| DELETE | `/api/v1/notifications/{id}` | 204, or 404 if it isn't yours |

Kinds: `REVIEW_REQUESTED`, `APPROVED`, `CHANGES_REQUESTED`, `REJECTED`, `OUTDATED`, `JOB_DONE`, `JOB_FAILED`, `RENDER_DONE`, `RENDER_FAILED`, `PUBLISHED`, `PUBLISH_FAILED`. Only the database writes notifications (V18 triggers); the app can read, mark read and remove its person's own.

## YouTube publishing and analytics (E9)

**Authorisation records (every post).** Posting or scheduling (`POST/PUT /api/v1/publish/posts` with `action: NOW` or `SCHEDULE`) records an authorisation for each target. The request takes `confirmPublic: true`, which is needed when a target will be public now or later (YouTube `privacy: public` or a `publishAt`; TikTok other than `SELF_ONLY`; every Facebook and Instagram post), otherwise 400. `POST /api/v1/publish/posts/{id}/targets/{targetId}/retry?confirmPublic=true` authorises a target that has no record. A post made from a project's Publish page can't be edited in the creator publisher (409).

Database errors: `LX020` no matching authorisation or not allowed to authorise (409), `LX021` public without confirmation (409), `LX022` education publication refused (403), `LX023` an authorisation can't be changed (409).

YouTube settings (`targets[].settings`) gained: `publishAt` (ISO time; needs `privacy: private`, 15 minutes to a year ahead), `playlistIds[]` (up to 5) and `captions[] {language, name, srt}` (up to 3).

Who: project members read; the project **Owner** or an **administrator** authorises, creates playlists and turns on reach reports.

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/projects/{projectId}/youtube` | `{canAuthorise, channels[] {id, name, username, avatarUrl, status, canManage, canReadAnalytics, lastError}, videos[] {resourceId, kind, level, topicId, title, versionId, versionNo, mediaId, seoApproved, captionsAvailable, publicationIds[]}, publications[], quotaUnits}`. Videos are approved versions with a render that passed its checks |
| GET | `/api/v1/projects/{projectId}/youtube/videos/{resourceId}/prepare` | What would be sent: `{versionId, mediaId, titles[] {id, text, chosen}, description, tags[], hashtags[], playlistIdeas[], captions {language, name, cues, srt}, language, problems[]}`, all from the approved SEO report and the checked render. 409 if the SEO report isn't approved |
| GET | `/api/v1/projects/{projectId}/youtube/channels/{accountId}` | Channel details from YouTube `{channelId, title, customUrl, thumbnail, subscribers (null if hidden), videos, views, subscribersHidden, playlists[] {id, title, privacy, items}, note}` |
| POST | `/api/v1/projects/{projectId}/youtube/channels/{accountId}/playlists` | `{title, description?, privacy}` → 201 the playlist (created on YouTube) |
| POST | `/api/v1/projects/{projectId}/youtube/publications` | The authorisation step. `{resourceId, versionId, accountId, titleId, visibility: private, unlisted, public or scheduled, publishAt?, when: NOW or SCHEDULE, uploadAt?, thumbnailMediaId?, playlistIds[], addCaptions, addHashtags, madeForKids, alteredOrSynthetic, notifySubscribers, categoryId? (27), rightsConfirmed, confirmPublic}` → 201 `{publicationId, post}`. 400 without the rights confirmation, 409 if a newer version was approved or the channel needs reconnecting |
| GET | `/api/v1/projects/{projectId}/youtube/analytics?accountId=&from=&to=` | The newest snapshot of the period (default: the 28 days ending yesterday): `{from, to, fetchedAt, fetchedByName, canRefresh, canManageReach, reach, totals, videos[], daily[], traffic {source: views}, searchTerms[], retention {videoId: [{ratio, audienceWatchRatio, relativeRetentionPerformance}]}, scenes {videoId: [{start, end, title}]}, recommendations[] {kind, title, detail, rule, videoId, evidence[] {metric, value, source}}, history[], notes[]}`. Nothing is fetched from YouTube |
| POST | `/api/v1/projects/{projectId}/youtube/analytics/refresh?accountId=&from=&to=` | Queries the YouTube Analytics API, stores the results as a snapshot set and returns it. 409 if the channel lacks the analytics permission or nothing is published on it; 429 when YouTube's quota is used up |
| GET | `/api/v1/projects/{projectId}/youtube/analytics/snapshots?from=&to=` | Snapshot sets overlapping the period `{accountId, startDate, endDate, fetchedAt, createdByName, parts}` |
| GET | `/api/v1/projects/{projectId}/youtube/analytics/snapshots/{fetchedAt}?accountId=` | An earlier snapshot set, as it was |
| POST | `/api/v1/projects/{projectId}/youtube/analytics/reach?accountId=` | Turns on the Reporting API reach report for the channel (or finds the job already there) |
| POST | `/api/v1/projects/{projectId}/youtube/analytics/reach/import?accountId=` | Imports reach files not imported yet; also runs daily |

## Lesson and quiz videos (E8)

Who:
- Every project member can read videos and their renders.
- Owners and editors plan, narrate and render videos.
- Plans are edited and reviewed through the E3 routes (`/reviews/resources/{id}` and the rest).
- Kinds: `LESSON_VIDEO` (per topic and level) and `QUIZ_VIDEO` (per topic).

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/projects/{projectId}/videos` | `{topicsApproved, canEdit, rendererAvailable, topics[]}`. A topic: `{topicId, title, depth, approvedDecks[] (levels), mcqApproved, seoApproved, videos[] {kind, level, resourceId, versionNo, status, renderStatus (CHECKED or the latest render's status), running (narration), error}}` |
| POST | `/api/v1/projects/{projectId}/videos` | `{kind: LESSON or QUIZ, topicId, level (lesson), language: EN or TA, aspect: 16:9 or 9:16, voice?, countdown? (3 to 30), questions? (1 to 30)}` → 201 `{resourceId}`. The plan is a draft (it replaces an existing draft). 409 until the presentation (lesson), or the MCQ set and SEO report (quiz), are approved, or while the video is in review |
| POST | `/api/v1/videos/{resourceId}/narration` | `{versionId}` (a draft with a voice) → 202 with the job. Speaks every part; 409 while one is running. The job fails if the words or voice change while it runs |
| GET | `/api/v1/videos/{resourceId}/preview?versionId=` | `{document, durationSeconds, media}`: the timeline the plan makes, for the preview. 409 without narration, or while a diagram isn't drawn |
| POST | `/api/v1/videos/{resourceId}/renders` | `{versionId, resolution: 720 to 2160, quality?}` → 202. Renders that version (draft, in review or changes requested) on the video's editor timeline |
| GET | `/api/v1/videos/{resourceId}/renders?versionId=` | Renders, newest first: `{renderJobId, versionId, versionNo, forThisContent, status, progress, error, output, validation, timelineId, createdAt, completedAt}` |

**Plan content:** `{kind, topicId, topicTitle, level, sourceVersionId, seoVersionId, title, language, style, aspect, voice {profileId | accountId + voiceId, model, speed, label}, quiz {countdown}, scenes[], narration, captions}`.
- A scene: `{id, kind, title, lines[], slide, question {questionId, number, total, stem, code, options[4], answer, explanation}, keywords[], narration, answerNarration, sizes}`.
  - Lesson scenes are INTRO, SLIDE and OUTRO; quiz scenes are QUIZ_INTRO, QUESTION, SUMMARY and OUTRO.
- `narration` is `{mediaId, scriptHash, segments[] {sceneId, part: MAIN or ANSWER, start, duration}, characters, voiceLabel, madeAt}`. The server drops it when the words or voice no longer match its `scriptHash`.
- **Checks before approval:**
  - Must pass: every scene has narration; a voice is chosen; the narration is made and current; the source is approved; slides and questions are as approved; and a render of this exact content passed its file checks.

**Render file checks** (all renders, editor exports included):
- `render_jobs.validation`, also returned as `validation` on render jobs, is `{ok, checkedAt, problems[], found {sizeBytes, durationSeconds, codec, pixelFormat, width, height, fps, frames, audioCodec, audioChannels, audioSeconds, meanVolumeDb}, expected {durationSeconds, width, height, fps, audio}}`.
- A render that fails is `FAILED` with "The file failed its checks: …".

**Google Cloud Text-to-Speech** (`GOOGLE_CLOUD_TTS`, voice):
- The key is a service account key file (JSON, up to 8,000 characters; keys are now up to 8,000 characters for every provider) or an API key.
- It is checked by listing the Tamil voices.
- Voices are listed through the usual `/voice/voices?accountId=`.

Rule errors from the database:
- `LX018`: a video's source isn't approved.
- `LX019`: no render of this content passed the file checks.

## Presentations (E7)

Who:
- Every project member can read decks.
- Owners and editors make decks, edit them and redo slides.
- Decks are reviewed through the E3 routes (`/reviews/resources/{id}` and the rest).

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/projects/{projectId}/slides` | `{topicsApproved, topics[], canEdit, styles {key: name}, types[], layouts[]}`. A topic: `{topicId, title, depth, approvedNotes[] (levels), qaApproved, seoApproved, decks[] {level, resourceId, versionNo, status, slides, running, error}}` |
| POST | `/api/v1/projects/{projectId}/slides` | `{topicId, level, accountId, model, style?, slides? (5 to 40, default 14), guidance?}` → 202 with the job. 409 until the notes at that level, the QA report and the SEO report are approved, while one is already being made, or while the deck is in review |
| POST | `/api/v1/projects/{projectId}/slides/fit` | A deck (as below, without `fit`) → its fit. Nothing is saved; used while editing |
| POST | `/api/v1/slides/{resourceId}/slides/{slideId}/regenerate` | `{versionId, accountId, model, guidance?}` → `{slide, fit}`: a proposal, not saved. 400 for the references slide; 409 if the notes are no longer approved |
| GET | `/api/v1/exports/versions/{versionId}?format=pptx\|pdf` | A deck version as PowerPoint (16:9) or PDF. 409 with the reasons if any text doesn't fit, or if the rendered file has a character outside its box |

**Deck content:** `{topicId, topicTitle, level, notesVersionId, qaVersionId, seoVersionId, style, title, slides[], fit, model}`.
- `style` is one of LECTURE, MINIMAL, DARK_TECH, INFOGRAPHIC, WHITEBOARD, CORPORATE, ILLUSTRATED.
- A slide: `{id, type, layout, title, subtitle, bullets[], term, text, table {headers[], rows[][]}, figure {kind: MERMAID or MATH, source}, image {assetId, alt}, caption, code {language, text}, notes, sources[]}`.
  - `type` is one of the 15 types: TITLE, OBJECTIVES, INTRODUCTION, OVERVIEW, DEFINITION, CONCEPT, DIAGRAM, FLOWCHART, EXAMPLE, COMPARISON, APPLICATIONS, EXAM_TIPS, SUMMARY, REVISION, REFERENCES.
  - `layout` is one of TITLE, BULLETS, BULLETS_IMAGE, DEFINITION, TABLE, DIAGRAM, EXAMPLE, IMAGE. Only the fields the layout shows are kept.
  - `sources` are the notes headings the slide draws on.
- **Limits:**
  - 60 slides and 10 bullets a slide.
  - A table has 8 columns and 15 rows.
  - Text: a title 200 characters, a bullet 400, text 2,000, code 4,000, speaker notes 5,000.
  - Too-long text is refused (400), never cut.
- **`fit`** is set by the server on every save: `{checked, ok, method, slides[] {id, layout, ok, problems[], boxes[] {field, size, fits, lines, needed, room, min, max, note}}}`. It is `{checked: false, error}` when the document service couldn't be reached.
- **Checks before approval:**
  - Must pass: the text was measured and fits; each layout has its content; the notes, QA report and SEO report are approved; diagrams are drawn; images are in the project's media; references come from the notes.
  - Warnings: content or numbers not found in the notes, dense or shrunk slides, a missing title slide, references slide or speaker notes, and notes changed since the deck was made.

Rule error from the database: `LX017`, a deck can't be approved without the approved notes, QA report and SEO report.

## YouTube SEO (E6)

Who:
- Every project member can read SEO data.
- Owners and editors run research and import or remove Trends files.
- SEO reports are reviewed through the E3 routes (`/reviews/resources/{id}` and the rest).

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/projects/{projectId}/seo` | `{topicsApproved, budget, trends[], topics[], youtube[] {id, name, status}, canEdit, weights}`. A topic: `{topicId, title, depth, hasNotes, researchId, researchStatus, researchedAt, reportId, reportVersionNo, reportStatus, chosenTitle, running, jobError}` |
| GET | `/api/v1/seo/budget` | `{dailySearches, usedToday, left, unitsToday, cacheDays, resetsAt, quotaDay}`. Counted across the whole install by Pacific-time day |
| POST | `/api/v1/projects/{projectId}/seo/research` | `{topicId, youtubeAccountId?, accountId, model, languages ["en","ta"], region ("LK"), searches (0 to 10), trendsIds[], guidance?}` → 202 with the job. 409 when fewer searches are left than asked, when research is already running for the topic, or while its report is in review. With `searches: 0`, only cached results are used |
| GET | `/api/v1/seo/research/{researchId}` | `{id, projectId, topicId, status (COMPLETE or PARTIAL), data, createdBy, createdAt}` |
| POST | `/api/v1/projects/{projectId}/seo/trends` | Multipart `file`: an "Interest over time" CSV from Google Trends → 201 `{id, filename, geo, period, firstDate, lastDate, terms[], status, createdBy, createdAt}`. 400 for anything else |
| DELETE | `/api/v1/seo/trends/{trendsId}` | Leaves the file out of new research; research that used it keeps its copy |
| GET | `/api/v1/exports/versions/{versionId}?format=pdf\|docx\|xlsx` | An SEO report version: the 23 parts (PDF, Word), or Keywords, Competitors, Titles and Sources sheets (Excel) |

**A Metric**, the shape of every number: `{value, unit, source, retrievedAt, estimated, method, note, inputs}`.
- It needs either a non-blank `source` or `estimated: true`.
- `value: null` needs a `note` saying why (for example "Hidden by the channel").
- A score's `inputs` holds the Metrics it was made from.
- Only `rank` (a position in search results) may be a bare number.

**Research data:** `{topicId, topicTitle, subtopics[], languages[], region, startedAt, ideas[], keywords[], searches[], competitors[], coverage[], analysis, trends[], sources[], budget, weights, limitations[], model}`.
- A keyword: `{text, language, kind, intent, audience, source, searched, relevance, demand, competition, gap, score, partial, trend}`.
- A competitor: `{videoId, url, title, channel, publishedAt, duration, views, likes, comments, description, tags[], thumbnail, covers[], foundBy[], rank}`.

**SEO report content:** `{topicId, topicTitle, researchId, titles[], recommendedTitleId, recommendationReason, chosenTitleId, description, primaryKeywords[], secondaryKeywords[], tags[], hashtags[], thumbnailText, thumbnailPrompt, chapters[] {title, start}, playlists[] {title, why}, nextVideos[] {title, why}, strategies {LESSON, MCQ, EXAM, REVISION, SHORTS: {titleIdea, angle, format, keywords[], notes}}, model}`.
- A title: `{id, text, style, language, check {length, hasKeyword, warnings[], ai {criterion: Metric}, overall, reasoning}}`.
- **Saving** re-runs the title rules, drops AI scores from edited titles, rebuilds chapter times as estimates, and keeps tags within 500 characters and hashtags to 15.
- **Checks before approval:** 10 to 15 titles, a usable chosen title, a description, and every number labelled, in the report and in its research. Warnings cover clickbait options, YouTube chapter rules, missing plans, partial research, and an unapproved academic QA report for the topic.

Rule error from the database: `LX016`, an imported Trends file can't be changed.

## Academic QA and QA reports (E5)

Who:
- Every project member can read QA.
- Owners, editors and reviewers run checks and rechecks.
- Owners and editors accept corrections, because accepting changes the content.
- Owners, editors and reviewers reject them.
- Owners and reviewers dismiss and reopen issues, with a reason.

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/projects/{projectId}/qa` | `{topicsApproved, topics[] {topicId, title, depth, items[], reportId, reportVersionNo, reportStatus, reportHeadline}, canEdit, canReview}`. An item: `{resourceId, kind, level, versionNo, versionStatus, checkedVersionNo, current, open {CRITICAL?, MAJOR?, MINOR?}, running}`. `current` means the newest version, as it is now, has a full check |
| GET | `/api/v1/qa/resources/{resourceId}` | For notes or a bank: `{resourceId, kind, writerModel, runs[], issues[], job, canRun, canEdit, canReview}` |
| POST | `/api/v1/qa/resources/{resourceId}/runs` | `{versionId?, accountId, model, secondAccountId?, secondModel?, recheckOnly}` → 202 with the job (`{id, status, error, summary, createdAt, startedBy}`). A full check first rechecks open issues. 409 while one is running; 400 for a recheck with nothing open |
| POST | `/api/v1/qa/corrections/{correctionId}/accept` | `{note?}`: applies the correction to the draft, or to a new draft from the newest version, and marks the issue corrected (it waits for a recheck). 409 while a version is in review, or when a find-and-replace can't be applied exactly |
| POST | `/api/v1/qa/corrections/{correctionId}/reject` | `{note?}` |
| POST | `/api/v1/qa/issues/{issueId}/dismiss` | `{reason}` (required) |
| POST | `/api/v1/qa/issues/{issueId}/reopen` | `{reason}` (required); for a dismissed or resolved issue |
| POST | `/api/v1/projects/{projectId}/qa/reports/{topicId}` | Makes the topic's QA report, or makes it again (a new version, or a refreshed draft). Returns the resource state; kind `QA` |
| GET | `/api/v1/exports/versions/{versionId}?format=pdf\|docx&part=COMBINED\|NOTES\|MCQ` | A QA report version as PDF or Word |

A QA report is reviewed through the E3 routes (`/reviews/resources/{id}`, submit, decision, events). Saving, editing and "approve with edits" are refused (409): Leenax makes the report, and nobody edits it.

**A run:** `{id, resourceId, versionId, versionNo, contentHash, mode (CHECK or RECHECK), model, provider, secondModel, writerModel, independent, result, createdBy, createdAt}`.
- `result` is `{categories {CATEGORY: {checked}}, claims[] {itemRef, claim, verdict (SUPPORTED, CONTRADICTED, UNSUPPORTED), basis, evidence[]}, questions[] {questionId, number, key, answer, secondAnswer, verdict, difficulty, suggestedDifficulty}, limitations[], summary}`.
- `contentHash` is the exact content checked; a draft edited after its check isn't "checked" any more.

**An issue:** `{id, number, resourceId, runId, foundInVersionId, foundInVersionNo, itemRef, itemLabel, category, type, severity, original, explanation, basis, evidence[], suggestion, confirmations[] {model, agrees, note}, status, statusNote, recheck {fixed, note, versionNo, model, at}, decidedBy, decidedAt, createdAt, correction}`.
- `category`: ACCURACY, COVERAGE, EXPLANATIONS, REFERENCES, MCQ_KEYS, DIFFICULTY or DUPLICATES.
- `type`: FACTUAL_ERROR, WRONG_KEY, AMBIGUOUS, MISSING_TOPIC, CONTRADICTION (these five are always CRITICAL, and only these), UNSUPPORTED_CLAIM, MATH_ERROR, CODE_ERROR, INCONSISTENT, WEAK_EXPLANATION, WEAK_EXAMPLE, BAD_REFERENCE, COVERAGE_GAP, WRONG_DIFFICULTY, DUPLICATE, RULE_BREAK, GRAMMAR or OTHER.
- `basis`: UPLOADED (a course document page; `evidence` has `documentId, versionId, page, quote, found`), EXTERNAL (a link the AI gave, not opened), AI, ESTIMATE (a rule in Leenax) or UNVERIFIED.
- `status`: OPEN → CORRECTED (correction accepted) → RESOLVED (a recheck found it fixed) or back to OPEN; OPEN or CORRECTED → DISMISSED (with a reason); DISMISSED or RESOLVED → OPEN (reopened, with a reason).
- `correction`: the latest proposal, `{id, issueId, proposal, status (PROPOSED, ACCEPTED, REJECTED), appliedVersionId, note, decidedBy, decidedAt, createdAt}`. A proposal is `{find, replace}` for notes, or `{questionId, answer?, options?, stem?, explanation?}` for a question.

**The blocking rule:** every NOTES, MCQ and QA version's `checks` include `qa`. It fails (blocking) while a critical issue is open or corrected-not-rechecked on that resource or, for a report, on what it covers. Approving then fails with 409 (`LX014`) from the database as well. `qa-open` warns about major and minor issues, and `qa-run` says whether this exact content was checked.

**QA report content:** `{topicId, topicTitle, generatedAt, items[], issues[], corrections[], coverage[] {subtopic, covered, levels[]}, parts {NOTES, MCQ, COMBINED}, headline, method, recommendations[], limitations[]}`.
- A part: `{checked, passed, failed, corrected, open, openCritical, score, headline (BLOCKED, NOT_CHECKED, NEEDS_ATTENTION, PASSED), categories {CATEGORY: {checked, passed, failed}}}`.
- Failed counts the issues still open; the headline is BLOCKED whenever `openCritical > 0`.
- Before approval: everything covered must be approved, and checked as it is now.

Rule errors from the database: `LX014`, an open critical QA issue blocks approval; `LX015`, a finding or proposal can't be rewritten, or the status step isn't allowed.

## Notes, question banks and quizzes (E4)

Notes (kind `NOTES`) and question banks (kind `MCQ`) are reviewed like the syllabus: same versions, statuses, decisions and record. Open them by resource id:
- `GET /api/v1/reviews/resources/{resourceId}`: same shape as `/projects/{id}/reviews/{kind}`. `resource` gains `topicId` and `level`.
- The version, draft, submit, decision and events routes from E3 apply unchanged.

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/projects/{projectId}/materials` | `{topicsApproved, topicsVersionNo, topics[] {id, title, depth, path}, notes[], banks[], jobs[], canEdit, canReview}`. Each note or bank: `{resourceId, kind, topicId, level, title, latestNo, latestStatus, approvedNo, approvedVersionId, questionCount}`. `jobs`: running ones and the latest per target |
| POST | `/api/v1/projects/{projectId}/notes/generate` | `{topicId, level, accountId, model, guidance?}` → 202 with the job. 409 without an approved topic list, or while that topic and level is being written |
| POST | `/api/v1/projects/{projectId}/notes/manual` | `{topicId, level}`: a blank draft (409 if those notes exist) |
| POST | `/api/v1/projects/{projectId}/mcq/generate` | `{topicId, notesLevel, count (1 to 30), difficulties[], styles[], accountId, model, guidance?, allowAllNone}` → 202. Needs approved notes at `notesLevel`. New questions go into the bank's draft, or a new draft |
| POST | `/api/v1/projects/{projectId}/mcq/manual` | `{topicId}`: an empty bank |
| POST | `/api/v1/materials/{resourceId}/update` | Ties outdated notes to the approved topic list, or a bank to the approved notes, as a new draft |
| POST | `/api/v1/mcq/{resourceId}/regenerate` | `{versionId, questionId, accountId, model, guidance?}` → one new question. Nothing is saved; the page puts it in the draft |
| POST | `/api/v1/projects/{projectId}/figures` | `{kind: MATH / MERMAID, source, svg}` → 204. Keeps the browser's drawing for exports (owners, editors, reviewers; ignored for viewers). The first drawing of a source is kept |
| GET | `/api/v1/reviews/resources/{resourceId}/comments` | `[{id, versionId, versionNo, quote, body, authorId, author, createdAt, resolvedAt, resolvedBy}]` |
| POST | `/api/v1/reviews/versions/{versionId}/comments` | `{quote? (≤500), body (≤2000)}`: owners, editors and reviewers. Returns the list |
| POST | `/api/v1/reviews/comments/{commentId}/resolve` | `{resolved}` |
| DELETE | `/api/v1/reviews/comments/{commentId}` | Your own, while open (403 otherwise) |
| GET | `/api/v1/projects/{projectId}/quizzes` | `[{id, title, questionCount, seed, createdBy, createdAt}]` |
| POST | `/api/v1/projects/{projectId}/quizzes` | `{title, bankIds[], count (1 to 200), difficulties[], styles[], shuffleOptions, seed?}`. Draws from each bank's approved version; 400 if fewer questions match |
| GET | `/api/v1/quizzes/{quizId}` | `{id, title, seed, config, questions[], createdBy, createdAt}`. A question: `{number, bankId, bankTitle, bankVersionNo, questionId, style, difficulty, stem, code, options[4], answer, explanation, optionExplanations, subtopic, source}` |
| DELETE | `/api/v1/quizzes/{quizId}` | Owners and editors |
| GET | `/api/v1/exports/versions/{versionId}?format=` | Notes: `pdf`, `docx`. Banks: `pdf`, `docx`, `xlsx`, `csv` (with answers) |
| GET | `/api/v1/exports/quizzes/{quizId}?format=&answers=` | `pdf`, `docx`, `xlsx`, `csv`; `answers=none` (for students) or `end` (answer key at the end) |

**Notes content:** `{topicId, topicTitle, level (BEGINNER, INTERMEDIATE, ADVANCED, UNIVERSITY, EXAM), topicsVersionId, doc, references[], model}`.
- `doc` is TipTap JSON. The server keeps only known nodes and marks: paragraph, heading, lists, blockquote, codeBlock, table, horizontalRule, hardBreak, mathBlock and mathInline `{latex}`, mermaid `{code}`, mediaImage `{assetId, alt}`; bold, italic, underline, strike, code, subscript, superscript, and links to http, https or mailto.
- A reference: `{label, documentId, versionId, page, note, found}`.
- The server sets `topicsVersionId`.

**Bank content:** `{topicId, topicTitle, notesVersionId, notesLevel, allowAllNone, questions[]}`.
- A question: `{id, style, difficulty, stem, code {language, text}, options[4], answer (0 to 3), explanation, optionExplanations[4], subtopic, source, verification (UNVERIFIED, PASSED, FAILED)}`.
- `style` is one of BEGINNER, INTERMEDIATE, ADVANCED, APPLICATION, SCENARIO, CODE, CALCULATION, EXAM.
- The server sets `notesVersionId`.
- Checks before review: every question has text, four different options, one key and an explanation, and no "all/none of the above" unless allowed. Near-repeats elsewhere in the project are a warning.

Comment rule from the database: `LX013`, a comment's text can't be changed.

### The document service: exports (internal)

`POST /export` with `{format, kind: notes / questions / qa / seo / slides, part (qa: COMBINED, NOTES or MCQ), report (qa, seo), research (seo), title, subtitle, meta[[label, value]], doc, figures {"KIND:sha256": svg}, images {assetId: dataUrl}, references[], questions[], answerKey: inline / end / none}` returns the file. Drawings are cleaned against an SVG allow-list before use. For `kind: slides`: `{format: pptx or pdf, deck, figures, images}`; a deck whose text doesn't fit, or whose rendered file has a character outside its box, gets 422 with `{message, problems[]}`. `POST /slides/fit` with `{deck}` returns the fit without making a file.

## Approvals, syllabus and topics (E3)

Who:
- Every project member can read.
- Owner, Editor and administrators write drafts and send them for review.
- Owner, Reviewer and administrators decide.
- With the project's `reviewerNotAuthor` on, the author of a version can't decide on it (403).

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/projects/{projectId}/reviews/{syllabus\|topics}` | `{kind, resource, versions[], latest, approvedVersionId, approvedVersionNo, canEdit, canReview}`; `resource` is null until the first version |
| GET | `/api/v1/reviews/versions/{versionId}` | `{version, content, checks[], latest, you}`. `checks`: `{code, ok, blocking, message}`. `you`: `{canEdit, canReview, isAuthor, mayDecide, reviewerMustDiffer}` |
| PUT | `/api/v1/reviews/versions/{versionId}` | `{content}`: change a draft (409 once it has left draft) |
| POST | `/api/v1/reviews/resources/{resourceId}/versions` | `{fromVersionId, content?}`: a new draft from an earlier version (409 while a draft exists or a version is in review) |
| POST | `/api/v1/reviews/versions/{versionId}/submit` | `{comment?}`: draft → in review; 409 if a blocking check fails |
| POST | `/api/v1/reviews/versions/{versionId}/withdraw` | in review → draft |
| POST | `/api/v1/reviews/versions/{versionId}/decision` | `{decision: APPROVE / APPROVE_WITH_EDITS / REQUEST_CHANGES / REJECT, comment, content}`. A comment is required to send back or reject; `content` is required with edits. Returns the resulting version (the new one when approved with edits) |
| GET | `/api/v1/reviews/resources/{resourceId}/events` | The decision record, newest first: `{action, versionNo, previousStatus, newStatus, comment, details, actor, createdAt}`. With edits, `details.changes` lists what changed |
| GET | `/api/v1/projects/{projectId}/syllabus/analysis` | `{documents[] (read documents to choose from, with pages and chars), maxChars, job}` |
| POST | `/api/v1/projects/{projectId}/syllabus/analysis` | `{documentIds[] (1 to 10), accountId, model, guidance?}` → 202 with the job. 400 over the size limit; 409 while one is running |
| POST | `/api/v1/projects/{projectId}/syllabus/analysis/{jobId}/cancel` | Only while waiting |
| POST | `/api/v1/projects/{projectId}/syllabus/manual` | A blank draft to write by hand (409 if a syllabus exists) |
| POST | `/api/v1/projects/{projectId}/topics/from-syllabus` | Builds the topic list from the approved syllabus, or updates it to a newer approved one; 409 without an approved syllabus |

**Syllabus content:**
- Top level: `{summary, structure[], outcomes[], assessments[], contradictions[], documents[], analysis}`.
- `structure` items: `{id, title, sources[], children[]}`, at most three levels.
- A source: `{documentId, versionId, page, quote, found}`.
- An assessment: `{id, title, kind (EXAM, COURSEWORK, QUIZ, PRACTICAL, PRESENTATION, PROJECT, OTHER), weight, details, sources}`.
- A contradiction: `{id, description, sources, resolution}`.

**Topic list content:**
- Top level: `{syllabusVersionId, nodes[]}`. The server sets `syllabusVersionId` from the version itself.
- A topic: `{id, title, syllabusItemIds[], selected, difficulty (INTRODUCTORY, INTERMEDIATE, ADVANCED), prerequisites[] (topic ids), sources[], notInSyllabus, children[]}`.

Errors from the database rules arrive as 409 or 403 with a plain message:
- `LX010`: the content can't change any more.
- `LX011`: only owners and reviewers decide, never a background job.
- `LX012`: the reviewer must not be the author.

## Course documents (E2)

Who: every member of the project can read, search and download; Owner, Editor and administrators can add, rename, re-read and delete (403 otherwise; 404 if you can't see the project).

| Method | Path | What it does |
|---|---|---|
| GET | `/api/v1/projects/{projectId}/documents` | `{serviceReady, canEdit, documents[]}`. A document: `id, projectId, title, createdAt, updatedAt, createdBy, versionCount, latest (version), subject {result: MATCH/DIFFERENT/UNKNOWN, found[], projectCode}` |
| POST | `/api/v1/projects/{projectId}/documents?title=` | Multipart `file` → 201 with the document detail. 415 for other file types, 413 over the limit, 409 if the same file is already in the project |
| GET | `/api/v1/projects/{projectId}/documents/search?q=` | `{query, words[], hits[], more}`. A hit: `documentId, title, versionId, versionNo, pageNo, snippet {text, cutBefore, cutAfter, highlights[{start, end}]}` (UTF-16 offsets) |
| GET | `/api/v1/documents/{id}` | `{document, versions[], canEdit}`, newest version first |
| PATCH | `/api/v1/documents/{id}` | `{title}` |
| DELETE | `/api/v1/documents/{id}` | Deletes every version and the stored files, 204 |
| POST | `/api/v1/documents/{id}/versions` | Multipart `file` → a new version; 409 if it's the same file as the newest version |
| GET | `/api/v1/documents/versions/{versionId}/pages?from=1` | Up to 20 pages from `from`: `{versionId, versionNo, pageCount, from, pages[{pageNo, method: TEXT/OCR, ocrConfidence, text}], more}` |
| GET | `/api/v1/documents/versions/{versionId}/download-url` | Short-lived signed link to the original file |
| POST | `/api/v1/documents/versions/{versionId}/reprocess` | `{forceOcr}`: read again (from the page images with `forceOcr`); 409 while it's being read |

A version: `id, versionNo, originalFilename, contentType, sizeBytes, status (QUEUED/PROCESSING/READY/FAILED), error, pageCount, pagesRead, ocrPages, ocrConfidence, language (en/ta/mixed/unknown), moduleCodes[], detectedTitle, forceOcr, createdAt, processedAt, uploadedBy`. While queued after a failed try, `error` says why and that it will try again.

Search: words are letters, combining marks and digits (Tamil vowel signs stay in their word), lower-cased and in Unicode NFC; each query word (at most 8, at least 2 characters) must begin a word in the same passage. Passages are about 1,000 characters and never cross a page.

### The document service (internal)

`docservice` (port 7200, Docker network only). `POST /extract` with multipart `file` and optional `force_ocr` → `{pageCount, pagesRead, truncated, ocrPages, converted, language, moduleCodes[], title, pages[{page, text, method, confidence}]}`. `GET /health` lists the installed OCR languages. Settings: `OCR_LANGUAGES` (default `eng+tam`), `MAX_DOCUMENT_PAGES` (500), `MAX_DOCUMENT_BYTES` (100 MB), `OCR_DPI` (300).

## People and access (E1)

### Accounts

| Method | Path | Who | What it does |
|---|---|---|---|
| GET | `/api/v1/users?page=0&size=50&search=...&status=ACTIVE` | Admin | Paginated `{users[], page, size, totalItems, totalPages}`. Search is by name/email prefix; status is optional. A person: `id, email, displayName, role (ADMIN/MEMBER), status (INVITED/ACTIVE/DEACTIVATED), canCreateProjects, mfaEnabled, lastLoginAt, activatedAt, deactivatedAt, createdAt` |
| POST | `/api/v1/users` | Admin | `{email, displayName, role, canCreateProjects}` → `{user, path, expiresAt}`. `path` is `/activate?token=…`, shown once |
| PUT | `/api/v1/users/{id}` | Admin | `{displayName, role, canCreateProjects}` |
| POST | `/api/v1/users/{id}/deactivate`, `/reactivate` | Admin | Not yourself; accounts can be reactivated without a seat cap |
| POST | `/api/v1/users/{id}/activation-link` | Admin | Invited people only; older links stop working |
| POST | `/api/v1/users/{id}/reset-link` | Admin | Active people; `/reset-password?token=…`, 24 hours |
| GET | `/api/v1/users/logins?userId=&page=&size=` | Admin | Sign-in history: `email, success, reason (wrong_password, wrong_code, account_not_active), ipAddress, userAgent, createdAt` |
| GET | `/api/v1/users/colleagues?page=0&size=20&search=...` | Anyone signed in | Paginated active people `{items[], page, size, totalItems, totalPages}` to add to projects; search matches name/email prefix |
| GET | `/api/v1/auth/link?token=` | Public | `{purpose: ACTIVATE/RESET, email, displayName, expiresAt}`; 404 for unknown, used or expired links (counts towards the sign-in lockout) |
| POST | `/api/v1/auth/link` | Public | `{token, password}`: activates or resets, 204 |

`/api/v1/auth/status` and `/auth/me` now include `id`, `role` and `canCreateProjects`.

### Projects

New fields: `kind` (CREATOR/EDUCATION), `subject {subjectName, moduleCode, academicYear, semester, university, department}`, `reviewerNotAuthor` (administrators change it), and in responses `myRole` (OWNER/EDITOR/REVIEWER/VIEWER, or ADMIN for an administrator who isn't a member) and `memberCount`.

| Method | Path | Who | What it does |
|---|---|---|---|
| GET | `/api/v1/projects/similar?name=&moduleCode=` | Anyone | Up to 5 visible projects with the same module code or name |
| GET | `/api/v1/projects/{id}/members` | Members | `[{userId, displayName, email, role, status, addedAt}]` |
| POST | `/api/v1/projects/{id}/members` | Owner, admin | `{userId, role}` |
| PUT | `/api/v1/projects/{id}/members/{userId}` | Owner, admin | `{role}`; a project keeps at least one owner |
| DELETE | `/api/v1/projects/{id}/members/{userId}` | Owner, admin, or yourself (leave) | Returns the remaining members |

Changing a project needs Owner or Editor; archiving and deleting need Owner.

### Who sees what

Enforced by PostgreSQL row-level security (V9) on projects, media, generation jobs, auto-edit jobs, render jobs, timelines, scripts, storyboards (and their scenes and references), characters, subtitles, thumbnail designs, posts (and targets and metrics), voices and media analyses:

- Administrators: everything.
- Members: rows in projects they belong to, plus rows with no project that they created. Shared voices are readable by everyone.
- Writing (insert, update, delete) needs Owner or Editor in the project, or being the creator of a row outside projects.

Answers: a refused insert is 403 `FORBIDDEN`; a refused update or delete is 409 `CONFLICT` ("This changed, or you can't edit it here"); losing the last administrator or deleting a person is 409 `CONFLICT`.

Administrator-only routes: `/users` (except `/users/colleagues`), `/system/**`, changes to `/ai/accounts`, `/ai/models`, `/ai/balances`, `/ai/usage`, and connecting, checking or disconnecting social accounts. `GET /api/v1/audit` shows administrators everyone's activity (with `userName`) and members their own; new group `people`.

The live job feed (`/ai/generation/events`) sends each person only the jobs they can see. Voice profiles have `shared` and `createdBy`; `PATCH /voice/profiles/{id}` takes `shared`.
