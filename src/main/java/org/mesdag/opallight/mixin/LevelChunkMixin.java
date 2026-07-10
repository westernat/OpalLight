package org.mesdag.opallight.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.mesdag.opallight.light.LightColorCache;
import org.mesdag.opallight.light.LightPropagator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashSet;
import java.util.Set;

@Mixin(LevelChunk.class)
public class LevelChunkMixin {
    @Shadow
    @Final
    Level level;

    @ModifyExpressionValue(method = "setBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LightEngine;hasDifferentLightProperties(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean wrapHasDifferentLightProperties(boolean original, @Local(argsOnly = true) BlockPos pos) {
        if (level.isClientSide) {
            long key = LightColorCache.sectionKey(pos);
            if (LightPropagator.isSectionAffected(key)) {
                int cx = SectionPos.blockToSectionCoord(pos.getX());
                int cz = SectionPos.blockToSectionCoord(pos.getZ());
                Set<ChunkPos> affectedChunks = new HashSet<>();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        affectedChunks.add(new ChunkPos(cx + dx, cz + dz));
                    }
                }
                LightPropagator.forceRepropagate(level, affectedChunks);
            }
        }
        return original;
    }
}
