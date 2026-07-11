package org.mesdag.opallight.mixin;

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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;

@Mixin(LightEngine.class)
public abstract class LightEngineMixin {
    @Unique
    private static final Set<ChunkPos> opalLight$affectedChunks = new HashSet<>();
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
        opalLight$affectedChunks.clear();
        for (long packedPos : blockNodesToCheck) {
            int cx = SectionPos.blockToSectionCoord(BlockPos.getX(packedPos));
            int cz = SectionPos.blockToSectionCoord(BlockPos.getZ(packedPos));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    opalLight$affectedChunks.add(new ChunkPos(cx + dx, cz + dz));
                }
            }
        }
    }

    @Inject(method = "runLightUpdates", at = @At("TAIL"))
    private void onTail(CallbackInfoReturnable<Integer> cir) {
        if (opalLight$isInValid || opalLight$affectedChunks.isEmpty()) return;
        LightPropagator.forceRepropagate((ClientLevel) chunkSource.getLevel(), opalLight$affectedChunks);
    }
}
