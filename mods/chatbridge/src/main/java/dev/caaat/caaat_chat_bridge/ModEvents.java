package dev.caaat.caaat_chat_bridge;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@EventBusSubscriber(modid = CaaatChatBridge.MOD_ID)
public class ModEvents {

    // ── Server started ────────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        CaaatChatBridge.LOGGER.info("✦ Server started — connecting WebSocket");
        WebSocketUtil.connect();

        // Small delay so the auth handshake completes before sending
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "server");
        payload.addProperty("event", "start");
        WebSocketUtil.sendEvent(payload);
    }

    // ── Server stopping ───────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "server");
        payload.addProperty("event", "stop");
        WebSocketUtil.sendEvent(payload);

        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        WebSocketUtil.disconnect();
    }

    // ── Player chat ───────────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        String player = event.getPlayer().getGameProfile().name();
        String text = event.getMessage().getString();

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "chat");
        payload.addProperty("player", player);
        payload.addProperty("text", text);
        WebSocketUtil.sendEvent(payload);
    }

    // ── Player join ───────────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "join");
        payload.addProperty("player", player.getGameProfile().name());
        WebSocketUtil.sendEvent(payload);
    }

    // ── Player leave ──────────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "leave");
        payload.addProperty("player", player.getGameProfile().name());
        WebSocketUtil.sendEvent(payload);
    }

    // ── Player death ──────────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        String deathMsg = event.getSource()
                .getLocalizedDeathMessage(player)
                .getString();

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "death");
        payload.addProperty("player", player.getGameProfile().name());
        payload.addProperty("text", deathMsg);
        WebSocketUtil.sendEvent(payload);
    }

    // ── Advancement earned ────────────────────────────────────────────────────
    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        var display = event.getAdvancement().value().display();
        if (display.isEmpty()) return;
        if (!display.get().shouldAnnounceChat()) return;

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "advancement");
        payload.addProperty("player", player.getGameProfile().name());
        payload.addProperty("advancement", display.get().getTitle().getString());
        WebSocketUtil.sendEvent(payload);
    }
}