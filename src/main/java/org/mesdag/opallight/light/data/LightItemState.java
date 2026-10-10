package org.mesdag.opallight.light.data;

import com.google.gson.JsonElement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;

import java.util.function.BiPredicate;

final class LightItemState {
    private LightItemState() {
    }

    static boolean enchanted(ItemStack stack) {
        return !enchantments(stack).isEmpty();
    }

    static int enchantmentLevel(ItemStack stack, ResourceLocation id) {
        var enchantments = enchantments(stack);
        String expected = id.toString();
        for (int i = 0; i < enchantments.size(); i++) {
            var tag = enchantments.getCompound(i);
            if (expected.equals(tag.getString("id"))) return tag.getInt("lvl");
        }
        return 0;
    }

    static boolean potion(ItemStack stack, ResourceLocation id) {
        return stack.hasTag() && stack.getTag().contains("Potion", 8) && id.equals(BuiltInRegistries.POTION.getKey(PotionUtils.getPotion(stack)));
    }

    static BiPredicate<ItemStack, Entity> nbt(String value) {
        try {
            var expected = TagParser.parseTag(value);
            return (stack, entity) -> expected.isEmpty() || NbtUtils.compareNbt(expected, stack.getTag(), true);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            throw new IllegalArgumentException("Invalid item NBT: " + exception.getMessage());
        }
    }

    static BiPredicate<ItemStack, Entity> components(JsonElement value) {
        throw new IllegalArgumentException("Data components require Minecraft 1.21.1; use nbt on 1.20.1");
    }

    private static ListTag enchantments(ItemStack stack) {
        return stack.is(Items.ENCHANTED_BOOK) ? EnchantedBookItem.getEnchantments(stack) : stack.getEnchantmentTags();
    }
}
