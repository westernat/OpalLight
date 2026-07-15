package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 从客户端主线程复制出的方块状态调色板。
 *
 * <p>类名保留 “BlockAndTintGetter” 是为了 TODO 6 的异步 mesh 快照继续复用同一世界视图；
 * 当前 RGB 阶段只实现原版遮挡算法实际需要的 {@link BlockGetter}，不伪造天空光、方块实体或
 * 生物群系着色。worker 持有的只有复制后的 {@link PalettedContainer}，没有 live level。</p>
 */
final class SnapshotBlockAndTintGetter implements BlockGetter, WorldLightSnapshot.AttenuationAccess {
    @FunctionalInterface
    interface EmissionResolver {
        int emission(BlockPos pos, BlockState state);
    }

    private static final Direction[] DIRECTIONS = Direction.values();
    private final int minBuildHeight;
    private final int height;
    private final Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sections;
    private final LongOpenHashSet airSections;
    private final Long2ObjectOpenHashMap<byte[]> attenuationCache = new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos fromPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos toPos = new BlockPos.MutableBlockPos();

    private SnapshotBlockAndTintGetter(
            int minBuildHeight,
            int height,
            Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sections,
            LongOpenHashSet airSections
    ) {
        this.minBuildHeight = minBuildHeight;
        this.height = height;
        // capture 为每个任务新建此 Map，getter 直接接管后只读，避免再复制一次 section 索引。
        this.sections = sections;
        this.airSections = airSections;
    }

    static Optional<WorldLightSnapshot> capture(
            ClientLevel level,
            LongSet loadedChunks,
            RgbLightEngine.PendingWork pendingWork,
            EmissionResolver emissionResolver,
            int maxSections
    ) {
        long started = System.nanoTime();
        int minSectionY = level.getMinSection();
        int maxSectionYExclusive = minSectionY + level.getSectionsCount();
        long[] changedPositions = pendingWork.changedPositions();
        Optional<LongOpenHashSet> captureDomain = WorldLightSnapshot.sectionsToCaptureWithinBudget(
                changedPositions, loadedChunks, minSectionY, maxSectionYExclusive, maxSections
        );
        if (captureDomain.isEmpty()) {
            return Optional.empty();
        }
        LongOpenHashSet sectionKeys = captureDomain.orElseThrow();
        LongOpenHashSet capturedSectionKeys = new LongOpenHashSet();
        LongOpenHashSet airSections = new LongOpenHashSet();
        LongOpenHashSet missingChunks = new LongOpenHashSet();
        LongOpenHashSet blockEntitySections = new LongOpenHashSet();
        Long2ObjectOpenHashMap<LevelChunk> capturedChunks = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<PalettedContainer<BlockState>> copiedSections = new Long2ObjectOpenHashMap<>();
        for (long sectionKey : sectionKeys) {
            int sectionX = PackedPosition.sectionX(sectionKey);
            int sectionY = PackedPosition.sectionY(sectionKey);
            int sectionZ = PackedPosition.sectionZ(sectionKey);
            long chunkKey = ChunkPos.asLong(sectionX, sectionZ);
            @Nullable LevelChunk chunk = capturedChunks.get(chunkKey);
            if (chunk == null) {
                if (missingChunks.contains(chunkKey)) {
                    continue;
                }
                chunk = level.getChunkSource().getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    missingChunks.add(chunkKey);
                    continue;
                }
                capturedChunks.put(chunkKey, chunk);
                for (BlockPos blockEntityPos : chunk.getBlockEntitiesPos()) {
                    blockEntitySections.add(PackedPosition.sectionKey(blockEntityPos.asLong()));
                }
            }
            int sectionIndex = level.getSectionIndexFromSectionY(sectionY);
            if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                continue;
            }
            if (blockEntitySections.contains(sectionKey)) {
                // 任意 Mod 的 getLightBlock/遮挡形状都可能读取 BlockEntity；无法冻结时必须精确同步回退。
                return Optional.empty();
            }
            LevelChunkSection section = chunk.getSection(sectionIndex);
            section.acquire();
            try {
                capturedSectionKeys.add(sectionKey);
                if (section.hasOnlyAir()) {
                    // 捕获域内的纯空气只需保存 section 键；缺失键仍表示域外封闭边界。
                    airSections.add(sectionKey);
                } else {
                    copiedSections.put(sectionKey, section.getStates().copy());
                }
            } finally {
                section.release();
            }
        }

        SnapshotBlockAndTintGetter getter = new SnapshotBlockAndTintGetter(
                level.getMinBuildHeight(), level.getHeight(), copiedSections, airSections
        );
        Long2IntOpenHashMap emissions = new Long2IntOpenHashMap();
        emissions.defaultReturnValue(0);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long packedPos : changedPositions) {
            if (!capturedSectionKeys.contains(PackedPosition.sectionKey(packedPos))) {
                continue;
            }
            pos.set(packedPos);
            int emission = emissionResolver.emission(pos, getter.getBlockState(pos));
            if (emission != 0) {
                emissions.put(packedPos, emission);
            }
        }
        Long2IntOpenHashMap boundarySeeds;
        if (pendingWork.hasBaseLight()) {
            boundarySeeds = WorldLightSnapshot.captureBoundarySeeds(
                    capturedSectionKeys, pendingWork::baseLight
            );
        } else {
            // 冷启动的旧 RGB 光场为空，边界值必然全零，无需逐面扫描捕获域。
            boundarySeeds = new Long2IntOpenHashMap();
            boundarySeeds.defaultReturnValue(0);
        }
        return Optional.of(new WorldLightSnapshot(
                level.getMinBuildHeight(),
                level.getHeight(),
                capturedSectionKeys,
                emissions,
                boundarySeeds,
                getter,
                System.nanoTime() - started
        ));
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        long sectionKey = PackedPosition.sectionKey(pos.asLong());
        @Nullable PalettedContainer<BlockState> section = sections.get(sectionKey);
        if (section == null) {
            return airSections.contains(sectionKey)
                    ? Blocks.AIR.defaultBlockState()
                    : Blocks.BEDROCK.defaultBlockState();
        }
        return section.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return minBuildHeight;
    }

    @Override
    public int attenuation(long from, long to, int direction) {
        int cached = targetAttenuation(to);
        if (cached <= 16) {
            return cached;
        }
        fromPos.set(from);
        toPos.set(to);
        BlockState fromState = getBlockState(fromPos);
        BlockState toState = getBlockState(toPos);
        int opacity = Math.max(1, toState.getLightBlock(this, toPos));
        return LightEngine.getLightBlockInto(
                this,
                fromState,
                fromPos,
                toState,
                toPos,
                DIRECTIONS[direction],
                opacity
        );
    }

    /**
     * 缓存只由目标方块决定的常见衰减。只有使用遮光形状的半透明方块返回 17，
     * 继续执行依赖来源面和方向的完整原版形状计算。
     */
    private int targetAttenuation(long pos) {
        long sectionKey = PackedPosition.sectionKey(pos);
        byte[] section = attenuationCache.computeIfAbsent(sectionKey, ignored -> new byte[4096]);
        int index = PackedPosition.index(pos);
        int cached = section[index] & 0xFF;
        if (cached != 0) {
            return cached;
        }

        toPos.set(pos);
        BlockState state = getBlockState(toPos);
        int opacity = Math.max(1, state.getLightBlock(this, toPos));
        int value;
        if (opacity >= 15) {
            value = 16;
        } else if (!state.canOcclude() || !state.useShapeForLightOcclusion()) {
            value = opacity;
        } else {
            value = 17;
        }
        section[index] = (byte) value;
        return value;
    }
}
