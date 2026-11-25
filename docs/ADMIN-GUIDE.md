# Administrator guide

For the person who installs and runs Leenax Studio. Installation itself is in the README ("Run it on your PC" and "Put it on the internet").

## First day

1. Start the stack and create your account with the setup token (README, "Run it on your PC").
2. Remove `SETUP_TOKEN` from `.env` and restart. Setup is then switched off.
3. Turn on two-step sign-in: Settings → Security.
4. Back up `.env`, above all `LEENAX_MASTER_KEY`. Without it, saved keys and sign-ins in a backup can't be read.

## People

- **Users** (Manage → Users): invite up to five people in total, counting yourself and anyone invited but not yet active. Each invitation is a one-time link you pass on yourself; the studio sends no email.
- **Roles in the studio:** Administrator (everything, including keys, accounts and system health) or Member. Members can be allowed or not to create projects.
- **Roles in a project:** Owner, Editor, Reviewer, Viewer. The project's owner adds people. Reviewers and owners approve; the author of a version can never approve it.
- **Leaving:** deactivate a person instead of deleting them, so their history stays. Their seat becomes free. Deactivated people get no notifications.
- The database refuses a sixth seat and refuses to remove the last administrator, whatever the path.

## AI accounts and costs

- Manage → AI accounts: add a provider key (OpenAI, Anthropic, Google Gemini, ElevenLabs, Google Cloud Text-to-Speech and the video providers). Keys are encrypted and are never shown again; the page checks a key by calling the provider once.
- Nothing paid is used until you add and enable an account. Costs the provider reports are recorded under Manage → Credits and usage.
- AI jobs run as a background user that can't approve anything. Every AI result is a draft until a person approves it.

## YouTube and other channels

Set up the developer apps once (README, "Social publishing set-up"):

- **Google Cloud:** YouTube Data API v3, YouTube Analytics API and YouTube Reporting API enabled; the scopes `youtube.upload`, `youtube.readonly`, `youtube.force-ssl` and `yt-analytics.readonly` on the consent screen; the client ID and secret in `.env`.
- **Connect channels** under Publish → Social accounts. A channel connected before E9 must be reconnected to allow playlists, captions and analytics; the Publish to YouTube page says so.
- **Quota:** a Google Cloud project gets 10,000 YouTube API units a day by default. An upload uses 1,600, captions 400. SEO research has its own daily search budget (`SEO_DAILY_SEARCHES`).
- **Audit:** until your Google Cloud project passes YouTube's API audit, YouTube keeps every upload private. Apply from the YouTube Data API's quota page if videos should go public.
- **Reach reports** (impressions, click-through rate) are turned on per channel by a project owner or you, on the YouTube analytics page. They import daily at `YOUTUBE_REACH_IMPORT_CRON` (14:20 UTC).

## Every day

- **Dashboard:** what's waiting for you, running jobs and recent decisions. The bell shows notifications: review requests, decisions on your work, finished or failed jobs, renders and posts.
- **Settings → System:** service health, the last backup, storage use.
- **Security → Recent activity:** sign-ins, connections and posts.

## Backups and restore

- The `backup` service writes `backups/<date-time>/` every day at `BACKUP_TIME` and keeps `BACKUP_KEEP` copies. Each dump is read back to check it.
- **Copy `backups/` somewhere else** and test a restore once: `scripts\restore.ps1 .\backups\<folder>` (this replaces everything).

## Updating

1. Back up (`scripts\backup.ps1`).
2. Unzip the new files over the folder, keeping `.env`.
3. `docker compose up -d --build`. Database changes (Flyway migrations V1 onwards) apply on start; the backend log shows each one.
4. Open Settings → System and check every service is healthy.

## Checks before a release (CI)

`.github/workflows/ci.yml` runs on every push if the folder is a GitHub repository:
- backend build and unit tests (Maven);
- every migration applied to an empty PostgreSQL, then all SQL suites (`backend/src/test/sql/run_all.sh`), including the whole-workflow test;
- frontend type check, tests, build and `npm audit`;
- renderer and document service tests;
- a scan for keys or tokens in the repository.

To run the SQL suites yourself, point them at a **throwaway** database with the migrations applied. They create and delete users and posts:

```
set LEENAX_TEST_DSN=host=localhost port=5432 user=leenax password=… dbname=leenax_test
backend\src\test\sql\run_all.sh
```

## When something goes wrong

| What you see | What to do |
|---|---|
| "needs to be reconnected" on a channel | Publish → Social accounts → Reconnect. Google sign-ins in testing mode expire after 7 days. |
| "YouTube API daily quota is used up" | Nothing; the upload is tried again later. Raise the quota in Google Cloud if it happens often. |
| "Not authorised" on a post | The video or its settings changed after they were authorised, or it was scheduled before E9. Open the post and use Try again to authorise it. |
| A render failed its checks | The reasons are listed on the video page (length, size, sound). Render again. |
| An AI job failed | The reason is in the notification and on the page that started it, often a missing or empty AI account. |
| The document service or renderer is unreachable | `docker compose ps`, then `docker compose logs docservice` or `renderer`. |
