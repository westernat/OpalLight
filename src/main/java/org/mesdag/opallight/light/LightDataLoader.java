package org.mesdag.opallight.light;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
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
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.event.IModBusEvent;
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
        /// 1.20.1 的 Codec.list 没有长度约束重载。
        private static final Codec<List<OpalColor>> COLORS_CODEC = Codec.list(OpalColor.CODEC).comapFlatMap(
            colors -> colors.size() >= 2 && colors.size() <= 16
                ? DataResult.success(colors)
                : DataResult.error(() -> "Expected 2-16 colors, got " + colors.size()),
            Function.identity());
        public static final Codec<CyclePattern> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            COLORS_CODEC.optionalFieldOf("colors", DEFAULT_COLORS).forGetter(CyclePattern::colors),
            Codec.intRange(20, 1200).optionalFieldOf("period_ticks", 120).forGetter(CyclePattern::periodTicks),
            Codec.intRange(1, 20).optionalFieldOf("update_interval_ticks", 2).forGetter(CyclePattern::updateIntervalTicks)
        ).apply(instance, CyclePattern::new));

        public CyclePattern {
            colors = List.copyOf(colors);
        }
    }

    public record OpalData(OpalColor color, Optional<StatePropertiesPredicate> statePredicate,
                           Optional<CyclePattern> cycle) {
        /// 1.20.1 的 {@code StatePropertiesPredicate} 还没有 Codec，用原生 JSON 桥接。
        /// 必须声明在 {@link #DIRECT_CODEC} 之前：该记录类可能先于外层类被初始化。
        private static final Codec<StatePropertiesPredicate> STATE_CODEC = Codec.PASSTHROUGH.xmap(
                dynamic -> {
                    StatePropertiesPredicate predicate = StatePropertiesPredicate.fromJson(
                            dynamic.convert(JsonOps.INSTANCE).getValue());
                    return predicate == null ? StatePropertiesPredicate.ANY : predicate;
                },
                predicate -> new Dynamic<>(JsonOps.INSTANCE, predicate.serializeToJson()));

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
            STATE_CODEC.optionalFieldOf("state").forGetter(Raw::statePredicate),
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

    /// 1.20.1 的 DataFixerUpper 没有 {@code Codec#lazyInitialized}。
    public static final Codec<Map<Block, List<OpalData>>> CODEC = createCodec();

    private static Codec<Map<Block, List<OpalData>>> createCodec() {
        Codec<List<OpalData>> listCodec = Codec.either(OpalData.CODEC, OpalData.CODEC.listOf()).xmap(
                either -> either.map(List::of, Function.identity()),
                list -> list.size() == 1 ? Either.left(list.get(0)) : Either.right(list)
        );
        return Codec.unboundedMap(BuiltInRegistries.BLOCK.byNameCodec(), listCodec);
    }

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
        /// 1.20.1 没有按条件解析 JSON 的 DynamicOps，彩光定义直接按普通 JSON 读取。
        Map<Block, List<OpalData>> mutable = new Reference2ObjectOpenHashMap<>();
        for (var entry : map.entrySet()) {
            /// 1.20.1 的 DataResult 只有 {@code result()}/{@code error()} 两个 Optional 出口。
            var result = CODEC.parse(JsonOps.INSTANCE, entry.getValue());
            result.error().ifPresent(error -> OpalLight.LOGGER.error("Invalid colored light definition {}: {}", entry.getKey(), error.message()));
            result.result().ifPresent(mutable::putAll);
        }
        ModLoader.get().postEvent(new ModificationEvent(mutable));
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
