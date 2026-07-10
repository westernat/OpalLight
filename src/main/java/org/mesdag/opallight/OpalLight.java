package org.mesdag.opallight;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = OpalLight.MODID, dist = Dist.CLIENT)
public class OpalLight {
    public static final String MODID = "opallight";

    public OpalLight() {}

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
