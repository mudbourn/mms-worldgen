package info.mudbourn.mmsworldgen;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MmsWorldgen implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("mms_worldgen");

    @Override
    public void onInitialize() {
        LOG.info("MMS Worldgen loaded.");
    }
}
