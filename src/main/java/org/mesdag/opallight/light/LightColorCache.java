package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongConsumer;

public final class LightColorCache {
    public static final LightColorCache INSTANCE = new LightColorCache();

    /// 每个通道用十六位存储；已提交分段只整体替换，网格工作线程可安全持有旧分段。
    private final Long2ObjectOpenHashMap<Long2LongOpenHashMap> sections = new Long2ObjectOpenHashMap<>();

    private LightColorCache() {}

    boolean isEmpty() {
        return sections.isEmpty();
    }

    public boolean hasColorNear(BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = pos.getX() + dx, y = pos.getY() + dy, z = pos.getZ() + dz;
                    Long2LongOpenHashMap section = sections.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
                    if (section != null && section.containsKey(BlockPos.asLong(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    /// 方块实体由独立渲染器绘制，采样自身与六个相邻位置的彩光。
    public long colorAtBlockEntity(BlockPos pos) {
        long color = colorAt(pos.getX(), pos.getY(), pos.getZ());
        color = maxChannels(color, colorAt(pos.getX() + 1, pos.getY(), pos.getZ()));
        color = maxChannels(color, colorAt(pos.getX() - 1, pos.getY(), pos.getZ()));
        color = maxChannels(color, colorAt(pos.getX(), pos.getY() + 1, pos.getZ()));
        color = maxChannels(color, colorAt(pos.getX(), pos.getY() - 1, pos.getZ()));
        color = maxChannels(color, colorAt(pos.getX(), pos.getY(), pos.getZ() + 1));
        return maxChannels(color, colorAt(pos.getX(), pos.getY(), pos.getZ() - 1));
    }

    private long colorAt(int x, int y, int z) {
        Long2LongOpenHashMap section = sections.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        return section == null ? 0 : section.get(BlockPos.asLong(x, y, z));
    }

    /// 方块实体顶点与普通方块遮罩使用相同的八点插值位置。
    public long sample(double x, double y, double z) {
        double gx = x - 0.5, gy = y - 0.5, gz = z - 0.5;
        int bx = (int) Math.floor(gx), by = (int) Math.floor(gy), bz = (int) Math.floor(gz);
        float fx = (float) (gx - bx), fy = (float) (gy - by), fz = (float) (gz - bz);
        float red = 0, green = 0, blue = 0;
        for (int dx = 0; dx <= 1; dx++) {
            float wx = dx == 0 ? 1 - fx : fx;
            for (int dy = 0; dy <= 1; dy++) {
                float wy = dy == 0 ? 1 - fy : fy;
                for (int dz = 0; dz <= 1; dz++) {
                    float weight = wx * wy * (dz == 0 ? 1 - fz : fz);
                    long color = colorAt(bx + dx, by + dy, bz + dz);
                    red += channel(color, 32) * weight;
                    green += channel(color, 16) * weight;
                    blue += channel(color, 0) * weight;
                }
            }
        }
        return pack(red, green, blue);
    }

    private static long maxChannels(long a, long b) {
        return Math.max((a >>> 32) & 65535L, (b >>> 32) & 65535L) << 32
                | Math.max((a >>> 16) & 65535L, (b >>> 16) & 65535L) << 16
                | Math.max(a & 65535L, b & 65535L);
    }

    static long addPacked(long previous, float red, float green, float blue) {
        return pack(channel(previous, 32) + red, channel(previous, 16) + green, channel(previous, 0) + blue);
    }

    record SectionUpdate(long key, @Nullable Long2LongOpenHashMap colors,
                         int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}

    /// 工作线程只读已发布的分段，计算准确的变化边界；null 表示完全没有变化。
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

    private static long pack(float red, float green, float blue) {
        return (long) (Math.min(1.0F, red) * 65535.0F + 0.5F) << 32
                | (long) (Math.min(1.0F, green) * 65535.0F + 0.5F) << 16
                | (long) (Math.min(1.0F, blue) * 65535.0F + 0.5F);
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
