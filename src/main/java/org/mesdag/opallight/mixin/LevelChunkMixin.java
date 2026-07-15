package org.mesdag.opallight.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
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

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    @Final
    Level level;

    // @Inject会产生新本地变量，故改用@ModifyReturnValue
    @ModifyReturnValue(method = "setBlockState", at = @At(value = "RETURN", ordinal = 3))
    private BlockState captureColoredLightChange(
            BlockState original,
            @Local(argsOnly = true) BlockPos pos,
            @Local(argsOnly = true) BlockState state
    ) {
        if (level instanceof ClientLevel clientLevel) {
            LightManager.blockChanged(clientLevel, pos, original, state);
        }
        return original;
    }
}
