package org.mesdag.opallight.impl;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;
import org.mesdag.opallight.OpalLight;
import org.mesdag.opallight.light.LightDataLoader;
import org.mesdag.opallight.light.OpalColor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class OpalDataProvider implements DataProvider {
    private final PackOutput output;
    private final CompletableFuture<HolderLookup.Provider> registries;
    private final String modid;
    private final Map<Block, List<LightDataLoader.OpalData>> map = new Reference2ObjectOpenHashMap<>();

    public OpalDataProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries, String modid) {
        this.output = output;
        this.registries = registries;
        this.modid = modid;
    }

    public void gather() {
        addCycle(Blocks.END_ROD);
    }

    public void addCycle(Block block) {
        var pattern = new LightDataLoader.CyclePattern(LightDataLoader.CyclePattern.DEFAULT_COLORS, 120, 2);
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

    @Override
    public CompletableFuture<?> run(CachedOutput cachedOutput) {
        gather();
        return registries.thenCompose(provider -> {
            Path path = output.getOutputFolder(PackOutput.Target.RESOURCE_PACK).resolve(modid).resolve("opal_data").resolve(modid + ".json");
            /// 1.20.1 的 {@code saveStable} 只接受已序列化的 JSON。
            JsonElement json = LightDataLoader.CODEC.encodeStart(JsonOps.INSTANCE, map)
                    .getOrThrow(false, error -> OpalLight.LOGGER.error("Failed to encode opal data: {}", error));
            return DataProvider.saveStable(cachedOutput, json, path);
        });
    }

    @Override
    public String getName() {
        return "Opal Data";
    }
}
