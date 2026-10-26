package org.mesdag.opallight.compat;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.mesdag.opallight.OpalLight;

@EventBusSubscriber(modid = OpalLight.MODID, value = Dist.CLIENT)
public final class LightIntegrations {
    private LightIntegrations() {}

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (ModList.get().isLoaded("curios")) CuriosLightIntegration.register();
        });
    }
}
