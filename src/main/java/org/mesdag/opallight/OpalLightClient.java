package org.mesdag.opallight;

import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.DyeColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/// 1.20.1 只允许一个 {@code @Mod} 类，客户端内容改为在客户端侧独立订阅注册。
@Mod.EventBusSubscriber(modid = OpalLight.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OpalLightClient {
    private OpalLightClient() {}

    @SubscribeEvent
    public static void registerColorHandlers$Block(RegisterColorHandlersEvent.Block event) {
        for (DyeColor color : OpalLight.COLORS) {
            event.register((BlockColor) (state, level, pos, tintIndex) -> {
                if (tintIndex == 0) {
                    return opaqueDyeColor(color);
                }
                return -1;
            }, BuiltInRegistries.BLOCK.get(OpalLight.asResource(color.getName() + "_lantern")));
        }
    }

    @SubscribeEvent
    public static void registerColorHandlers$Item(RegisterColorHandlersEvent.Item event) {
        for (DyeColor color : OpalLight.COLORS) {
            event.register((ItemColor) (stack, tintIndex) -> {
                if (tintIndex == 1) {
                    return opaqueDyeColor(color);
                }
                return -1;
            }, BuiltInRegistries.ITEM.get(OpalLight.asResource(color.getName() + "_lantern")));
        }
    }

    /// 1.20.1 还没有 {@code DyeColor#getTextureDiffuseColor()}，由颜色分量自行打包。
    private static int opaqueDyeColor(DyeColor color) {
        float[] rgb = color.getTextureDiffuseColors();
        return FastColor.ARGB32.color(255, channel(rgb[0]), channel(rgb[1]), channel(rgb[2]));
    }

    private static int channel(float value) {
        return Math.min(255, Math.max(0, Math.round(value * 255.0F)));
    }
}
