package org.mesdag.opallight.light.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;
import org.mesdag.opallight.light.engine.LightColorCache;

import java.util.BitSet;

// 范围包含两端；精确变化位集发布后不再修改，工作线程可复用旧快照。
record LightUpdateBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                         @Nullable Long2ObjectOpenHashMap<BitSet> changedSections) {
    LightUpdateBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this(minX, minY, minZ, maxX, maxY, maxZ, null);
    }

    static LightUpdateBounds from(LightColorCache.SectionUpdate update) {
        Long2ObjectOpenHashMap<BitSet> sections = null;
        // 整段都变化时，包围盒已足够精确，无需再逐位检查。
        if (update.changedPositions() != null && update.changedPositions().cardinality() < 4096) {
            sections = new Long2ObjectOpenHashMap<>(1);
            sections.put(update.key(), (BitSet) update.changedPositions().clone());
        }
        return new LightUpdateBounds(update.minX(), update.minY(), update.minZ(),
            update.maxX(), update.maxY(), update.maxZ(), sections);
    }

    LightUpdateBounds union(LightUpdateBounds other) {
        Long2ObjectOpenHashMap<BitSet> sections = null;
        if (changedSections != null && other.changedSections != null) {
            sections = new Long2ObjectOpenHashMap<>(changedSections);
            for (var entry : other.changedSections.long2ObjectEntrySet()) {
                BitSet previous = sections.get(entry.getLongKey());
                if (previous == null) sections.put(entry.getLongKey(), entry.getValue());
                else {
                    BitSet merged = (BitSet) previous.clone();
                    merged.or(entry.getValue());
                    sections.put(entry.getLongKey(), merged);
                }
            }
        }
        return new LightUpdateBounds(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
            Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ), sections);
    }

    boolean intersects(LightUpdateBounds other) {
        if (minX > other.maxX || maxX < other.minX || minY > other.maxY || maxY < other.minY
            || minZ > other.maxZ || maxZ < other.minZ) return false;
        if (changedSections == null) return true;
        int fromX = Math.max(minX, other.minX), toX = Math.min(maxX, other.maxX);
        int fromY = Math.max(minY, other.minY), toY = Math.min(maxY, other.maxY);
        int fromZ = Math.max(minZ, other.minZ), toZ = Math.min(maxZ, other.maxZ);
        for (int cy = fromY >> 4; cy <= toY >> 4; cy++) {
            for (int cz = fromZ >> 4; cz <= toZ >> 4; cz++) {
                for (int cx = fromX >> 4; cx <= toX >> 4; cx++) {
                    BitSet changed = changedSections.get(SectionPos.asLong(cx, cy, cz));
                    if (changed == null) continue;
                    int sx = cx << 4, sy = cy << 4, sz = cz << 4;
                    int x0 = Math.max(0, fromX - sx), x1 = Math.min(15, toX - sx);
                    int y0 = Math.max(0, fromY - sy), y1 = Math.min(15, toY - sy);
                    int z0 = Math.max(0, fromZ - sz), z1 = Math.min(15, toZ - sz);
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            int row = (y << 8) | (z << 4);
                            int next = changed.nextSetBit(row | x0);
                            if (next >= 0 && next <= (row | x1)) return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
