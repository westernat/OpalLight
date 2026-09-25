package org.mesdag.opallight.light;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
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

    public record OpalData(OpalColor color, Optional<StatePropertiesPredicate> statePredicate) {
        /// 1.20.1 的 {@code StatePropertiesPredicate} 还没有 Codec，用原生 JSON 桥接。
        /// 必须声明在 {@link #DIRECT_CODEC} 之前：该记录类可能先于外层类被初始化。
        private static final Codec<StatePropertiesPredicate> STATE_CODEC = Codec.PASSTHROUGH.xmap(
                dynamic -> {
                    StatePropertiesPredicate predicate = StatePropertiesPredicate.fromJson(
                            dynamic.convert(JsonOps.INSTANCE).getValue());
                    return predicate == null ? StatePropertiesPredicate.ANY : predicate;
                },
                predicate -> new Dynamic<>(JsonOps.INSTANCE, predicate.serializeToJson()));

        public static final Codec<OpalData> DIRECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                OpalColor.CODEC.fieldOf("color").forGetter(OpalData::color),
                STATE_CODEC.optionalFieldOf("state").forGetter(OpalData::statePredicate)
        ).apply(instance, OpalData::new));
        public static final Codec<OpalData> CODEC = Codec.either(DIRECT_CODEC, OpalColor.CODEC).xmap(
                either -> either.map(Function.identity(), color -> new OpalData(color, Optional.empty())),
                data -> data.statePredicate.isEmpty() ? Either.right(data.color) : Either.left(data)
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
            if (data.matches(state)) return data.color;
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
