package org.mesdag.opallight.light.data;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforge.common.conditions.ConditionalOps;
import org.jetbrains.annotations.Nullable;
import org.mesdag.opallight.OpalLight;
import org.mesdag.opallight.light.color.OpalColor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

public final class LightDataLoader extends SimpleJsonResourceReloadListener {
    public static final LightDataLoader INSTANCE = new LightDataLoader();
    public static final Codec<Map<Block, List<OpalData>>> CODEC = Codec.lazyInitialized(() -> {
        Codec<List<OpalData>> listCodec = Codec.either(OpalData.CODEC, OpalData.CODEC.listOf()).xmap(
                either -> either.map(List::of, Function.identity()),
                list -> list.size() == 1 ? Either.left(list.getFirst()) : Either.right(list)
        );
        return Codec.unboundedMap(BuiltInRegistries.BLOCK.byNameCodec(), listCodec);
    });
    public static final Codec<Map<String, List<DynamicLightDefinition>>> ITEM_RULE_CODEC = ruleCodec(BuiltInRegistries.ITEM);
    public static final Codec<Map<String, List<DynamicLightDefinition>>> ENTITY_RULE_CODEC = ruleCodec(BuiltInRegistries.ENTITY_TYPE);

    private final LightTagRules<Item> itemTags = new LightTagRules<>(BuiltInRegistries.ITEM);
    private final LightTagRules<EntityType<?>> entityTags = new LightTagRules<>(BuiltInRegistries.ENTITY_TYPE);
    private Map<Item, List<DynamicLightDefinition>> dataByItem = ImmutableMap.of();
    private Map<EntityType<?>, List<DynamicLightDefinition>> dataByEntity = ImmutableMap.of();
    private Map<Block, List<OpalData>> dataByBlock = ImmutableMap.of();

    private LightDataLoader() {
        super(new Gson(), "opal_data");
    }

    public boolean hasItemDefinition(Item item) {
        return dataByItem.containsKey(item) || !itemTags.get(item).isEmpty();
    }

    public @Nullable DynamicLightDefinition getItemDefinition(ItemStack stack, @Nullable Entity owner, boolean burning) {
        var direct = matchingDefinition(dataByItem.get(stack.getItem()), stack, owner, burning);
        return direct != null ? direct : matchingDefinition(itemTags.get(stack.getItem()), stack, owner, burning);
    }

    public @Nullable DynamicLightDefinition getEntityDefinition(Entity entity) {
        var direct = matchingDefinition(dataByEntity.get(entity.getType()), null, entity, entity.isOnFire());
        return direct != null ? direct : matchingDefinition(entityTags.get(entity.getType()), null, entity, entity.isOnFire());
    }

    public @Nullable OpalColor getColor(BlockState state) {
        List<OpalData> list = dataByBlock.get(state.getBlock());
        if (list == null) return null;
        for (OpalData data : list) {
            if (data.matches(state)) return data.cycle().isPresent() ? null : data.color();
        }
        return null;
    }

    @Override
    public String getName() {
        return "Opal Light Data Loader";
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager manager, ProfilerFiller filler) {
        ConditionalOps<JsonElement> ops = makeConditionalOps();
        Map<Block, List<OpalData>> mutable = new Reference2ObjectOpenHashMap<>();
        Map<Item, List<DynamicLightDefinition>> items = new Reference2ObjectOpenHashMap<>();
        Map<EntityType<?>, List<DynamicLightDefinition>> entities = new Reference2ObjectOpenHashMap<>();
        Map<TagKey<Item>, List<DynamicLightDefinition>> itemTagData = new HashMap<>();
        Map<TagKey<EntityType<?>>, List<DynamicLightDefinition>> entityTagData = new HashMap<>();
        for (var entry : new TreeMap<>(map).entrySet()) {
            readResource(entry.getKey(), entry.getValue(), ops, mutable, items, entities, itemTagData, entityTagData);
        }
        ModLoader.postEvent(new ModificationEvent(mutable, items, entities, itemTagData, entityTagData));
        this.dataByBlock = ImmutableMap.copyOf(mutable);
        this.dataByItem = ImmutableMap.copyOf(items);
        this.dataByEntity = ImmutableMap.copyOf(entities);
        itemTags.replace(itemTagData);
        entityTags.replace(entityTagData);
    }

    public void invalidateTags() {
        itemTags.invalidate();
        entityTags.invalidate();
    }

    static void readResource(ResourceLocation id, JsonElement json, DynamicOps<JsonElement> ops,
                             Map<Block, List<OpalData>> blocks, Map<Item, List<DynamicLightDefinition>> items,
                             Map<EntityType<?>, List<DynamicLightDefinition>> entities,
                             Map<TagKey<Item>, List<DynamicLightDefinition>> itemTags,
                             Map<TagKey<EntityType<?>>, List<DynamicLightDefinition>> entityTags) {
        if (json.isJsonObject() && (json.getAsJsonObject().has("blocks") || json.getAsJsonObject().has("items") || json.getAsJsonObject().has("entities"))) {
            var object = json.getAsJsonObject();
            readSection(id, "blocks", object.get("blocks"), ops, CODEC, blocks);
            readRules(id, "items", object.get("items"), ops, ITEM_RULE_CODEC, BuiltInRegistries.ITEM, items, itemTags);
            readRules(id, "entities", object.get("entities"), ops, ENTITY_RULE_CODEC, BuiltInRegistries.ENTITY_TYPE, entities, entityTags);
        } else {
            readSection(id, "blocks", json, ops, CODEC, blocks);
        }
    }

    public @Nullable LightProfile getProfile(BlockState state) {
        List<OpalData> list = dataByBlock.get(state.getBlock());
        if (list == null) return null;
        for (OpalData data : list) {
            if (data.matches(state)) return new LightProfile(data.color(), data.cycle().orElse(null));
        }
        return null;
    }

    private static <T> Codec<Map<String, List<DynamicLightDefinition>>> ruleCodec(Registry<T> registry) {
        Codec<String> keyCodec = Codec.STRING.comapFlatMap(key -> {
            boolean tag = key.startsWith("#");
            var id = ResourceLocation.tryParse(tag ? key.substring(1) : key);
            if (id == null || id.getPath().isEmpty()) return DataResult.error(() -> "Invalid light rule key: " + key);
            if (!tag && !registry.containsKey(id)) return DataResult.error(() -> "Unknown light rule key: " + key);
            return DataResult.success(key);
        }, Function.identity());
        var values = Codec.either(DynamicLightDefinition.CODEC, DynamicLightDefinition.CODEC.listOf())
                .xmap(either -> either.map(List::of, Function.identity()),
                        list -> list.size() == 1 ? Either.left(list.get(0)) : Either.right(list));
        return Codec.unboundedMap(keyCodec, values).comapFlatMap(rules -> {
            if (registry == BuiltInRegistries.ENTITY_TYPE && rules.values().stream().flatMap(List::stream).anyMatch(value -> value.itemState().isPresent()))
                return DataResult.error(() -> "item_state can only be used in item light rules");
            return DataResult.success(rules);
        }, Function.identity());
    }

    private static @Nullable DynamicLightDefinition matchingDefinition(List<DynamicLightDefinition> definitions,
                                                                       @Nullable ItemStack stack, @Nullable Entity entity, boolean burning) {
        if (definitions != null) {
            for (var definition : definitions) if (definition.matches(stack, entity, burning)) return definition;
        }
        return null;
    }

    private static <T> void readRules(ResourceLocation id, String section, @Nullable JsonElement json, DynamicOps<JsonElement> ops,
                                       Codec<Map<String, List<DynamicLightDefinition>>> codec, Registry<T> registry,
                                       Map<T, List<DynamicLightDefinition>> direct, Map<TagKey<T>, List<DynamicLightDefinition>> tags) {
        Map<String, List<DynamicLightDefinition>> rules = new LinkedHashMap<>();
        readSection(id, section, json, ops, codec, rules);
        for (var entry : rules.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("#")) tags.put(TagKey.create(registry.key(), ResourceLocation.tryParse(key.substring(1))), entry.getValue());
            else direct.put(registry.get(ResourceLocation.tryParse(key)), entry.getValue());
        }
    }

    private static <K, V> void readSection(ResourceLocation id, String section, @Nullable JsonElement json,
                                           DynamicOps<JsonElement> ops, Codec<Map<K, V>> codec, Map<K, V> destination) {
        if (json == null) return;
        var result = codec.parse(ops, json);
        result.error().ifPresent(error -> OpalLight.LOGGER.error("Invalid colored light definition {} ({}): {}", id, section, error.message()));
        result.result().ifPresent(destination::putAll);
    }

    public record CyclePattern(List<OpalColor> colors, int periodTicks, int updateIntervalTicks) {
        public static final List<OpalColor> DEFAULT_COLORS = List.of(
            OpalColor.of(0xFF0000), OpalColor.of(0xFFFF00), OpalColor.of(0x00FF00),
            OpalColor.of(0x00FFFF), OpalColor.of(0x0000FF), OpalColor.of(0xFF00FF));
        public static final Codec<CyclePattern> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(OpalColor.CODEC, 2, 16).optionalFieldOf("colors", DEFAULT_COLORS).forGetter(CyclePattern::colors),
            Codec.intRange(20, 1200).optionalFieldOf("period_ticks", 120).forGetter(CyclePattern::periodTicks),
            Codec.intRange(1, 20).optionalFieldOf("update_interval_ticks", 2).forGetter(CyclePattern::updateIntervalTicks)
        ).apply(instance, CyclePattern::new));

        public CyclePattern {
            colors = List.copyOf(colors);
        }
    }

    public record OpalData(OpalColor color, Optional<StatePropertiesPredicate> statePredicate,
                           Optional<CyclePattern> cycle) {
        private static final Codec<Raw> RAW_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            OpalColor.CODEC.optionalFieldOf("color").forGetter(Raw::color),
            StatePropertiesPredicate.CODEC.lenientOptionalFieldOf("state").forGetter(Raw::statePredicate),
            CyclePattern.CODEC.optionalFieldOf("cycle").forGetter(Raw::cycle)
        ).apply(instance, Raw::new));
        public static final Codec<OpalData> DIRECT_CODEC = RAW_CODEC.comapFlatMap(raw -> {
            if (raw.color().isPresent() == raw.cycle().isPresent()) {
                return DataResult.error(() -> "Exactly one of 'color' or 'cycle' is required");
            }
            return DataResult.success(new OpalData(raw.color().orElseGet(() -> OpalColor.of(1.0F, 1.0F, 1.0F)),
                raw.statePredicate(), raw.cycle()));
        }, data -> new Raw(data.cycle().isPresent() ? Optional.empty() : Optional.of(data.color()),
            data.statePredicate(), data.cycle()));
        public static final Codec<OpalData> CODEC = Codec.either(DIRECT_CODEC, OpalColor.CODEC).xmap(
                either -> either.map(Function.identity(), color -> new OpalData(color, Optional.empty())),
            data -> data.statePredicate.isEmpty() && data.cycle.isEmpty()
                ? Either.right(data.color) : Either.left(data)
        );

        public OpalData(OpalColor color, Optional<StatePropertiesPredicate> statePredicate) {
            this(color, statePredicate, Optional.empty());
        }

        public static OpalData cycle(CyclePattern pattern, Optional<StatePropertiesPredicate> statePredicate) {
            return new OpalData(OpalColor.of(1.0F, 1.0F, 1.0F), statePredicate, Optional.of(pattern));
        }

        public boolean matches(BlockState state) {
            return statePredicate.isEmpty() || statePredicate.get().matches(state);
        }

        private record Raw(Optional<OpalColor> color, Optional<StatePropertiesPredicate> statePredicate,
                           Optional<CyclePattern> cycle) {
        }
    }

    public static class ModificationEvent extends Event implements IModBusEvent {
        private final Map<Block, List<OpalData>> dataByBlock;
        private final Map<Item, List<DynamicLightDefinition>> dataByItem;
        private final Map<EntityType<?>, List<DynamicLightDefinition>> dataByEntity;
        private final Map<TagKey<Item>, List<DynamicLightDefinition>> itemTags;
        private final Map<TagKey<EntityType<?>>, List<DynamicLightDefinition>> entityTags;

        public ModificationEvent(Map<Block, List<OpalData>> dataByBlock) {
            this(dataByBlock, new Reference2ObjectOpenHashMap<>(), new Reference2ObjectOpenHashMap<>(), new HashMap<>(), new HashMap<>());
        }

        public ModificationEvent(Map<Block, List<OpalData>> dataByBlock,
                                 Map<Item, List<DynamicLightDefinition>> dataByItem,
                                 Map<EntityType<?>, List<DynamicLightDefinition>> dataByEntity,
                                 Map<TagKey<Item>, List<DynamicLightDefinition>> itemTags,
                                 Map<TagKey<EntityType<?>>, List<DynamicLightDefinition>> entityTags) {
            this.itemTags = itemTags;
            this.entityTags = entityTags;
            this.dataByBlock = dataByBlock;
            this.dataByItem = dataByItem;
            this.dataByEntity = dataByEntity;
        }

        public Map<TagKey<Item>, List<DynamicLightDefinition>> getItemTags() { return itemTags; }

        public Map<TagKey<EntityType<?>>, List<DynamicLightDefinition>> getEntityTags() { return entityTags; }

        public Map<Block, List<OpalData>> getDataByBlock() { return dataByBlock; }

        public Map<Item, List<DynamicLightDefinition>> getDataByItem() { return dataByItem; }

        public Map<EntityType<?>, List<DynamicLightDefinition>> getDataByEntity() { return dataByEntity; }
    }
}
