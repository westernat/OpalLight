package org.mesdag.opallight.light.data;

import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.mesdag.opallight.OpalLight;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

public final class LightConditions {
    private static final Map<ResourceLocation, Function<JsonElement, BiPredicate<ItemStack, Entity>>> item = new HashMap<>();
    private static final Map<ResourceLocation, Function<JsonElement, BiPredicate<ItemStack, Entity>>> entity = new HashMap<>();
    private static volatile long revision;

    private LightConditions() {}

    // 工厂在配置解析时绑定；匹配阶段只调用已编译的条件。
    public static synchronized AutoCloseable registerItem(ResourceLocation id,
            Function<JsonElement, BiPredicate<ItemStack, Entity>> factory) {
        return register(item, id, factory);
    }

    public static synchronized AutoCloseable registerEntity(ResourceLocation id,
            Function<JsonElement, BiPredicate<ItemStack, Entity>> factory) {
        return register(entity, id, factory);
    }

    static synchronized BiPredicate<ItemStack, Entity> compile(boolean forItem, ResourceLocation id, JsonElement value) {
        var registry = forItem ? item : entity;
        return new BiPredicate<>() {
            private long boundRevision = revision;
            private @Nullable BiPredicate<ItemStack, Entity> predicate = registry.containsKey(id)
                    ? registry.get(id).apply(value.deepCopy()) : null;

            @Override
            public boolean test(ItemStack stack, Entity owner) {
                if (boundRevision != revision) {
                    synchronized (LightConditions.class) {
                        var factory = registry.get(id);
                        try {
                            predicate = factory == null ? null : factory.apply(value.deepCopy());
                        } catch (RuntimeException error) {
                            predicate = null;
                            OpalLight.LOGGER.error("Invalid custom light condition: {}", id, error);
                        }
                        boundRevision = revision;
                    }
                }
                // 未安装适配模组时条件不成立，不能意外落入常亮状态。
                return predicate != null && predicate.test(stack, owner);
            }
        };
    }

    private static AutoCloseable register(Map<ResourceLocation, Function<JsonElement, BiPredicate<ItemStack, Entity>>> registry,
            ResourceLocation id, Function<JsonElement, BiPredicate<ItemStack, Entity>> factory) {
        if (factory == null) throw new IllegalArgumentException("Condition factory is required");
        if (registry.putIfAbsent(id, factory) != null) throw new IllegalArgumentException("Duplicate light condition: " + id);
        revision++;
        return () -> {
            synchronized (LightConditions.class) {
                if (registry.remove(id, factory)) revision++;
            }
        };
    }
}
