package org.mesdag.opallight.light.data;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.mesdag.opallight.light.color.OpalColor;

import java.util.Optional;

public record DynamicLightDefinition(OpalColor color, int lightLevel, Optional<LightDataLoader.CyclePattern> cycle,
                                     Optional<Boolean> burning,
                                     Optional<LightStatePredicate> itemState,
                                     Optional<LightStatePredicate> entityState) {
    public static final Codec<DynamicLightDefinition> CODEC = Codec.PASSTHROUGH.comapFlatMap(DynamicLightDefinition::decode, DynamicLightDefinition::encode);

    public DynamicLightDefinition {
        if (lightLevel < 0 || lightLevel > 15)
            throw new IllegalArgumentException("Light level must be between 0 and 15");
    }

    public DynamicLightDefinition(OpalColor color, int lightLevel, Optional<LightDataLoader.CyclePattern> cycle, Optional<Boolean> burning) {
        this(color, lightLevel, cycle, burning, Optional.empty(), Optional.empty());
    }

    public DynamicLightDefinition(OpalColor color, int lightLevel) {
        this(color, lightLevel, Optional.empty(), Optional.empty());
    }

    boolean matches(ItemStack stack, Entity entity, boolean onFire) {
        return (burning.isEmpty() || burning.get() == onFire)
            && (itemState.isEmpty() || itemState.get().matches(stack, entity))
            && (entityState.isEmpty() || entityState.get().matches(stack, entity));
    }

    private static <T> DataResult<DynamicLightDefinition> decode(Dynamic<T> input) {
        var ops = input.getOps();
        return ops.getMap(input.getValue()).flatMap(map -> {
            if (map.get("state") != null)
                return DataResult.error(() -> "Block state predicates are not supported for dynamic lights");
            T level = map.get("light_level");
            T burning = map.get("burning");
            DataResult<Integer> emission = level == null ? DataResult.success(15) : Codec.INT.parse(ops, level).flatMap(value ->
                value >= 0 && value <= 15 ? DataResult.success(value) : DataResult.error(() -> "Light level must be between 0 and 15"));
            DataResult<Optional<Boolean>> condition = burning == null ? DataResult.success(Optional.empty())
                : Codec.BOOL.parse(ops, burning).map(Optional::of);
            T itemState = map.get("item_state");
            T entityState = map.get("entity_state");
            DataResult<Optional<LightStatePredicate>> item = itemState == null ? DataResult.success(Optional.empty())
                : LightStatePredicate.ITEM_CODEC.parse(ops, itemState).map(Optional::of);
            DataResult<Optional<LightStatePredicate>> entity = entityState == null ? DataResult.success(Optional.empty())
                : LightStatePredicate.ENTITY_CODEC.parse(ops, entityState).map(Optional::of);
            return LightDataLoader.OpalData.DIRECT_CODEC.parse(ops, input.getValue()).flatMap(data ->
                emission.flatMap(value -> condition.flatMap(fire -> item.flatMap(itemRule -> entity.map(entityRule ->
                    new DynamicLightDefinition(data.color(), value, data.cycle(), fire, itemRule, entityRule))))));
        });
    }

    private static Dynamic<JsonElement> encode(DynamicLightDefinition definition) {
        var data = new LightDataLoader.OpalData(definition.color(), Optional.empty(), definition.cycle());
        var json = LightDataLoader.OpalData.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, data).result().orElseThrow().getAsJsonObject();
        json.addProperty("light_level", definition.lightLevel());
        definition.burning().ifPresent(value -> json.addProperty("burning", value));
        definition.itemState().ifPresent(value -> json.add("item_state", LightStatePredicate.ITEM_CODEC.encodeStart(JsonOps.INSTANCE, value).result().orElseThrow()));
        definition.entityState().ifPresent(value -> json.add("entity_state", LightStatePredicate.ENTITY_CODEC.encodeStart(JsonOps.INSTANCE, value).result().orElseThrow()));
        return new Dynamic<>(JsonOps.INSTANCE, json);
    }
}
