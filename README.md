# CaaatMinecraftBridge

A Node.js bridge that connects a NeoForge Minecraft server, a Discord server, and a website into a unified live chat. Messages sent in-game, in Discord, or on the website all appear in all three places in real time.

Built for [caaat.dev](https://caaat.dev) — a NeoForge 1.21.11 Minecraft server.

---

## Downloads

> ⚠️ The mods require **NeoForge 1.21.11** and are **server-side only** — do not install on the client.

| Download | Description |
|---|---|
| [📦 bot.js (source)](https://github.com/Cooper-Rice/CaaatMinecraftBridge/releases/tag/bot.js) | The Node.js bridge server |
| [🔧 caaat_chat_bridge.jar](https://github.com/Cooper-Rice/CaaatMinecraftBridge/releases/tag/caaat_chat_bridge) | Chat/events mod — drop into your server's `mods/` folder |
| [📊 caaat_stats.jar](https://github.com/Cooper-Rice/CaaatMinecraftBridge/releases/tag/caaat_stats) | Stats mod — drop into your server's `mods/` folder |

Full releases and changelogs on the [Releases page](https://github.com/Cooper-Rice/CaaatMinecraftBridge/releases).

---

## Features

- 💬 **Bidirectional chat** between Minecraft, Discord, and the website
- 📡 **Server-Sent Events (SSE)** for real-time updates on the website (no polling)
- 🟢 **Live server status** — player count, version, uptime
- 🏆 **Game events** — joins, leaves, deaths, and advancements posted to Discord and website
- 🛡️ **Profanity filter** on all website messages
- ⏱️ **Rate limiting** on website chat (per IP)
- 🔇 **Bridge controls** via Discord slash commands (`/bridge web readonly`, `/bridge web on`)
- 🔒 **WebSocket auth** — the Minecraft mod must authenticate with a shared token

---

## Architecture

```
┌─────────────────────────────┐
│   Minecraft Server          │
│   ├─ caaat_chat_bridge     │◄─────────────────────┐
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

- **caaat_chat_bridge** handles chat, join/leave, death, and advancement events over WebSocket
- **caaat_stats** sends live player count and server info
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
git clone https://github.com/Cooper-Rice/CaaatMinecraftBridge.git
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

## Website Integration

bot.js exposes two HTTP endpoints that your website connects to. Since bot.js runs locally on your server machine, you'll need a way to expose it publicly — the recommended approach is a **Cloudflare Tunnel**.

### Exposing bot.js publicly

**Option A — Cloudflare Tunnel (recommended)**

Cloudflare Tunnel lets you expose bot.js to the internet without opening any ports or having a static IP. It's free and works on any machine.

1. Install [cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/)
2. Authenticate: `cloudflared tunnel login`
3. Create a tunnel: `cloudflared tunnel create my-tunnel`
4. Create a config file at `~/.cloudflared/config.yml`:

```yaml
tunnel: <your-tunnel-id>
credentials-file: /path/to/.cloudflared/<tunnel-id>.json

ingress:
  - hostname: api.yourdomain.com
    service: http://localhost:3000
  - service: http_status:404
```

5. Route the tunnel to your domain: `cloudflared tunnel route dns my-tunnel api.yourdomain.com`
6. Run it: `cloudflared tunnel run my-tunnel`

Your bot is now reachable at `https://api.yourdomain.com`.

**Option B — Any reverse proxy**

You can also expose bot.js via nginx, Caddy, or any other reverse proxy pointed at `localhost:3000`.

---

### Connecting your website

Once bot.js is publicly accessible, your website needs to hit two endpoints:

**`GET /events`** — real-time SSE stream

Connect to this on page load to receive live messages, status updates, and player stats:

```javascript
const events = new EventSource('https://api.yourdomain.com/events');

events.addEventListener('message', (e) => {
    const msg = JSON.parse(e.data);

    if (msg.type === 'history')   // array of recent messages on connect
    if (msg.type === 'chat')      // a chat message (from MC, Discord, or web)
    if (msg.type === 'join')      // player joined
    if (msg.type === 'leave')     // player left
    if (msg.type === 'death')     // player died
    if (msg.type === 'advancement') // player got an advancement
    if (msg.type === 'stats')     // { players, max, version }
    if (msg.type === 'status')    // { online: bool, readonly: bool }
    if (msg.type === 'server')    // server started/stopped
});
```

**`POST /`** — send a message from the website

```javascript
await fetch('https://api.yourdomain.com/', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'Steve', message: 'Hello from the web!' })
});
```

Messages are rate-limited to one per 1.5 seconds per IP and filtered for profanity. The `username` field is sanitized to alphanumeric + underscores/hyphens, max 20 characters. Messages are capped at 200 characters.

---

## Notes

- `bridge-state.json` is auto-generated and saves the current web chat state across restarts
- The Minecraft mod connects to `ws://localhost:3000/ws` (same machine) or the bot's internal IP if running on separate machines
- Slash commands are registered per-guild on startup for instant availability
