package info.mudbourn.mmsworldgen;

import info.mudbourn.mmsworldgen.budget.BudgetRegistry;
import info.mudbourn.mmsworldgen.command.WorldgenCommand;
import info.mudbourn.mmsworldgen.config.WorldgenConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MmsWorldgen implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("mms_worldgen");

    @Override
    public void onInitialize() {
        BudgetRegistry.setConfig(WorldgenConfig.load());
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> WorldgenCommand.register(dispatcher)
        );
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> BudgetRegistry.clear());
        LOG.info("MMS Worldgen loaded.");
    }
}
