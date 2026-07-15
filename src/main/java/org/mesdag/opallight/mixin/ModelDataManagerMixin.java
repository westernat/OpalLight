package org.mesdag.opallight.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.client.model.data.ModelDataManager;
import org.mesdag.opallight.light.LightManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/// 将 NeoForge 独立的 ModelData 刷新通知同步到彩光遮罩网格。
///
/// 放在方法尾部可以保证 NeoForge 已经验证线程和所属 level，也已把方块位置加入
/// 它自己的待刷新集合。此混入只增加 OpalLight 的网格失效通知，不改变 NeoForge 缓存时序。
@Mixin(value = ModelDataManager.class, remap = false)
public abstract class ModelDataManagerMixin {
    @Inject(method = "requestRefresh", at = @At("TAIL"))
    private void refreshOpalLightMesh(BlockEntity blockEntity, CallbackInfo ci) {
        if (blockEntity.getLevel() instanceof ClientLevel level) {
            LightManager.modelDataChanged(level, blockEntity.getBlockPos());
        }
    }
}
