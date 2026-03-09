package dev.caaat.caaat_chat_bridge;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> BOT_WS_URL;
    public static final ModConfigSpec.ConfigValue<String> BOT_TOKEN;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("WebSocket URL of your bot.js server");
        BOT_WS_URL = builder.define("bot_ws_url", "ws://localhost:3000/ws");

        builder.comment("Shared secret token to authenticate with bot.js");
        BOT_TOKEN = builder.define("bot_token", "changeme");

        SPEC = builder.build();
    }
}