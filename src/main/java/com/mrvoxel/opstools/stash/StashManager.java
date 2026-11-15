package com.mrvoxel.opstools.stash;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mrvoxel.opstools.OpsTools;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Stores every registered stash and the indexed contents of its containers, and keeps them in
 * {@code <meteor folder>/ops-tools/stashes.json}.
 *
 * Loading and saving happen on one background thread, so the game thread never waits for the disk. All data access goes
 * through a read / write lock, so rendering (read) and indexing (write) can safely run on different threads.
 * {@link #version()} goes up on every change so renderers can cache what they build.
 */
public final class StashManager {
    // ------------------------------------------------------------------ data

    public static class ItemEntry {
        public String itemId;
        public String customName;
        public int count;
    }

    public static class ShulkerInfo {
        public String name;
        public List<ItemEntry> items = new ArrayList<>();
    }

    public static class ContainerData {
        public int x, y, z;
        public String type;
        public List<ItemEntry> items = new ArrayList<>();
        public List<ShulkerInfo> shulkers = new ArrayList<>();
        public long updated;
    }

    public static class Stash {
        public String stashName;
        public String dimension;
        public double centerX, centerY, centerZ;
        public List<ContainerData> containers = new ArrayList<>();
    }

    public enum UpsertStatus { ADDED, UPDATED, UNASSIGNED }

    /** @param stash the stash the container was filed under (null when UNASSIGNED). */
    public record UpsertResult(UpsertStatus status, Stash stash) {}

    /** One search match. {@code inShulker} means the item is inside a shulker box in that container. */
    public record SearchHit(Stash stash, ContainerData container, String itemId, String customName, long count,
                            double score, boolean inShulker) {}

    private static class Root {
        List<Stash> stashes = new ArrayList<>();
    }

    // ------------------------------------------------------------------ singleton

    private static StashManager instance;

    public static synchronized StashManager get() {
        if (instance == null) instance = new StashManager();
        return instance;
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<Stash> stashes = new ArrayList<>();
    private final AtomicInteger version = new AtomicInteger();
    private final AtomicBoolean saveQueued = new AtomicBoolean(false);
    private boolean backedUp = false; // io thread only
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file = MeteorClient.FOLDER.toPath().resolve("ops-tools").resolve("stashes.json");
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "OPS-Tools stash io");
        thread.setDaemon(true);
        return thread;
    });

    private StashManager() {
        io.execute(this::load);
    }

    // ------------------------------------------------------------------ helpers shared with the module / commands

    /** Short dimension name used in the save file: overworld, the_nether, the_end (or the registry key of a custom one). */
    public static String dimensionName(Level level) {
        if (level == null) return "unknown";
        if (level.dimension() == Level.OVERWORLD) return "overworld";
        if (level.dimension() == Level.NETHER) return "the_nether";
        if (level.dimension() == Level.END) return "the_end";

        // "ResourceKey[minecraft:dimension / namespace:name]" -> "name"
        String text = String.valueOf(level.dimension());
        int slash = text.lastIndexOf("/ ");
        int end = text.lastIndexOf(']');
        if (slash >= 0 && end > slash) text = text.substring(slash + 2, end);
        int colon = text.indexOf(':');
        return colon >= 0 ? text.substring(colon + 1) : text;
    }

    public int version() {
        return version.get();
    }

    /** Runs the consumer with the stash list under the read lock. Do not keep the list outside the callback. */
    public void readLocked(Consumer<List<Stash>> consumer) {
        lock.readLock().lock();
        try {
            consumer.accept(Collections.unmodifiableList(stashes));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Same as {@link #readLocked} but returns a value computed under the lock. */
    public <T> T query(Function<List<Stash>, T> function) {
        lock.readLock().lock();
        try {
            return function.apply(Collections.unmodifiableList(stashes));
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<String> stashNames() {
        return query(list -> list.stream().map(s -> s.stashName).toList());
    }

    // ------------------------------------------------------------------ indexing

    /**
     * Files a container's contents under the nearest registered stash within {@code assignRadius} blocks (same
     * dimension). Returns UNASSIGNED, storing nothing, when no stash is close enough.
     */
    public UpsertResult upsertContainer(String dimension, BlockPos pos, String type, List<ItemEntry> items,
                                        List<ShulkerInfo> shulkers, int assignRadius) {
        lock.writeLock().lock();
        try {
            Stash best = null;
            double bestDistSq = (double) assignRadius * assignRadius;
            for (Stash stash : stashes) {
                if (!stash.dimension.equals(dimension)) continue;
                double dx = pos.getX() + 0.5 - stash.centerX;
                double dy = pos.getY() + 0.5 - stash.centerY;
                double dz = pos.getZ() + 0.5 - stash.centerZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq <= bestDistSq) {
                    bestDistSq = distSq;
                    best = stash;
                }
            }
            if (best == null) return new UpsertResult(UpsertStatus.UNASSIGNED, null);

            ContainerData data = null;
            for (ContainerData existing : best.containers) {
                if (existing.x == pos.getX() && existing.y == pos.getY() && existing.z == pos.getZ()) {
                    data = existing;
                    break;
                }
            }
            UpsertStatus status = data == null ? UpsertStatus.ADDED : UpsertStatus.UPDATED;
            if (data == null) {
                data = new ContainerData();
                data.x = pos.getX();
                data.y = pos.getY();
                data.z = pos.getZ();
                best.containers.add(data);
            }
            data.type = type;
            data.items = new ArrayList<>(items);
            data.shulkers = new ArrayList<>(shulkers);
            data.updated = System.currentTimeMillis();

            changed();
            return new UpsertResult(status, best);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------ stash management (used by .stash)

    /** @return the new stash, or null if the name is already taken. */
    public Stash createStash(String name, String dimension, double x, double y, double z) {
        lock.writeLock().lock();
        try {
            if (indexOf(name) >= 0) return null;
            Stash stash = new Stash();
            stash.stashName = name;
            stash.dimension = dimension;
            stash.centerX = x;
            stash.centerY = y;
            stash.centerZ = z;
            stashes.add(stash);
            changed();
            return stash;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean deleteStash(String name) {
        lock.writeLock().lock();
        try {
            int index = indexOf(name);
            if (index < 0) return false;
            stashes.remove(index);
            changed();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean renameStash(String oldName, String newName) {
        lock.writeLock().lock();
        try {
            int index = indexOf(oldName);
            if (index < 0) return false;
            int clash = indexOf(newName);
            if (clash >= 0 && clash != index) return false;
            stashes.get(index).stashName = newName;
            changed();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean moveStash(String name, String dimension, double x, double y, double z) {
        lock.writeLock().lock();
        try {
            int index = indexOf(name);
            if (index < 0) return false;
            Stash stash = stashes.get(index);
            stash.dimension = dimension;
            stash.centerX = x;
            stash.centerY = y;
            stash.centerZ = z;
            changed();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Forgets all indexed containers of a stash (the stash itself stays). @return how many were removed, -1 if unknown. */
    public int clearStash(String name) {
        lock.writeLock().lock();
        try {
            int index = indexOf(name);
            if (index < 0) return -1;
            Stash stash = stashes.get(index);
            int removed = stash.containers.size();
            stash.containers.clear();
            changed();
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Caller must hold the lock. Case-insensitive. */
    private int indexOf(String name) {
        for (int i = 0; i < stashes.size(); i++) {
            if (stashes.get(i).stashName.equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ searching (used by .find)

    /** Fuzzy search over every item (loose and inside shulker boxes) and shulker name. Best matches first. */
    public List<SearchHit> search(String query, double threshold) {
        return query(list -> {
            Scorer scorer = new Scorer(query);
            List<SearchHit> hits = new ArrayList<>();
            for (Stash stash : list) {
                for (ContainerData container : stash.containers) {
                    collect(scorer, threshold, stash, container, hits);
                }
            }
            hits.sort(Comparator.comparingDouble(SearchHit::score).reversed()
                .thenComparing(Comparator.comparingLong(SearchHit::count).reversed()));
            return hits;
        });
    }

    private static void collect(Scorer scorer, double threshold, Stash stash, ContainerData container, List<SearchHit> out) {
        if (container.items != null) {
            for (ItemEntry item : container.items) {
                double score = scoreOf(scorer, item);
                if (score >= threshold) out.add(new SearchHit(stash, container, item.itemId, item.customName, item.count, score, false));
            }
        }

        if (container.shulkers == null) return;
        Map<String, SearchHit> inside = new HashMap<>();
        for (ShulkerInfo shulker : container.shulkers) {
            if (shulker.name != null) {
                double score = scorer.score(shulker.name);
                if (score >= threshold) out.add(new SearchHit(stash, container, "minecraft:shulker_box", shulker.name, 1, score, false));
            }
            if (shulker.items == null) continue;
            for (ItemEntry item : shulker.items) {
                double score = scoreOf(scorer, item);
                if (score < threshold) continue;
                String key = item.itemId + "|" + item.customName;
                SearchHit previous = inside.get(key);
                long count = item.count + (previous == null ? 0 : previous.count());
                inside.put(key, new SearchHit(stash, container, item.itemId, item.customName, count, score, true));
            }
        }
        out.addAll(inside.values());
    }

    /** Scores each distinct name once per query (there are only ~1500 item ids, but millions of entries). */
    private static final class Scorer {
        private final String query;
        private final Map<String, Double> memo = new HashMap<>();

        Scorer(String query) {
            this.query = query;
        }

        double score(String candidate) {
            if (candidate == null) return 0.0;
            return memo.computeIfAbsent(candidate, c -> FuzzySearchUtil.score(query, c));
        }
    }

    private static double scoreOf(Scorer scorer, ItemEntry item) {
        return Math.max(scorer.score(item.itemId), scorer.score(item.customName));
    }

    // ------------------------------------------------------------------ persistence

    private void changed() {
        version.incrementAndGet();
        if (saveQueued.compareAndSet(false, true)) {
            io.schedule(this::saveNow, 750, TimeUnit.MILLISECONDS); // batches bursts of changes into one write
        }
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Root root = gson.fromJson(reader, Root.class);
            if (root == null || root.stashes == null) return;

            lock.writeLock().lock();
            try {
                for (Stash stash : root.stashes) {
                    if (stash == null || stash.stashName == null || stash.dimension == null) continue;
                    if (stash.containers == null) stash.containers = new ArrayList<>();
                    if (indexOf(stash.stashName) < 0) stashes.add(stash);
                }
                version.incrementAndGet();
            } finally {
                lock.writeLock().unlock();
            }
            OpsTools.LOG.info("Loaded {} stash(es).", root.stashes.size());
        } catch (Exception e) {
            OpsTools.LOG.warn("Could not load stashes: {}", e.toString());
            quarantineBadFile();
        }
    }

    /** Moves an unreadable save aside so the next save cannot overwrite it. */
    private void quarantineBadFile() {
        try {
            Files.move(file, file.resolveSibling("stashes.json.corrupt-" + System.currentTimeMillis()),
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {}
    }

    private void saveNow() {
        saveQueued.set(false);

        String json;
        lock.readLock().lock();
        try {
            Root root = new Root();
            root.stashes = new ArrayList<>(stashes);
            json = gson.toJson(root);
        } finally {
            lock.readLock().unlock();
        }

        try {
            Files.createDirectories(file.getParent());
            if (!backedUp && Files.exists(file)) { // one backup of the previous save per session
                Files.copy(file, file.resolveSibling("stashes.json.bak"), StandardCopyOption.REPLACE_EXISTING);
                backedUp = true;
            }
            Path tmp = file.resolveSibling("stashes.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                writer.write(json);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            OpsTools.LOG.warn("Could not save stashes: {}", e.toString());
        }
    }
}