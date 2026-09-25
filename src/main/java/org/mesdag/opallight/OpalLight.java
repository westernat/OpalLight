package org.mesdag.opallight;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(OpalLight.MODID)
public class OpalLight {
    public static final String MODID = "opallight";
    public static final Logger LOGGER = LoggerFactory.getLogger("OpalLight");
    public static final DyeColor[] COLORS = {
            DyeColor.RED,
            DyeColor.ORANGE,
            DyeColor.YELLOW,
            DyeColor.GREEN,
            DyeColor.CYAN,
            DyeColor.BLUE,
            DyeColor.PURPLE
    };

    public OpalLight() {}

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
