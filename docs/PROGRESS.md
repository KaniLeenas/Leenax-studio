# Progress

The progress record the specification asks for (section 22, step 4). Details per increment are in the README ("What's in …" and "Known limits") and `docs/CHECKLIST.md`.

## Completed

| Increment | What | Database | Tests added |
|---|---|---|---|
| Phases 1 to 8 | Creator studio: projects, media, AI video and images, voice, subtitles, thumbnails, editor, auto-editor, social publishing, analytics, security, backups, scripts and storyboards | V1 to V8 | see README |
| E1 | Multi-user accounts, roles, project members, row-level security | V9, V20 | Updated V9 suite (rerun pending) |
| E2 | Course documents, OCR, search | V10 | V10 (29), E2 (19) |
| E3 | Approval engine, syllabus report, topics | V11 | V11 (33), E3 (24) |
| E4 | Notes, question banks, quizzes, exports | V12 | V12 (32), E4 (47) |
| E5 | Academic QA and QA reports | V13 | V13 (39), E5 (36) |
| E6 | YouTube SEO | V14 | E6 (32) |
| E7 | Presentations | V15 | E7 (32) |
| E8 | Lesson and quiz videos, render checks, Google Cloud TTS | V16 | E8 (24) |
| E9 | YouTube publishing with authorisation records, analytics | V17 | E9 (59) |
| E10 | Dashboard, notifications, whole-workflow test, CI, guides | V18 | E10 (39) |
| Desktop | Windows app (Electron) for the server: window, tray, notifications | none | desktop rules (7), run against the mock server |
| E11 | Topic analysis, automatic notes, questions and presentation after approvals | V19 | E11 (29) |

Totals at E11: 308 backend unit tests, 205 frontend tests, 17 SQL suites with 568 checks on PostgreSQL 16 (19 migrations on an empty database), renderer and document-service tests.

## Current state

All education increments are built. The app has not yet been compiled by Maven or started in a real Spring server, and no provider was called live: see `docs/ACCEPTANCE.md`, "Before you rely on it".

## Known issues and limits

- Live provider calls (AI text, voice, YouTube, Meta, TikTok) are untested here; each is built to the provider's documented API.
- YouTube keeps uploads private until the Google Cloud project passes YouTube's API audit.
- The reach report's click-through rate is read as a percentage, as Google documents it; check the first real file.
- Notifications are kept until the person removes them; there is no automatic clean-up.
- Meaning-based (semantic) document search needs an embedding model, a provider decision not yet made (E2).

## Next steps

1. Build with `docker compose up -d --build` or CI, and fix anything the first real compile finds.
2. Try each provider once (`docs/ACCEPTANCE.md`).
3. Then, if wanted: more social platforms (old Increment 9), extra analytics and scene detection (old Increment 10).
