package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

/** 从一个不可变发布代派生的写时复制 RGB 候选态。 */
final class MutableRgbCandidate {
    private final FrozenRgbState base;
    private final ComponentCandidate light;
    private final ComponentCandidate directEmission;
    private boolean sealed;

    MutableRgbCandidate(FrozenRgbState base) {
        this.base = base;
        light = new ComponentCandidate(base::lightSectionView, base::forEachLightSectionKey);
        directEmission = new ComponentCandidate(
                base::directEmissionSectionView, base::forEachDirectEmissionSectionKey);
    }

    int getLight(long pos) {
        return light.get(pos);
    }

    int getDirectEmission(long pos) {
        return directEmission.get(pos);
    }

    boolean setLight(long pos, int value) {
        ensureWritable();
        return light.set(pos, value);
    }

    boolean setDirectEmission(long pos, int value) {
        ensureWritable();
        return directEmission.set(pos, value);
    }

    int lightSectionCount() {
        return light.sectionCount();
    }

    int directEmissionSectionCount() {
        return directEmission.sectionCount();
    }

    void forEachLightSectionKey(LongConsumer consumer) {
        light.forEachSectionKey(consumer);
    }

    void forEachDirectEmissionSectionKey(LongConsumer consumer) {
        directEmission.forEachSectionKey(consumer);
    }

    void forEachNonZeroLight(long sectionKey, FrozenRgbState.LightConsumer consumer) {
        light.forEachNonZero(sectionKey, consumer);
    }

    void forEachNonZeroDirectEmission(long sectionKey, FrozenRgbState.LightConsumer consumer) {
        directEmission.forEachNonZero(sectionKey, consumer);
    }

    /** 删除指定 chunk 的传播光 section，并报告位于 X/Z 外缘上的非零值。 */
    int removeLightChunk(int chunkX, int chunkZ, FrozenRgbState.LightConsumer removedBoundary) {
        ensureWritable();
        return light.removeChunk(chunkX, chunkZ, removedBoundary, true);
    }

    /** 删除指定 chunk 的直接发光 section；直接发光不参与跨 chunk 边界回灌。 */
    int removeDirectEmissionChunk(int chunkX, int chunkZ) {
        ensureWritable();
        return directEmission.removeChunk(chunkX, chunkZ, null, false);
    }

    FrozenRgbState freeze() {
        ensureWritable();
        sealed = true;
        if (!light.changed() && !directEmission.changed()) {
            return base;
        }
        Long2ObjectOpenHashMap<RgbSectionView> lights = light.freezeSections();
        Long2ObjectOpenHashMap<RgbSectionView> direct = directEmission.freezeSections();
        return lights.isEmpty() && direct.isEmpty()
                ? FrozenRgbState.EMPTY
                : new FrozenRgbState(lights, direct);
    }

    int lightCloneCountForTest() {
        return light.cloneCount;
    }

    int directEmissionCloneCountForTest() {
        return directEmission.cloneCount;
    }

    Object lightSectionIdentityForTest(long sectionKey) {
        return light.identity(sectionKey);
    }

    Object directEmissionSectionIdentityForTest(long sectionKey) {
        return directEmission.identity(sectionKey);
    }

    private void ensureWritable() {
        if (sealed) {
            throw new IllegalStateException("RGB candidate 已冻结，不能继续修改或再次发布");
        }
    }

    /** 单个传播分量的 base+overlay；删除表是对 base section 的 tombstone。 */
    private static final class ComponentCandidate {
        private final LongFunction<RgbSectionView> baseLookup;
        private final Consumer<LongConsumer> baseKeyTraversal;
        private final Long2ObjectOpenHashMap<MutableSection> modified = new Long2ObjectOpenHashMap<>();
        private final LongOpenHashSet removed = new LongOpenHashSet();
        private int cloneCount;

        private ComponentCandidate(LongFunction<RgbSectionView> baseLookup,
                                   Consumer<LongConsumer> baseKeyTraversal) {
            this.baseLookup = baseLookup;
            this.baseKeyTraversal = baseKeyTraversal;
        }

        private int get(long pos) {
            RgbSectionView payload = payload(PackedPosition.sectionKey(pos));
            return payload == null ? 0 : payload.get(PackedPosition.index(pos));
        }

        private boolean set(long pos, int light) {
            int value = light & 0xFFF;
            long sectionKey = PackedPosition.sectionKey(pos);
            int index = PackedPosition.index(pos);
            RgbSectionView current = payload(sectionKey);
            int previous = current == null ? 0 : current.get(index);
            if (previous == value) {
                return false;
            }

            MutableSection writable = modified.get(sectionKey);
            if (writable == null) {
                if (current == null) {
                    // value 必然非零，否则前面的同值判断已经返回。
                    writable = new MutableSection();
                } else {
                    writable = new MutableSection(current);
                    cloneCount++;
                }
                modified.put(sectionKey, writable);
                removed.remove(sectionKey);
            }
            writable.set(index, value);
            if (writable.isEmpty()) {
                modified.remove(sectionKey);
                if (baseLookup.apply(sectionKey) != null) {
                    removed.add(sectionKey);
                }
            }
            return true;
        }

        private int sectionCount() {
            return effectiveKeys().size();
        }

        private void forEachSectionKey(LongConsumer consumer) {
            effectiveKeys().forEach(consumer);
        }

        private void forEachNonZero(long sectionKey, FrozenRgbState.LightConsumer consumer) {
            RgbSectionView payload = payload(sectionKey);
            if (payload != null) {
                payload.forEachNonZero(sectionKey, consumer);
            }
        }

        private int removeChunk(int chunkX, int chunkZ, FrozenRgbState.LightConsumer consumer,
                                boolean boundaryOnly) {
            LongOpenHashSet keys = effectiveKeys();
            int removedCount = 0;
            for (long sectionKey : keys) {
                if (PackedPosition.sectionX(sectionKey) != chunkX || PackedPosition.sectionZ(sectionKey) != chunkZ) {
                    continue;
                }
                RgbSectionView payload = payload(sectionKey);
                if (consumer != null) {
                    payload.forEachNonZero(sectionKey, (pos, value) -> {
                        int localX = PackedPosition.x(pos) & 15;
                        int localZ = PackedPosition.z(pos) & 15;
                        if (!boundaryOnly || localX == 0 || localX == 15 || localZ == 0 || localZ == 15) {
                            consumer.accept(pos, value);
                        }
                    });
                }
                modified.remove(sectionKey);
                if (baseLookup.apply(sectionKey) != null) {
                    removed.add(sectionKey);
                }
                removedCount++;
            }
            return removedCount;
        }

        private RgbSectionView payload(long sectionKey) {
            MutableSection changed = modified.get(sectionKey);
            if (changed != null) {
                return changed;
            }
            return removed.contains(sectionKey) ? null : baseLookup.apply(sectionKey);
        }

        private LongOpenHashSet effectiveKeys() {
            LongOpenHashSet keys = new LongOpenHashSet();
            baseKeyTraversal.accept(keys::add);
            keys.removeAll(removed);
            keys.addAll(modified.keySet());
            return keys;
        }

        private boolean changed() {
            return !modified.isEmpty() || !removed.isEmpty();
        }

        private Long2ObjectOpenHashMap<RgbSectionView> freezeSections() {
            Long2ObjectOpenHashMap<RgbSectionView> sections = new Long2ObjectOpenHashMap<>();
            baseKeyTraversal.accept(key -> {
                if (!removed.contains(key)) {
                    sections.put(key, baseLookup.apply(key));
                }
            });
            sections.putAll(modified);
            return sections;
        }

        private Object identity(long sectionKey) {
            RgbSectionView payload = payload(sectionKey);
            return payload == null ? null : payload.identityToken();
        }
    }

    /**
     * 仅候选态持有写能力。freeze 后外层 candidate 被 sealed；FrozenRgbState 只把它
     * 当作 {@link RgbSectionView} 保存，因此不会暴露此处的私有 set 方法。
     */
    private static final class MutableSection implements RgbSectionView {
        private final short[] values = new short[4096];
        private final Object identityToken = new Object();
        private int nonZeroCount;

        private MutableSection() {
        }

        private MutableSection(RgbSectionView source) {
            source.copyValuesTo(values);
            nonZeroCount = source.nonZeroCount();
        }

        @Override
        public int get(int index) {
            return values[index] & 0xFFFF;
        }

        private void set(int index, int value) {
            int previous = values[index] & 0xFFFF;
            values[index] = (short) value;
            if (previous == 0 && value != 0) {
                nonZeroCount++;
            } else if (previous != 0 && value == 0) {
                nonZeroCount--;
            }
        }

        private boolean isEmpty() {
            return nonZeroCount == 0;
        }

        @Override
        public int nonZeroCount() {
            return nonZeroCount;
        }

        @Override
        public Object identityToken() {
            return identityToken;
        }

        @Override
        public void copyValuesTo(short[] destination) {
            if (destination.length != values.length) {
                throw new IllegalArgumentException("目标数组长度必须为 4096");
            }
            System.arraycopy(values, 0, destination, 0, values.length);
        }

        @Override
        public void forEachNonZero(long sectionKey, FrozenRgbState.LightConsumer consumer) {
            for (int index = 0; index < values.length; index++) {
                int value = values[index] & 0xFFFF;
                if (value != 0) {
                    consumer.accept(PackedPosition.fromSectionIndex(sectionKey, index), value);
                }
            }
        }
    }
}
