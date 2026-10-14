package org.mesdag.opallight;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = OpalLight.MODID)
public class OpalLight {
    public static final String MODID = "opallight";
    public static final Logger LOGGER = LoggerFactory.getLogger("OpalLight");

    public OpalLight(net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, OpalLightConfig.SPEC);
    }

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
