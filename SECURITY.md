# Security Policy

This document describes how to report security vulnerabilities for PalmClaw.

## Supported Versions

Security fixes are provided on a best-effort basis for:

- Latest release
- `main` branch

Older releases may not receive security updates.

## Reporting a Vulnerability

Please do not disclose vulnerabilities publicly before a fix is available.

Preferred method:

- Use GitHub private vulnerability reporting:
  - https://github.com/ModalityDance/PalmClaw/security/advisories/new

If private reporting is unavailable, contact a maintainer directly via GitHub and include "Security" in the title.

## What to Include in a Report

Please include as much of the following as possible:

- Affected version/commit
- Android version and device model
- Clear reproduction steps
- Expected behavior vs actual behavior
- Proof of concept or logs (redact all secrets)
- Impact assessment (for example: credential leak, privilege escalation, remote execution)

## Project-Specific Security Scope

PalmClaw is a local-first Android app, but it can connect to external providers and channels.

Sensitive material in this project includes:

- LLM provider API keys
- Channel credentials and tokens (Telegram, Discord, Slack, Feishu, WeCom)
- Email IMAP/SMTP credentials
- User local files and media accessed through tools

Please prioritize reporting issues related to:

- Secret exposure or insecure storage/transmission
- Unauthorized channel message routing
- Insecure tool execution paths
- Permission misuse on Android
- Data leakage through logs, diagnostics, or exported data

## Response Process

We aim to:

- Acknowledge reports within 3 business days
- Provide an initial triage update within 7 business days
- Share fix/mitigation guidance as soon as practical

Timelines may vary based on severity and maintainer availability.


## Security Best Practices for Users

- Install builds only from trusted release sources
- Use least-privilege tokens/credentials for channels and providers
- Rotate compromised tokens immediately
- Avoid posting logs that may contain secrets
- Keep your Android OS and app version updated
