package org.mesdag.opallight.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.mesdag.opallight.light.LightColorCache;
import org.mesdag.opallight.light.LightMaskMeshCache;
import org.mesdag.opallight.light.LightPropagator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelChunk.class)
public class LevelChunkMixin {
    @Shadow
    @Final
    Level level;

    @ModifyExpressionValue(method = "setBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LightEngine;hasDifferentLightProperties(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean wrapHasDifferentLightProperties(boolean original, @Local(argsOnly = true) BlockPos pos, @Local(argsOnly = true) BlockState state) {
        if (level.isClientSide) {
            if (LightColorCache.INSTANCE.hasColorNear(pos)) LightMaskMeshCache.invalidateChangedGeometry(pos.asLong());
            long key = LightColorCache.sectionKey(pos);
            boolean affected = LightPropagator.isNearAffectedSection(key);
            boolean sourceChanged = LightPropagator.updateSource(level, pos, state);
            if (sourceChanged || affected && original) LightPropagator.scheduleAround(pos);
        }
        return original;
    }
}
