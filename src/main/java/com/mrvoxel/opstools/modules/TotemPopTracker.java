package com.mrvoxel.opstools.modules;

import com.mrvoxel.opstools.OpsTools;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Totem Pop Tracker - counts how many totems every player pops and tells you in chat.
 *
 * The server sends an "entity event" packet with id 35 when a totem of undying is used and id 3 when a living entity
 * dies. Packets arrive on the network thread, so they are only queued there and handled on the game thread in onTick.
 */
public class TotemPopTracker extends Module {
    private static final byte EVENT_DEATH = 3;
    private static final byte EVENT_TOTEM = 35;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> trackSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("track-self")
        .description("Also report your own totem pops.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> announceDeath = sgGeneral.add(new BoolSetting.Builder()
        .name("announce-death")
        .description("Report a player's death together with how many totems they popped before it.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Only report players within this many blocks of you.")
        .defaultValue(128).min(8).max(512).sliderMax(256)
        .build()
    );

    private record Event(Player player, boolean death) {}

    private final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Integer> pops = new HashMap<>();
    private Object lastLevel = null;

    public TotemPopTracker() {
        super(OpsTools.CATEGORY, "totem-pop-tracker", "Counts and reports every totem pop and death of nearby players.");
    }

    @Override
    public void onActivate() {
        queue.clear();
        pops.clear();
        lastLevel = null;
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.level == null) return;
        if (!(event.packet instanceof ClientboundEntityEventPacket packet)) return;

        byte id = packet.getEventId();
        if (id != EVENT_TOTEM && id != EVENT_DEATH) return;

        Entity entity = packet.getEntity(mc.level);
        if (entity instanceof Player player) queue.add(new Event(player, id == EVENT_DEATH));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) {
            queue.clear();
            return;
        }

        // New world / dimension: old counts no longer mean anything.
        if (lastLevel != mc.level) {
            lastLevel = mc.level;
            pops.clear();
            queue.clear();
            return;
        }

        Event e;
        while ((e = queue.poll()) != null) {
            Player player = e.player();
            boolean self = player == mc.player;
            if (self && !trackSelf.get()) continue;
            if (!self && mc.player.distanceTo(player) > range.get()) continue;

            String name = self ? "You" : player.getName().getString();
            UUID id = player.getUUID();

            if (e.death()) {
                Integer count = pops.remove(id);
                if (announceDeath.get() && count != null && count > 0) {
                    info(name + " died after popping " + count + " totem" + (count == 1 ? "" : "s") + ".");
                }
            } else {
                int count = pops.merge(id, 1, Integer::sum);
                info(name + " popped " + count + " totem" + (count == 1 ? "" : "s") + ".");
            }
        }
    }
}
