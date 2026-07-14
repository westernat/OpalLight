package org.mesdag.opallight;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.mesdag.opallight.impl.OpalBlockStateProvider;
import org.mesdag.opallight.impl.OpalDataProvider;
import org.mesdag.opallight.impl.OpalRecipeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

@Mod(value = OpalLight.MODID)
@EventBusSubscriber(modid = OpalLight.MODID)
public class OpalLight {
    public static final String MODID = "opallight";
    public static final Logger LOGGER = LoggerFactory.getLogger("OpalLight");
    public static final DyeColor[] COLORS = {
            DyeColor.RED,
            DyeColor.ORANGE,
            DyeColor.YELLOW,
            DyeColor.GREEN,
            DyeColor.CYAN,
            DyeColor.BLUE,
            DyeColor.PURPLE
    };

    public OpalLight(IEventBus eventBus) {
        DeferredRegister.Blocks blocks = DeferredRegister.createBlocks(MODID);
        DeferredRegister.Items items = DeferredRegister.createItems(MODID);
        DeferredRegister<CreativeModeTab> tabs = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
        blocks.register(eventBus);
        items.register(eventBus);
        tabs.register(eventBus);
        for (DyeColor color : COLORS) {
            String name = color.getName() + "_lantern";
            DeferredHolder<Block, LanternBlock> block = blocks.register(name, () -> new LanternBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .forceSolidOn()
                    .requiresCorrectToolForDrops()
                    .strength(3.5F)
                    .sound(SoundType.LANTERN)
                    .lightLevel(state -> 15)
                    .noOcclusion()
                    .pushReaction(PushReaction.DESTROY)));
            items.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        }
        tabs.register("lanterns", () -> CreativeModeTab.builder()
                .icon(BuiltInRegistries.ITEM.get(asResource("green_lantern"))::getDefaultInstance)
                .title(Component.translatable("itemGroup.opallight")).displayItems(((parameters, output) -> {
                    for (DeferredHolder<Item, ? extends Item> entry : items.getEntries()) {
                        output.accept(entry.get());
                    }
                })).build());
    }

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        CompletableFuture<HolderLookup.Provider> registries = event.getLookupProvider();

        boolean client = event.includeClient();
        generator.addProvider(client, new OpalDataProvider(output, registries, MODID));
        generator.addProvider(client, new OpalBlockStateProvider(output, event.getExistingFileHelper()));

        boolean server = event.includeServer();
        generator.addProvider(server, new OpalRecipeProvider(output, registries));
    }
}
