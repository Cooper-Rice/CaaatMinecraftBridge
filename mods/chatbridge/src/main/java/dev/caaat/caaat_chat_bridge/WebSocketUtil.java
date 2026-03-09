package dev.caaat.caaat_chat_bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

public class WebSocketUtil {

    private static HttpClient httpClient;
    private static WebSocket webSocket;
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "caaat-ws");
                t.setDaemon(true);
                return t;
            });

    public enum WebState { ON, READONLY, OFF }

    private static volatile boolean running = false;
    private static volatile boolean connected = false;
    public static volatile WebState state = WebState.ON;

    public static boolean isConnected() { return connected; }

    // ── CONNECT (called on server start) ─────────────────────────────────────
    public static void connect() {
        running = true;
        httpClient = HttpClient.newHttpClient();
        attemptConnect();
    }

    private static void attemptConnect() {
        if (!running) return;
        CaaatChatBridge.LOGGER.info("✦ Connecting to bot.js WebSocket...");

        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(Config.BOT_WS_URL.get()), new WsListener())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        CaaatChatBridge.LOGGER.warn("✦ WebSocket connection failed: {} — retrying in 10s", err.getMessage());
                        scheduleReconnect();
                    } else {
                        webSocket = ws;
                        connected = true;
                        CaaatChatBridge.LOGGER.info("✦ Connected to bot.js ✓");

                        // First message: authenticate
                        JsonObject auth = new JsonObject();
                        auth.addProperty("auth", Config.BOT_TOKEN.get());
                        sendRaw(auth.toString());
                    }
                });
    }

    private static void scheduleReconnect() {
        if (!running) return;
        connected = false;
        scheduler.schedule(WebSocketUtil::attemptConnect, 10, TimeUnit.SECONDS);
    }

    // ── SEND A GAME EVENT TO BOT.JS ───────────────────────────────────────────
    public static void sendEvent(JsonObject payload) {
        if (state == WebState.OFF) return;
        if (!connected || webSocket == null) {
            CaaatChatBridge.LOGGER.warn("✦ WebSocket not connected — dropping event: {}", payload);
            return;
        }
        sendRaw(payload.toString());
    }

    // ── SET STATE (on / readonly / off) ───────────────────────────────────────
    public static void setState(WebState newState) {
        state = newState;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", switch (newState) {
            case ON       -> "resume";
            case READONLY -> "readonly";
            case OFF      -> "pause";
        });
        if (connected && webSocket != null) sendRaw(msg.toString());
    }

    private static void sendRaw(String text) {
        try {
            webSocket.sendText(text, true);
        } catch (Exception e) {
            CaaatChatBridge.LOGGER.warn("✦ Failed to send via WebSocket: {}", e.getMessage());
        }
    }

    // ── DISCONNECT (called on server stop) ────────────────────────────────────
    public static void disconnect() {
        running = false;
        connected = false;
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Server stopping").join();
            } catch (Exception ignored) {}
        }
        scheduler.shutdownNow();
        CaaatChatBridge.LOGGER.info("✦ WebSocket disconnected");
    }

    // ── HANDLE INCOMING MESSAGE FROM BOT.JS ───────────────────────────────────
    private static void handleIncoming(String raw) {
        try {
            JsonObject msg = JsonParser.parseString(raw).getAsJsonObject();
            String type = msg.get("type").getAsString();

            if (type.equals("request")) {
                if (state == WebState.OFF) return;
                String request = msg.get("request").getAsString();
                String id = msg.get("id").getAsString();
                if (request.equals("list")) {
                    MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
                    if (srv != null) {
                        srv.execute(() -> {
                            JsonObject response = new JsonObject();
                            response.addProperty("type", "response");
                            response.addProperty("id", id);
                            JsonArray players = new JsonArray();
                            srv.getPlayerList().getPlayers()
                                    .forEach(p -> players.add(p.getName().getString()));
                            response.add("players", players);
                            sendRaw(response.toString());
                        });
                    }
                }
                return;
            }

            if (type.equals("setState")) {
                String newState = msg.has("state") ? msg.get("state").getAsString() : "on";
                state = switch (newState) {
                    case "readonly" -> WebState.READONLY;
                    case "off"      -> WebState.OFF;
                    default         -> WebState.ON;
                };
                CaaatChatBridge.LOGGER.info("✦ Web state set to {} by Discord", state);
                return;
            }

            if (state == WebState.OFF) return;

            String name = msg.has("name") ? msg.get("name").getAsString() : "";
            String text = msg.has("text") ? msg.get("text").getAsString() : "";

            Component component = switch (type) {
                // <[WEB] Name> message — cyan
                case "web" -> Component.literal(
                        "§8<§b[WEB] §f" + name + "§8>§r " + text
                );
                // <[Discord] Name> message — blue
                case "discord" -> Component.literal(
                        "§8<§9[Discord] §f" + name + "§8>§r " + text
                );
                default -> null;
            };

            if (component != null) {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    final Component finalComponent = component;
                    server.execute(() ->
                            server.getPlayerList().broadcastSystemMessage(finalComponent, false)
                    );
                }
            }
        } catch (Exception e) {
            CaaatChatBridge.LOGGER.warn("✦ Failed to handle incoming message: {}", e.getMessage());
        }
    }

    // ── WEBSOCKET LISTENER ────────────────────────────────────────────────────
    private static class WsListener implements WebSocket.Listener {
        private final StringBuilder sb = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            CaaatChatBridge.LOGGER.info("✦ WebSocket opened");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            sb.append(data);
            if (last) {
                String message = sb.toString();
                sb.setLength(0);
                handleIncoming(message);
            }
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
            CaaatChatBridge.LOGGER.warn("✦ WebSocket closed (code {}: {}) — reconnecting...", statusCode, reason);
            connected = false;
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            CaaatChatBridge.LOGGER.warn("✦ WebSocket error: {} — reconnecting...", error.getMessage());
            connected = false;
            scheduleReconnect();
        }
    }
}