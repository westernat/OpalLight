package org.mesdag.opallight.light.data;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.Registry;
import net.minecraft.tags.TagKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class LightTagRules<T> {
    private final Registry<T> registry;
    private List<Map.Entry<TagKey<T>, List<DynamicLightDefinition>>> tags = List.of();
    private final Map<T, List<DynamicLightDefinition>> candidates = new Reference2ObjectOpenHashMap<>();

    LightTagRules(Registry<T> registry) {
        this.registry = registry;
    }

    void replace(Map<TagKey<T>, List<DynamicLightDefinition>> definitions) {
        tags = definitions.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .map(entry -> Map.entry(entry.getKey(), List.copyOf(entry.getValue()))).toList();
        invalidate();
    }

    void invalidate() {
        candidates.clear();
    }

    List<DynamicLightDefinition> get(T value) {
        if (tags.isEmpty()) return List.of();
        return candidates.computeIfAbsent(value, key -> {
            var holder = registry.wrapAsHolder(key);
            var matches = new ArrayList<DynamicLightDefinition>();
            for (var entry : tags) if (holder.is(entry.getKey())) matches.addAll(entry.getValue());
            return List.copyOf(matches);
        });
    }
}
