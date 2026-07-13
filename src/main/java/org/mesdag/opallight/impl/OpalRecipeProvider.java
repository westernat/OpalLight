package org.mesdag.opallight.impl;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import org.mesdag.opallight.OpalLight;

import java.util.concurrent.CompletableFuture;

public class OpalRecipeProvider extends RecipeProvider {
    public OpalRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput recipeOutput) {
        for (DyeColor color : OpalLight.COLORS) {
            ResourceLocation dyeId = ResourceLocation.withDefaultNamespace(color.getName() + "_dye");
            ResourceLocation torchId = ResourceLocation.fromNamespaceAndPath(OpalLight.MODID, color.getName() + "_lantern");
            ShapelessRecipeBuilder.shapeless(RecipeCategory.DECORATIONS, BuiltInRegistries.ITEM.get(torchId))
                    .requires(Items.LANTERN)
                    .requires(BuiltInRegistries.ITEM.get(dyeId))
                    .unlockedBy("has_stone_pickaxe", has(Items.STONE_PICKAXE))
                    .save(recipeOutput, torchId);
        }
    }
}
