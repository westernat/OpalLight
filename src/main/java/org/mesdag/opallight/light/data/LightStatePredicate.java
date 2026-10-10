package org.mesdag.opallight.light.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.DoublePredicate;

public final class LightStatePredicate {
    public static final Codec<LightStatePredicate> ITEM_CODEC = codec(true);
    public static final Codec<LightStatePredicate> ENTITY_CODEC = codec(false);

    private final JsonObject json;
    private final List<BiPredicate<ItemStack, Entity>> tests;
    private final boolean item;

    private LightStatePredicate(JsonObject json, boolean item) {
        this.json = json.deepCopy();
        this.item = item;
        var tests = new ArrayList<BiPredicate<ItemStack, Entity>>();
        for (var entry : json.entrySet()) tests.add(item ? itemTest(entry.getKey(), entry.getValue()) : entityTest(entry.getKey(), entry.getValue()));
        this.tests = List.copyOf(tests);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LightStatePredicate predicate && item == predicate.item && json.equals(predicate.json);
    }

    @Override
    public int hashCode() { return 31 * json.hashCode() + Boolean.hashCode(item); }

    boolean matches(ItemStack stack, Entity entity) {
        if (item ? stack == null || stack.isEmpty() : entity == null) return false;
        for (var test : tests) if (!test.test(stack, entity)) return false;
        return true;
    }

    static String string(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string");
        return value.getAsString();
    }

    static ResourceLocation id(String value) {
        var id = ResourceLocation.tryParse(value);
        if (id == null || id.getPath().isEmpty()) throw new IllegalArgumentException("Invalid resource location: " + value);
        return id;
    }

    private static Codec<LightStatePredicate> codec(boolean item) {
        return Codec.PASSTHROUGH.comapFlatMap(input -> {
            try {
                var json = input.convert(JsonOps.INSTANCE).getValue();
                if (!json.isJsonObject()) return DataResult.error(() -> "Expected state predicate object");
                return DataResult.success(new LightStatePredicate(json.getAsJsonObject(), item));
            } catch (IllegalArgumentException exception) {
                return DataResult.error(exception::getMessage);
            }
        }, value -> new Dynamic<>(JsonOps.INSTANCE, value.json.deepCopy()));
    }

    private static BiPredicate<ItemStack, Entity> itemTest(String key, JsonElement value) {
        return switch (key) {
            case "count" -> {
                var range = range(value, true, 0, Integer.MAX_VALUE);
                yield (stack, entity) -> range.test(stack.getCount());
            }
            case "damage" -> {
                var range = range(value, true, 0, Integer.MAX_VALUE);
                yield (stack, entity) -> range.test(stack.getDamageValue());
            }
            case "durability" -> {
                var range = range(value, true, 0, Integer.MAX_VALUE);
                yield (stack, entity) -> stack.isDamageableItem() && range.test(stack.getMaxDamage() - stack.getDamageValue());
            }
            case "enchanted" -> {
                boolean expected = flag(value);
                yield (stack, entity) -> LightItemState.enchanted(stack) == expected;
            }
            case "potion" -> {
                var id = id(value);
                yield (stack, entity) -> LightItemState.potion(stack, id);
            }
            case "nbt" -> LightItemState.nbt(string(value));
            case "components" -> LightItemState.components(value);
            case "enchantments" -> {
                if (!value.isJsonObject()) throw new IllegalArgumentException("Expected enchantment ID to level map");
                var tests = new ArrayList<BiPredicate<ItemStack, Entity>>();
                for (var entry : value.getAsJsonObject().entrySet()) {
                    var id = id(entry.getKey());
                    var range = range(entry.getValue(), true, 0, Integer.MAX_VALUE);
                    tests.add((stack, entity) -> range.test(LightItemState.enchantmentLevel(stack, id)));
                }
                yield (stack, entity) -> {
                    for (var test : tests) if (!test.test(stack, entity)) return false;
                    return true;
                };
            }
            default -> throw new IllegalArgumentException("Unknown item state: " + key);
        };
    }

    private static BiPredicate<ItemStack, Entity> entityTest(String key, JsonElement value) {
        if (key.equals("health") || key.equals("health_fraction")) {
            var range = range(value, false, 0, key.equals("health_fraction") ? 1 : Float.MAX_VALUE);
            return (stack, entity) -> entity instanceof LivingEntity living && range.test(key.equals("health")
                    ? living.getHealth() : living.getHealth() / Math.max(living.getMaxHealth(), 0.0001F));
        }
        boolean expected = flag(value);
        return switch (key) {
            case "burning" -> (stack, entity) -> entity.isOnFire() == expected;
            case "on_ground" -> (stack, entity) -> entity.onGround() == expected;
            case "in_water" -> (stack, entity) -> entity.isInWater() == expected;
            case "sprinting" -> (stack, entity) -> entity.isSprinting() == expected;
            case "crouching" -> (stack, entity) -> entity.isCrouching() == expected;
            case "invisible" -> (stack, entity) -> entity.isInvisible() == expected;
            case "glowing" -> (stack, entity) -> entity.isCurrentlyGlowing() == expected;
            case "baby" -> (stack, entity) -> entity instanceof LivingEntity living && living.isBaby() == expected;
            case "using_item" -> (stack, entity) -> entity instanceof LivingEntity living && living.isUsingItem() == expected;
            case "aggressive" -> (stack, entity) -> entity instanceof Mob mob && mob.isAggressive() == expected;
            case "powered" -> (stack, entity) -> entity instanceof Creeper creeper && creeper.isPowered() == expected;
            case "ignited" -> (stack, entity) -> entity instanceof Creeper creeper && creeper.isIgnited() == expected;
            case "swelling" -> (stack, entity) -> entity instanceof Creeper creeper && (creeper.getSwellDir() > 0) == expected;
            case "charging" -> (stack, entity) -> {
                if (entity instanceof Blaze blaze) return blaze.isOnFire() == expected;
                if (entity instanceof Ghast ghast) return ghast.isCharging() == expected;
                return entity instanceof Vex vex && vex.isCharging() == expected;
            };
            default -> throw new IllegalArgumentException("Unknown entity state: " + key);
        };
    }

    private static DoublePredicate range(JsonElement value, boolean integer, double lower, double upper) {
        double min, max;
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (object.size() == 0) throw new IllegalArgumentException("Range needs min or max");
            for (String key : object.keySet()) if (!key.equals("min") && !key.equals("max")) throw new IllegalArgumentException("Unknown range field: " + key);
            min = object.has("min") ? number(object.get("min"), integer) : lower;
            max = object.has("max") ? number(object.get("max"), integer) : upper;
        } else {
            min = max = number(value, integer);
        }
        if (min < lower || max > upper || min > max) throw new IllegalArgumentException("Invalid state range");
        return actual -> actual >= min && actual <= max;
    }

    private static double number(JsonElement value, boolean integer) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected number");
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || integer && number != Math.rint(number)) throw new IllegalArgumentException("Invalid number");
        return number;
    }

    private static boolean flag(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Expected boolean");
        return value.getAsBoolean();
    }

    private static ResourceLocation id(JsonElement value) { return id(string(value)); }
}
