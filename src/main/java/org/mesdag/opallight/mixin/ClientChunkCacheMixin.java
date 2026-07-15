package org.mesdag.opallight.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import org.mesdag.opallight.light.LightManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/// 补偿视距或视图中心变化时的“静默区块卸载”。
///
/// 客户端区块缓存是比实际视距更大的环形槽位表。长距离传送只更新视图中心并逐步用
/// 新区块覆盖槽位；没有被新坐标映射命中的旧槽位不会立刻调用 `ClientLevel.unload`，
/// 但 `getChunk(..., false)` 已经因超出新中心范围而不可见。若只监听视距变化，这些
/// 旧槽位会让光源索引残留数千个远端灯笼。
///
/// 两个注入点都必须在原版方法尾部：先让新中心/新 Storage 生效，再以原版查询结果
/// 与 Mod 的已加载集合做差，才能安全移除不可达光源、RGB section 与 VBO。
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Inject(method = "updateViewCenter", at = @At("TAIL"))
    private void reconcileAfterViewCenterChange(int chunkX, int chunkZ, CallbackInfo ci) {
        opallight$reconcileLoadedChunks();
    }

    @Inject(method = "updateViewRadius", at = @At("TAIL"))
    private void reconcileAfterViewRadiusChange(int viewDistance, CallbackInfo ci) {
        opallight$reconcileLoadedChunks();
    }

    @Unique
    private void opallight$reconcileLoadedChunks() {
        ClientChunkCache cache = (ClientChunkCache) (Object) this;
        if (cache.getLevel() instanceof ClientLevel level) {
            LightManager.reconcileLoadedChunks(level);
        }
    }
}
