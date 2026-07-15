package org.mesdag.opallight.impl;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.LanternBlock;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import org.mesdag.opallight.OpalLight;

public class OpalBlockStateProvider extends BlockStateProvider {
    public OpalBlockStateProvider(PackOutput output, ExistingFileHelper exFileHelper) {
        super(output, OpalLight.MODID, exFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        ModelFile lantern = new ModelFile.UncheckedModelFile(ResourceLocation.withDefaultNamespace("block/template_lantern"));
        ModelFile hangingLantern = new ModelFile.UncheckedModelFile(ResourceLocation.withDefaultNamespace("block/template_hanging_lantern"));
        ModelFile itemGenerated = new ModelFile.UncheckedModelFile(ResourceLocation.withDefaultNamespace("item/generated"));
        for (DyeColor color : OpalLight.COLORS) {
            String path = color.getName() + "_lantern";
            String hangingPath = color.getName() + "_hanging_lantern";
            ResourceLocation texture = OpalLight.asResource("block/" + path);
            getVariantBuilder(BuiltInRegistries.BLOCK.get(OpalLight.asResource(path)))
                    .partialState().with(LanternBlock.HANGING, false).setModels(new ConfiguredModel(models()
                            .getBuilder(path)
                            .parent(lantern)
                            .renderType("cutout")
                            .texture("lantern", texture)
                    ))
                    .partialState().with(LanternBlock.HANGING, true).setModels(new ConfiguredModel(models()
                            .getBuilder(hangingPath)
                            .parent(hangingLantern)
                            .renderType("cutout")
                            .texture("lantern", texture)
                    ));
            itemModels().getBuilder(path).parent(itemGenerated).texture("layer0", OpalLight.asResource("item/" + path));
        }
    }
}
