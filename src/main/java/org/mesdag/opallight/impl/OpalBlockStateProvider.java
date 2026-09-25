package org.mesdag.opallight.impl;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraftforge.client.model.generators.BlockStateProvider;
import net.minecraftforge.client.model.generators.ConfiguredModel;
import net.minecraftforge.client.model.generators.ModelFile;
import net.minecraftforge.common.data.ExistingFileHelper;
import org.mesdag.opallight.OpalLight;

public class OpalBlockStateProvider extends BlockStateProvider {
    public OpalBlockStateProvider(PackOutput output, ExistingFileHelper exFileHelper) {
        super(output, OpalLight.MODID, exFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        /// 每种颜色一套模型，直接继承原版灯笼模板并挂上自己的贴图，不再依赖方块/物品染色。
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
