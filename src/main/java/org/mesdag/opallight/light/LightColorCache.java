package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongConsumer;

public final class LightColorCache {
    public static final LightColorCache INSTANCE = new LightColorCache();

    // 已发布分段只替换、不修改，工作线程可持有旧引用。
    private final Long2ObjectOpenHashMap<Long2LongOpenHashMap> sections = new Long2ObjectOpenHashMap<>();
    private final LightColorSampler sampler = new LightColorSampler(this::colorAt, false);
    private final float[] sampled = new float[3];

    private LightColorCache() {}

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    public boolean hasColorNear(BlockPos pos) {
        int centerX = pos.getX(), centerY = pos.getY(), centerZ = pos.getZ();
        boolean sameSection = (centerX & 15) > 0 && (centerX & 15) < 15
            && (centerY & 15) > 0 && (centerY & 15) < 15
            && (centerZ & 15) > 0 && (centerZ & 15) < 15;
        Long2LongOpenHashMap local = sameSection
            ? sections.get(SectionPos.asLong(centerX >> 4, centerY >> 4, centerZ >> 4)) : null;
        if (sameSection && local == null) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = centerX + dx, y = centerY + dy, z = centerZ + dz;
                    Long2LongOpenHashMap section = sameSection ? local
                        : sections.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
                    if (section != null && section.containsKey(BlockPos.asLong(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    public long colorAtBlockEntity(BlockPos pos) {
        long color = colorAt(pos.getX(), pos.getY(), pos.getZ());
        color = LightColorData.maximum(color, colorAt(pos.getX() + 1, pos.getY(), pos.getZ()));
        color = LightColorData.maximum(color, colorAt(pos.getX() - 1, pos.getY(), pos.getZ()));
        color = LightColorData.maximum(color, colorAt(pos.getX(), pos.getY() + 1, pos.getZ()));
        color = LightColorData.maximum(color, colorAt(pos.getX(), pos.getY() - 1, pos.getZ()));
        color = LightColorData.maximum(color, colorAt(pos.getX(), pos.getY(), pos.getZ() + 1));
        return LightColorData.maximum(color, colorAt(pos.getX(), pos.getY(), pos.getZ() - 1));
    }

    private long colorAt(int x, int y, int z) {
        Long2LongOpenHashMap section = sections.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        return section == null ? 0 : section.get(BlockPos.asLong(x, y, z));
    }

    public long sample(double x, double y, double z) {
        return sample(Minecraft.getInstance().level, x, y, z);
    }

    long sample(@Nullable BlockGetter view, double x, double y, double z) {
        sampler.sample(view, x, y, z, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), sampled);
        return pack(sampled[0], sampled[1], sampled[2]);
    }

    record SectionUpdate(long key, @Nullable Long2LongOpenHashMap colors,
                         int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}

    static @Nullable SectionUpdate difference(long key, @Nullable Long2LongOpenHashMap old, @Nullable Long2LongOpenHashMap updated) {
        if (old == updated) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        if (old != null) {
            for (var entry : old.long2LongEntrySet()) {
                long pos = entry.getLongKey();
                if (updated != null && updated.getOrDefault(pos, -1L) == entry.getLongValue()) continue;
                minX = Math.min(minX, BlockPos.getX(pos)); maxX = Math.max(maxX, BlockPos.getX(pos));
                minY = Math.min(minY, BlockPos.getY(pos)); maxY = Math.max(maxY, BlockPos.getY(pos));
                minZ = Math.min(minZ, BlockPos.getZ(pos)); maxZ = Math.max(maxZ, BlockPos.getZ(pos));
            }
        }
        if (updated != null) {
            for (long pos : updated.keySet()) {
                if (old != null && old.containsKey(pos)) continue;
                minX = Math.min(minX, BlockPos.getX(pos)); maxX = Math.max(maxX, BlockPos.getX(pos));
                minY = Math.min(minY, BlockPos.getY(pos)); maxY = Math.max(maxY, BlockPos.getY(pos));
                minZ = Math.min(minZ, BlockPos.getZ(pos)); maxZ = Math.max(maxZ, BlockPos.getZ(pos));
            }
        }
        return minX == Integer.MAX_VALUE ? null
                : new SectionUpdate(key, updated, minX, minY, minZ, maxX, maxY, maxZ);
    }

    void apply(SectionUpdate update) {
        if (update.colors() == null || update.colors().isEmpty()) sections.remove(update.key());
        else sections.put(update.key(), update.colors());
    }

    static long pack(float red, float green, float blue) {
        return LightColorData.pack(red, green, blue);
    }

    public static float channel(long packed, int shift) {
        return ((packed >>> shift) & 65535L) / 65535.0F;
    }

    public boolean clearSection(SectionPos sp) {
        long key = sp.asLong();
        return sections.remove(key) != null;
    }

    public void clearAll() {
        sections.clear();
    }

    void forEachSection(LongConsumer consumer) {
        for (long key : sections.keySet()) consumer.accept(key);
    }

    Long2LongOpenHashMap getSection(long key) {
        return sections.get(key);
    }

    boolean hasSection(long key) {
        return sections.containsKey(key);
    }

    public static long sectionKey(BlockPos pos) {
        return SectionPos.asLong(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ())
        );
    }
}
