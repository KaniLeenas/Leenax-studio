# Acceptance criteria and evidence

The specification's acceptance list (section 21), each with where it is built and how it was checked. "Checked" means it ran here: unit tests, the SQL suites on a real PostgreSQL 16 (`backend/src/test/sql`, all run on an empty database with the 18 migrations), frontend tests, or a real render. **"Not tried live"** means it is built to the provider's documented API and tested against a stand-in, because no credentials were used. Your first real use is that test.

The backend has also never run inside a real Spring server here, because Maven Central can't be reached from this environment. The code is type-checked against stand-ins, so `docker compose up --build` (or the CI workflow) is the first full compile. See "Before you rely on it" below.

| Criterion | Built in | Checked | Status |
|---|---|---|---|
| Five or fewer authorised users can access the system | E1 | V9 suite: seat limit in the database | Met |
| A sixth active account cannot be created | E1 | V9 suite; E10 workflow test | Met |
| Public registration is disabled | E1 | One-time setup token, then invitations only | Met |
| Users can securely log in | Phase 1, E1 | BCrypt, lockout, two-step sign-in, HttpOnly cookie, CSRF; unit tests | Met |
| Project permissions work correctly | E1 | V9 suite: each role in a project; every later suite as owner, editor, reviewer, viewer and outsider | Met |
| Users can create multiple subject projects | E1 | V9 and E-suites create several | Met |
| Documents can be uploaded and managed | E2 | E2 suite with real document-service output; docservice tests | Met (the docservice Docker image not built here) |
| AI can analyse syllabus information | E3 | Prompt and reading of answers tested; page-checked citations | Not tried live (no AI key used) |
| Users can approve topics | E3 | E3 suite; E10 workflow | Met |
| AI can generate detailed notes | E4 | Prompt, checks and storage tested | Not tried live |
| AI can generate MCQs | E4 | Four-options-one-key rule, repeats, storage tested | Not tried live |
| Academic QA can identify and record errors | E5 | E5 suite: issue log, corrections, rechecks, blocking rule | Met for recording and blocking; the AI check not tried live |
| Reports can be generated and downloaded | E4 to E7 | Exports rendered and checked by the docservice tests | Met |
| Approval rules are enforced | E3 to E11 | Database triggers LX010 to LX024, each tested; E10 runs the whole chain | Met |
| Topics are analysed and the analysis is approved before questions and the video follow it | E11 | E11 suite (29), TopicAnalysisLogicTest, TopicAnalysisPages tests | Met (AI not called live) |
| SEO research can use available data sources | E6 | YouTube Data API stand-in, Trends CSV import, quota ledger | Not tried live against YouTube |
| Public competitor videos can be analysed | E6 | Public numbers only, hidden counts kept unknown | Not tried live |
| Titles can be generated and evaluated | E6 | Nine criteria scored and tested | Met for scoring; AI titles not tried live |
| Presentations can be created and edited | E7 | Fit measurement, exports checked character by character | Met |
| Voice narration can be generated through configured providers | E8 | ElevenLabs, Gemini and Google Cloud TTS adapters against stand-ins | Not tried live |
| Videos can be rendered and validated | E8 | Real Remotion renders checked with ffprobe and ffmpeg | Met |
| Users can approve final videos | E8 | LX019: only with a passing render of exactly that content | Met |
| YouTube publishing requires explicit authorisation | E9 | LX020 to LX022, 59 checks; E10 workflow | Met (YouTube upload itself not tried live) |
| Analytics can retrieve authorised channel information | E9 | Analytics and Reporting API stand-ins | Not tried live |
| Private project data remains isolated | E1 to E10 | Row-level security; outsiders see nothing in every suite, including the dashboard and notifications | Met |
| All processing progress is saved | Phase 2 onwards | Jobs, renders and uploads are database rows with their step and state; uploads resume their session | Met |
| Failed jobs can be safely retried | Phase 2 onwards | Retries tested per job type; YouTube extras never re-upload; "Try again" on posts | Met |
| Backups and recovery procedures are available | Phase 7 | Daily dump read back; restore scripts | Met (test a restore once on your PC) |

## Before you rely on it

1. **Build it for real.** Run `docker compose up -d --build`, or push to GitHub so `.github/workflows/ci.yml` runs Maven. If a page is empty after sign-in, look at the backend log first: the connection wrapper fails closed.
2. **Try each provider once** with a small job: a syllabus report, notes for one topic, a QA check, SEO research for one keyword, one narration, then one unlisted YouTube upload and an analytics refresh.
3. **Restore a backup once** into a spare folder.
