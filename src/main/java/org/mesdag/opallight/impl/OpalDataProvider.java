package org.mesdag.opallight.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import org.mesdag.opallight.light.color.OpalColor;
import org.mesdag.opallight.light.data.DynamicLightDefinition;
import org.mesdag.opallight.light.data.LightDataLoader;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class OpalDataProvider implements DataProvider {
    private final PackOutput output;
    private final CompletableFuture<HolderLookup.Provider> registries;
    private final String modid;
    private final Map<Block, List<LightDataLoader.OpalData>> map = new Reference2ObjectOpenHashMap<>();
    private final Map<Item, List<DynamicLightDefinition>> items = new Reference2ObjectOpenHashMap<>();
    private final Map<EntityType<?>, List<DynamicLightDefinition>> entities = new Reference2ObjectOpenHashMap<>();
    private final Map<TagKey<Item>, List<DynamicLightDefinition>> itemTags = new HashMap<>();
    private final Map<TagKey<EntityType<?>>, List<DynamicLightDefinition>> entityTags = new HashMap<>();

    public OpalDataProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries, String modid) {
        this.output = output;
        this.registries = registries;
        this.modid = modid;
    }

    public void gather() {}

    public void addCycle(Block block) {
        var pattern = new LightDataLoader.CyclePattern(
            LightDataLoader.CyclePattern.DEFAULT_COLORS, 120, 2);
        map.computeIfAbsent(block, unused -> new ArrayList<>())
            .add(LightDataLoader.OpalData.cycle(pattern, Optional.empty()));
    }

    public void add(Block block, OpalColor color, @Nullable StatePropertiesPredicate predicate) {
        map.computeIfAbsent(block, b -> new ArrayList<>()).add(new LightDataLoader.OpalData(color, Optional.ofNullable(predicate)));
    }

    public void add(Block block, OpalColor color) {
        add(block, color, null);
    }

    public void add(Block block, float red, float green, float blue) {
        add(block, OpalColor.of(red, green, blue));
    }

    public void add(Block block, int rgb) {
        add(block, OpalColor.of(rgb));
    }

    public void add(Item item, DynamicLightDefinition definition) {
        items.computeIfAbsent(item, unused -> new ArrayList<>()).add(definition);
    }

    public void add(EntityType<?> type, DynamicLightDefinition definition) {
        entities.computeIfAbsent(type, unused -> new ArrayList<>()).add(definition);
    }

    public void add(Item item, OpalColor color, int lightLevel) {
        add(item, new DynamicLightDefinition(color, lightLevel));
    }

    public void add(EntityType<?> type, OpalColor color, int lightLevel) {
        add(type, new DynamicLightDefinition(color, lightLevel));
    }

    public void addItemTag(TagKey<Item> tag, DynamicLightDefinition definition) {
        itemTags.computeIfAbsent(tag, unused -> new ArrayList<>()).add(definition);
    }

    public void addEntityTag(TagKey<EntityType<?>> tag, DynamicLightDefinition definition) {
        entityTags.computeIfAbsent(tag, unused -> new ArrayList<>()).add(definition);
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cachedOutput) {
        gather();
        return registries.thenCompose(provider -> {
            Path path = output.getOutputFolder(PackOutput.Target.RESOURCE_PACK).resolve(modid).resolve("opal_data").resolve(modid + ".json");
            return DataProvider.saveStable(cachedOutput, encodeDefinitions(), path);
        });
    }

    @Override
    public String getName() {
        return "Opal Data";
    }

    private static <T> Map<String, List<DynamicLightDefinition>> rules(Registry<T> registry,
                                                                       Map<T, List<DynamicLightDefinition>> direct, Map<TagKey<T>, List<DynamicLightDefinition>> tags) {
        Map<String, List<DynamicLightDefinition>> result = new TreeMap<>();
        direct.forEach((key, value) -> result.put(registry.getKey(key).toString(), value));
        tags.forEach((key, value) -> result.put("#" + key.location(), value));
        return result;
    }

    private JsonElement encodeDefinitions() {
        var blocks = LightDataLoader.CODEC.encodeStart(JsonOps.INSTANCE, map).result().orElseThrow();
        if (items.isEmpty() && entities.isEmpty() && itemTags.isEmpty() && entityTags.isEmpty()) return blocks;
        JsonObject json = new JsonObject();
        if (!map.isEmpty()) json.add("blocks", blocks);
        if (!items.isEmpty() || !itemTags.isEmpty())
            json.add("items", LightDataLoader.ITEM_RULE_CODEC.encodeStart(JsonOps.INSTANCE, rules(BuiltInRegistries.ITEM, items, itemTags)).result().orElseThrow());
        if (!entities.isEmpty() || !entityTags.isEmpty())
            json.add("entities", LightDataLoader.ENTITY_RULE_CODEC.encodeStart(JsonOps.INSTANCE, rules(BuiltInRegistries.ENTITY_TYPE, entities, entityTags)).result().orElseThrow());
        return json;
    }
}
