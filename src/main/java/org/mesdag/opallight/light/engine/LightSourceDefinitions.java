package org.mesdag.opallight.light.engine;

import it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.mesdag.opallight.light.color.OpalColor;
import org.mesdag.opallight.light.data.DynamicLightDefinition;
import org.mesdag.opallight.light.data.LightDataLoader;
import org.mesdag.opallight.light.data.LightProfile;

import java.util.Map;

final class LightSourceDefinitions {
    private static final Map<BlockState, LightProfile> colorCache = new Reference2ObjectOpenHashMap<>();
    private static final Map<DynamicLightDefinition, LightProfile> dynamicProfiles = new Reference2ObjectOpenHashMap<>();

    private LightSourceDefinitions() {
    }

    static void clear() {
        colorCache.clear();
        dynamicProfiles.clear();
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

    static LightProfile profile(DynamicLightDefinition definition) {
        return dynamicProfiles.computeIfAbsent(definition, value -> new LightProfile(value.color(), value.cycle().orElse(null)));
    }

    static @Nullable ObjectIntPair<LightProfile> itemWithEmissive(Level level, BlockPos pos, ItemStack stack, @Nullable Entity owner, boolean burning) {
        if (stack.isEmpty()) return null;
        var definitions = LightDataLoader.INSTANCE;
        if (definitions.hasItemDefinition(stack.getItem())) {
            var definition = definitions.getItemDefinition(stack, owner, burning);
            return definition == null || definition.lightLevel() == 0 ? null
                : new ObjectIntImmutablePair<>(profile(definition), definition.lightLevel());
        }
        return stack.getItem() instanceof BlockItem blockItem
            ? colorWithEmissive(level, pos, blockItem.getBlock().defaultBlockState()) : null;
    }
}
