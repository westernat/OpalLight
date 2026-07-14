package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.function.LongPredicate;

/**
 * 比较 Mod 记录的加载集合与 Minecraft 当前可查询的区块，补偿没有走通常卸载回调的存储裁剪。
 *
 * <p>Minecraft 1.21.1 在收缩客户端视距时会直接替换 {@code ClientChunkCache.Storage}：
 * 超出新半径的旧槽位不一定逐个调用 {@code ClientLevel.unload}。如果只相信 unload
 * 回调，引擎会继续保留这些区块的 RGB 体素、光源与网格，最终表现为远处残影或空间透光。
 * 本类只做集合差分，不直接修改引擎状态，便于用纯单元测试锁定这个版本边界行为。
 */
final class ChunkLifecycleReconciler {
    private ChunkLifecycleReconciler() {
    }

    static LongOpenHashSet findUnavailable(LongSet trackedChunks, LongPredicate isAvailable) {
        LongOpenHashSet unavailable = new LongOpenHashSet();
        for (long chunkKey : trackedChunks) {
            if (!isAvailable.test(chunkKey)) {
                unavailable.add(chunkKey);
            }
        }
        return unavailable;
    }
}
