package org.mesdag.opallight.light;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/// 将玩家双手和掉落的发光方块物品转换为当前刻的彩光源。
final class DynamicLightSources {
    private DynamicLightSources() {}

    static int update(ClientLevel level) {
        Int2ObjectOpenHashMap<LightSource> current = new Int2ObjectOpenHashMap<>();
        Player localPlayer = Minecraft.getInstance().player;
        if (localPlayer != null) addPlayer(level, localPlayer, current);
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == localPlayer) continue;
            if (entity instanceof Player player) {
                addPlayer(level, player, current);
            } else if (entity instanceof ItemEntity item) {
                BlockPos pos = BlockPos.containing(item.getX(), item.getY() + 0.5, item.getZ());
                add(level, item.getId() * 4 + 2, pos, item.getItem(), current);
            }
        }
        return LightPropagator.replaceDynamicSources(current);
    }

    private static void addPlayer(ClientLevel level, Player player, Int2ObjectOpenHashMap<LightSource> current) {
        BlockPos pos = BlockPos.containing(player.getX(), player.getEyeY() - 0.3, player.getZ());
        add(level, player.getId() * 4, pos, player.getMainHandItem(), current);
        add(level, player.getId() * 4 + 1, pos, player.getOffhandItem(), current);
    }

    private static void add(ClientLevel level, int id, BlockPos pos, ItemStack stack, Int2ObjectOpenHashMap<LightSource> current) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) return;
        var colored = LightSourceDefinitions.colorWithEmissive(level, pos, blockItem.getBlock().defaultBlockState());
        if (colored != null) {
            current.put(id, new LightSource(pos.asLong(), colored.left(), colored.rightInt()));
        }
    }
}
