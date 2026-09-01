# AGENTS.md

This file provides guidance to the AI agent when working with code in this repository.

## Overview

Spring Boot 3.4 multi-module Maven project: an AI agent that turns natural language into SQL, runs multi-step DB workflows, and compares databases. Uses Spring AI + DeepSeek (OpenAI-compatible API).

## Build & Test

- Build: `./mvnw clean package -DskipTests` (use the wrapper; Java 21 is required — pom targets 21 despite README mentioning 22+).
- Run: env vars must be loaded first, e.g. `export $(cat .env | xargs)` then `java -jar db-genius-web/target/db-genius-web-1.0.0.jar`.
- Test: `./mvnw test`. Tests exist but are sparse (a few per module); don't assume broad coverage.
- App runs at `http://localhost:8109/api` (note the `/api` context-path).

## Architecture Gotchas

- **Two database roles**: PostgreSQL 16 is the *system* DB (users, configs, conversations). User-managed target DBs are connected dynamically at runtime — don't conflate them. Supported target types: MySQL / PostgreSQL / MongoDB / MariaDB / TiDB / Doris / StarRocks / OceanBase / Oracle / SQL Server (see README "Supported Databases"; extension point is `DbType` + `DatabaseAdapter`, MySQL-protocol types reuse the MySQL driver). (SQLite was dropped: it has no remote access, so it makes no sense for a SaaS product.)
- **PostgreSQL schema is `app`, not `public`**: the JDBC URL MUST include `?currentSchema=app`. `schema.sql` (`db-genius-web/src/main/resources/db/schema.sql`) is NOT auto-run on startup — apply it manually to a fresh DB.
- **RabbitMQ required** for async db-config connection verification and doc generation. Start it via `docker compose up -d`.
- **Agent framework** is a Template Method hierarchy: `BaseAgent → ReActAgent → ToolCallAgent`, with concrete `DbSqlAgent`, `DbWorkflowAgent`, `DbCompareAgent`. Intent routing: `IntentClassifier → IntentHandlerRegistry` (Strategy + Registry). Add new intents as `IntentHandler` beans (auto-discovered).
- **Chat concurrency**: intent routing and agent step loops run on the `chatTaskExecutor` bean (`ChatExecutorConfig`) — never bare `CompletableFuture.runAsync` on the ForkJoinPool commonPool, which minute-long agent runs would starve. `ToolCallAgent.think()` streams via `ReasoningChatModel.streamAggregated` (reasoning deltas pushed as SSE `reasoning` events, then aggregated for tool-call handling); only `call()` keeps DeepSeek's `reasoning_content` pass-back for tool-call turns.
- **SSE streaming**: `/chat` is the unified entry and streams typed JSON events (see README "SSE Event Protocol"). `taskId` is carried through logs via MDC (`%X{taskId}` in the log pattern).
- **Client disconnect is not an error**: all SSE writes go through `SseChannel` (`com.dbgenius.agent.stream`) — the single event exit shared by `IntentRouter` → `IntentHandler` → `BaseAgent`. It swallows `AsyncRequestNotUsableException`/`IllegalStateException`, sets `isAborted()`, and logs one stack-free INFO. Never `log.error` a disconnect and never call `emitter.send` directly. On abort the agent stops the ReAct loop, cancels the in-flight LLM stream (`ReasoningChatModel.setCancelSignal` → partial aggregate with `finishReason = CLIENT_ABORTED`), skips the summary, still persists token usage, and saves the partial answer as `message.type = 'aborted'` via `AgentMessageSink#onAborted`. `aborted` is excluded from `getRecentMessages`, so half-finished answers never re-enter LLM context.
- **MongoAutoConfiguration is excluded** on `DbGeniusApplication`: `mongodb-driver-sync` is only for user target DBs (`MongoDbAdapter` builds its own clients), but the auto-config would otherwise create a `MongoClient` bean pointed at `localhost:27017` and spam connection-refused.
- **Observability (OTel + Micrometer)**: export-side deps (actuator / micrometer-tracing-bridge-otel / opentelemetry-exporter-otlp / micrometer-registry-prometheus) live in `db-genius-web` only; the agent module writes `Observation`/`MeterRegistry` code against micrometer APIs that arrive compile-scoped via `spring-ai-commons`. Three instrumentation breakpoints must stay wired: `ChatModelFactory` passes the container `ObservationRegistry` into `OpenAiChatModel` (its default is NOOP); `ToolCallAgent.setToolCallingManager` replaces the bare constructor-built manager with the Spring bean (else no `spring.ai.tool` spans); `ReasoningChatModel` hand-writes `gen_ai.client.operation` observations + a `dbgenius.llm.ttft` timer because `call()`/`streamAggregated()` bypass `OpenAiChatModel` (reasoning_content pass-back). `chatTaskExecutor` carries `ContextPropagatingTaskDecorator` — removing it orphans every span after the first thread hop. Business metrics live in `AgentMetrics` (`com.dbgenius.agent.metrics`, injected per-run via `BaseAgent.setAgentMetrics`); metric tags must stay enum-like (intent / tool / dbType / termination reason — never userId/taskId/SQL text). Client abort is a `gen_ai.response.finish_reasons` attribute, never `obs.error()`. Sa-Token excludes `/actuator/**` so Prometheus can scrape (`/api/actuator/prometheus`); OTLP endpoint defaults to `http://localhost:4318/v1/traces` (compose overrides it to the `jaeger` service, UI on 16686).

## Module Layout

`common` (Result/exceptions/AES) → `model` (entity/DTO/VO/enums) → `service` (business + MyBatis-Plus mappers) → `agent` (agent framework + tools) → `web` (controllers, config, entry point).

## Conventions & Security

- Auth is Sa-Token; token header is `Authorization`.
- DB passwords are encrypted with AES-256-GCM using `DB_GENIUS_ENCRYPT_KEY` (must be 32 chars). Never log or return raw credentials.
- **Trial mode** (`DB_GENIUS_TRIAL_ENABLED=true`) enforces many restrictions (read-only SQL only, masked config fields, blocked mutations returning 403). When touching db-config, chat, file-upload, or user creation, respect the trial guards — see `TrialGuardTest`. Method-level "deny in trial mode" uses the `@TrialDeny(errorCode)` annotation (`com.dbgenius.trial`, enforced by `TrialGuardAspect` via Spring AOP — works only on external calls through the Spring proxy, not self-invocation); conditional checks (e.g. `denyIfTrialBuiltin`, `isTrialMode`) still call `TrialGuard` directly.

## i18n Conventions

- **User-facing errors MUST go through `ErrorCode`** (`com.dbgenius.common.exception.ErrorCode`): throw `new BusinessException(ErrorCode, args...)`; `GlobalExceptionHandler` / `SaTokenExceptionHandler` localize via `MessageService` + `LocaleContextHolder`. LLM-facing tool errors (`SqlExecuteTool` failure text, MongoDB command validation, `SqlSafetyGuard` red lines) stay literal — they are caught and fed back to the model, never rendered to users.
- **Message bundles** live in `db-genius-web/src/main/resources/i18n/messages*.properties` (base = en, plus zh_CN/zh_TW/es/fr/ja/ms). Locale comes from the `Accept-Language` header via `LocaleConfig` (AcceptHeaderLocaleResolver, default en). MessageFormat applies at all times — escape apostrophes as `''`.
- **Async chains (SSE chat)**: `LocaleContextHolder` dies on `chatTaskExecutor` threads. `ChatController` captures the locale in the sync segment and passes it explicitly (`IntentRouter.route(..., locale)` → `ChatContext.locale` → `IntentHandler.handle(..., locale)` → agents/compressors), same style as `userId`. Never read `LocaleContextHolder` in async code.
- **AI prompts MUST go through `PromptTemplateLoader`** (`com.dbgenius.agent.prompt`): templates in `db-genius-agent/src/main/resources/prompts/{name}_{locale}.md`, only zh_CN + en variants (others fall back to en); `{placeholder}` substitution via `render`, system+user sections split by a `===USER===` line. Every system prompt gets `withOutputLanguage(...)` appended ("You MUST respond in {language}.") — do not reintroduce soft "same language as the user" clauses.
- Client IP: `ClientIpUtils.getClientIp(request)` (X-Forwarded-For first hop → X-Real-IP → remoteAddr). The frontend never sends an IP.

- MyBatis-Plus: `map-underscore-to-camel-case` is on; enums use `MybatisEnumTypeHandler` (`com.dbgenius.model.enums`).
- **File storage is Aliyun OSS** (`db-genius.oss.*` env vars; `uploaded_file` table stores `oss_key`, not a local path). Agent file tools are `FileReadTool` (docs) and `ImageReadTool` (OCR via `db-genius.ocr.*`) — the old `ExcelParseTool` is gone. Tools take only a `fileId`; `userId`/`allowedFileIds` travel via Spring AI `ToolContext` (`FileAccessGuard`), never in LLM-visible args.

## Environment

Copy `.env.example` → `.env`. Required: `SPRING_DATASOURCE_*`, `DEEPSEEK_API_KEY`, `DB_GENIUS_ENCRYPT_KEY`. Dev shell here is git-bash on Windows.
