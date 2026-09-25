package org.mesdag.opallight.impl;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import org.mesdag.opallight.OpalLight;

import java.util.function.Consumer;

public class OpalRecipeProvider extends RecipeProvider {
    public OpalRecipeProvider(PackOutput output) {
        super(output);
    }

    @Override
    protected void buildRecipes(Consumer<FinishedRecipe> recipeOutput) {
        for (DyeColor color : OpalLight.COLORS) {
            ResourceLocation torchId = OpalLight.asResource(color.getName() + "_lantern");
            ShapelessRecipeBuilder.shapeless(RecipeCategory.DECORATIONS, BuiltInRegistries.ITEM.get(torchId))
                    .requires(Items.LANTERN)
                    .requires(BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(color.getName() + "_dye")))
                    .unlockedBy("has_stone_pickaxe", has(Items.STONE_PICKAXE))
                    .save(recipeOutput, torchId);
        }
    }
}
