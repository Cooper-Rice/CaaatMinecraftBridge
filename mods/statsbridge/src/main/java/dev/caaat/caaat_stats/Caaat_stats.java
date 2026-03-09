package dev.caaat.caaat_stats;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(Caaat_stats.MOD_ID)
public class Caaat_stats {

    public static final String MOD_ID = "caaat_stats";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public Caaat_stats(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        ServerEvents.register(modEventBus);
        LOGGER.info("✦ Caaat Stats loading...");
    }
}