package org.mesdag.opallight.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.mesdag.opallight.light.LightManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void captureColoredLightChange(
            BlockPos pos,
            BlockState state,
            boolean isMoving,
        CallbackInfoReturnable<BlockState> cir
    ) {
        BlockState previous = cir.getReturnValue();
        if (previous != null && level instanceof ClientLevel clientLevel) {
            LightManager.blockChanged(clientLevel, pos, previous, state);
        }
    }
}
