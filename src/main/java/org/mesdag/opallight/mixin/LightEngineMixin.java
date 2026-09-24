package org.mesdag.opallight.mixin;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.mesdag.opallight.light.LightPropagator;
import org.mesdag.opallight.light.LightColorCache;
import org.mesdag.opallight.light.LightMaskMeshCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightEngine.class)
public abstract class LightEngineMixin {
    @Final
    @Shadow
    protected LightChunkGetter chunkSource;
    @Final
    @Shadow
    private LongOpenHashSet blockNodesToCheck;
    @Unique
    private boolean opalLight$isInValid;

    @SuppressWarnings("all")
    @Inject(method = "<init>", at = @At("TAIL"))
    private void check(CallbackInfo ci) {
        this.opalLight$isInValid = !(chunkSource instanceof ClientChunkCache) || !((LightEngine<?, ?>) (Object) this instanceof BlockLightEngine);
    }

    @Inject(method = "runLightUpdates", at = @At("HEAD"))
    private void onHead(CallbackInfoReturnable<Integer> cir) {
        if (opalLight$isInValid || blockNodesToCheck.isEmpty()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        for (long packedPos : blockNodesToCheck) {
            BlockPos pos = BlockPos.of(packedPos);
            boolean affected = LightPropagator.isNearAffectedSection(LightColorCache.sectionKey(pos));
            boolean sourceChanged = LightPropagator.updateSource(level, pos, level.getBlockState(pos));
            if (!affected && !sourceChanged) continue;
            if (affected && LightColorCache.INSTANCE.hasColorNear(pos)) {
                LightMaskMeshCache.markLightingChanged(packedPos);
            }
            /// 原版亮度变化只需要刷新模型顶点；彩光源变化才需要重新传播彩光。
            if (sourceChanged) {
                LightPropagator.scheduleAround(pos);
            }
        }
    }
}
