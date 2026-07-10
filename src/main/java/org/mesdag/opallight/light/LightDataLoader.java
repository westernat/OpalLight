package org.mesdag.opallight.light;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforge.common.conditions.ConditionalOps;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public final class LightDataLoader extends SimpleJsonResourceReloadListener {
    public static final LightDataLoader INSTANCE = new LightDataLoader();
    private static final Codec<Map<Block, OpalColor>> BLOCK_DATA_CODEC = Codec.unboundedMap(BuiltInRegistries.BLOCK.byNameCodec(), OpalColor.CODEC);
    private static final Codec<Map<BlockState, OpalColor>> STATE_DATA_CODEC = Codec.compoundList(BlockState.CODEC, OpalColor.CODEC).xmap(list -> {
        ImmutableMap.Builder<BlockState, OpalColor> builder = ImmutableMap.builder();
        for (Pair<BlockState, OpalColor> pair : list) {
            builder.put(pair.getFirst(), pair.getSecond());
        }
        return builder.build();
    }, map -> {
        ImmutableList.Builder<Pair<BlockState, OpalColor>> builder = ImmutableList.builder();
        for (Map.Entry<BlockState, OpalColor> entry : map.entrySet()) {
            builder.add(new Pair<>(entry.getKey(), entry.getValue()));
        }
        return builder.build();
    });

    private Map<Block, OpalColor> blockData = ImmutableMap.of();
    private Map<BlockState, OpalColor> stateData = ImmutableMap.of();

    public @Nullable OpalColor getColor(Block block) {
        return blockData.get(block);
    }

    public @Nullable OpalColor getColor(BlockState state, boolean useBlockAsFallback) {
        OpalColor color = stateData.get(state);
        if (color == null && useBlockAsFallback) {
            return getColor(state.getBlock());
        }
        return color;
    }

    private LightDataLoader() {
        super(new Gson(), "opal_data");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager manager, ProfilerFiller filler) {
        ConditionalOps<JsonElement> ops = makeConditionalOps();
        this.blockData = new Reference2ObjectOpenHashMap<>();
        this.stateData = new Reference2ObjectOpenHashMap<>();
        for (JsonElement element : map.values()) {
            JsonObject object = GsonHelper.convertToJsonObject(element, "light_data");
            BLOCK_DATA_CODEC.parse(ops, object.get("block")).ifSuccess(blockData::putAll);
            STATE_DATA_CODEC.parse(ops, object.get("state")).ifSuccess(stateData::putAll);
        }
        ModLoader.postEvent(new ModificationEvent(blockData, stateData));
        this.blockData = ImmutableMap.copyOf(blockData);
        this.stateData = ImmutableMap.copyOf(stateData);
    }

    public static class ModificationEvent extends Event implements IModBusEvent {
        private final Map<Block, OpalColor> blockData;
        private final Map<BlockState, OpalColor> stateData;

        public ModificationEvent(Map<Block, OpalColor> blockData, Map<BlockState, OpalColor> stateData) {
            this.blockData = blockData;
            this.stateData = stateData;
        }

        public Map<Block, OpalColor> getBlockData() {
            return blockData;
        }

        public Map<BlockState, OpalColor> getStateData() {
            return stateData;
        }
    }

    @Override
    public String getName() {
        return "Opal Light Data Loader";
    }
}
