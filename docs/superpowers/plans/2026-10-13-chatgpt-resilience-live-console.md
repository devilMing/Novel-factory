# ChatGPT Resilience And Live Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Continue with ChatGPT calls recover from transient overloads, preserve planning checkpoints across failed jobs, and show live model-call activity in the generation page.

**Architecture:** Keep the existing asynchronous job polling contract. Add a bounded per-job interaction tracker in the domain layer, make the ChatGPT branch of `SpringAiLlmGateway` retry only classified transient failures with exponential backoff and a shared cooldown, and expose tracker snapshots through `JobStatusResponseDTO`. Add a planning checkpoint service that creates the story/run directories after validated input and persists input, volume blueprints, and stage blueprints as soon as each stage completes, allowing the existing resume preload logic to reuse them.

**Tech Stack:** Java 17, Spring Boot 3.4, Spring AI, JUnit 5, Mockito, static HTML/CSS/JavaScript.

---

### Task 1: Per-job model interaction tracker

**Files:**
- Create: `novel_factory-domain/src/main/java/cn/novel/yonren/domain/novel/service/job/LlmInteractionEvent.java`
- Create: `novel_factory-domain/src/main/java/cn/novel/yonren/domain/novel/service/job/LlmInteractionTracker.java`
- Test: `novel_factory-domain/src/test/java/cn/novel/yonren/domain/novel/service/job/LlmInteractionTrackerTest.java`

- [ ] **Step 1: Write the failing bounded-history test**

Create a tracker, append more than its per-job capacity, and assert `snapshot(jobId)` returns only the newest entries in chronological order. Also assert prompt and response previews are length-capped.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn -pl novel_factory-domain -am -Dtest=LlmInteractionTrackerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation failure because the tracker and event types do not exist.

- [ ] **Step 3: Implement the bounded tracker**

Define immutable event fields for call id, event type, scene, model, attempt/max attempts, timestamp, retry time, duration, message, prompt preview, and response preview. Store a synchronized deque per job, cap each job at 80 events, cap previews at 600 characters, and return defensive snapshots. Never accept or store authorization headers or access tokens.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2 and expect all tracker tests to pass.

### Task 2: ChatGPT transient retry and shared cooldown

**Files:**
- Modify: `novel_factory-infrastructure/src/main/java/cn/novel/yonren/infrastructure/gateway/SpringAiLlmGateway.java`
- Modify: `novel_factory-app/src/main/resources/application.yml`
- Modify: `novel_factory-infrastructure/src/test/java/cn/novel/yonren/infrastructure/gateway/SpringAiLlmGatewayTest.java`

- [ ] **Step 1: Write failing retry tests**

Add tests proving `server_is_overloaded` is retried until success, invalid/authentication failures are attempted once, and exhausted transient failures are rethrown after the configured attempt count. Stub the sleep hook so tests do not wait and assert the tracker receives REQUESTED, RETRY_WAIT, SUCCEEDED/FAILED events.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `mvn -pl novel_factory-infrastructure -am -Dtest=SpringAiLlmGatewayTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: retry assertions fail because the ChatGPT branch currently invokes `chatGptGateway.complete` only once.

- [ ] **Step 3: Implement classified exponential backoff**

Wrap only the ChatGPT branch in an attempt loop. Retry `LlmErrorClassifier.Kind.TRANSIENT` and `UPSTREAM_INTERNAL`; fail fast for authentication, quota, invalid request, model-not-found, and content-policy errors. Use configurable attempts and exponential delays with bounded jitter, record a shared `cooldownUntilMs` after transient failure, and make subsequent calls respect that cooldown before sending. Preserve thread interruption and cancellation semantics.

- [ ] **Step 4: Add configuration**

Add `novel.llm.chatgpt-retry.max-attempts`, `initial-backoff-ms`, and `max-backoff-ms` defaults of 4, 5000, and 60000. Keep API-key retry configuration unchanged.

- [ ] **Step 5: Run focused and classifier tests**

Run the command from Step 2 plus `LlmErrorClassifierTest`; expect all tests to pass.

### Task 3: Planning-stage checkpoints

**Files:**
- Create: `novel_factory-domain/src/main/java/cn/novel/yonren/domain/novel/service/armory/checkpoint/PlanningCheckpointService.java`
- Modify: `novel_factory-domain/src/main/java/cn/novel/yonren/domain/novel/service/armory/node/BuildStoryContextNode.java`
- Modify: `novel_factory-domain/src/main/java/cn/novel/yonren/domain/novel/service/armory/node/BuildVolumeBlueprintNode.java`
- Modify: `novel_factory-domain/src/main/java/cn/novel/yonren/domain/novel/service/armory/node/BuildStageBlueprintNode.java`
- Test: `novel_factory-domain/src/test/java/cn/novel/yonren/domain/novel/service/armory/checkpoint/PlanningCheckpointServiceTest.java`

- [ ] **Step 1: Write failing checkpoint tests**

Assert initialization creates/reuses story and run directories, persists story bible/meta/input, and exposes directory names on the job. Assert volume and stage checkpoint methods write their chains and foreshadow schedule without creating another directory.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn -pl novel_factory-domain -am -Dtest=PlanningCheckpointServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation failure because the checkpoint service does not exist.

- [ ] **Step 3: Implement checkpoint service and node hooks**

Initialize the checkpoint after validated story context is built. Persist input before the first LLM call, persist volumes immediately after volume planning, and persist stage blueprints/schedules immediately after stage planning. Treat checkpoint write failure as fail-soft with an explicit warning so a disk-observability failure cannot corrupt an otherwise valid generation.

- [ ] **Step 4: Verify resume reuse**

Extend `StoryGenerateServiceTest` or node tests to preload saved volume/stage chains for a zero-chapter story and assert planning nodes reuse the covering blueprints without invoking their LLM gateways.

- [ ] **Step 5: Run domain tests**

Run: `mvn -pl novel_factory-domain -am test`

Expected: all domain tests pass.

### Task 4: Expose interaction events in job status

**Files:**
- Create: `novel_factory-api/src/main/java/cn/novel/yonren/api/dto/LlmInteractionEventDTO.java`
- Modify: `novel_factory-api/src/main/java/cn/novel/yonren/api/dto/JobStatusResponseDTO.java`
- Modify: `novel_factory-trigger/src/main/java/cn/novel/yonren/trigger/http/JobController.java`
- Modify: `novel_factory-trigger/src/test/java/cn/novel/yonren/trigger/http/JobControllerTest.java`

- [ ] **Step 1: Write a failing controller mapping test**

Seed the tracker with a REQUESTED and RETRY_WAIT event, call the status endpoint method, and assert the response contains both sanitized events in order.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn -pl novel_factory-trigger -am -Dtest=JobControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation/assertion failure because the DTO field and controller mapping are absent.

- [ ] **Step 3: Add DTO and mapping**

Expose `llmInteractions` as a list in job status. Return an empty list when no event exists and keep the existing endpoint and polling interval unchanged.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2 and expect all controller tests to pass.

### Task 5: Live model communication console

**Files:**
- Modify: `novel_factory-app/src/main/resources/static/index.html`
- Modify: `novel_factory-app/src/main/resources/static/style.css`
- Modify: `novel_factory-app/src/main/resources/static/app.js`

- [ ] **Step 1: Add the console markup**

Insert a `MODEL LINK` card between job status/plan review and the observatory. Include a live-state indicator, current-call summary, and bounded timeline container with an informative empty state.

- [ ] **Step 2: Implement rendering**

Render scene, model, attempt, event label, local time, elapsed duration, retry countdown, and safe prompt/response excerpts. Use `<details>` for excerpts, escape every server-provided string, preserve the final timeline after terminal job states, and reset it for a new job.

- [ ] **Step 3: Style to match the existing industrial console**

Use the existing graphite/cyan variables, a left-side signal rail, animated cyan pulse only while a call is active, amber countdown for retry wait, green completion, and red terminal failure. Add a narrow-screen layout without horizontal overflow and light-theme overrides.

- [ ] **Step 4: Verify in browser**

Start the app with the project-local Java configuration, submit a short job, and verify REQUESTED appears before completion, elapsed time advances, retry wait is visible for a simulated transient failure, prompt/response excerpts are collapsible, and no token/API key appears.

### Task 6: Full verification

**Files:**
- Modify only files needed to fix regressions found by verification.

- [ ] **Step 1: Run the full test suite**

Run: `mvn test`

Expected: BUILD SUCCESS.

- [ ] **Step 2: Restart and smoke-test the application**

Use the repository-local Java 17 launch command with the existing proxy JVM options. Verify `/index.html`, `/api/auth/chatgpt/models`, job submission/status polling, live interaction rendering, retry/cooldown behavior, and checkpoint files under the new story directory.

- [ ] **Step 3: Review the diff and working tree**

Run: `git diff --check`, `git status --short`, and inspect the final diff for credentials, generated stories, logs, or unrelated changes. None may be committed.
