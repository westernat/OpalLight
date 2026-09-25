package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/// 解析后的彩光定义及方块状态查询缓存。
final class LightSourceDefinitions {
    private static final Map<BlockState, LightProfile> colorCache = new Reference2ObjectOpenHashMap<>();

    private LightSourceDefinitions() {}

    static void clear() {
        colorCache.clear();
    }

    static @Nullable ObjectIntPair<LightProfile> colorWithEmissive(Level level, BlockPos pos, BlockState state) {
        int emission = state.getLightEmission(level, pos);
        if (emission <= 0) return null;
        LightProfile profile = colorCache.get(state);
        if (profile == null) {
            profile = LightDataLoader.INSTANCE.getProfile(state);
            if (profile == null) profile = new LightProfile(OpalColor.EMPTY, null);
            colorCache.put(state, profile);
        }
        return profile.color() == OpalColor.EMPTY ? null : new ObjectIntImmutablePair<>(profile, emission);
    }
}
