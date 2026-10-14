package org.mesdag.opallight.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.mesdag.opallight.OpalLightConfig;
import org.mesdag.opallight.light.engine.LightColorCache;
import org.mesdag.opallight.light.render.LightWorld;
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
    private boolean wrapHasDifferentLightProperties(
            boolean original,
            @Local(argsOnly = true) BlockPos pos,
            @Local(argsOnly = true) BlockState state
    ) {
        if (OpalLightConfig.isEnabled() && level instanceof ClientLevel client) {
            var world = LightWorld.get(client);
            // 光源移除后旧几何仍可能留在缓存中，方块变化不能依赖当前是否有彩光。
            world.meshes().invalidateChangedGeometry(pos.asLong());
            long key = LightColorCache.sectionKey(pos);
            boolean affected = world.propagator().isNearAffectedSection(key);
            boolean sourceChanged = world.propagator().updateSource(level, pos, state);
            if (sourceChanged || affected && original) world.propagator().scheduleAround(pos);
        }
        return original;
    }
}
