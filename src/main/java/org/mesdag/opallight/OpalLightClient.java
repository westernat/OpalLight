package org.mesdag.opallight;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.DyeColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

@Mod(value = OpalLight.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = OpalLight.MODID, value = Dist.CLIENT)
public class OpalLightClient {
    public OpalLightClient() {}

    @SubscribeEvent
    public static void registerColorHandlers$Block(RegisterColorHandlersEvent.Block event) {
        for (DyeColor color : OpalLight.COLORS) {
            event.register((state, level, pos, tintIndex) -> {
                if (tintIndex == 0) {
                    return FastColor.ARGB32.opaque(color.getTextureDiffuseColor());
                }
                return -1;
            }, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(OpalLight.MODID, color.getName() + "_lantern")));
        }
    }

    @SubscribeEvent
    public static void registerColorHandlers$Item(RegisterColorHandlersEvent.Item event) {
        for (DyeColor color : OpalLight.COLORS) {
            event.register((stack, tintIndex) -> {
                if (tintIndex == 1) {
                    return FastColor.ARGB32.opaque(color.getTextureDiffuseColor());
                }
                return -1;
            }, BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(OpalLight.MODID, color.getName() + "_lantern")));
        }
    }
}
