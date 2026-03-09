# CaaatMinecraftBridge

A Node.js bridge that connects a NeoForge Minecraft server, a Discord server, and a website into a unified live chat. Messages sent in-game, in Discord, or on the website all appear in all three places in real time.

Built for [caaat.dev](https://caaat.dev) — a NeoForge 1.21.11 Minecraft server.

---

## Features

- 💬 **Bidirectional chat** between Minecraft, Discord, and the website
- 📡 **Server-Sent Events (SSE)** for real-time updates on the website (no polling)
- 🟢 **Live server status** — player count, version, uptime
- 🏆 **Game events** — joins, leaves, deaths, and advancements posted to Discord
- 🛡️ **Profanity filter** on all website messages
- ⏱️ **Rate limiting** on website chat (per IP)
- 🔇 **Bridge controls** via Discord slash commands (`/bridge web readonly`, `/bridge web on`)
- 🔒 **WebSocket auth** — the Minecraft mod must authenticate with a shared token

---

## Architecture

```
┌─────────────────────────────┐
│   Minecraft Server          │
│   ├─ caaat_discord_bridge   │◄─────────────────────┐
│   └─ caaat_stats            │                      │
└────────────┬────────────────┘                      │
             │ WebSocket /ws                         │
             │ (chat, joins, deaths, stats...)       │ (Discord/web messages
             ▼                                       │  relayed to Minecraft)
      ┌─────────────┐   SSE /events   ┌─────────────────────┐
      │   bot.js    │────────────────►│  Website (caaat.dev) │
      │  (Node.js)  │◄────────────────│                     │
      └──────┬──────┘   POST /        └─────────────────────┘
             │
             │ Webhook (events)
             ▼
      ┌─────────────┐
      │   Discord   │
      │  #minecraft │
      └──────┬──────┘
             │ Bot reads messages
             └──────────────────────►  bot.js  (loops back above)
```

- **caaat_discord_bridge** handles chat, join/leave, death, and advancement events over WebSocket
- **caaat_stats** sends periodic player count and server info
- The **website** sends messages via `POST /` and receives real-time updates via `GET /events` (SSE)
- **Discord** receives events via a webhook, and the bot watches the channel to relay messages back to Minecraft and the website
- The bot is exposed publicly via a **Cloudflare Tunnel** (`api.caaat.dev`)

---

## Setup

### Prerequisites

- Node.js 18+
- A Discord bot with the following enabled:
  - `Message Content Intent`
  - `Server Members Intent`
- A Discord webhook in your chat channel
- The companion NeoForge mod installed on your Minecraft server

### Install

```bash
git clone https://github.com/yourusername/CaaatMinecraftBridge.git
cd CaaatMinecraftBridge
npm install
```

### Configure

Copy `.env.example` to `.env` and fill in your values:

```bash
cp .env.example .env
```

```env
DISCORD_TOKEN=        # Your Discord bot token
MC_AUTH_TOKEN=        # A secret string shared with the Minecraft mod
WEBHOOK_URL=          # Discord webhook URL for your chat channel
CHANNEL_ID=           # Discord channel ID to watch for messages
APPLICATION_ID=       # Discord application ID (for slash commands)
GUILD_ID=             # Your Discord server ID
PORT=3000             # Port to run the HTTP server on
```

### Run

```bash
node bot.js
```

---

## Discord Slash Commands

| Command | Description | Admin only |
|---|---|---|
| `/status` | Shows server online status, player count, and uptime | No |
| `/list` | Lists currently online players | No |
| `/bridge web on` | Enables website chat fully | Yes |
| `/bridge web readonly` | Makes website chat read-only | Yes |

---

## Environment Variables

| Variable | Description |
|---|---|
| `DISCORD_TOKEN` | Bot token from Discord Developer Portal |
| `MC_AUTH_TOKEN` | Shared secret for mod WebSocket authentication |
| `WEBHOOK_URL` | Full Discord webhook URL |
| `CHANNEL_ID` | ID of the Discord channel to bridge |
| `APPLICATION_ID` | Discord app ID (for registering slash commands) |
| `GUILD_ID` | Discord server ID (for guild-scoped slash commands) |
| `PORT` | HTTP server port (default: 3000) |

---

## File Structure

```
bot.js               # Main bridge server
.env                 # Secrets (never committed)
.env.example         # Template for .env
bridge-state.json    # Persisted bridge state (auto-generated, never committed)
package.json
```

---

## Notes

- `bridge-state.json` is auto-generated and saves the current web chat state across restarts
- The Minecraft mod connects to `ws://localhost:3000/ws` (same machine) or the bot's internal IP if running on separate machines
- Slash commands are registered per-guild on startup for instant availability
