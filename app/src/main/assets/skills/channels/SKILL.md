---
name: channels
description: Configure per-session remote channels (Telegram/Discord/Slack/Feishu/Email/WeCom), then verify inbound/outbound routing and processing visibility.
---

# Channels

Use this skill when users ask to connect Telegram/Discord/Slack/Feishu/Email/WeCom channels, bind a session to a remote channel, or debug channel routing.

## Policy

- Prefer session-level channel binding.
- Treat global `Channels` settings as controller/status only.
- Prioritize automation and guided discovery over manual IDs.

## Telegram Setup

1. Open target session settings from sidebar.
2. Set `Channel = Telegram`.
3. Paste `Telegram Bot Token`.
4. Ask user to send one message to the bot in Telegram.
5. Tap `Detect Chats`.
6. Select a detected chat.
7. Save.

Manual chat ID entry is optional fallback only.

## Discord Setup

1. Open target session settings from sidebar.
2. Set `Channel = Discord`.
3. Paste `Discord Bot Token`.
4. Invite bot to server and send one message in target channel.
5. Paste `Discord Channel ID` and save.

Tip: Enable Discord Developer Mode, then right-click target channel and copy ID.
If server-channel message text appears empty, enable `Message Content Intent` for the bot in Discord Developer Portal.

## Slack Setup

1. Open target session settings from sidebar.
2. Set `Channel = Slack`.
3. Fill `Slack App Token (xapp...)`.
4. Fill `Slack Bot Token (xoxb...)`.
5. Fill `Target Channel ID` (usually starts with `C`, `G`, or `D`).
6. Choose `Response Mode`:
   - `mention` = reply only when bot is mentioned in channels
   - `open` = reply to all messages in the bound channel
7. Optional: fill `Allowed User IDs` allow-list.
8. Save.

Slack app prerequisites:
- Socket Mode enabled
- App-level token with `connections:write`
- Bot scopes: `chat:write`, `reactions:write`, `app_mentions:read`
- Event subscriptions include message/app mention events
- App installed to workspace

## Feishu Setup

1. Open target session settings from sidebar.
2. Set `Channel = Feishu`.
3. Fill `Feishu App ID` and `Feishu App Secret`.
4. Save once so the app starts the long connection.
5. In Feishu Open Platform:
   - enable Bot capability,
   - use Long Connection mode,
   - add event `im.message.receive_v1`,
   - add permissions `im:message` and `im:message.p2p_msg:readonly`,
   - publish the app.
6. Send one message to the bot from Feishu.
7. Re-open session settings and tap `Detect Chats`.
8. Select the detected conversation:
   - private chats bind to `open_id` (`ou_...`)
   - group chats bind to `chat_id` (`oc_...`)
9. Optional: add `Allowed Open IDs`.
10. Save again if you changed the detected target.

`Encrypt Key` and `Verification Token` are optional for Long Connection mode and can stay blank unless your app setup requires them.

## Email Setup

1. Open target session settings from sidebar.
2. Set `Channel = Email`.
3. Turn `Consent Granted` on.
4. Fill IMAP settings:
   - `IMAP Host`
   - `IMAP Port`
   - `IMAP Username`
   - `IMAP Password`
5. Fill SMTP settings:
   - `SMTP Host`
   - `SMTP Port`
   - `SMTP Username`
   - `SMTP Password`
   - `From Address`
6. Optional: disable `Auto Reply` if the mailbox should only ingest mail and not answer automatically.
7. Save once so mailbox polling can start.
8. Send one email to the bot mailbox from the target sender address.
9. Tap `Detect Senders`.
10. Select the detected sender address.
11. Save again if you changed the detected target.

Recommended Gmail defaults:
- `imap.gmail.com:993`
- `smtp.gmail.com:587`
- use an app password after enabling 2-Step Verification
- `From Address` should match the mailbox account

## WeCom Setup

1. Open target session settings from sidebar.
2. Set `Channel = WeCom`.
3. Fill `WeCom Bot ID` and `WeCom Secret`.
4. Save once so the long connection can start.
5. Open the bot in WeCom and send one message.
6. Tap `Detect Chats`.
7. Select the detected conversation.
8. Optional: add `Allowed User IDs`.
9. Save again if you changed the detected target.

Binding note:
- Prefer detect-driven binding after inbound traffic appears in diagnostics.
- Manual target ID is fallback only.

## Expected Runtime Behavior

- Telegram -> Session:
  - inbound message routes to the bound local session,
  - local session shows processing state,
  - Telegram shows typing while processing,
  - final response is sent back to Telegram.
- Discord -> Session:
  - inbound message routes to the bound local session,
  - local session shows processing state,
  - Discord shows typing while processing,
  - final response is sent back to Discord.
- Slack -> Session:
  - inbound message routes to the bound local session,
  - local session shows processing state,
  - Slack final response is sent back to the same bound channel.
- Feishu -> Session:
  - inbound message routes to the bound local session,
  - local session shows processing state,
  - final response is sent back to the same bound Feishu target.
- WeCom -> Session:
  - inbound message routes to the bound local session,
  - local session shows processing state,
  - final response is sent back to the same WeCom target.
- Email -> Session:
  - unread inbound mail is polled from the configured mailbox,
  - matching sender mail routes to the bound local session,
  - local session shows processing state,
  - final response is sent back via SMTP when auto reply is enabled.
- Local -> Telegram in the same bound session:
  - local session keeps full history,
  - final response is mirrored to Telegram (unless already sent by message tool in-turn).
- Local -> Discord in the same bound session:
  - local session keeps full history,
  - final response is mirrored to Discord (unless already sent by message tool in-turn).
- Local -> Slack in the same bound session:
  - local session keeps full history,
  - final response is mirrored to Slack (unless already sent by message tool in-turn).
- Local -> Feishu in the same bound session:
  - local session keeps full history,
  - final response is mirrored to Feishu (unless already sent by message tool in-turn).
- Local -> WeCom in the same bound session:
  - local session keeps full history,
  - final response is mirrored to WeCom when the session still has a valid reply route or active target binding.
- Local -> Email in the same bound session:
  - local session keeps full history,
  - final response is emailed back to the bound sender address when auto reply is enabled or when an explicit outbound send is requested.

## Quick Verification

- Session row shows `<channel>:<chat_id>`.
- Global Channels page lists the session as connected.
- Bidirectional message flow works in the same session.

## Recovery

- Detect returns empty: send a message to the bot first, then detect again.
- Inbound not routed: verify saved chat ID matches incoming chat ID.
- No outbound: verify channels gateway is enabled and this session has token + chat ID.
- Discord group no response: mention the bot once in that channel (default mention policy).
- Slack no response in channels: use mention mode and mention the bot once; verify Socket Mode and tokens.
- Feishu detect empty: save App ID / Secret first so long connection can start, then send a bot message and detect again.
- Email detect empty: save mailbox credentials first so polling can start, then send one unread email to the bot mailbox and detect again.
- Email no outbound: verify SMTP host/port/username/password, and check whether `Auto Reply` is enabled for reply flows.
- WeCom detect empty: save Bot ID / Secret first so long connection can start, then send a WeCom message to the bot and detect again.
