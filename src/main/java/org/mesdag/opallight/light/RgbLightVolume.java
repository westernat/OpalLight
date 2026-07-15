package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.function.LongConsumer;

/// 由 Minecraft 原生尺寸区段组成的稀疏光照存储。
///
/// 已经发布或进入快照的 section 由 [FrozenRgbState] 持有；后续写入通过
/// [MutableRgbCandidate] 对首次修改的 section 做一次写时复制。这样全量快照和
/// 整代恢复只复制稀疏索引，不再深复制每个 `short[4096]`。
final class RgbLightVolume {
    interface LightConsumer {
        void accept(long pos, int light);
    }

    static final class ChunkSnapshot {
        private final FrozenRgbState state;

        private ChunkSnapshot(FrozenRgbState state) {
            this.state = state;
        }

        int sectionCount() {
            return state.lightSectionCount();
        }

        int get(long pos) {
            return state.getLight(pos);
        }

        void forEachSectionKey(LongConsumer consumer) {
            state.forEachLightSectionKey(consumer);
        }
    }

    private final LongOpenHashSet dirtySections = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<long[]> increaseQueued = new Long2ObjectOpenHashMap<>();
    private final boolean trackDirty;
    private FrozenRgbState published = FrozenRgbState.EMPTY;
    private MutableRgbCandidate mutable = published.mutableCandidate();

    RgbLightVolume() {
        this(true);
    }

    RgbLightVolume(boolean trackDirty) {
        this.trackDirty = trackDirty;
    }

    int get(long pos) {
        return mutable.getLight(pos);
    }

    boolean set(long pos, int light) {
        if (!mutable.setLight(pos, light)) {
            return false;
        }
        if (trackDirty) {
            dirtySections.add(PackedPosition.sectionKey(pos));
        }
        return true;
    }

    int allocatedSectionCount() {
        return mutable.lightSectionCount();
    }

    boolean markIncreaseQueued(long pos) {
        if (get(pos) == 0) {
            return false;
        }
        long sectionKey = PackedPosition.sectionKey(pos);
        int index = PackedPosition.index(pos);
        int word = index >>> 6;
        long mask = 1L << (index & 63);
        long[] flags = increaseQueued.computeIfAbsent(sectionKey, ignored -> new long[64]);
        if ((flags[word] & mask) != 0L) {
            return false;
        }
        flags[word] |= mask;
        return true;
    }

    void clearIncreaseQueued(long pos) {
        long[] flags = increaseQueued.get(PackedPosition.sectionKey(pos));
        if (flags == null) {
            return;
        }
        int index = PackedPosition.index(pos);
        flags[index >>> 6] &= ~(1L << (index & 63));
    }

    void clear() {
        published = FrozenRgbState.EMPTY;
        mutable = published.mutableCandidate();
        dirtySections.clear();
        increaseQueued.clear();
    }

    void drainDirtySections(LongConsumer consumer) {
        dirtySections.forEach(consumer);
        dirtySections.clear();
    }

    void clearChunk(int chunkX, int chunkZ) {
        removeChunk(chunkX, chunkZ, null);
    }

    ChunkSnapshot snapshotChunk(int chunkX, int chunkZ) {
        freezePublished();
        return new ChunkSnapshot(published.chunkSubset(chunkX, chunkZ));
    }

    ChunkSnapshot snapshotAll() {
        freezePublished();
        return new ChunkSnapshot(published);
    }

    /// 合并一个区块子快照。区块流式恢复不是整代替换，仍逐个非零值写入候选态；
    /// 全量 generation 命中应使用 [#replaceAll(ChunkSnapshot)] 的 O(section index) 路径。
    void restoreChunk(ChunkSnapshot snapshot) {
        snapshot.state.forEachLightSectionKey(sectionKey -> snapshot.state.forEachNonZeroLight(sectionKey, this::set));
    }

    /// 整体激活一个不可变状态；不复制 RGB 载荷，也不在此处伪造 dirty section。
    void replaceAll(ChunkSnapshot snapshot) {
        published = snapshot.state;
        mutable = published.mutableCandidate();
        increaseQueued.clear();
    }

    void removeChunk(int chunkX, int chunkZ, LightConsumer removedBoundary) {
        mutable.removeLightChunk(chunkX, chunkZ, removedBoundary == null
                ? null
                : removedBoundary::accept);
        increaseQueued.keySet().removeIf(sectionKey -> PackedPosition.sectionX(sectionKey) == chunkX && PackedPosition.sectionZ(sectionKey) == chunkZ);
    }

    void removeChunks(LongSet chunkKeys, LightConsumer removedBoundary) {
        for (long chunkKey : chunkKeys) {
            int chunkX = (int) chunkKey;
            int chunkZ = (int) (chunkKey >> 32);
            mutable.removeLightChunk(chunkX, chunkZ, removedBoundary == null ? null : (pos, light) -> {
                int localX = PackedPosition.x(pos) & 15;
                int localZ = PackedPosition.z(pos) & 15;
                boolean outerBoundary = localX == 0 && !chunkKeys.contains(chunkKey(chunkX - 1, chunkZ)) ||
                        localX == 15 && !chunkKeys.contains(chunkKey(chunkX + 1, chunkZ)) ||
                        localZ == 0 && !chunkKeys.contains(chunkKey(chunkX, chunkZ - 1)) ||
                        localZ == 15 && !chunkKeys.contains(chunkKey(chunkX, chunkZ + 1));
                if (outerBoundary) {
                    removedBoundary.accept(pos, light);
                }
            });
        }
        increaseQueued.keySet().removeIf(sectionKey -> chunkKeys.contains(chunkKey(PackedPosition.sectionX(sectionKey), PackedPosition.sectionZ(sectionKey))));
    }

    void forEachSectionKey(LongConsumer consumer) {
        mutable.forEachLightSectionKey(consumer);
    }

    void forEachNonZero(long sectionKey, LightConsumer consumer) {
        mutable.forEachNonZeroLight(sectionKey, consumer::accept);
    }

    /// 在一个传播批次收敛后冻结 active CPU 状态，后续写入自动走 section COW。
    void freezePublished() {
        published = mutable.freeze();
        mutable = published.mutableCandidate();
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkZ << 32 | chunkX & 0xFFFF_FFFFL;
    }
}
