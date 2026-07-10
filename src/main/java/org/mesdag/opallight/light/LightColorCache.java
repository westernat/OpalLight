package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public final class LightColorCache {
    public static final LightColorCache INSTANCE = new LightColorCache();

    private final Map<Long, Map<Long, OpalColor>> sections = new Object2ObjectOpenHashMap<>(); // section pos -> block pos -> color

    private LightColorCache() {}

    public @Nullable OpalColor get(BlockPos pos) {
        long key = sectionKey(pos);
        Map<Long, OpalColor> section = sections.get(key);
        return section != null ? section.get(pos.asLong()) : null;
    }

    public void put(BlockPos pos, OpalColor color) {
        long key = sectionKey(pos);
        sections.computeIfAbsent(key, k -> new Object2ObjectOpenHashMap<>()).put(pos.asLong(), color);
        LightMaskMeshCache.markDirty();
    }

    public void remove(BlockPos pos) {
        long key = sectionKey(pos);
        Map<Long, OpalColor> section = sections.get(key);
        if (section != null) {
            section.remove(pos.asLong());
            if (section.isEmpty()) {
                sections.remove(key);
            }
            LightMaskMeshCache.markDirty();
        }
    }

    public void clearSection(SectionPos sp) {
        long key = sp.asLong();
        if (sections.remove(key) != null) {
            LightPropagator.clearSectionSources(key);
            LightMaskMeshCache.markDirty();
        }
    }

    public void clearAll() {
        if (!sections.isEmpty()) {
            LightMaskMeshCache.markDirty();
            sections.clear();
            LightPropagator.clearAllSources();
        }
    }

    public Map<Long, Map<Long, OpalColor>> getSections() {
        return sections;
    }

    public static long sectionKey(BlockPos pos) {
        return SectionPos.asLong(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ())
        );
    }
}
