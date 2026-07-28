# DB-Genius - AI Database Master

> Intelligent SQL generation, execution, and database comparison powered by AI agents.

## Overview

DB-Genius is an AI-powered database management tool that transforms natural language into SQL, executes complex multi-step database workflows, and compares database structures for release management.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Frontend (Vue 3)                       │
│              SSE Real-time Streaming UI                   │
└────────────────────────┬────────────────────────────────┘
                         │ SSE / REST
┌────────────────────────▼────────────────────────────────┐
│                    Spring Boot 3.4                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐  │
│  │ AuthCtrl │  │DbConfig  │  │      ChatCtrl        │  │
│  │(Sa-Token)│  │Controller│  │   (POST /chat)       │  │
│  └──────────┘  └──────────┘  └──────────┬───────────┘  │
│                                         │                │
│  ┌──────────────────────────────────────▼────────────┐  │
│  │              Intent Router & Handlers              │  │
│  │  IntentClassifier → IntentHandlerRegistry          │  │
│  │  SimpleChat | SqlQuery | Workflow | Compare        │  │
│  └──────────────────────────────────────┬─────────────┘  │
│                                         │                │
│  ┌──────────────────────────────────────▼────────────┐  │
│  │              AI Agent Framework                     │  │
│  │  BaseAgent → ReActAgent → ToolCallAgent             │  │
│  │       ┌──────────┬────────────┬──────────┐         │  │
│  │       │DbSqlAgent│DbWorkflow  │DbCompare │         │  │
│  │       │          │Agent       │Agent     │         │  │
│  │       └──────────┴────────────┴──────────┘         │  │
│  │  Tools: SqlExecute | ExcelParse | DbCompare | Stop  │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────┐    │
│  │  PostgreSQL   │  │   MyBatis-   │  │  AES-256   │    │
│  │ (system DB)  │  │    Plus      │  │ Encryption  │    │
│  └──────────────┘  └──────────────┘  └────────────┘    │
└──────────────────────────────────────────────────────────┘
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
    ┌──────────┐  ┌──────────┐  ┌──────────┐
    │ MySQL #1 │  │ MySQL #2 │  │ MySQL #N │
    │ (user DB)│  │ (user DB)│  │ (user DB)│
    └──────────┘  └──────────┘  └──────────┘
```

## Features

| Feature | Description |
|---------|-------------|
| **Natural Language SQL** | Describe queries in plain language, AI generates and executes SQL |
| **File-to-Database Import** | Upload Excel files, AI parses and imports data into tables |
| **Database Comparison** | Compare pre and test environments, generate deployment SQL |
| **Real-time SSE Streaming** | Every step is transparently streamed to the frontend |
| **Encrypted Credentials** | AES-256-GCM encryption for database passwords |
| **Auto Documentation** | Automatically generates database schema docs for AI context |

## Tech Stack

| Component | Technology |
|-----------|-----------|
| JDK | 22+ |
| Framework | Spring Boot 3.4.4 + Spring AI 1.0.0 |
| AI Model | DeepSeek (OpenAI-compatible API) |
| ORM | MyBatis-Plus 3.5.9 |
| Auth | Sa-Token 1.39.0 |
| API Docs | OpenAPI 3 YAML (`api-docs.yaml`) |
| System DB | PostgreSQL 16 |
| Message Queue | RabbitMQ 3.13 (async db-config verify & doc generation) |
| Excel | EasyExcel 4.0.3 |
| Encryption | AES-256-GCM |
| Logging | SLF4J + MDC (taskId tracing) |

## Module Structure

```
db-genius/
├── db-genius-common/     # Result wrapper, exceptions, AES encryption, utilities
├── db-genius-model/      # Entity, DTO, VO, enums
├── db-genius-service/    # Business logic, mappers, CRUD operations
├── db-genius-agent/      # AI Agent framework, tools, workflow engine
└── db-genius-web/        # Controllers, configs, application entry point
```

## Agent Workflow

```
User Prompt
    │
    ▼
┌─────────────────┐
│ Intent Router   │  classify → route → clarify
│ (POST /chat)    │
└───────┬─────────┘
        │
┌───────▼───────┐
│ IntentHandler │  SimpleChat | SqlQuery | Workflow | Compare
│   Strategy    │
└───────┬───────┘
        │
┌───────▼───────┐
│  BaseAgent     │  State: IDLE → RUNNING
│  runStream()   │  MDC taskId assigned
└──────┬────────┘
       │
┌──────▼───────┐
│  ReActAgent   │  step() = think() + act()
│  step loop    │  Repeat up to maxSteps
└──────┬───────┘
       │
┌──────▼──────────┐
│  ToolCallAgent   │
│  think():        │──── Call LLM (DeepSeek)
│    LLM decides   │     with tool list
│    which tools   │
│  act():          │──── Execute tools via ToolCallingManager
│    Run tools,    │     Update conversation history
│    update state  │
└──────┬──────────┘
       │
       ▼
  SSE Events streamed to frontend:
  {classifying} → {classified} → {routing} → {thinking} → {step} → {summary} → {done}
```

## Quick Start

### Prerequisites

- JDK 21+
- PostgreSQL 16+
- RabbitMQ 3.13+ (started via `docker compose up -d`)
- DeepSeek API key

### 1. Start PostgreSQL

```bash
docker compose up -d
```

### 2. Configure Environment

```bash
cp .env.example .env
# Edit .env with your actual values
```

### 3. Build & Run

```bash
# Build
./mvnw clean package -DskipTests

# Run with environment variables
export $(cat .env | xargs)
java -jar db-genius-web/target/db-genius-web-1.0.0.jar
```

### 4. Access

- Application: http://localhost:8109/api
- API Docs: see `api-docs.yaml` in project root
- Default admin: `admin` / `admin123`

## API Endpoints

### Authentication
| Method | Path | Description |
|--------|------|-------------|
| POST | `/auth/login` | Login |
| POST | `/auth/logout` | Logout |
| POST | `/auth/user` | Create user (admin) |

### Database Configuration
| Method | Path | Description |
|--------|------|-------------|
| GET | `/db-config` | List configs |
| POST | `/db-config` | Create config |
| PUT | `/db-config/{id}` | Update config |
| DELETE | `/db-config/{id}` | Delete config |
| POST | `/db-config/{id}/test` | Test connection |
| POST | `/db-config/{id}/generate-doc` | Generate docs |
| GET | `/db-config/{id}/doc` | Get docs |

### AI Chat (SSE)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/chat` | Unified chat entry with intent recognition (SSE stream) |
| GET | `/chat/conversations` | List conversations |
| GET | `/chat/conversations/{id}/messages` | Get messages |
| DELETE | `/chat/conversations/{id}` | Delete conversation |

**Request body example:**
```json
{
  "message": "查询用户表总数",
  "dbConfigIds": [1],
  "conversationId": null
}
```

**Intent routing:**
- `simple_chat` — simple Q&A, no DB required
- `sql_query` — single SQL query, requires `dbConfigIds`
- `workflow` — multi-step workflow, requires `dbConfigIds`, optional `fileIds`
- `db_compare` — compare two DBs, requires `preDbConfigId` + `testDbConfigId`

**Clarification flow:**
When confidence is low or prerequisites are missing, the server emits a `clarify` event and closes the stream. The frontend should present the options and call `/chat` again with the selected intent in `confirmedIntent`.

### File Upload
| Method | Path | Description |
|--------|------|-------------|
| POST | `/file/upload` | Upload file |

### Trial Status
| Method | Path | Description |
|--------|------|-------------|
| GET | `/trial/status` | Check if trial mode is enabled |

### Contact Sales
| Method | Path | Description |
|--------|------|-------------|
| POST | `/sales/contact` | Submit sales inquiry (public, no auth) |

## Trial Mode

When `DB_GENIUS_TRIAL_ENABLED=true`, the system runs in public trial mode with the following restrictions:

- A built-in read-only `db-genius` database config is automatically created.
- Database config connection info (`host`, `port`, `dbName`, `username`, `docContent`) is masked with `*` in API responses.
- Creating / updating / deleting / testing / regenerating docs for the built-in config is blocked.
- File upload (`/file/upload`) and user creation (`/auth/user`) are blocked.
- Only read-only SQL (`SELECT`, `SHOW`, `DESC`, `EXPLAIN`) is allowed in `sql_query` intent.
- `workflow` and `db_compare` chat intents are blocked.
- All blocked operations return `403` with code `R.fail(403, "...")`.

## SSE Event Protocol

All SSE events follow this JSON format:

```json
{
  "taskId": "uuid",
  "step": 1,
  "type": "classifying | classified | clarify | routing | thinking | reasoning | content | sql | result | error | file_parsed | step | summary | done",
  "content": "...",
  "timestamp": 1719648000000
}
```

| Type | Description |
|------|-------------|
| `classifying` | System is analyzing the user intent |
| `classified` | Intent classification result JSON |
| `clarify` | Low confidence / missing prerequisite, ask user to confirm |
| `routing` | Routing to the selected handler |
| `thinking` | Agent is analyzing the request |
| `reasoning` | LLM reasoning content (thinking mode); streaming deltas for both simple chat and agent steps |
| `content` | Streaming text token for simple chat |
| `step` | Tool execution result |
| `summary` | Final Markdown summary |
| `error` | Error details |
| `done` | Stream end signal |

## Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL | Yes |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL username | Yes |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL password | Yes |
| `DEEPSEEK_API_KEY` | DeepSeek API key | Yes |
| `DB_GENIUS_ENCRYPT_KEY` | AES-256 encryption key (32 chars) | Yes |
| `ALIYUN_OSS_ENDPOINT` | Aliyun OSS endpoint (file upload storage) | Yes |
| `ALIYUN_OSS_BUCKET` | Aliyun OSS bucket name | Yes |
| `ALIYUN_ACCESS_KEY_ID` | Aliyun RAM AccessKey ID (OSS read/write + OCR) | Yes |
| `ALIYUN_ACCESS_KEY_SECRET` | Aliyun RAM AccessKey secret | Yes |
| `ALIYUN_OSS_DIR_PREFIX` | OSS object key prefix | No (default `uploads/`) |
| `ALIYUN_OCR_ENABLED` | Enable Aliyun OCR for image text recognition | No (default `false`) |
| `ALIYUN_OCR_ENDPOINT` | Aliyun OCR endpoint | No (default `ocr-api.cn-hangzhou.aliyuncs.com`) |
| `SPRING_RABBITMQ_HOST` | RabbitMQ host | No (default `localhost`) |
| `SPRING_RABBITMQ_PORT` | RabbitMQ port | No (default `5672`) |
| `SPRING_RABBITMQ_USERNAME` | RabbitMQ username | No (default `guest`) |
| `SPRING_RABBITMQ_PASSWORD` | RabbitMQ password | No (default `guest`) |

## Design Patterns

| Pattern | Usage |
|---------|-------|
| Template Method | BaseAgent → ReActAgent → ToolCallAgent lifecycle |
| Strategy | IntentHandler implementations for different intents |
| Registry | IntentHandlerRegistry auto-discovers all Handler beans |
| LLM Router | IntentClassifier routes user requests via structured output |
| State Machine | AgentState (IDLE → RUNNING → FINISHED/ERROR) |
| TypeHandler | MyBatis auto-fill for timestamps |
| MDC Tracing | Request-level taskId in all log entries |

## License

MIT
