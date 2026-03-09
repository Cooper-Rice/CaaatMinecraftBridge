package dev.caaat.caaat_stats;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ServerEvents {

    private static HttpClient httpClient;
    private static WebSocket webSocket;
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "caaat-stats-ws");
                t.setDaemon(true);
                return t;
            });

    private static volatile boolean running = false;
    private static volatile boolean connected = false;
    private static MinecraftServer mcServer;

    public static void register(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(ServerEvents::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onPlayerLeave);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        mcServer = event.getServer();
        running = true;
        httpClient = HttpClient.newHttpClient();
        attemptConnect();
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        running = false;
        connected = false;
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Server stopping").join();
            } catch (Exception ignored) {}
        }
        scheduler.shutdownNow();
        Caaat_stats.LOGGER.info("✦ Stats WebSocket disconnected");
    }

    private static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        // Player is already in the list when this fires, no offset needed
        if (mcServer != null) mcServer.execute(() -> sendStats(0));
    }

    private static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        // Player is still in the list when this fires, so subtract 1
        if (mcServer != null) mcServer.execute(() -> sendStats(-1));
    }

    private static void attemptConnect() {
        if (!running) return;
        Caaat_stats.LOGGER.info("✦ Stats connecting to bot.js...");

        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(Config.BOT_WS_URL.get()), new WsListener())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        Caaat_stats.LOGGER.warn("✦ Stats connection failed: {} — retrying in 10s", err.getMessage());
                        scheduleReconnect();
                    } else {
                        webSocket = ws;
                        connected = true;
                        Caaat_stats.LOGGER.info("✦ Stats connected to bot.js ✓");

                        // Authenticate
                        JsonObject auth = new JsonObject();
                        auth.addProperty("auth", Config.BOT_TOKEN.get());
                        sendRaw(auth.toString());

                        // Send initial stats
                        if (mcServer != null) mcServer.execute(() -> sendStats(0));
                    }
                });
    }

    private static void scheduleReconnect() {
        if (!running) return;
        connected = false;
        scheduler.schedule(ServerEvents::attemptConnect, 10, TimeUnit.SECONDS);
    }

    private static void sendRaw(String text) {
        try {
            webSocket.sendText(text, true);
        } catch (Exception e) {
            Caaat_stats.LOGGER.warn("✦ Stats failed to send: {}", e.getMessage());
        }
    }

    // Must be called on the main server thread
    // offset: 0 normally, -1 on leave (player still in list when event fires)
    private static void sendStats(int offset) {
        if (!connected || webSocket == null) return;

        List<ServerPlayer> players = mcServer.getPlayerList().getPlayers();
        String playerList = players.stream()
                .map(p -> "\"" + p.getGameProfile().name() + "\"")
                .collect(Collectors.joining(","));

        JsonObject json = new JsonObject();
        json.addProperty("type", "stats");
        json.addProperty("players", players.size() + offset);
        json.addProperty("max", mcServer.getMaxPlayers());
        json.addProperty("version", mcServer.getServerVersion());
        json.addProperty("playerList", "[" + playerList + "]");

        sendRaw(json.toString());
        Caaat_stats.LOGGER.info("✦ Sent stats update ({} players)", players.size() + offset);
    }

    // ── WEBSOCKET LISTENER ────────────────────────────────────────────────────
    private static class WsListener implements WebSocket.Listener {
        private final StringBuilder sb = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            Caaat_stats.LOGGER.info("✦ Stats WebSocket opened");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            sb.append(data);
            if (last) sb.setLength(0);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.sendPong(message);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            Caaat_stats.LOGGER.warn("✦ Stats WebSocket closed ({}: {}) — reconnecting...", statusCode, reason);
            connected = false;
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            Caaat_stats.LOGGER.warn("✦ Stats WebSocket error: {} — reconnecting...", error.getMessage());
            connected = false;
            scheduleReconnect();
        }
    }
}