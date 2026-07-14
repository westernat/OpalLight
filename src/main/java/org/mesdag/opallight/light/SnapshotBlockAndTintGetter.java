package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.material.FluidState;

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
    private final BlockPos.MutableBlockPos fromPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos toPos = new BlockPos.MutableBlockPos();

    private SnapshotBlockAndTintGetter(
            int minBuildHeight,
            int height,
            Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sections
    ) {
        this.minBuildHeight = minBuildHeight;
        this.height = height;
        this.sections = new Long2ObjectOpenHashMap<>(sections);
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
        Optional<LongOpenHashSet> captureDomain = WorldLightSnapshot.sectionsToCaptureWithinBudget(
                pendingWork.changedPositions(), loadedChunks, minSectionY, maxSectionYExclusive, maxSections
        );
        if (captureDomain.isEmpty()) {
            return Optional.empty();
        }
        LongOpenHashSet sectionKeys = captureDomain.orElseThrow();
        Long2ObjectOpenHashMap<PalettedContainer<BlockState>> copiedSections = new Long2ObjectOpenHashMap<>();
        for (long sectionKey : sectionKeys) {
            int sectionX = PackedPosition.sectionX(sectionKey);
            int sectionY = PackedPosition.sectionY(sectionKey);
            int sectionZ = PackedPosition.sectionZ(sectionKey);
            LevelChunk chunk = level.getChunkSource().getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
            if (chunk == null) {
                continue;
            }
            int sectionIndex = level.getSectionIndexFromSectionY(sectionY);
            if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                continue;
            }
            boolean containsBlockEntity = chunk.getBlockEntitiesPos().stream().anyMatch(
                    blockEntityPos -> blockEntityPos.getY() >> 4 == sectionY
            );
            if (containsBlockEntity) {
                // 任意 Mod 的 getLightBlock/遮挡形状都可能读取 BlockEntity；无法冻结时必须精确同步回退。
                return Optional.empty();
            }
            LevelChunkSection section = chunk.getSection(sectionIndex);
            section.acquire();
            try {
                copiedSections.put(sectionKey, section.getStates().copy());
            } finally {
                section.release();
            }
        }

        SnapshotBlockAndTintGetter getter = new SnapshotBlockAndTintGetter(
                level.getMinBuildHeight(), level.getHeight(), copiedSections
        );
        Long2IntOpenHashMap emissions = new Long2IntOpenHashMap();
        emissions.defaultReturnValue(0);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long packedPos : pendingWork.changedPositions()) {
            if (!copiedSections.containsKey(PackedPosition.sectionKey(packedPos))) {
                continue;
            }
            pos.set(packedPos);
            int emission = emissionResolver.emission(pos, level.getBlockState(pos));
            if (emission != 0) {
                emissions.put(packedPos, emission);
            }
        }
        return Optional.of(new WorldLightSnapshot(
                level.getMinBuildHeight(),
                level.getHeight(),
                copiedSections.keySet(),
                emissions,
                WorldLightSnapshot.captureBoundarySeeds(copiedSections.keySet(), pendingWork::baseLight),
                getter,
                System.nanoTime() - started
        ));
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        PalettedContainer<BlockState> section = sections.get(PackedPosition.sectionKey(pos.asLong()));
        if (section == null) {
            return Blocks.BEDROCK.defaultBlockState();
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
}
