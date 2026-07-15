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
    private boolean sealed;

    MutableRgbCandidate(FrozenRgbState base) {
        this.base = base;
        light = new ComponentCandidate(base::lightSectionView, base::forEachLightSectionKey);
    }

    int getLight(long pos) {
        return light.get(pos);
    }

    boolean setLight(long pos, int value) {
        ensureWritable();
        return light.set(pos, value);
    }

    int lightSectionCount() {
        return light.sectionCount();
    }

    void forEachLightSectionKey(LongConsumer consumer) {
        light.forEachSectionKey(consumer);
    }

    void forEachNonZeroLight(long sectionKey, FrozenRgbState.LightConsumer consumer) {
        light.forEachNonZero(sectionKey, consumer);
    }

    /** 删除指定 chunk 的传播光 section，并报告位于 X/Z 外缘上的非零值。 */
    void removeLightChunk(int chunkX, int chunkZ, FrozenRgbState.LightConsumer removedBoundary) {
        ensureWritable();
        light.removeChunk(chunkX, chunkZ, removedBoundary);
    }

    FrozenRgbState freeze() {
        ensureWritable();
        sealed = true;
        if (!light.changed()) {
            return base;
        }
        Long2ObjectOpenHashMap<RgbSectionView> lights = light.freezeSections();
        return lights.isEmpty()
                ? FrozenRgbState.EMPTY
                : new FrozenRgbState(lights);
    }

    private void ensureWritable() {
        if (sealed) {
            throw new IllegalStateException("RGB candidate is frozen and cannot be modified or published again");
        }
    }

    /** 单个传播分量的 base+overlay；删除表是对 base section 的 tombstone。 */
    private static final class ComponentCandidate {
        private final LongFunction<RgbSectionView> baseLookup;
        private final Consumer<LongConsumer> baseKeyTraversal;
        private final Long2ObjectOpenHashMap<MutableSection> modified = new Long2ObjectOpenHashMap<>();
        private final LongOpenHashSet removed = new LongOpenHashSet();

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

        private void removeChunk(int chunkX, int chunkZ, FrozenRgbState.LightConsumer consumer) {
            LongOpenHashSet keys = effectiveKeys();
            for (long sectionKey : keys) {
                if (PackedPosition.sectionX(sectionKey) != chunkX || PackedPosition.sectionZ(sectionKey) != chunkZ) {
                    continue;
                }
                RgbSectionView payload = payload(sectionKey);
                if (consumer != null) {
                    payload.forEachNonZero(sectionKey, (pos, value) -> {
                        int localX = PackedPosition.x(pos) & 15;
                        int localZ = PackedPosition.z(pos) & 15;
                        if (localX == 0 || localX == 15 || localZ == 0 || localZ == 15) {
                            consumer.accept(pos, value);
                        }
                    });
                }
                modified.remove(sectionKey);
                if (baseLookup.apply(sectionKey) != null) {
                    removed.add(sectionKey);
                }
            }
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

    }

    /**
     * 仅候选态持有写能力。freeze 后外层 candidate 被 sealed；FrozenRgbState 只把它
     * 当作 {@link RgbSectionView} 保存，因此不会暴露此处的私有 set 方法。
     */
    private static final class MutableSection implements RgbSectionView {
        private final short[] values;
        private int nonZeroCount;

        private MutableSection() {
            values = new short[4096];
        }

        private MutableSection(RgbSectionView source) {
            this();
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
        public void copyValuesTo(short[] destination) {
            if (destination.length != values.length) {
                throw new IllegalArgumentException("Destination array length must be 4096");
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
