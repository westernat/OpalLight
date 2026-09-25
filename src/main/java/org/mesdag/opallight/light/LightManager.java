package org.mesdag.opallight.light;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.mesdag.opallight.OpalLight;

import java.io.IOException;
import java.util.OptionalLong;
import java.util.function.Supplier;

@Mod.EventBusSubscriber(modid = OpalLight.MODID, value = Dist.CLIENT)
public final class LightManager {
    static ShaderInstance lightMaskShader;

    @SubscribeEvent
    public static void clientTick$Pre(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        updateShaderPackMode();
        DynamicLightSources.update(level);
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
            if (LightColorCache.INSTANCE.clearSection(section)) LightMaskMeshCache.markDirtyAroundSection(section.asLong());
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

    private static boolean reloadInProgress;
    private static Boolean previousShaderPackMode;

    public static boolean isReloadInProgress() {
        return reloadInProgress;
    }

    private static void beginResourceReload() {
        boolean previousReloadInProgress = reloadInProgress;
        reloadInProgress = false;
        LightSourceDefinitions.clear();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        int changedSources = LightPropagator.refreshDefinitions(level) + DynamicLightSources.update(level);
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

    /// 通过 Mixin 接入渲染流程以兼容其他渲染模组。
    public static void render(Matrix4f viewMatrix, Camera camera) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || lightMaskShader == null) return;
        /// 工作线程完成后立即衔接网格构建，不必等下一次客户端刻。
        updateLighting(level);
        LightMaskMeshCache.draw(viewMatrix, camera, lightMaskShader, reloadInProgress,
                LightPropagator::isGroupPropagationPending);
    }

    private static void updateLighting(ClientLevel level) {
        boolean firstMeshPending = !LightMaskMeshCache.hasMeshes();
        Supplier<OptionalLong> preferred = () ->
                LightMaskMeshCache.firstVisiblePropagationGroup(level, LightPropagator::isGroupPropagationPending);
        var commit = LightPropagator.flushPending(level, firstMeshPending, preferred);
        if (commit != null) {
            LightMaskMeshCache.beginDirtyBatch();
            try {
                for (var update : commit.updates()) {
                    LightColorCache.INSTANCE.apply(update);
                    LightMaskMeshCache.markColorChanged(update.minX(), update.minY(), update.minZ(),
                            update.maxX(), update.maxY(), update.maxZ(), commit.dynamic());
                }
            } finally {
                LightMaskMeshCache.endDirtyBatch();
            }
            /// 发布完成后才允许捕获下一份传播快照。
            LightPropagator.flushPending(level, firstMeshPending, preferred);
        }
        if (reloadInProgress) reloadInProgress = LightPropagator.hasPendingUpdates();
    }

    public static void captureTerrainFog() {
        LightMaskRenderer.captureTerrainFog();
    }

    /// 1.20.1 的着色器与资源重载事件位于模组事件总线，只能放在独立的订阅类中。
    @Mod.EventBusSubscriber(modid = OpalLight.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBusEvents {
        @SuppressWarnings("deprecation")
        @SubscribeEvent
        public static void registerShaders(RegisterShadersEvent event) throws IOException {
            event.registerShader(new ShaderInstance(
                    event.getResourceProvider(),
                    /// VulkanMod 需要字符串形式的着色器名称。
                    "opallight:light_mask",
                    DefaultVertexFormat.POSITION_TEX_COLOR
            ), shader -> lightMaskShader = shader);
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
