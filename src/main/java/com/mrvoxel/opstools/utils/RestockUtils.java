package com.mrvoxel.opstools.utils;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Inventory / container helpers shared by Auto Restock, Base Guardian and the Ender Chest Farmer. */
public final class RestockUtils {
    private RestockUtils() {}

    /** Items of this type in the main inventory, hotbar and offhand. */
    public static int countItem(Item item) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.is(item)) count += offhand.getCount();
        return count;
    }

    /** Empty slots in the main inventory + hotbar. */
    public static int emptySlots() {
        if (mc.player == null) return 0;
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) empty++;
        }
        return empty;
    }

    public static boolean isShulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock;
    }

    /** How many of {@code item} are inside the shulker box stack. */
    public static int shulkerItemCount(ItemStack shulker, Item item) {
        ItemContainerContents contents = shulker.get(DataComponents.CONTAINER);
        if (contents == null) return 0;
        int count = 0;
        for (ItemStack inner : contents.stream().toList()) {
            if (!inner.isEmpty() && inner.is(item)) count += inner.getCount();
        }
        return count;
    }

    /** Shulker boxes in the inventory that contain at least one of {@code item}. */
    public static int countShulkersWith(Item item) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (isShulker(stack) && shulkerItemCount(stack, item) > 0) count++;
        }
        return count;
    }

    /** All shulker boxes in the inventory, whatever they contain. */
    public static int countShulkers() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (isShulker(mc.player.getInventory().getItem(i))) count++;
        }
        return count;
    }

    /** True while a chest / shulker / other container screen is open (not just your own inventory). */
    public static boolean containerOpen() {
        return mc.player != null && mc.player.containerMenu != null && mc.player.containerMenu != mc.player.inventoryMenu;
    }

    /** Slot id in the player inventory menu for an inventory index (0-8 hotbar, 9-35 main). */
    public static int menuSlot(int inventoryIndex) {
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }

    /** Turns the player to look at the point, on the client and for the server (rotation packet). */
    public static void snapLook(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        PvpUtils.faceAngles(yaw, pitch);
    }
}
