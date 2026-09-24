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
    private static final Map<BlockState, OpalColor> colorCache = new Reference2ObjectOpenHashMap<>();

    private LightSourceDefinitions() {}

    static void clear() {
        colorCache.clear();
    }

    static @Nullable ObjectIntPair<OpalColor> colorWithEmissive(Level level, BlockPos pos, BlockState state) {
        int emission = state.getLightEmission(level, pos);
        if (emission <= 0) return null;
        OpalColor color = colorCache.get(state);
        if (color == null) {
            color = LightDataLoader.INSTANCE.getColor(state);
            if (color == null) color = OpalColor.EMPTY;
            colorCache.put(state, color);
        }
        return color == OpalColor.EMPTY ? null : new ObjectIntImmutablePair<>(color, emission);
    }
}
