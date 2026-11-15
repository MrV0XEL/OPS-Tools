package com.mrvoxel.opstools.modules;

import com.mrvoxel.opstools.OpsTools;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Kill Effects - a client-side effect where something dies, in the style of the kill effects on big minigame servers.
 *
 * Pick WHICH entities trigger it (players by default; add any mobs you like) and WHICH effect plays:
 * Lightning, Explosion, Firework, Flames, Hearts, Soul, Portal, Totem, Notes, Angry, Smoke, Enchant, Stars, Snow,
 * Tornado (a rising spiral) or Random. Everything is visual and only exists on your screen.
 *
 * Safety: the packet handler only queues the death; effects are created on the game thread. Nothing crashes when the
 * dead entity is unknown, and the lightning bolt gets a negative id so it can't clash with a real server entity.
 */
public class KillEffects extends Module {
    private static final byte EVENT_DEATH = 3;

    public enum Effect {
        Lightning, Explosion, Firework, Flames, Hearts, Soul, Portal, Totem, Notes, Angry, Smoke, Enchant, Stars, Snow, Tornado, Random
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgEffect = settings.createGroup("Effect");

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Which entities trigger an effect when they die. Add mobs here.")
        .defaultValue(defaultEntities())
        .build()
    );
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Only deaths within this many blocks of you trigger an effect.")
        .defaultValue(64).min(8).max(512).sliderMax(256)
        .build()
    );
    private final Setting<Boolean> avoidSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("avoid-self")
        .description("No effect when you die yourself.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> maxPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-per-tick")
        .description("Safety limit for mass deaths: effects started per tick.")
        .defaultValue(3).min(1).max(10).sliderMax(10)
        .build()
    );

    private final Setting<Effect> effect = sgEffect.add(new EnumSetting.Builder<Effect>()
        .name("effect")
        .description("Which effect to play. Random picks a different one each time.")
        .defaultValue(Effect.Lightning)
        .build()
    );
    private final Setting<Boolean> addLightning = sgEffect.add(new BoolSetting.Builder()
        .name("add-lightning")
        .description("Also strike lightning on top of the chosen effect.")
        .defaultValue(false)
        .visible(() -> effect.get() != Effect.Lightning && effect.get() != Effect.Random)
        .build()
    );
    private final Setting<Double> intensity = sgEffect.add(new DoubleSetting.Builder()
        .name("intensity")
        .description("Multiplies the number of particles.")
        .defaultValue(1.0).min(0.25).max(4.0).sliderMax(3.0)
        .build()
    );

    private record Death(Vec3 pos, double height) {}

    /** A rising spiral that emits one ring of particles per tick. */
    private static final class Spiral {
        final Vec3 pos;
        int age = 0;
        Spiral(Vec3 pos) { this.pos = pos; }
    }

    private final ConcurrentLinkedQueue<Death> queue = new ConcurrentLinkedQueue<>();
    private final List<Spiral> spirals = new ArrayList<>();
    private int nextBoltId = -2_000_000;

    public KillEffects() {
        super(OpsTools.CATEGORY, "kill-effects", "Plays a (client-side) effect where players or mobs die: lightning, fireworks, explosions and more.");
    }

    private static Set<EntityType<?>> defaultEntities() {
        Set<EntityType<?>> set = new HashSet<>();
        set.add(EntityType.PLAYER);
        return set;
    }

    @Override
    public void onActivate() {
        queue.clear();
        spirals.clear();
    }

    // ------------------------------------------------------------------ input

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.level == null || mc.player == null) return;
        if (!(event.packet instanceof ClientboundEntityEventPacket packet)) return;
        if (packet.getEventId() != EVENT_DEATH) return;

        Entity entity = packet.getEntity(mc.level);
        if (entity == null) return;
        if (entity == mc.player && avoidSelf.get()) return;
        if (!entities.get().contains(entity.getType())) return;
        if (mc.player.distanceTo(entity) > range.get()) return;

        queue.add(new Death(entity.position(), entity.getBbHeight()));
    }

    // ------------------------------------------------------------------ playing

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null || mc.player == null) {
            queue.clear();
            spirals.clear();
            return;
        }

        for (int i = 0; i < maxPerTick.get(); i++) {
            Death death = queue.poll();
            if (death == null) break;
            play(death);
        }

        tickSpirals();
    }

    private void play(Death death) {
        Effect chosen = effect.get();
        if (chosen == Effect.Random) {
            Effect[] all = Effect.values();
            chosen = all[ThreadLocalRandom.current().nextInt(all.length - 1)]; // last entry is Random itself
        }

        Vec3 center = death.pos().add(0, Math.max(0.5, death.height() / 2.0), 0);

        switch (chosen) {
            case Lightning -> strike(death.pos());
            case Explosion -> {
                burst(ParticleTypes.EXPLOSION_EMITTER, center, 1, 0.1, 0.0, 0.0);
                burst(ParticleTypes.POOF, center, 25, 0.5, 0.12, 0.05);
            }
            case Firework -> burst(ParticleTypes.FIREWORK, center, 60, 0.2, 0.22, 0.05);
            case Flames -> burst(ParticleTypes.FLAME, center, 45, 0.4, 0.06, 0.12);
            case Hearts -> burst(ParticleTypes.HEART, center, 12, 0.6, 0.0, 0.05);
            case Soul -> {
                burst(ParticleTypes.SOUL, center, 30, 0.4, 0.03, 0.08);
                burst(ParticleTypes.SOUL_FIRE_FLAME, center, 15, 0.3, 0.03, 0.1);
            }
            case Portal -> burst(ParticleTypes.PORTAL, center, 90, 0.6, 0.4, 0.1);
            case Totem -> burst(ParticleTypes.TOTEM_OF_UNDYING, center, 60, 0.3, 0.35, 0.2);
            case Notes -> burst(ParticleTypes.NOTE, center, 15, 0.6, 0.0, 0.05);
            case Angry -> burst(ParticleTypes.ANGRY_VILLAGER, center, 8, 0.6, 0.0, 0.0);
            case Smoke -> burst(ParticleTypes.LARGE_SMOKE, center, 40, 0.4, 0.02, 0.1);
            case Enchant -> burst(ParticleTypes.ENCHANT, center, 80, 0.8, 0.1, 0.1);
            case Stars -> burst(ParticleTypes.END_ROD, center, 40, 0.2, 0.25, 0.05);
            case Snow -> burst(ParticleTypes.SNOWFLAKE, center, 50, 0.6, 0.05, 0.02);
            case Tornado -> spirals.add(new Spiral(death.pos()));
            default -> { }
        }

        if (addLightning.get() && effect.get() != Effect.Lightning && effect.get() != Effect.Random) {
            strike(death.pos());
        }
    }

    // ------------------------------------------------------------------ building blocks

    private void strike(Vec3 pos) {
        LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, mc.level);
        bolt.setPos(pos.x, pos.y, pos.z);
        bolt.setId(nextBoltId--);
        if (nextBoltId < -2_100_000) nextBoltId = -2_000_000;
        mc.level.addEntity(bolt);
    }

    /**
     * @param spread random offset around the center, in blocks
     * @param speed  outward speed of each particle
     * @param lift   extra upward speed
     */
    private void burst(ParticleOptions type, Vec3 center, int baseCount, double spread, double speed, double lift) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int count = Math.max(1, (int) Math.round(baseCount * intensity.get()));

        for (int i = 0; i < count; i++) {
            double ox = (random.nextDouble() - 0.5) * 2 * spread;
            double oy = (random.nextDouble() - 0.5) * 2 * spread;
            double oz = (random.nextDouble() - 0.5) * 2 * spread;

            double len = Math.sqrt(ox * ox + oy * oy + oz * oz);
            double dx = len > 1.0E-6 ? ox / len * speed : 0;
            double dy = len > 1.0E-6 ? oy / len * speed : 0;
            double dz = len > 1.0E-6 ? oz / len * speed : 0;

            mc.level.addParticle(type, center.x + ox, center.y + oy, center.z + oz, dx, dy + lift, dz);
        }
    }

    private void tickSpirals() {
        Iterator<Spiral> it = spirals.iterator();
        while (it.hasNext()) {
            Spiral spiral = it.next();
            if (spiral.age > 30) {
                it.remove();
                continue;
            }

            int arms = Math.max(2, (int) Math.round(2 * intensity.get()));
            for (int arm = 0; arm < arms; arm++) {
                double angle = spiral.age * 0.55 + arm * (Math.PI * 2 / arms);
                double radius = 0.4 + spiral.age * 0.03;
                double x = spiral.pos.x + Math.cos(angle) * radius;
                double z = spiral.pos.z + Math.sin(angle) * radius;
                double y = spiral.pos.y + spiral.age * 0.09;
                mc.level.addParticle(ParticleTypes.CLOUD, x, y, z, 0, 0.02, 0);
                mc.level.addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0.01, 0);
            }
            spiral.age++;
        }
    }
}
