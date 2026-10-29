package info.mudbourn.mmsworldgen.mixin;

import com.mojang.datafixers.DataFixer;
import info.mudbourn.mmsworldgen.MmsWorldgen;
import info.mudbourn.mmsworldgen.templates.TemplateCache;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateManagerCacheMixin {

    @Shadow
    @Final
    private static FileToIdConverter RESOURCE_LISTER;

    @Shadow
    @Final
    private DataFixer fixerUpper;

    @Shadow
    private ResourceManager resourceManager;

    @Shadow
    public abstract StructureTemplate readStructure(CompoundTag tag);

    @Inject(method = "loadFromResource", at = @At("HEAD"), cancellable = true)
    private void mmswg$diskCache(Identifier id, CallbackInfoReturnable<Optional<StructureTemplate>> cir) {
        if (!MmsWorldgen.config.templates.diskCache) {
            return;
        }
        Optional<StructureTemplate> template = TemplateCache.load(
            resourceManager,
            RESOURCE_LISTER.idToFile(id),
            id,
            fixerUpper,
            this::readStructure
        );
        if (template.isPresent()) {
            cir.setReturnValue(template);
        }
    }
}
