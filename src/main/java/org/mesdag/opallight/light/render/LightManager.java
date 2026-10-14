package org.mesdag.opallight.light.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.mesdag.opallight.OpalLight;
import org.mesdag.opallight.light.data.LightDataLoader;
import org.mesdag.opallight.light.engine.LightColorCache;
import org.mesdag.opallight.light.engine.LightPropagator;

import java.io.IOException;
import java.util.OptionalLong;
import java.util.function.Supplier;

@Mod.EventBusSubscriber(modid = OpalLight.MODID, value = Dist.CLIENT)
public final class LightManager {
    static LightMaskShader lightMaskShader;
    private static boolean reloadInProgress;
    private static @Nullable Boolean previousShaderPackMode;
    private static Matrix4f capturedViewMatrix, capturedProjectionMatrix;

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        Minecraft.getInstance().execute(LightDataLoader.INSTANCE::invalidateTags);
    }

    @SubscribeEvent
    public static void clientTick$Post(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        updateShaderPackMode();
        // 实体更新后采集本刻位置，先发布已完成的批次再追赶最新移动。
        updateLighting(level);
        LightPropagator.updateDynamicSources(level);
        LightPropagator.scheduleCyclingSources(level);
        updateLighting(level);
    }

    @SubscribeEvent
    public static void chunk$Load(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide()) return;
        ChunkPos cp = event.getChunk().getPos();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        LightPropagator.indexChunk(level, cp);
        LightMaskMeshCache.refreshLoadedChunk(cp);
        LightPropagator.scheduleChunkAndNeighbors(cp.x, cp.z);
    }

    @SubscribeEvent
    public static void chunk$Unload(ChunkEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        ChunkPos cp = event.getChunk().getPos();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        int minSY = SectionPos.blockToSectionCoord(level.getMinBuildHeight());
        int maxSY = SectionPos.blockToSectionCoord(level.getMaxBuildHeight() - 1);
        for (int sy = minSY; sy <= maxSY; sy++) {
            SectionPos section = SectionPos.of(cp.x, sy, cp.z);
            if (LightColorCache.INSTANCE.clearSection(section))
                LightMaskMeshCache.markDirtyAroundSection(section.asLong());
        }
        LightMaskMeshCache.discardChunk(cp);
        LightPropagator.forgetChunk(cp);
        LightPropagator.scheduleChunkAndNeighbors(cp.x, cp.z);
    }

    @SubscribeEvent
    public static void level$Unload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            LightColorCache.INSTANCE.clearAll();
            LightPropagator.clearAllSources();
            LightMaskMeshCache.invalidate();
            LightMaskRenderer.clearTerrainFog();
            reloadInProgress = false;
            previousShaderPackMode = null;
        }
    }

    public static void captureMatrices(Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        capturedViewMatrix = viewMatrix;
        capturedProjectionMatrix = projectionMatrix;
    }

    // 在光影包合成后叠加，避免彩光被当作材质颜色再次参与光照。
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || lightMaskShader == null || capturedViewMatrix == null || capturedProjectionMatrix == null)
            return;
        updateLighting(level);
        LightMaskMeshCache.draw(capturedViewMatrix, capturedProjectionMatrix, event.getCamera(), lightMaskShader,
            reloadInProgress, LightPropagator::isGroupPropagationPending);
    }

    public static void captureTerrainFog() {
        LightMaskRenderer.captureTerrainFog();
    }

    private static void beginResourceReload() {
        boolean previousReloadInProgress = reloadInProgress;
        reloadInProgress = false;
        LightPropagator.clearDefinitions();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        int changedSources = LightPropagator.refreshDefinitions(level) + LightPropagator.updateDynamicSources(level);
        if (changedSources == 0) {
            reloadInProgress = previousReloadInProgress && LightPropagator.hasPendingUpdates();
            return;
        }
        LightMaskMeshCache.beginDefinitionReload();
        reloadInProgress = LightPropagator.hasPendingUpdates();
    }

    private static void updateShaderPackMode() {
        boolean current = LightShaderCompatibility.isShaderPackInUse();
        if (previousShaderPackMode != null && previousShaderPackMode != current) {
            LightMaskMeshCache.invalidate();
            LightColorCache.INSTANCE.forEachSection(LightMaskMeshCache::markDirtyAroundSection);
        }
        previousShaderPackMode = current;
    }

    private static void updateLighting(ClientLevel level) {
        if (LightPropagator.consumeDynamicInvalidation()) LightMaskMeshCache.invalidateDynamicBatch();
        LightMaskMeshCache.finishCompletedMeshes(level);
        boolean firstMeshPending = !LightMaskMeshCache.hasMeshes();
        Supplier<OptionalLong> preferred = () ->
            LightMaskMeshCache.firstVisiblePropagationGroup(level, LightPropagator::isGroupPropagationPending);
        // 网格构建期间允许下一批传播计算，结果留待当前网格整批发布后提交。
        if (LightMaskMeshCache.hasPendingDynamicBatch()) {
            LightPropagator.flushPending(level, firstMeshPending, preferred, false);
            return;
        }
        var commit = LightPropagator.flushPending(level, firstMeshPending, preferred);
        if (commit != null) {
            LightMaskMeshCache.beginDirtyBatch(commit.dynamic());
            try {
                for (var update : commit.updates()) {
                    LightColorCache.INSTANCE.apply(update);
                    LightMaskMeshCache.markColorChanged(update.minX(), update.minY(), update.minZ(),
                        update.maxX(), update.maxY(), update.maxZ(), commit.dynamic());
                }
            } finally {
                LightMaskMeshCache.endDirtyBatch();
            }
            // 发布完成后才允许捕获下一份传播快照。
            LightPropagator.flushPending(level, firstMeshPending, preferred, false);
        }
        if (!LightPropagator.hasPendingUpdates()) LightMaskMeshCache.resumeDynamicBatch();
        if (reloadInProgress) reloadInProgress = LightPropagator.hasPendingUpdates();
    }

    @Mod.EventBusSubscriber(modid = OpalLight.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBusEvents {
        @SubscribeEvent
        public static void registerShaders(RegisterShadersEvent event) throws IOException {
            event.registerShader(new ShaderInstance(
                event.getResourceProvider(),
                OpalLight.asResource("light_mask"),
                LightMaskMeshBuilder.VERTEX_FORMAT
            ), shader -> lightMaskShader = new LightMaskShader(shader));
        }

        @SubscribeEvent
        public static void registerClientReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((barrier, resources, preparationProfiler, reloadProfiler,
                                          backgroundExecutor, gameExecutor) ->
                LightDataLoader.INSTANCE.reload(barrier, resources, preparationProfiler, reloadProfiler,
                        backgroundExecutor, gameExecutor)
                    .thenRunAsync(LightManager::beginResourceReload, gameExecutor));
        }
    }
}
