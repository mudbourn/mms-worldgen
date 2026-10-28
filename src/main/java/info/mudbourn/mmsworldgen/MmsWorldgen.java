package info.mudbourn.mmsworldgen;

import info.mudbourn.mmsworldgen.command.WorldgenCommand;
import info.mudbourn.mmsworldgen.config.WorldgenConfig;
import info.mudbourn.mmsworldgen.parity.Parity;
import info.mudbourn.mmsworldgen.sched.Sched;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MmsWorldgen implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("mms_worldgen");
    public static WorldgenConfig config;

    @Override
    public void onInitialize() {
        config = WorldgenConfig.load();
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> WorldgenCommand.register(dispatcher)
        );
        Parity.init();
        Sched.init();
        LOG.info("MMS Worldgen loaded.");
    }
}
