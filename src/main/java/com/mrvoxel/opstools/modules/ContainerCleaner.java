package com.mrvoxel.opstools.modules;

import com.mrvoxel.opstools.OpsTools;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Container Cleaner - throws unwanted items out of a container while it is open.
 *
 * Works in chests, double chests, barrels, ender chests, shulker boxes, hoppers and dispensers/droppers.
 * Blacklist: throws every item that is in the list. Whitelist: throws everything that is NOT in the list.
 */
public class ContainerCleaner extends Module {
    public enum Mode { Blacklist, Whitelist }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Blacklist: throw the listed items. Whitelist: throw everything except the listed items.")
        .defaultValue(Mode.Blacklist)
        .build()
    );
    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("The items the mode works with.")
        .defaultValue(List.of(
            Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.COBBLESTONE, Items.DIRT,
            Items.GRAVEL, Items.NETHERRACK, Items.ANDESITE, Items.DIORITE, Items.GRANITE
        ))
        .build()
    );
    private final Setting<Boolean> includeInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("also-inventory")
        .description("Also throw unwanted items from your own inventory while a container is open.")
        .defaultValue(false)
        .build()
    );
    private final Setting<Integer> stacksPerAction = sgGeneral.add(new IntSetting.Builder()
        .name("stacks-per-action")
        .description("Stacks thrown per action.")
        .defaultValue(1)
        .min(1).max(10).sliderMax(10)
        .build()
    );
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between actions. Raise it if the server kicks you or puts items back.")
        .defaultValue(1)
        .min(0).max(20).sliderMax(10)
        .build()
    );
    private final Setting<Boolean> closeWhenDone = sgGeneral.add(new BoolSetting.Builder()
        .name("close-when-done")
        .description("Close the container once everything unwanted has been thrown.")
        .defaultValue(false)
        .build()
    );
    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Tell you in chat how many stacks were thrown.")
        .defaultValue(true)
        .build()
    );

    private int lastMenuId = -1;
    private int cooldown = 0;
    private int dropped = 0;
    private boolean announced = false;

    public ContainerCleaner() {
        super(OpsTools.CATEGORY, "container-cleaner", "Throws unwanted items out of containers you open.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    private void reset() {
        lastMenuId = -1;
        cooldown = 0;
        dropped = 0;
        announced = false;
    }

    private static boolean supported(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu
            || menu instanceof HopperMenu || menu instanceof DispenserMenu;
    }

    private boolean unwanted(Item item) {
        boolean listed = items.get().contains(item);
        return mode.get() == Mode.Blacklist ? listed : !listed;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.gameMode == null) return;

        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == mc.player.inventoryMenu || !supported(menu)) {
            reset();
            return;
        }

        if (menu.containerId != lastMenuId) { // a new container was opened
            reset();
            lastMenuId = menu.containerId;
        }

        if (!menu.getCarried().isEmpty()) return;
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // The last 36 menu slots are always the player's inventory, everything before them belongs to the container.
        int containerSlots = menu.slots.size() - 36;
        int limit = includeInventory.get() ? menu.slots.size() : containerSlots;

        int thrown = 0;
        for (int i = 0; i < limit && thrown < stacksPerAction.get(); i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !unwanted(stack.getItem())) continue;
            if (!slot.mayPickup(mc.player)) continue;

            // THROW with button 1 throws the whole stack. The slot id is the position in the menu, not slot.index.
            mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 1, ClickType.THROW, mc.player);
            thrown++;
            dropped++;
        }

        if (thrown > 0) {
            cooldown = delay.get();
            return;
        }

        if (dropped > 0 && !announced) {
            announced = true;
            if (notify.get()) info("Threw %d unwanted stack%s.", dropped, dropped == 1 ? "" : "s");
            if (closeWhenDone.get()) mc.player.closeContainer();
        }
    }
}
