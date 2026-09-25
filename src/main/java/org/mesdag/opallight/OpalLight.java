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
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.mesdag.opallight.impl.OpalBlockStateProvider;
import org.mesdag.opallight.impl.OpalDataProvider;
import org.mesdag.opallight.impl.OpalRecipeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

@Mod(OpalLight.MODID)
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

    public OpalLight(FMLJavaModLoadingContext context) {
        IEventBus eventBus = context.getModEventBus();
        DeferredRegister<Block> blocks = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
        DeferredRegister<Item> items = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
        DeferredRegister<CreativeModeTab> tabs = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
        blocks.register(eventBus);
        items.register(eventBus);
        tabs.register(eventBus);
        for (DyeColor color : COLORS) {
            String name = color.getName() + "_lantern";
            RegistryObject<LanternBlock> block = blocks.register(name, () -> new LanternBlock(BlockBehaviour.Properties.of()
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
                    for (RegistryObject<Item> entry : items.getEntries()) {
                        output.accept(entry.get());
                    }
                })).build());
    }

    public static ResourceLocation asResource(String path) {
        /// Forge 1.20.1 回移了 1.21 的工厂方法，构造器已标记为待移除。
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    /// 1.20.1 的数据生成事件位于模组事件总线，无法与 {@code @Mod} 类上的订阅混用。
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBusEvents {
        @SubscribeEvent
        public static void gatherData(GatherDataEvent event) {
            DataGenerator generator = event.getGenerator();
            PackOutput output = generator.getPackOutput();
            CompletableFuture<HolderLookup.Provider> registries = event.getLookupProvider();

            boolean client = event.includeClient();
            generator.addProvider(client, new OpalDataProvider(output, registries, MODID));
            generator.addProvider(client, new OpalBlockStateProvider(output, event.getExistingFileHelper()));

            boolean server = event.includeServer();
            generator.addProvider(server, new OpalRecipeProvider(output));
        }
    }
}
