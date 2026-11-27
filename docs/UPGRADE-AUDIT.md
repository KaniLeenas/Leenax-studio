# Leenax Studio master upgrade audit

Audit date: 27 November 2026

## Verdict

**The master upgrade is not complete.** The existing app already contains substantial multi-user, education, creator, media, and publishing functionality. Recent code changes remove the account cap, add paginated account directories, and add configurable public registration with activation email. This change adds agent configuration/execution, a visual workflow editor, and a checkpointed LangGraph runner. Tool invocation, controlled memory APIs, full educational video automation, database migration validation, and deployment validation remain outstanding.

Status labels below distinguish source code from runtime validation. “Built” means there is an implementation in the repository; it does not imply a live provider call or production deployment was exercised.

## Architecture discovered

- **Frontend:** React 19, TypeScript 7, Vite 8, TanStack Query, Tailwind CSS 4.
- **Primary backend:** Java 21, Spring Boot 3.5.7, modular monolith.
- **Database:** PostgreSQL with Flyway migrations; project row-level security and database triggers enforce isolation and invariants.
- **Authentication:** Spring Security, BCrypt, Redis-backed server sessions, HttpOnly/SameSite cookies, CSRF protection, MFA, login throttling, and single-use activation/reset links.
- **Existing roles:** global `ADMIN`/`MEMBER`; project `OWNER`/`EDITOR`/`REVIEWER`/`VIEWER`. The requested global Content Manager role and custom roles are not implemented.
- **Existing AI providers:** Java backend stores encrypted provider credentials and owns provider adapters, usage recording, and cost tracking.
- **Document processing:** a Python FastAPI document service for PDF/Office extraction and OCR; document state is managed by the primary backend/database.
- **Media:** S3-compatible object storage (SeaweedFS in local Compose; AWS S3 configurable), with media kept outside PostgreSQL.
- **Video:** Remotion rendering service; FFmpeg is used by backend media processing and render validation. The primary editor is not a LangGraph-driven educational video pipeline.
- **Deployment:** Docker Compose includes frontend/nginx, backend, PostgreSQL, Redis, SeaweedFS, document service, image tools, renderer, and backups. The document service now runs the LangGraph module in-process and uses the existing PostgreSQL service for checkpoints; no extra container is added.
- **Architecture report:** [ARCHITECTURE.md](ARCHITECTURE.md).

## Requirement status

| Master prompt area | Status | Evidence / gap |
|---|---|---|
| Preserve the existing application and architecture | **Built in code; deployment unverified** | Changes extend the current React/Spring/PostgreSQL app. Docker Desktop’s Linux engine was unavailable, so the complete stack was not started. |
| Remove arbitrary five-user cap | **Implemented; migration unverified** | V20 removes the database cap and obsolete setting, retains the last-active-admin guard, and adds directory indexes. It has not been applied to a running PostgreSQL instance here. |
| Registration modes and verification | **Implemented in code; runtime unverified** | V21 defaults to invitation-only; admin settings support public mode only when outbound email is configured. Public signup creates an inactive account and sends an activation link. SMTP and migration behavior have not been exercised in a running stack. |
| User directory and account controls | **Partial** | Paginated, searchable directory with status filter, activation links, deactivation/reactivation and login history. The full requested dashboard/reporting and management coverage is not confirmed complete. |
| Requested role set and custom RBAC | **Partial** | Existing global and project roles provide server-side enforcement. Global Content Manager, requested global Editor/Viewer semantics, owner-defined custom roles, and a permission editor are absent. |
| Multi-user projects and isolation | **Built in existing code; broad retest pending** | Project membership roles and PostgreSQL row-level security exist. Project-specific AI policies and a complete project activity/history surface are not confirmed. |
| Document processing and analysis | **Partial to built** | Existing document upload, parsing/OCR, course documents, syllabus/topic analysis, notes and question-bank workflows are present. A unified chapter/topic/source-citation pipeline with all requested extraction guarantees is not established. |
| Scripts, MCQs, topic analysis and educational materials | **Built as separate workflows** | Existing script, notes, MCQ, quiz, syllabus, topic-analysis and approval features work through separate modules. They are not coordinated as one end-to-end agent workflow. |
| Agent templates, custom configuration and execution monitoring | **Built in code; live runtime unverified** | V22 adds agent/template/tool/execution/version tables. Spring APIs reuse existing encrypted AI accounts and provider adapters; frontend has dashboard, builder, test run, version restore, logs, retry and cancellation. Usage tokens/costs are not yet populated by the agent runner. |
| LangGraph orchestration and visual workflow builder | **Partial; graph unit tests pass** | FastAPI runner validates DAGs, supports sequential/parallel branches, JSON conditions, mappings, PostgreSQL checkpoints and approval interrupts. Frontend builder edits agent nodes and edges. End-to-end execution requires the V22 migration and a configured shared service token. |
| Workflow interrupts, approvals, pause/resume and recovery | **Partial** | Approval and reject resume paths are implemented and tested. Pause/resume is limited to approval checkpoints. Retry creates a new run rather than recovering failed nodes from their last checkpoint. |
| Tool invocation and educational video automation | **Partial / not integrated end to end** | V22 seeds 32 agent templates and an allowlisted tool catalog, but the model execution path currently returns structured JSON and does not invoke those tools. Existing document, image, voice, subtitle, render and publish modules are not wired into one autonomous production graph. Publishing remains explicit/manual. |
| Image, voice, subtitles and video production | **Partial to built** | Existing provider-backed image generation, scene/storyboard tools, Tamil/English voice, subtitles, editor, Remotion render queue and FFmpeg validation exist. They are separate creator tools, not an automated educational pipeline. Per-image approval/history and complete requested production controls need a criterion-by-criterion pass. |
| Video QA | **Partial** | Render validation and video checks exist. The complete requested end-to-end synchronization, subtitle, asset, duration, and output-integrity QA workflow is not demonstrated as an automated gate. |
| YouTube SEO and publishing approval | **Built for existing flows; end-to-end agent integration missing** | SEO reports, YouTube publishing and authorization/approval records exist. No LangGraph metadata/publishing agent connects them; publishing integration still depends on configured provider accounts and live credentials. |
| Provider management and cost safety | **Ollama and FreeLLMAPI added for agents; direct paid agent providers blocked by default** | Agent model selection/execution allows local Ollama and the bundled FreeLLMAPI router unless the owner explicitly enables `AGENT_ALLOW_PAID_MODELS`. FreeLLMAPI can be configured with free-tier or custom/paid endpoints, so its fallback chain must be restricted by the operator to keep those runs free. Other studio features retain their existing provider choices. There is no complete owner budget gate for non-agent features. |
| Storage, Docker, backups and AWS | **Partial / not fully verified** | S3-compatible storage and backup services exist. LangGraph deployment is absent. No AWS instance/account inspection was possible from this workspace, so capacity, Free Tier, storage headroom and production configuration remain unknown. |
| Acceptance tests and real deployment | **Partial** | Backend tests, frontend suite and LangGraph graph tests pass. SQL migrations, complete Compose startup, live provider workflows and production multi-user acceptance have not been validated in this run. |

## Acceptance criteria summary

| # | Criterion | Result |
|---:|---|---|
| 1–3 | Existing app, owner and records preserved | **Code compatibility designed for it; live owner/database preservation not verified.** No migration has been run here. |
| 4 | Five-user restriction removed | **Implemented in V20; SQL execution pending.** |
| 5 | Configurable verified registration | **Implemented in code; requires SMTP and V21 migration/runtime verification.** |
| 6 | No arbitrary account count cap | **Implemented in code; practical capacity remains infrastructure-limited.** |
| 7 | Requested backend RBAC | **Partial; role model does not match the requested complete set/custom-role system.** |
| 8 | Unauthorized project access blocked | **Existing project authorization/RLS built; full new multi-user regression run pending.** |
| 9 | LangGraph workflows | **Graph runner and editor implemented; database/deployment validation pending.** |
| 10–14 | Analysis, scripts/MCQs, storyboard/images, voice, subtitles | **Existing standalone features; unified workflow and full acceptance verification are partial.** |
| 15–16 | Render and automated video QA | **Existing editor/rendering/validation; full requested FFmpeg-based educational pipeline and QA gates partial.** |
| 17–18 | YouTube SEO and approval-gated publishing | **Existing features built; no agent workflow integration.** |
| 19 | Docker deployment | **Compose configuration exists; full stack not run because Docker engine was unavailable.** |
| 20 | Security and multi-user tests | **Unit and focused UI checks pass; SQL and full-stack security/load validation outstanding.** |

## Validation performed

- `mvn -B test`: **322 tests passed, 0 failures, 0 errors**, including the Ollama adapter and free-only agent policy changes. After the final cloud-tag filter edit, `mvn -B -Dtest=LumaAndTextAdaptersTest test` passed all 6 targeted tests.
- `python -m pytest -q test_agent_graph.py`: **5 tests passed**, including conditional routing, parallel branches, and approval pause/resume/reject.
- Focused registration/account UI tests: **11 tests passed**.
- `npm run build`: **passed**; Vite reports a large JavaScript chunk warning.
- `git diff --check`: **passed**.
- `docker info`: failed to connect because the Docker Desktop Linux engine pipe is unavailable. Consequently, PostgreSQL migrations V20/V21, SQL suites and full Compose startup were not run.
- `npm test -- --reporter=dot --maxWorkers=1`: **206 tests passed across 34 files**. The single-worker run avoids a flaky analytics navigation test seen during parallel runs.
- `docker compose config --quiet`: **passed** with temporary validation-only environment values. This checks Compose interpolation and syntax, not service startup. Use the workspace's actual secrets for deployment; do not copy example credentials into production.
- Local Ollama check: `qwen3:1.7b` is installed on this workstation; the local model endpoint generated `{"ok":true}` with `reasoning_effort=none`. Leenax now has an Ollama provider adapter, but backend-to-Ollama account creation has not been exercised because the full app/database stack could not be started here.
- Live site read-only check: the public homepage and `/manage/agents` route return HTTP 200; `/actuator/health` reports **UP**. The served JavaScript bundle differs from this workspace build and does not contain the AI Agents route, so the new agent features are **not deployed** to that site. No sign-in or provider actions were performed.

## Remaining work before claiming the upgrade complete

1. Implement runtime tool dispatch for the registered integrations, with per-tool authorization, limits, usage accounting and secret-safe logs.
2. Connect the educational workflow end to end: source analysis and references → topic/research/fact check → script/MCQs → storyboard → image/voice/subtitle production → render → QA → SEO → explicit publishing approval.
3. Add scoped memory inspection/deletion APIs and controls; currently the migration only creates the isolated memory table.
4. Complete the requested global role/permission model and owner-controlled paid-use budgets.
5. Run V20/V21/V22 and the SQL integration suite against PostgreSQL; start the full Compose stack and run multi-user/security acceptance tests.
6. Validate live provider paths with configured accounts, verify email delivery, and inspect the actual AWS host/account before making infrastructure claims.

No claim is made here that every requested feature has been built or that the deployed studio has been changed.
