package dev.caaat.caaat_chat_bridge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(CaaatChatBridge.MOD_ID)
public class CaaatChatBridge {

    public static final String MOD_ID = "caaat_chat_bridge";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public CaaatChatBridge(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        modEventBus.addListener(this::setup);
        LOGGER.info("✦ Caaat Chat Bridge loading...");
    }
    private void setup(FMLCommonSetupEvent event) {
        LOGGER.info("✦ Caaat Chat Bridge ready!");
    }
}