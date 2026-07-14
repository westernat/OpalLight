package org.mesdag.opallight.impl;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
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
        for (DyeColor color : OpalLight.COLORS) {
            add(BuiltInRegistries.BLOCK.get(OpalLight.asResource(color.getName() + "_lantern")), color.getTextColor());
        }
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
            return DataProvider.saveStable(cachedOutput, provider, LightDataLoader.CODEC, map, path);
        });
    }

    @Override
    public String getName() {
        return "Opal Data";
    }
}
