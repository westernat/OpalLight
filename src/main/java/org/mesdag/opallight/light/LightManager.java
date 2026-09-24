package org.mesdag.opallight.light;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.mesdag.opallight.OpalLight;

import java.io.IOException;
import java.util.Map;

@EventBusSubscriber(modid = OpalLight.MODID, value = Dist.CLIENT)
public final class LightManager {
    static ShaderInstance lightMaskShader;

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

    private static final Map<BlockState, OpalColor> colorCache = new Reference2ObjectOpenHashMap<>();

    public static @Nullable ObjectIntPair<OpalColor> colorWithEmissive(Level level, BlockPos pos, BlockState state) {
        int emission = state.getLightEmission(level, pos);
        if (emission > 0) {
            OpalColor color = colorCache.get(state);
            if (color == null) {
                color = LightDataLoader.INSTANCE.getColor(state);
                if (color == null) {
                    color = OpalColor.EMPTY;
                }
                colorCache.put(state, color);
            }
            if (color != OpalColor.EMPTY) {
                return new ObjectIntImmutablePair<>(color, emission);
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void registerClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((a, b, c, d, e, f) -> LightDataLoader.INSTANCE.reload(a, b, c, d, e, f)
                .thenRun(() -> Minecraft.getInstance().execute(LightManager::beginResourceReload)));
    }

    private static boolean reloadInProgress;

    public static boolean isReloadInProgress() {
        return reloadInProgress;
    }

    private static void beginResourceReload() {
        boolean previousReloadInProgress = reloadInProgress;
        reloadInProgress = false;
        colorCache.clear();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        int changedSources = LightPropagator.refreshDefinitions(level);
        if (changedSources == 0) {
            reloadInProgress = previousReloadInProgress && LightPropagator.hasPendingUpdates();
            return;
        }
        LightMaskMeshCache.beginDefinitionReload();
        reloadInProgress = LightPropagator.hasPendingUpdates();
    }

    @SubscribeEvent
    public static void clientTick$Pre(ClientTickEvent.Pre event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
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
            LightColorCache.INSTANCE.clearSection(SectionPos.of(cp.x, sy, cp.z));
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
        }
    }

    /// 通过 Mixin 接入渲染流程以兼容其他渲染模组。
    public static void render(Matrix4f viewMatrix, Camera camera) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        /// 工作线程完成后立即衔接网格构建，不必等下一次客户端刻。
        updateLighting(level);
        LightMaskMeshCache.draw(viewMatrix, camera);
    }

    private static void updateLighting(ClientLevel level) {
        LightPropagator.flushPending(level, !LightMaskMeshCache.hasMeshes());
        if (reloadInProgress) reloadInProgress = LightPropagator.hasPendingUpdates();
    }

    public static void captureTerrainFog() {
        LightMaskRenderer.captureTerrainFog();
    }
}
