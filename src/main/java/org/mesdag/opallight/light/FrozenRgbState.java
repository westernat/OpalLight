package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.function.LongConsumer;

/**
 * 一代已经发布的不可变 RGB CPU 状态。
 *
 * <p>传播光与直接发光分别使用稀疏 section 表。每个已分配 section 恰好保存
 * 4096 个 {@code short}，因此这里报告的字节数是精确的 RGB 载荷字节数，不把
 * 哈希表对象头等 JVM 实现细节混入缓存权重。</p>
 */
final class FrozenRgbState {
    @FunctionalInterface
    interface LightConsumer {
        void accept(long pos, int light);
    }

    private static final long SECTION_BYTES = 4096L * Short.BYTES;
    static final FrozenRgbState EMPTY = new FrozenRgbState(
            new Long2ObjectOpenHashMap<>(), new Long2ObjectOpenHashMap<>());

    private final Long2ObjectOpenHashMap<RgbSectionView> lightSections;
    private final Long2ObjectOpenHashMap<RgbSectionView> directEmissionSections;

    FrozenRgbState(Long2ObjectOpenHashMap<RgbSectionView> lightSections,
                   Long2ObjectOpenHashMap<RgbSectionView> directEmissionSections) {
        // 防御性复制索引，避免构造方随后修改 map；section 只读视图仍可安全共享。
        this.lightSections = new Long2ObjectOpenHashMap<>(lightSections);
        this.directEmissionSections = new Long2ObjectOpenHashMap<>(directEmissionSections);
    }

    MutableRgbCandidate mutableCandidate() {
        return new MutableRgbCandidate(this);
    }

    int getLight(long pos) {
        return get(lightSections, pos);
    }

    int getDirectEmission(long pos) {
        return get(directEmissionSections, pos);
    }

    int lightSectionCount() {
        return lightSections.size();
    }

    int directEmissionSectionCount() {
        return directEmissionSections.size();
    }

    int sectionCount() {
        return lightSections.size() + directEmissionSections.size();
    }

    long lightByteCount() {
        return lightSections.size() * SECTION_BYTES;
    }

    long directEmissionByteCount() {
        return directEmissionSections.size() * SECTION_BYTES;
    }

    long byteCount() {
        return sectionCount() * SECTION_BYTES;
    }

    void forEachLightSectionKey(LongConsumer consumer) {
        lightSections.keySet().forEach(consumer);
    }

    void forEachDirectEmissionSectionKey(LongConsumer consumer) {
        directEmissionSections.keySet().forEach(consumer);
    }

    void forEachNonZeroLight(long sectionKey, LightConsumer consumer) {
        forEachNonZero(lightSections.get(sectionKey), sectionKey, consumer);
    }

    void forEachNonZeroDirectEmission(long sectionKey, LightConsumer consumer) {
        forEachNonZero(directEmissionSections.get(sectionKey), sectionKey, consumer);
    }

    /**
     * 创建一个仅含指定 chunk 的不可变子集。这里只复制很小的 section 索引，
     * RGB 载荷仍与原状态共享，因而不会复制 {@code short[4096]}。
     */
    FrozenRgbState chunkSubset(int chunkX, int chunkZ) {
        Long2ObjectOpenHashMap<RgbSectionView> lights = chunkSubset(lightSections, chunkX, chunkZ);
        Long2ObjectOpenHashMap<RgbSectionView> direct = chunkSubset(directEmissionSections, chunkX, chunkZ);
        return lights.isEmpty() && direct.isEmpty() ? EMPTY : new FrozenRgbState(lights, direct);
    }

    RgbSectionView lightSectionView(long sectionKey) {
        return lightSections.get(sectionKey);
    }

    RgbSectionView directEmissionSectionView(long sectionKey) {
        return directEmissionSections.get(sectionKey);
    }

    Object lightSectionIdentityForTest(long sectionKey) {
        return identity(lightSections.get(sectionKey));
    }

    Object directEmissionSectionIdentityForTest(long sectionKey) {
        return identity(directEmissionSections.get(sectionKey));
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

    private static Object identity(RgbSectionView payload) {
        return payload == null ? null : payload.identityToken();
    }
}

/**
 * 冻结状态内部唯一可见的 section 能力：只读与复制到调用方新数组。
 * 接口不返回源数组，也没有写方法，所以发布代无法被包内集成代码原地改写。
 */
interface RgbSectionView {
    int get(int index);

    int nonZeroCount();

    Object identityToken();

    void copyValuesTo(short[] destination);

    void forEachNonZero(long sectionKey, FrozenRgbState.LightConsumer consumer);
}
