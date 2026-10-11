package org.mesdag.opallight.light.data;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.mesdag.opallight.OpalLight;

import java.util.ArrayList;
import java.util.function.BiPredicate;

final class LightItemState {
    private LightItemState() {}

    static boolean enchanted(ItemStack stack) { return !enchantments(stack).isEmpty(); }

    static int enchantmentLevel(ItemStack stack, ResourceLocation id) {
        for (var entry : enchantments(stack).entrySet()) {
            if (entry.getKey().unwrapKey().map(key -> key.location().equals(id)).orElse(false)) return entry.getIntValue();
        }
        return 0;
    }

    static boolean potion(ItemStack stack, ResourceLocation id) {
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).potion()
                .flatMap(potion -> potion.unwrapKey()).map(key -> key.location().equals(id)).orElse(false);
    }

    static BiPredicate<ItemStack, Entity> nbt(String value) {
        try {
            var expected = TagParser.parseTag(value);
            return (stack, entity) -> stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).matchedBy(expected);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            throw new IllegalArgumentException("Invalid item NBT: " + exception.getMessage());
        }
    }

    static BiPredicate<ItemStack, Entity> components(JsonElement value) {
        if (!value.isJsonObject()) throw new IllegalArgumentException("Expected components object");
        for (String key : value.getAsJsonObject().keySet()) {
            if (!BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(LightStatePredicate.id(key)))
                throw new IllegalArgumentException("Unknown data component: " + key);
        }
        JsonElement json = value.deepCopy();
        return new BiPredicate<>() {
            private RegistryAccess access;
            private DataComponentPredicate predicate;

            @Override
            public boolean test(ItemStack stack, Entity entity) {
                if (entity == null || entity.level() == null) return false;
                RegistryAccess current = entity.level().registryAccess();
                // 组件可能引用世界注册表；进入世界后解析一次，避免逐刻序列化物品。
                if (access != current) {
                    access = current;
                    var result = DataComponentPredicate.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, current), json);
                    predicate = result.result().orElse(null);
                    result.error().ifPresent(error -> OpalLight.LOGGER.error("Invalid light item components {}: {}", json, error.message()));
                }
                return predicate != null && predicate.test(stack);
            }
        };
    }

    static BiPredicate<ItemStack, Entity> componentFields(JsonElement value) {
        if (!value.isJsonObject()) throw new IllegalArgumentException("Expected component field map");
        var matchers = new ArrayList<BiPredicate<ItemStack, Entity>>();
        for (var entry : value.getAsJsonObject().entrySet()) {
            var id = LightStatePredicate.id(entry.getKey());
            if (!BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(id)) throw new IllegalArgumentException("Unknown data component: " + id);
            var type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(id);
            if (type.codec() == null) throw new IllegalArgumentException("Data component has no persistent codec: " + id);
            matchers.add(componentField(type, entry.getValue().deepCopy()));
        }
        return (stack, entity) -> {
            for (var matcher : matchers) if (!matcher.test(stack, entity)) return false;
            return true;
        };
    }

    private static <T> BiPredicate<ItemStack, Entity> componentField(DataComponentType<T> type, JsonElement expected) {
        return new BiPredicate<>() {
            private RegistryAccess access;
            private T previous;
            private boolean matched;

            @Override
            public boolean test(ItemStack stack, Entity entity) {
                var actual = stack.get(type);
                if (actual == null || entity == null || entity.level() == null) return false;
                var current = entity.level().registryAccess();
                // 组件不可变，替换组件或世界注册表时才重新编码所需组件。
                if (access != current || !actual.equals(previous)) {
                    previous = actual;
                    access = current;
                    var encoded = type.codecOrThrow().encodeStart(RegistryOps.create(JsonOps.INSTANCE, current), actual);
                    matched = encoded.result().map(json -> LightDataMatch.matches(expected, json)).orElse(false);
                }
                return matched;
            }
        };
    }

    private static ItemEnchantments enchantments(ItemStack stack) {
        return stack.getOrDefault(stack.is(Items.ENCHANTED_BOOK) ? DataComponents.STORED_ENCHANTMENTS : DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
    }
}
