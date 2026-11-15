# MCP Setup

RepoLens exposes four MCP tools over **streamable HTTP** (`/mcp`) and **stdio**:

| Tool | Inputs | Returns |
|---|---|---|
| `list_repositories` | none | repositories with id, slug, status |
| `search_docs` | `repo` (`owner/name`), `query`, `limit` (1..12) | ranked snippets, URLs, fused score, ranks |
| `ask_repo` | `repo`, `question` | grounded answer with citations, `refused`, `cached` |
| `get_document` | `repo`, `path` (path or `issue-N`) | full document text, capped at 20,000 chars |

Retrieved text is wrapped in `<<<UNTRUSTED_DOCUMENT>>> ... <<<END_UNTRUSTED_DOCUMENT>>>` so an
assistant can treat it as data, never instructions.

## Prerequisites

- Postgres + pgvector running (`docker compose up -d`).
- At least one repository ingested (`POST /api/v1/repos`).
- An API key for HTTP transports: set `REPOLENS_API_KEY` in `.env` (the hash is stored on startup).
  Stdio mode is a local process and does not use the HTTP key filter.

## Claude Desktop (stdio, recommended for local)

Claude Desktop launches the server as a child process. Logs must not go to stdout, so the
`logback-stdio.xml` config sends them to stderr.

`claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "repolens": {
      "command": "/ABSOLUTE/PATH/TO/java",
      "args": [
        "-jar", "/ABSOLUTE/PATH/TO/repolens-0.0.1-SNAPSHOT.jar",
        "--spring.main.web-application-type=none",
        "--spring.ai.mcp.server.stdio=true",
        "--logging.config=classpath:logback-stdio.xml"
      ],
      "env": {
        "DB_URL": "jdbc:postgresql://localhost:55432/repolens",
        "DB_USERNAME": "repolens",
        "DB_PASSWORD": "repolens",
        "LLM_PROVIDER": "fake"
      }
    }
  }
}
```

Notes:

- Use absolute paths; Claude Desktop does not expand `~`.
- Set `LLM_PROVIDER`/`LLM_*` in `env` for real answers (any OpenAI-compatible endpoint).
- The first start loads the ONNX embedding model (cached under `${EMBEDDING_CACHE_DIR:-$TMPDIR/repolens-onnx}`).
- Restart Claude Desktop after editing the file.

## Generic MCP client (streamable HTTP)

Endpoint: `POST/GET http://localhost:8081/mcp` with header `X-API-Key: <REPOLENS_API_KEY>`.
The transport is stateful streamable HTTP (protocol `2025-06-18` is negotiated).

Minimal raw JSON-RPC sequence (session handling is normally done by the client library):

```bash
curl -s -X POST localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "X-API-Key: $REPOLENS_API_KEY" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
```

Java SDK example:

```java
HttpClientStreamableHttpTransport transport =
    HttpClientStreamableHttpTransport.builder("http://localhost:8081")
        .endpoint("/mcp")
        .requestBuilder(HttpRequest.newBuilder().header("X-API-Key", apiKey))
        .build();

McpSyncClient client = McpClient.sync(transport).build();
client.initialize();
var tools = client.listTools();                    // 4 tools
var result = client.callTool(new CallToolRequest(
    "ask_repo", Map.of("repo", "octocat/Hello-World", "question", "What is this repo?")));
client.close();
```

The HTTP transport is unauthenticated by itself; RepoLens' `ApiKeyFilter` protects `/mcp` and the
REST API. `/actuator/health` and `/actuator/prometheus` are exempt (protect Prometheus at the
network layer).

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `REPOLENS_API_KEY` | empty | bootstrap HTTP API key (hash stored in `api_key`) |
| `REPOLENS_API_KEY_REQUIRED` | `true` | require `X-API-Key` on `/api/*` and `/mcp` |
| `MCP_STDIO` | `false` | enable stdio transport (`spring.ai.mcp.server.stdio`) |
| `MCP_PROTOCOL` | `streamable` | HTTP protocol: `streamable`, `sse`, `stateless` |

Disabling keys (`REPOLENS_API_KEY_REQUIRED=false`) is only appropriate for local experiments.
