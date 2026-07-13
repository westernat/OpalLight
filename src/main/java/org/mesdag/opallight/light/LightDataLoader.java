package org.mesdag.opallight.light;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforge.common.conditions.ConditionalOps;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class LightDataLoader extends SimpleJsonResourceReloadListener {
    public static final LightDataLoader INSTANCE = new LightDataLoader();

    public record OpalData(OpalColor color, Optional<StatePropertiesPredicate> statePredicate) {
        public static final Codec<OpalData> DIRECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                OpalColor.CODEC.fieldOf("color").forGetter(OpalData::color),
                StatePropertiesPredicate.CODEC.lenientOptionalFieldOf("state").forGetter(OpalData::statePredicate)
        ).apply(instance, OpalData::new));
        public static final Codec<OpalData> CODEC = Codec.either(DIRECT_CODEC, OpalColor.CODEC).xmap(
                either -> either.map(Function.identity(), color -> new OpalData(color, Optional.empty())),
                data -> data.statePredicate.isEmpty() ? Either.right(data.color) : Either.left(data)
        );

        public boolean matches(BlockState state) {
            return statePredicate.isEmpty() || statePredicate.get().matches(state);
        }
    }

    public static final Codec<Map<Block, List<OpalData>>> CODEC = Codec.lazyInitialized(() -> {
        Codec<List<OpalData>> listCodec = Codec.either(OpalData.CODEC, OpalData.CODEC.listOf()).xmap(
                either -> either.map(List::of, Function.identity()),
                list -> list.size() == 1 ? Either.left(list.getFirst()) : Either.right(list)
        );
        return Codec.unboundedMap(BuiltInRegistries.BLOCK.byNameCodec(), listCodec);
    });

    private Map<Block, List<OpalData>> dataByBlock = ImmutableMap.of();

    public @Nullable OpalColor getColor(BlockState state) {
        List<OpalData> list = dataByBlock.get(state.getBlock());
        if (list == null) return null;
        for (OpalData data : list) {
            if (data.matches(state)) return data.color;
        }
        return null;
    }

    private LightDataLoader() {
        super(new Gson(), "opal_data");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager manager, ProfilerFiller filler) {
        ConditionalOps<JsonElement> ops = makeConditionalOps();
        Map<Block, List<OpalData>> mutable = new Reference2ObjectOpenHashMap<>();
        for (JsonElement element : map.values()) {
            CODEC.parse(ops, element).ifSuccess(mutable::putAll);
        }
        ModLoader.postEvent(new ModificationEvent(mutable));
        this.dataByBlock = ImmutableMap.copyOf(mutable);
    }

    public static class ModificationEvent extends Event implements IModBusEvent {
        private final Map<Block, List<OpalData>> dataByBlock;

        public ModificationEvent(Map<Block, List<OpalData>> dataByBlock) {
            this.dataByBlock = dataByBlock;
        }

        public Map<Block, List<OpalData>> getDataByBlock() {
            return dataByBlock;
        }
    }

    @Override
    public String getName() {
        return "Opal Light Data Loader";
    }
}
