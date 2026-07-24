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

- **Two database roles**: PostgreSQL 16 is the *system* DB (users, configs, conversations). User-managed target DBs are MySQL, connected dynamically at runtime — don't conflate them.
- **PostgreSQL schema is `app`, not `public`**: the JDBC URL MUST include `?currentSchema=app`. `schema.sql` (`db-genius-web/src/main/resources/db/schema.sql`) is NOT auto-run on startup — apply it manually to a fresh DB.
- **RabbitMQ required** for async db-config connection verification and doc generation. Start it via `docker compose up -d`.
- **Agent framework** is a Template Method hierarchy: `BaseAgent → ReActAgent → ToolCallAgent`, with concrete `DbSqlAgent`, `DbWorkflowAgent`, `DbCompareAgent`. Intent routing: `IntentClassifier → IntentHandlerRegistry` (Strategy + Registry). Add new intents as `IntentHandler` beans (auto-discovered).
- **SSE streaming**: `/chat` is the unified entry and streams typed JSON events (see README "SSE Event Protocol"). `taskId` is carried through logs via MDC (`%X{taskId}` in the log pattern).

## Module Layout

`common` (Result/exceptions/AES) → `model` (entity/DTO/VO/enums) → `service` (business + MyBatis-Plus mappers) → `agent` (agent framework + tools) → `web` (controllers, config, entry point).

## Conventions & Security

- Auth is Sa-Token; token header is `Authorization`.
- DB passwords are encrypted with AES-256-GCM using `DB_GENIUS_ENCRYPT_KEY` (must be 32 chars). Never log or return raw credentials.
- **Trial mode** (`DB_GENIUS_TRIAL_ENABLED=true`) enforces many restrictions (read-only SQL only, masked config fields, blocked mutations returning 403). When touching db-config, chat, file-upload, or user creation, respect the trial guards — see `TrialGuardTest`.
- MyBatis-Plus: `map-underscore-to-camel-case` is on; enums use `MybatisEnumTypeHandler` (`com.dbgenius.model.enums`).

## Environment

Copy `.env.example` → `.env`. Required: `SPRING_DATASOURCE_*`, `DEEPSEEK_API_KEY`, `DB_GENIUS_ENCRYPT_KEY`. Dev shell here is git-bash on Windows.
