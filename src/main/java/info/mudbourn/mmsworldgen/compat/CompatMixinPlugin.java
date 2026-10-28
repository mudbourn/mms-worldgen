package info.mudbourn.mmsworldgen.compat;

import info.mudbourn.mmsworldgen.config.WorldgenConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class CompatMixinPlugin implements IMixinConfigPlugin {

    private WorldgenConfig.Compat compat;

    @Override
    public void onLoad(String mixinPackage) {
        compat = WorldgenConfig.load().compat;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains("Bwg")) {
            return compat.bwgBiomeCache && FabricLoader.getInstance().isModLoaded("biomeswevegone");
        }
        if (mixinClassName.contains("TerraBlender")) {
            return compat.terrablenderLockfree && FabricLoader.getInstance().isModLoaded("terrablender");
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
