package com.mrvoxel.opstools.stash;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads what is inside an open container menu: the loose items (grouped by item + custom name) and, for every shulker
 * box in it, the box's name and its contents.
 */
public final class ContainerInspector {
    private ContainerInspector() {}

    public record Inspection(List<StashManager.ItemEntry> items, List<StashManager.ShulkerInfo> shulkers) {}

    /** @param slotCount number of slots that belong to the container itself (the player inventory is excluded). */
    public static Inspection inspect(AbstractContainerMenu menu, int slotCount) {
        Map<String, StashManager.ItemEntry> loose = new LinkedHashMap<>();
        List<StashManager.ShulkerInfo> shulkers = new ArrayList<>();

        int n = Math.min(slotCount, menu.slots.size());
        for (int i = 0; i < n; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;

            add(loose, itemId(stack), customName(stack), stack.getCount());

            if (isShulker(stack)) {
                StashManager.ShulkerInfo info = new StashManager.ShulkerInfo();
                info.name = customName(stack);
                info.items = new ArrayList<>(contents(stack).values());
                shulkers.add(info);
            }
        }
        return new Inspection(new ArrayList<>(loose.values()), shulkers);
    }

    private static Map<String, StashManager.ItemEntry> contents(ItemStack shulker) {
        Map<String, StashManager.ItemEntry> out = new LinkedHashMap<>();
        ItemContainerContents container = shulker.get(DataComponents.CONTAINER);
        if (container == null) return out;

        for (ItemStack inner : container.stream().toList()) {
            if (inner.isEmpty()) continue;
            add(out, itemId(inner), customName(inner), inner.getCount());
        }
        return out;
    }

    private static void add(Map<String, StashManager.ItemEntry> map, String id, String name, int count) {
        String key = id + "|" + (name == null ? "" : name);
        StashManager.ItemEntry entry = map.get(key);
        if (entry == null) {
            entry = new StashManager.ItemEntry();
            entry.itemId = id;
            entry.customName = name;
            map.put(key, entry);
        }
        entry.count += count;
    }

    private static String itemId(ItemStack stack) {
        return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static String customName(ItemStack stack) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name == null ? null : name.getString();
    }

    private static boolean isShulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock;
    }
}
