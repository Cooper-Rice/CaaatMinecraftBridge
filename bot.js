const http = require('http');
require('dotenv').config();
const fs = require('fs');
const { WebSocketServer } = require('ws');
const { Client, GatewayIntentBits, WebhookClient, SlashCommandBuilder, REST, Routes } = require('discord.js');

// ── CONFIG ──────────────────────────────────────────────────────────────────
const DISCORD_TOKEN = process.env.DISCORD_TOKEN;
const CHANNEL_ID = process.env.CHANNEL_ID;
const APPLICATION_ID = process.env.APPLICATION_ID;
const GUILD_ID = process.env.GUILD_ID;
const PORT = process.env.PORT;
const MAX_MESSAGES = 50;
const MC_AUTH_TOKEN = process.env.MC_AUTH_TOKEN; 
// ────────────────────────────────────────────────────────────────────────────

const messages = [];
let lastId = 0;
const sseClients = new Set();
const modSockets = new Set();
let serverStartTime = null;
const STATE_FILE = './bridge-state.json';
let webState = 'on';
let lastStats = null;
try {
    const saved = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    if (saved.webState) webState = saved.webState;
    console.log(`✦ Loaded bridge state: ${webState}`);
} catch { }

function saveBridgeState() {
    fs.writeFileSync(STATE_FILE, JSON.stringify({ webState }));
}

const pendingRequests = new Map();

const webhookClient = new WebhookClient({ url: process.env.WEBHOOK_URL });

// ── PROFANITY FILTER ─────────────────────────────────────────────────────────
const filter = require('leo-profanity');
filter.add(['hitler', 'stalin', 'nazi', 'rape', 'nonce', 'pedo', 'pedophile',
    'epstein'
]);

function containsBadWord(text) {
    return filter.check(text);
}

// ── RATE LIMITER (on;y applies to website) ───────────────────────────────────────────────
const rateLimits = new Map();
const RATE_LIMIT_MS = 1500;

function isRateLimited(ip) {
    const now = Date.now();
    const last = rateLimits.get(ip) ?? 0;
    if (now - last < RATE_LIMIT_MS) return true;
    rateLimits.set(ip, now);
    if (rateLimits.size > 1000) {
        for (const [key, val] of rateLimits)
            if (now - val > RATE_LIMIT_MS * 2) rateLimits.delete(key);
    }
    return false;
}

// ── MESSAGE STORE ─────────────────────────────────────────────────────────────
function addMessage(msg) {
    if (msg.text) msg.text = filter.clean(msg.text);
    msg.id = ++lastId;
    messages.push(msg);
    if (messages.length > MAX_MESSAGES) messages.shift();
    console.log(`[${msg.type}] ${msg.name ?? ''}: ${msg.text ?? ''}`);
    broadcastSSE(msg);
}

// ── PUSH TO MINECRAFT ─────────────────────────────────────────────────────────
function pushToMod(msg) {
    for (const ws of modSockets) {
        if (ws.readyState === ws.OPEN) {
            try { ws.send(JSON.stringify(msg)); }
            catch (err) { console.warn('✦ Failed to push to mod:', err.message); }
        }
    }
}

// ── REQUEST/RESPONSE TO MOD ───────────────────────────────────────────────────
function requestFromMod(type, timeoutMs = 5000) {
    return new Promise((resolve) => {
        const id = `${type}_${Date.now()}`;
        const timer = setTimeout(() => {
            pendingRequests.delete(id);
            resolve(null);
        }, timeoutMs);
        pendingRequests.set(id, (data) => {
            clearTimeout(timer);
            pendingRequests.delete(id);
            resolve(data);
        });
        pushToMod({ type: 'request', request: type, id });
    });
}

// ── SSE BROADCAST ─────────────────────────────────────────────────────────────
function broadcastSSE(msg) {
    const data = `data: ${JSON.stringify(msg)}\n\n`;
    for (const client of sseClients) {
        try { client.write(data); }
        catch { sseClients.delete(client); }
    }
}

setInterval(() => {
    const ping = `: ping\n\n`;
    for (const client of sseClients) {
        try { client.write(ping); }
        catch { sseClients.delete(client); }
    }
}, 25_000);

// ── SEND TO DISCORD ───────────────────────────────────────────────────────────
async function sendToDiscord(content, webhookName, avatarURL) {
    try {
        await webhookClient.send({
            content,
            username: webhookName,
            avatarURL: avatarURL ?? 'https://caaat.dev/images/CatPunch.png'
        });
    } catch (err) {
        console.warn('Discord send failed:', err.message);
    }
}

// ── FORMAT UPTIME ─────────────────────────────────────────────────────────────
function formatUptime(ms) {
    const s = Math.floor(ms / 1000);
    const m = Math.floor(s / 60);
    const h = Math.floor(m / 60);
    const d = Math.floor(h / 24);
    if (d > 0) return `${d}d ${h % 24}h ${m % 60}m`;
    if (h > 0) return `${h}h ${m % 60}m`;
    if (m > 0) return `${m}m ${s % 60}s`;
    return `${s}s`;
}

// ── SLASH COMMANDS ────────────────────────────────────────────────────────────
const commands = [
    new SlashCommandBuilder().setName('status').setDescription('Check if the Minecraft server is online'),
    new SlashCommandBuilder().setName('list').setDescription('See who is currently online in-game'),
    new SlashCommandBuilder()
        .setName('bridge')
        .setDescription('Control the web chat bridge')
        .setDefaultMemberPermissions('8')
        .addSubcommandGroup(group => group
            .setName('web')
            .setDescription('Web chat bridge controls')
            .addSubcommand(sub => sub.setName('on').setDescription('Enable web chat fully'))
            .addSubcommand(sub => sub.setName('readonly').setDescription('Make web chat read-only'))
        ),
].map(c => c.toJSON());

async function registerCommands() {
    try {
        const rest = new REST().setToken(DISCORD_TOKEN);
        await rest.put(Routes.applicationGuildCommands(APPLICATION_ID, GUILD_ID), { body: commands });
        console.log('✦ Slash commands registered');
    } catch (err) {
        console.warn('✦ Failed to register slash commands:', err.message);
    }
}

// ── HANDLE MOD EVENTS ─────────────────────────────────────────────────────────
async function handleModEvent(event) {
    const time = new Date().toTimeString().slice(0, 8);
    const playerAvatar = event.player
        ? `https://minotar.net/avatar/${event.player}`
        : 'https://caaat.dev/images/CatPunch.png';

    if (event.type === 'response' && event.id && pendingRequests.has(event.id)) {
        pendingRequests.get(event.id)(event);
        return;
    }

    switch (event.type) {
        case 'chat': {
            addMessage({ type: 'chat', time, name: `[Server] ${event.player}`, text: event.text });
            await webhookClient.send({ content: event.text, username: event.player, avatarURL: playerAvatar });
            break;
        }
        case 'join': {
            addMessage({ type: 'join', time, name: event.player, text: 'joined the game' });
            await webhookClient.send({ username: 'Server', avatarURL: 'https://caaat.dev/images/CatPunch.png', embeds: [{ description: `**${event.player}** joined the game`, color: 0x6dcc7f, thumbnail: { url: playerAvatar } }] });
            break;
        }
        case 'leave': {
            addMessage({ type: 'leave', time, name: event.player, text: 'left the game' });
            await webhookClient.send({ username: 'Server', avatarURL: 'https://caaat.dev/images/CatPunch.png', embeds: [{ description: `**${event.player}** left the game`, color: 0xe06c75, thumbnail: { url: playerAvatar } }] });
            break;
        }
        case 'death': {
            addMessage({ type: 'death', time, name: event.player, text: event.text });
            await webhookClient.send({ username: 'Server', avatarURL: 'https://caaat.dev/images/CatPunch.png', embeds: [{ description: `💀 ${event.text}`, color: 0x8b0000, thumbnail: { url: playerAvatar } }] });
            break;
        }
        case 'advancement': {
            addMessage({ type: 'advancement', time, name: event.player, text: event.advancement });
            await webhookClient.send({ username: 'Server', avatarURL: 'https://caaat.dev/images/CatPunch.png', embeds: [{ description: `🏆 **${event.player}** got advancement **[${event.advancement}]**`, color: 0xc3a6ff, thumbnail: { url: playerAvatar } }] });
            break;
        }
        case 'stats': {
            lastStats = { type: 'stats', players: event.players, max: event.max, version: event.version };
            broadcastSSE(lastStats);
            break;
        }
        case 'server': {
            const text = event.event === 'start' ? 'Server Started!' : 'Server Stopped!';
            const color = event.event === 'start' ? 0xc3a6ff : 0x888888;
            if (event.event === 'start') serverStartTime = Date.now();
            if (event.event === 'stop') serverStartTime = null;
            addMessage({ type: 'server', time, name: 'Server', text });
            await webhookClient.send({ username: 'Server', avatarURL: 'https://caaat.dev/images/CatPunch.png', embeds: [{ description: `⚡ ${text}`, color }] });
            break;
        }
        case 'pause': {
            console.log('✦ Web chat paused by server');
            broadcastSSE({ type: 'status', online: false });
            break;
        }
        case 'readonly': {
            console.log('✦ Web chat set to readonly by server');
            broadcastSSE({ type: 'status', online: true, readonly: true });
            break;
        }
        case 'resume': {
            console.log('✦ Web chat resumed by server');
            broadcastSSE({ type: 'status', online: true, readonly: false });
            break;
        }
        default:
            console.warn('✦ Unknown mod event type:', event.type);
    }
}

// ── DISCORD CLIENT ────────────────────────────────────────────────────────────
const client = new Client({
    intents: [
        GatewayIntentBits.Guilds,
        GatewayIntentBits.GuildMessages,
        GatewayIntentBits.MessageContent
    ]
});

let discordChannel = null;

client.once('ready', async () => {
    console.log(`✦ Discord bot logged in as ${client.user.tag}`);
    discordChannel = await client.channels.fetch(CHANNEL_ID);
    console.log(`✦ Watching channel: #${discordChannel.name}`);
    await registerCommands();
});

client.on('messageCreate', async message => {
    if (message.channelId !== CHANNEL_ID) return;
    if (message.webhookId) return;
    if (message.embeds.length > 0 || !message.content) return;

    const time = new Date().toTimeString().slice(0, 8);
    const name = message.author.displayName ?? message.author.username;
    const guild = message.guild;
    const resolvedText = guild ? await resolveMentions(message.content, guild) : message.content;

    addMessage({ type: 'chat', time, name: `[Discord] ${name}`, text: resolvedText });
    pushToMod({ type: 'discord', name, text: resolvedText });
});

// ── SLASH COMMAND HANDLER ─────────────────────────────────────────────────────
client.on('interactionCreate', async interaction => {
    if (!interaction.isChatInputCommand()) return;

    await interaction.deferReply();

    const modOnline = modSockets.size > 0;

    switch (interaction.commandName) {
        case 'status': {
            if (!modOnline) {
                await interaction.editReply({ embeds: [{ description: '🔴 **mc.caaat.dev** is currently offline', color: 0xe06c75 }] });
                return;
            }
            const response = await requestFromMod('list');
            const playerCount = response?.players?.length ?? 0;
            const uptime = serverStartTime ? formatUptime(Date.now() - serverStartTime) : '?';
            await interaction.editReply({
                embeds: [{
                    title: '🟢 mc.caaat.dev',
                    color: 0x6dcc7f,
                    fields: [
                        { name: 'Players', value: `${playerCount} online`, inline: true },
                        { name: 'Uptime', value: uptime, inline: true },
                        { name: 'Web Chat', value: webState === 'readonly' ? '🟡 read-only' : '🟢 on', inline: true },
                    ]
                }]
            });
            break;
        }
        case 'list': {
            if (!modOnline) {
                await interaction.editReply({ embeds: [{ description: '🔴 Server is offline', color: 0xe06c75 }] });
                return;
            }
            const response = await requestFromMod('list');
            if (!response || !response.players) {
                await interaction.editReply({ embeds: [{ description: '❓ Could not get player list from server', color: 0x888888 }] });
                return;
            }
            const players = response.players;
            const desc = players.length === 0
                ? '*No players online*'
                : players.map(p => `• ${p}`).join('\n');
            await interaction.editReply({
                embeds: [{
                    title: `👥 Players online (${players.length})`,
                    description: desc,
                    color: 0xc9b8f0
                }]
            });
            break;
        }
        case 'bridge': {
            const sub = interaction.options.getSubcommand();
            const labelMap = { on: '🟢 Web chat is now **on**', readonly: '🟡 Web chat is now **read-only**' };
            const colorMap = { on: 0x6dcc7f, readonly: 0xf5c842 };
            if (sub === 'on') { webState = 'on'; broadcastSSE({ type: 'status', online: true, readonly: false }); pushToMod({ type: 'setState', state: 'on' }); }
            if (sub === 'readonly') { webState = 'readonly'; broadcastSSE({ type: 'status', online: true, readonly: true }); pushToMod({ type: 'setState', state: 'readonly' }); }
            saveBridgeState();
            await interaction.editReply({ embeds: [{ description: labelMap[sub], color: colorMap[sub] }] });
            break;
        }
    }
});

process.on('unhandledRejection', err => {
    console.warn('Unhandled rejection:', err.message);
});

async function resolveMentions(text, guild) {
    return text.replace(/<@!?(\d+)>/g, (match, id) => {
        const member = guild.members.cache.get(id);
        return member ? `@${member.displayName}` : match;
    });
}

// ── HTTP SERVER ───────────────────────────────────────────────────────────────
const server = http.createServer(async (req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Accept');

    if (req.method === 'OPTIONS') {
        res.writeHead(204);
        res.end();
        return;
    }

    const ip = req.headers['x-forwarded-for'] ?? req.socket.remoteAddress;
    const url = new URL(req.url, `http://localhost:${PORT}`);

    // ── GET /events — SSE stream for website ─────────────────────────────────
    if (req.method === 'GET' && url.pathname === '/events') {
        res.writeHead(200, {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            'Connection': 'keep-alive',
            'X-Accel-Buffering': 'no',
        });

        const since = parseInt(url.searchParams.get('since') ?? '0');
        const history = messages.filter(m => m.id > since);
        if (history.length > 0) {
            res.write(`data: ${JSON.stringify({ type: 'history', messages: history, lastId })}\n\n`);
        }

        if (lastStats) {
            res.write(`data: ${JSON.stringify(lastStats)}\n\n`);
        }

        const isOnline = modSockets.size > 0;
        res.write(`data: ${JSON.stringify({ type: 'status', online: isOnline, readonly: webState === 'readonly' })}\n\n`);

        sseClients.add(res);
        console.log(`✦ SSE client connected (total: ${sseClients.size})`);
        req.on('close', () => {
            sseClients.delete(res);
            console.log(`✦ SSE client disconnected (total: ${sseClients.size})`);
        });
        return;
    }

    // ── GET / — legacy JSON poll fallback ────────────────────────────────────
    if (req.method === 'GET') {
        res.setHeader('Content-Type', 'application/json');
        const since = parseInt(url.searchParams.get('since') ?? '0');
        res.end(JSON.stringify({ messages: messages.filter(m => m.id > since), lastId, online: true }));
        return;
    }

    // ── POST / — website sends a message ─────────────────────────────────────
    if (req.method === 'POST') {
        res.setHeader('Content-Type', 'application/json');

        if (isRateLimited(ip)) {
            res.writeHead(429);
            res.end(JSON.stringify({ error: 'Too fast! Wait 5 seconds.' }));
            return;
        }

        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', async () => {
            try {
                const { username, message } = JSON.parse(body);

                if (!username || !message) {
                    res.writeHead(400);
                    res.end(JSON.stringify({ error: 'Missing username or message.' }));
                    return;
                }
                if (containsBadWord(username) || containsBadWord(message)) {
                    res.writeHead(400);
                    res.end(JSON.stringify({ error: 'Message blocked.' }));
                    return;
                }

                const safeName = username.replace(/[^a-zA-Z0-9_\- ]/g, '').slice(0, 20);
                const safeMsg = message.slice(0, 200);
                const time = new Date().toTimeString().slice(0, 8);

                addMessage({ type: 'chat', time, name: `[Website] ${safeName}`, text: safeMsg });
                await sendToDiscord(`[WEB] ${safeName}: ${safeMsg}`, 'caaat.dev');

                pushToMod({ type: 'web', name: safeName, text: safeMsg });

                res.end(JSON.stringify({ ok: true, lastId }));
            } catch (err) {
                res.writeHead(400);
                res.end(JSON.stringify({ error: 'Invalid request.' }));
            }
        });
        return;
    }

    res.setHeader('Content-Type', 'application/json');
    res.writeHead(404);
    res.end('{}');
});

// ── WEBSOCKET SERVER (for Minecraft mod) ─────────────────────────────────────
const wss = new WebSocketServer({ server, path: '/ws' });

wss.on('connection', (ws, req) => {
    console.log('✦ WebSocket connection attempt from mod...');

    let authenticated = false;

    ws.once('message', (raw) => {
        let msg;
        try {
            msg = JSON.parse(raw.toString());
        } catch {
            console.warn('✦ Mod sent invalid auth message — closing');
            ws.close(4000, 'Invalid message');
            return;
        }

        if (msg.auth !== MC_AUTH_TOKEN) {
            console.warn('✦ Mod WebSocket auth failed — closing');
            ws.close(4001, 'Unauthorized');
            return;
        }

        authenticated = true;
        modSockets.add(ws);
        if (!serverStartTime) serverStartTime = Date.now();
        broadcastSSE({ type: 'status', online: true, readonly: webState === 'readonly' });
        if (lastStats) broadcastSSE(lastStats);
        console.log(`✦ Minecraft mod connected via WebSocket ✓ (total: ${modSockets.size})`);

        ws.on('message', async (data) => {
            try {
                const event = JSON.parse(data.toString());
                await handleModEvent(event);
            } catch (err) {
                console.warn('✦ Bad mod message:', err.message);
            }
        });

        ws.on('close', () => {
            modSockets.delete(ws);
            if (modSockets.size === 0) {
                lastStats = null;
                broadcastSSE({ type: 'stats', players: 0, max: 0, version: '-' });
                broadcastSSE({ type: 'status', online: false });
            }
            console.log(`✦ Minecraft mod disconnected (remaining: ${modSockets.size})`);
        });

        ws.on('error', (err) => {
            console.warn('✦ Mod WebSocket error:', err.message);
        });

        if (webState !== 'on') pushToMod({ type: 'setState', state: webState });
    });

    setTimeout(() => {
        if (!authenticated) {
            ws.close(4001, 'Auth timeout');
        }
    }, 5000);
});

server.listen(PORT, () => {
    console.log(`✦ HTTP server running on http://localhost:${PORT}`);
    console.log('✦ caaat.dev Discord chat bridge is running!');
    console.log('✦ SSE endpoint:  GET  /events');
    console.log('✦ Mod WebSocket: WS   /ws');
});

client.login(DISCORD_TOKEN).catch(err => {
    console.error('✦ Failed to login to Discord:', err.message);
    process.exit(1);
});