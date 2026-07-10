package org.mesdag.opallight;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = OpalLight.MODID, dist = Dist.CLIENT)
public class OpalLight {
    public static final String MODID = "opallight";
    public static final Logger LOGGER = LoggerFactory.getLogger("OpalLight");

    public OpalLight() {
        LOGGER.info("Colorful light system loading!");
    }

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
