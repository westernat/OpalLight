package org.mesdag.opallight.light.engine;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.mesdag.opallight.light.data.DynamicLightDefinition;
import org.mesdag.opallight.light.data.LightDataLoader;

final class DynamicLightSources {
    private static final EquipmentSlot[] EQUIPMENT_SLOTS = EquipmentSlot.values();
    // 客户端线程逐刻复用；传播器只在发生变化时复制结果。
    private static final Long2ObjectOpenHashMap<LightSource> current = new Long2ObjectOpenHashMap<>();

    private DynamicLightSources() {}

    static int update(ClientLevel level) {
        current.clear();
        Player localPlayer = Minecraft.getInstance().player;
        if (localPlayer != null) collect(level, localPlayer, current);
        for (Entity entity : level.entitiesForRendering()) {
            if (entity != localPlayer) collect(level, entity, current);
        }
        return LightPropagator.replaceDynamicSources(current);
    }

    static void collect(Level level, Entity entity, Long2ObjectOpenHashMap<LightSource> sources) {
        if (entity.isRemoved() || entity.isSpectator()) return;
        boolean living = entity instanceof LivingEntity;
        double height = living ? entity.getEyeY() - 0.3
                : entity instanceof ItemEntity ? entity.getY() + 0.5 : entity.getY() + entity.getBbHeight() * 0.5;
        BlockPos pos = BlockPos.containing(entity.getX(), height, entity.getZ());
        var definition = LightDataLoader.INSTANCE.getEntityDefinition(entity);
        addDefinition(entity, 0, pos, definition, sources);
        switch (entity) {
            case LivingEntity equipped -> {
                for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
                    addItem(level, entity, 3 + slot.ordinal(), pos, equipped.getItemBySlot(slot), sources);
                }
            }
            case ItemEntity item -> addItem(level, entity, 1, pos, item.getItem(), sources);
            case ItemFrame frame -> addItem(level, entity, 1, pos, frame.getItem(), sources);
            case Display.ItemDisplay display -> addItem(level, entity, 1, pos, display.getSlot(0).get(), sources);
            case ItemSupplier projectile -> addItem(level, entity, 1, pos, projectile.getItem(), sources);
            case FallingBlockEntity block -> addBlock(level, entity, pos, block.getBlockState(), sources);
            case Display.BlockDisplay display when display.blockRenderState() != null ->
                addBlock(level, entity, pos, display.blockRenderState().blockState(), sources);
            default -> {
            }
        }
    }

    // 实体编号和光源槽分别占 32 位，避免装备扩展后与其他实体冲突。
    static long sourceKey(int entityId, int slot) {
        return (long) entityId << 32 | Integer.toUnsignedLong(slot);
    }

    private static void addItem(Level level, Entity entity, int slot, BlockPos pos, ItemStack stack, Long2ObjectOpenHashMap<LightSource> sources) {
        var light = LightSourceDefinitions.itemWithEmissive(level, pos, stack, entity, entity.isOnFire());
        if (light != null) sources.put(sourceKey(entity.getId(), slot), new LightSource(pos.asLong(), light.left(), light.rightInt()));
    }

    private static void addBlock(Level level, Entity entity, BlockPos pos, BlockState state, Long2ObjectOpenHashMap<LightSource> sources) {
        var light = LightSourceDefinitions.colorWithEmissive(level, pos, state);
        if (light != null) sources.put(sourceKey(entity.getId(), 2), new LightSource(pos.asLong(), light.left(), light.rightInt()));
    }

    private static void addDefinition(Entity entity, int slot, BlockPos pos, DynamicLightDefinition definition, Long2ObjectOpenHashMap<LightSource> sources) {
        if (definition == null || definition.lightLevel() == 0) return;
        sources.put(sourceKey(entity.getId(), slot), new LightSource(pos.asLong(),
                LightSourceDefinitions.profile(definition), definition.lightLevel()));
    }
}
