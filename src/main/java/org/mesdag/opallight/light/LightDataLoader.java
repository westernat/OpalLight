package org.mesdag.opallight.light;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
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
import org.mesdag.opallight.OpalLight;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class LightDataLoader extends SimpleJsonResourceReloadListener {
    public static final LightDataLoader INSTANCE = new LightDataLoader();

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
        private record Raw(Optional<OpalColor> color, Optional<StatePropertiesPredicate> statePredicate,
                           Optional<CyclePattern> cycle) {
        }

        public OpalData(OpalColor color, Optional<StatePropertiesPredicate> statePredicate) {
            this(color, statePredicate, Optional.empty());
        }

        public static OpalData cycle(CyclePattern pattern, Optional<StatePropertiesPredicate> statePredicate) {
            return new OpalData(OpalColor.of(1.0F, 1.0F, 1.0F), statePredicate, Optional.of(pattern));
        }

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
            /// 循环光源没有固定的 RGB 值。
            if (data.matches(state)) return data.cycle().isPresent() ? null : data.color();
        }
        return null;
    }

    @Nullable LightProfile getProfile(BlockState state) {
        List<OpalData> list = dataByBlock.get(state.getBlock());
        if (list == null) return null;
        for (OpalData data : list) {
            if (data.matches(state)) return new LightProfile(data.color(), data.cycle().orElse(null));
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
        for (var entry : map.entrySet()) {
            CODEC.parse(ops, entry.getValue())
                    .ifError(error -> OpalLight.LOGGER.error("Invalid colored light definition {}: {}", entry.getKey(), error.message()))
                    .ifSuccess(mutable::putAll);
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
