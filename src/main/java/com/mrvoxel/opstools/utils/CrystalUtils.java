package com.mrvoxel.opstools.utils;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * End crystal maths for {@link com.mrvoxel.opstools.modules.CrystalAuraPlus}.
 *
 * Follows the vanilla explosion formula (power 6): distance falloff, a per-point exposure check that walks the blocks
 * between the blast and the body, difficulty scaling for players, armor + toughness, and the resistance effect.
 * Enchantments (Blast Protection) cannot be read reliably across versions, so they are an explicit assumption
 * ({@link Prot#blast}) instead of a guess hidden in the maths.
 *
 * Block lookups are cached per tick ({@link #newTick()}) because the same blocks are sampled by hundreds of rays.
 */
public final class CrystalUtils {
    private CrystalUtils() {}

    public static final double POWER = 6.0;
    private static final double DIAMETER = POWER * 2.0;

    /** Blocks an explosion cannot remove. Only used by the optional "ignore terrain" mode. */
    private static final Set<Block> HARD = Set.of(
        Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.BEDROCK, Blocks.NETHERITE_BLOCK,
        Blocks.ENDER_CHEST, Blocks.RESPAWN_ANCHOR, Blocks.ENCHANTING_TABLE,
        Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
        Blocks.REINFORCED_DEEPSLATE, Blocks.BARRIER, Blocks.END_PORTAL_FRAME
    );

    /**
     * Everything about an entity that reduces crystal damage.
     * @param armor      armor points
     * @param toughness  armor toughness
     * @param resistance damage multiplier from the Resistance effect (1 = none, 0 = immune)
     * @param blast      extra reduction fraction (0..0.8) standing in for Protection / Blast Protection
     */
    public record Prot(double armor, double toughness, double resistance, double blast) {}

    // ------------------------------------------------------------------ per tick cache

    private static final Long2ByteOpenHashMap SOLID = new Long2ByteOpenHashMap();
    private static final Long2ByteOpenHashMap SOLID_HARD = new Long2ByteOpenHashMap();

    static {
        SOLID.defaultReturnValue((byte) -1);
        SOLID_HARD.defaultReturnValue((byte) -1);
    }

    /** Call once at the start of every tick (and whenever the world may have changed a lot). */
    public static void newTick() {
        SOLID.clear();
        SOLID_HARD.clear();
    }

    private static boolean solid(BlockPos.MutableBlockPos pos, boolean onlyHard) {
        long key = pos.asLong();
        Long2ByteOpenHashMap cache = onlyHard ? SOLID_HARD : SOLID;
        byte cached = cache.get(key);
        if (cached != -1) return cached == 1;

        BlockState state = mc.level.getBlockState(pos);
        boolean result;
        if (state.isAir()) result = false;
        else if (onlyHard) result = HARD.contains(state.getBlock()) && !state.getCollisionShape(mc.level, pos).isEmpty();
        else result = !state.getCollisionShape(mc.level, pos).isEmpty();

        cache.put(key, (byte) (result ? 1 : 0));
        return result;
    }

    // ------------------------------------------------------------------ protection

    public static Prot protection(LivingEntity entity, double blastAssumption, boolean scaleByArmor) {
        double armor = entity.getArmorValue();
        double toughness = entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS);

        double resistance = 1.0;
        int amp = effectAmplifier(entity, "resistance");
        if (amp >= 0) resistance = Math.max(0.0, (25.0 - (amp + 1) * 5.0) / 25.0);

        double blast = blastAssumption;
        // Someone in no armor cannot have enchanted armor, so scale the assumption by how much armor they really wear.
        if (scaleByArmor) blast *= Math.min(1.0, armor / 20.0);

        return new Prot(armor, toughness, resistance, Math.max(0.0, Math.min(0.8, blast)));
    }

    /** Amplifier of the effect whose id ends with the given name (e.g. "weakness"), or -1 if the entity has none. */
    public static int effectAmplifier(LivingEntity entity, String name) {
        String suffix = "." + name;
        for (MobEffectInstance effect : entity.getActiveEffects()) {
            if (effect.getDescriptionId().endsWith(suffix)) return effect.getAmplifier();
        }
        return -1;
    }

    // ------------------------------------------------------------------ damage

    /** Highest damage possible at this distance (full exposure). Cheap - used to skip the expensive ray checks. */
    public static double maxDamage(Vec3 center, Vec3 feet, Prot prot) {
        double dist = Math.sqrt(feet.distanceToSqr(center)) / DIAMETER;
        if (dist > 1.0) return 0.0;
        return reduce(Math.floor(((1.0 - dist) * (1.0 - dist) + (1.0 - dist)) / 2.0 * 7.0 * DIAMETER + 1.0), prot);
    }

    /**
     * Estimated damage a crystal exploding at {@code center} deals to a player standing at {@code feet} with hitbox
     * {@code box}.
     *
     * @param ignoreSoft treat everything except blast-proof blocks as see-through (optimistic - for enemy damage only)
     */
    public static double damage(Vec3 center, Vec3 feet, AABB box, Prot prot, boolean ignoreSoft) {
        double dist = Math.sqrt(feet.distanceToSqr(center)) / DIAMETER;
        if (dist > 1.0) return 0.0;

        double impact = (1.0 - dist) * exposure(center, box, ignoreSoft);
        if (impact <= 0.0) return 0.0;

        return reduce(Math.floor((impact * impact + impact) / 2.0 * 7.0 * DIAMETER + 1.0), prot);
    }

    private static double reduce(double damage, Prot prot) {
        Difficulty difficulty = mc.level.getDifficulty();
        if (difficulty == Difficulty.PEACEFUL) return 0.0;
        if (difficulty == Difficulty.EASY) damage = Math.min(damage / 2.0 + 1.0, damage);
        else if (difficulty == Difficulty.HARD) damage = damage * 3.0 / 2.0;

        double f = 2.0 + prot.toughness() / 4.0;
        double g = Math.min(20.0, Math.max(prot.armor() * 0.2, prot.armor() - damage / f));
        damage *= 1.0 - g / 25.0;
        damage *= prot.resistance();
        damage *= 1.0 - prot.blast();
        return Math.max(0.0, damage);
    }

    /** Vanilla's "seen percent": share of a grid of points on the body that the blast can reach in a straight line. */
    public static double exposure(Vec3 center, AABB box, boolean ignoreSoft) {
        double sx = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double sy = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double sz = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        double ox = (1.0 - Math.floor(1.0 / sx) * sx) / 2.0;
        double oz = (1.0 - Math.floor(1.0 / sz) * sz) / 2.0;

        if (sx < 0.0 || sy < 0.0 || sz < 0.0) return 0.0;

        int total = 0;
        int seen = 0;
        for (double u = 0.0; u <= 1.0; u += sx) {
            for (double v = 0.0; v <= 1.0; v += sy) {
                for (double w = 0.0; w <= 1.0; w += sz) {
                    Vec3 point = new Vec3(
                        box.minX + (box.maxX - box.minX) * u + ox,
                        box.minY + (box.maxY - box.minY) * v,
                        box.minZ + (box.maxZ - box.minZ) * w + oz
                    );
                    if (!blocked(point, center, ignoreSoft)) seen++;
                    total++;
                }
            }
        }
        return total == 0 ? 0.0 : seen / (double) total;
    }

    // ------------------------------------------------------------------ line of sight

    /** True if a straight line between the two points is clear of blocks. */
    public static boolean visible(Vec3 from, Vec3 to) {
        return !blocked(from, to, false);
    }

    /** Walks every block voxel on the segment (Amanatides-Woo) and reports whether one of them is solid. */
    public static boolean blocked(Vec3 from, Vec3 to, boolean ignoreSoft) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;

        int x = (int) Math.floor(from.x);
        int y = (int) Math.floor(from.y);
        int z = (int) Math.floor(from.z);
        int endX = (int) Math.floor(to.x);
        int endY = (int) Math.floor(to.y);
        int endZ = (int) Math.floor(to.z);

        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;

        double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);

        double maxX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? (x + 1 - from.x) : (from.x - x)) * deltaX;
        double maxY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? (y + 1 - from.y) : (from.y - y)) * deltaY;
        double maxZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? (z + 1 - from.z) : (from.z - z)) * deltaZ;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int guard = 0; guard < 96; guard++) {
            if (solid(pos.set(x, y, z), ignoreSoft)) return true;
            if (x == endX && y == endY && z == endZ) return false;

            if (maxX < maxY && maxX < maxZ) {
                x += stepX;
                maxX += deltaX;
            } else if (maxY < maxZ) {
                y += stepY;
                maxY += deltaY;
            } else {
                z += stepZ;
                maxZ += deltaZ;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ geometry

    /** Distance from a point to the closest point of a box (0 if inside) - what the server uses for interaction range. */
    public static double distanceToBox(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0), point.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
