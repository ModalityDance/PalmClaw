# Tool Usage Notes

Tool signatures are provided automatically via function calling.
This file documents non-obvious constraints and usage patterns.

## exec - Safety Limits

- Commands have a configurable timeout (default 60s)
- Dangerous commands are blocked (rm -rf, format, dd, shutdown, etc.)
- Output is truncated at 10,000 characters
- `restrictToWorkspace` config can limit file access to the workspace

## cron - Scheduled Reminders

- Please refer to cron skill for usage.
- `cron` now covers both jobs and global cron policy.
- When creating jobs, prefer `session_id` over raw `channel`/`to`.
- If a cron job has `session_id` and `deliver=true`, it runs in that session and mirrors to the session's remote binding when one exists.
- Raw `channel`/`to` is legacy and should only be used when no session binding exists.
- Global cron settings that match the settings page are:
  - `enabled`
  - `min_every_ms`
  - `max_jobs`
- Use `action="set_config"` to update one or more of those persisted settings.
- Use `action="status"` to inspect current cron policy and current job count.

## runtime_get / runtime_set - Agent Runtime Controls

- Use `runtime_get` before changing runtime values when you need the current persisted configuration.
- Use `runtime_set` only for these persisted runtime controls:
  - `max_tool_rounds`
  - `tool_result_max_chars`
  - `memory_consolidation_window`
  - `llm_call_timeout_seconds`
  - `llm_connect_timeout_seconds`
  - `llm_read_timeout_seconds`
  - `default_tool_timeout_seconds`
  - `context_messages`
  - `tool_args_preview_max_chars`
- Do not use `runtime_set` for credentials, provider API keys, channel tokens, allow lists, or consent flags.

## heartbeat_get / heartbeat_set / heartbeat_trigger - Heartbeat Controls

- Use `heartbeat_get` to inspect the persisted heartbeat config and current `HEARTBEAT.md` content.
- Use `heartbeat_set` to update:
  - `enabled`
  - `interval_seconds`
  - `document_content`
- Heartbeat itself is global and does not have a fixed target session anymore.
- If a heartbeat run needs to post into a specific session, use `sessions_list` and `sessions_send` during that run.
- Use `heartbeat_trigger` only when heartbeat is already enabled and you want an immediate run.

## session_status / session_set - Session Binding Controls

- Channel enable/disable is per session now. There are no per-channel-type global switches.
- Use `session_status` to inspect session state, including binding status and route information. Pass `session_id` when you want one exact session.
- Use `session_set` to enable or disable one specific session binding.
- Prefer `session_id` whenever possible.
- Use `session_title` only when it clearly and uniquely identifies one session. If ambiguous, use `session_id`.
- `session_set` only toggles the session binding switch. It does not edit credentials or remote target identifiers.

## memory_get / memory_set / memory_history / memory_search - Memory Model

- `memory_get` and `memory_set` operate on shared global long-term memory (`MEMORY.md`).
- `memory_history` and `memory_search` operate on per-session history files.
- Pass `session_id` when you need a specific session history.
- If `session_id` is omitted, history tools default to the current session.

## mcp_status - MCP Runtime Status

- MCP tools are registered dynamically when MCP servers connect successfully.
- Use `mcp_status` when you need to know:
  - whether MCP is enabled
  - which servers are configured
  - which servers are actually connected
  - whether each server is currently usable
  - how many MCP tools are available
- Do not guess MCP availability from memory. Check `mcp_status` first if MCP access matters to the task.
- A server is only `usable=true` when it is connected and has at least one MCP tool registered for the agent.

## sessions_send - Cross-Session Delivery

- Use `sessions_list` first when you need to discover available sessions or check which session is current/off.
- Treat `session_id` as the canonical unique identifier. Session titles are labels, not stable unique keys.
- Use `sessions_send` to proactively post a message into another local session.
- Prefer `session_id` whenever possible.
- Use `session_title` only when it clearly and uniquely identifies one session. If the title is ambiguous, use `session_id`.
- This tool sends a message into that session. It does not switch the current UI session for the user.
- If the target session is bound to `WeCom`, remote delivery is special: it is reply-context based, not a fully proactive push. It only works after that WeCom chat has recently sent an inbound message.
