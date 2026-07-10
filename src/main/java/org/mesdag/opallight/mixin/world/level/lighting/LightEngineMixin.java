package org.mesdag.opallight.mixin.world.level.lighting;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.mesdag.opallight.light.LightPropagator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;

@Mixin(LightEngine.class)
public abstract class LightEngineMixin {
    @Final
    @Shadow
    protected LightChunkGetter chunkSource;

    @Final
    @Shadow
    private LongOpenHashSet blockNodesToCheck;

    @SuppressWarnings("all")
    @Inject(method = "runLightUpdates", at = @At("HEAD"))
    private void onHead(CallbackInfoReturnable<Integer> cir, @Share("chunks") LocalRef<Set<ChunkPos>> ref) {
        if (blockNodesToCheck.isEmpty() ||
                !(chunkSource instanceof ClientChunkCache) ||
                !((LightEngine<?, ?>) (Object) this instanceof BlockLightEngine)
        ) return;
        Set<ChunkPos> affectedChunks = new HashSet<>();
        for (long packedPos : blockNodesToCheck) {
            int cx = SectionPos.blockToSectionCoord(BlockPos.getX(packedPos));
            int cz = SectionPos.blockToSectionCoord(BlockPos.getZ(packedPos));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    affectedChunks.add(new ChunkPos(cx + dx, cz + dz));
                }
            }
        }
        ref.set(affectedChunks);
    }

    @Inject(method = "runLightUpdates", at = @At("TAIL"))
    private void onTail(CallbackInfoReturnable<Integer> cir, @Share("chunks") LocalRef<Set<ChunkPos>> ref) {
        Set<ChunkPos> affectedChunks = ref.get();
        if (affectedChunks == null || affectedChunks.isEmpty()) return;
        LightPropagator.forceRepropagate((ClientLevel) chunkSource.getLevel(), affectedChunks);
    }
}
