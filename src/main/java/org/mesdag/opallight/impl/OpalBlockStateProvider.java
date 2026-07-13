package org.mesdag.opallight.impl;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
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
        ConfiguredModel lanternModel = new ConfiguredModel(new ModelFile.UncheckedModelFile(ResourceLocation.fromNamespaceAndPath(OpalLight.MODID, "block/lantern")));
        ConfiguredModel lanternHangingModel = new ConfiguredModel(new ModelFile.UncheckedModelFile(ResourceLocation.fromNamespaceAndPath(OpalLight.MODID, "block/lantern_hanging")));
        ResourceLocation lanternLayer0 = ResourceLocation.fromNamespaceAndPath(OpalLight.MODID, "item/lantern");
        ResourceLocation lanternLayer1 = ResourceLocation.fromNamespaceAndPath(OpalLight.MODID, "item/lantern_layer");
        ModelFile.UncheckedModelFile itemGenerated = new ModelFile.UncheckedModelFile(ResourceLocation.withDefaultNamespace("item/generated"));
        for (DyeColor color : OpalLight.COLORS) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(OpalLight.MODID, color.getName() + "_lantern");
            Block block = BuiltInRegistries.BLOCK.get(id);
            getVariantBuilder(block)
                    .partialState().with(LanternBlock.HANGING, false).setModels(lanternModel)
                    .partialState().with(LanternBlock.HANGING, true).setModels(lanternHangingModel);
            itemModels().getBuilder(id.getPath()).parent(itemGenerated)
                    .texture("layer0", lanternLayer0)
                    .texture("layer1", lanternLayer1);
        }
    }
}
