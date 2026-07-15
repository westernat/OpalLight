package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.function.LongConsumer;

/**
 * 一代已经发布的不可变 RGB CPU 状态。
 *
 * <p>每个光照分量各自持有一份此状态，并使用稀疏 section 表保存非零区域。每个已分配
 * section 恰好保存 4096 个 {@code short}；发布后只共享只读 section，不再深复制载荷。</p>
 */
final class FrozenRgbState {
    @FunctionalInterface
    interface LightConsumer {
        void accept(long pos, int light);
    }

    static final FrozenRgbState EMPTY = new FrozenRgbState(new Long2ObjectOpenHashMap<>());

    private final Long2ObjectOpenHashMap<RgbSectionView> lightSections;

    FrozenRgbState(Long2ObjectOpenHashMap<RgbSectionView> lightSections) {
        // 防御性复制索引，避免构造方随后修改 map；section 只读视图仍可安全共享。
        this.lightSections = new Long2ObjectOpenHashMap<>(lightSections);
    }

    MutableRgbCandidate mutableCandidate() {
        return new MutableRgbCandidate(this);
    }

    int getLight(long pos) {
        return get(lightSections, pos);
    }

    int lightSectionCount() {
        return lightSections.size();
    }

    void forEachLightSectionKey(LongConsumer consumer) {
        lightSections.keySet().forEach(consumer);
    }

    void forEachNonZeroLight(long sectionKey, LightConsumer consumer) {
        forEachNonZero(lightSections.get(sectionKey), sectionKey, consumer);
    }

    /**
     * 创建一个仅含指定 chunk 的不可变子集。这里只复制很小的 section 索引，
     * RGB 载荷仍与原状态共享，因而不会复制 {@code short[4096]}。
     */
    FrozenRgbState chunkSubset(int chunkX, int chunkZ) {
        Long2ObjectOpenHashMap<RgbSectionView> lights = chunkSubset(lightSections, chunkX, chunkZ);
        return lights.isEmpty() ? EMPTY : new FrozenRgbState(lights);
    }

    RgbSectionView lightSectionView(long sectionKey) {
        return lightSections.get(sectionKey);
    }

    private static int get(Long2ObjectOpenHashMap<RgbSectionView> sections, long pos) {
        RgbSectionView section = sections.get(PackedPosition.sectionKey(pos));
        return section == null ? 0 : section.get(PackedPosition.index(pos));
    }

    private static void forEachNonZero(RgbSectionView payload, long sectionKey, LightConsumer consumer) {
        if (payload != null) {
            payload.forEachNonZero(sectionKey, consumer);
        }
    }

    private static Long2ObjectOpenHashMap<RgbSectionView> chunkSubset(
            Long2ObjectOpenHashMap<RgbSectionView> source, int chunkX, int chunkZ) {
        Long2ObjectOpenHashMap<RgbSectionView> subset = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<RgbSectionView> entry : source.long2ObjectEntrySet()) {
            long sectionKey = entry.getLongKey();
            if (PackedPosition.sectionX(sectionKey) == chunkX && PackedPosition.sectionZ(sectionKey) == chunkZ) {
                subset.put(sectionKey, entry.getValue());
            }
        }
        return subset;
    }

}

/**
 * 冻结状态内部唯一可见的 section 能力：只读与复制到调用方新数组。
 * 接口不返回源数组，也没有写方法，所以发布代无法被包内集成代码原地改写。
 */
interface RgbSectionView {
    int get(int index);

    int nonZeroCount();

    void copyValuesTo(short[] destination);

    void forEachNonZero(long sectionKey, FrozenRgbState.LightConsumer consumer);
}
