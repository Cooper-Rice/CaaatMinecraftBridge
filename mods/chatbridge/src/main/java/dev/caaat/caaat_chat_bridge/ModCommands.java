package dev.caaat.caaat_chat_bridge;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = CaaatChatBridge.MOD_ID)
public class ModCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("bridge")
                .requires(source -> {
                    ServerPlayer p = source.getPlayer();
                    return p != null && source.getServer().getPlayerList().isOp(
                            new NameAndId(p.getGameProfile().id(), p.getGameProfile().name())
                    );
                });

        // /bridge web on|off|readonly
        var web = Commands.literal("web");

        web.then(Commands.literal("on").executes(ctx -> {
            WebSocketUtil.setState(WebSocketUtil.WebState.ON);
            ctx.getSource().sendSuccess(
                    () -> Component.literal("§a✦ Web bridge ON - website is live and two-way"),
                    true
            );
            CaaatChatBridge.LOGGER.info("✦ Web bridge set to ON by {}", ctx.getSource().getTextName());
            return Command.SINGLE_SUCCESS;
        }));

        web.then(Commands.literal("off").executes(ctx -> {
            WebSocketUtil.setState(WebSocketUtil.WebState.OFF);
            ctx.getSource().sendSuccess(
                    () -> Component.literal("§c✦ Web bridge OFF - website shows offline"),
                    true
            );
            CaaatChatBridge.LOGGER.info("✦ Web bridge set to OFF by {}", ctx.getSource().getTextName());
            return Command.SINGLE_SUCCESS;
        }));

        web.then(Commands.literal("readonly").executes(ctx -> {
            WebSocketUtil.setState(WebSocketUtil.WebState.READONLY);
            ctx.getSource().sendSuccess(
                    () -> Component.literal("§e✦ Web bridge READONLY - website can receive but not send"),
                    true
            );
            CaaatChatBridge.LOGGER.info("✦ Web bridge set to READONLY by {}", ctx.getSource().getTextName());
            return Command.SINGLE_SUCCESS;
        }));

        root.then(web);

        // /bridge status
        root.then(Commands.literal("status").executes(ctx -> {
            WebSocketUtil.WebState webState = WebSocketUtil.state;
            boolean ws = WebSocketUtil.isConnected();

            String webColour = switch (webState) {
                case ON       -> "§a";
                case READONLY -> "§e";
                case OFF      -> "§c";
            };
            String webLabel = switch (webState) {
                case ON       -> "on (live)";
                case READONLY -> "readonly";
                case OFF      -> "off";
            };
            String wsColour = ws ? "§a" : "§c";
            String wsLabel  = ws ? "connected" : "disconnected";

            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§7✦ Bridge status\n" +
                            "§7  web:       " + webColour + webLabel + "\n" +
                            "§7  websocket: " + wsColour + wsLabel
            ), false);

            return Command.SINGLE_SUCCESS;
        }));

        event.getDispatcher().register(root);
    }
}