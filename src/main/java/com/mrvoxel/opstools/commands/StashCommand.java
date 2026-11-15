package com.mrvoxel.opstools.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mrvoxel.opstools.modules.GlobalStashTrackerModule;
import com.mrvoxel.opstools.stash.StashManager;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * {@code .stash} - manage the stashes the Global Stash Tracker files containers under.
 *
 *   .stash create <name>          register a stash centered on where you stand
 *   .stash delete <name>          remove a stash and everything indexed in it
 *   .stash rename <old> <new>     rename a stash (use "quotes" if the old name has spaces)
 *   .stash move <name>            move a stash's center to where you stand
 *   .stash clear <name>           forget the indexed containers but keep the stash
 *   .stash info <name>            show a stash's size and contents summary
 *   .stash list                   list every stash
 *   .stash near                   show the nearest stash in this dimension
 */
public class StashCommand extends Command {
    public StashCommand() {
        super("stash", "Create and manage the stashes used by the Global Stash Tracker.");
    }

    private static LiteralArgumentBuilder<SharedSuggestionProvider> lit(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static RequiredArgumentBuilder<SharedSuggestionProvider, String> nameArg() {
        return RequiredArgumentBuilder.<SharedSuggestionProvider, String>argument("name", StringArgumentType.greedyString())
            .suggests((context, suggestions) -> SharedSuggestionProvider.suggest(StashManager.get().stashNames(), suggestions));
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.then(lit("create").then(RequiredArgumentBuilder.<SharedSuggestionProvider, String>argument("name", StringArgumentType.greedyString())
            .executes(context -> {
                create(StringArgumentType.getString(context, "name").trim());
                return 1;
            })));

        builder.then(lit("delete").then(nameArg().executes(context -> {
            delete(StringArgumentType.getString(context, "name").trim());
            return 1;
        })));

        builder.then(lit("move").then(nameArg().executes(context -> {
            move(StringArgumentType.getString(context, "name").trim());
            return 1;
        })));

        builder.then(lit("clear").then(nameArg().executes(context -> {
            clear(StringArgumentType.getString(context, "name").trim());
            return 1;
        })));

        builder.then(lit("info").then(nameArg().executes(context -> {
            info(StringArgumentType.getString(context, "name").trim());
            return 1;
        })));

        builder.then(lit("rename").then(RequiredArgumentBuilder.<SharedSuggestionProvider, String>argument("old", StringArgumentType.string())
            .suggests((context, suggestions) -> SharedSuggestionProvider.suggest(StashManager.get().stashNames(), suggestions))
            .then(RequiredArgumentBuilder.<SharedSuggestionProvider, String>argument("new", StringArgumentType.greedyString())
                .executes(context -> {
                    rename(StringArgumentType.getString(context, "old").trim(), StringArgumentType.getString(context, "new").trim());
                    return 1;
                }))));

        builder.then(lit("list").executes(context -> {
            list();
            return 1;
        }));

        builder.then(lit("near").executes(context -> {
            near();
            return 1;
        }));
    }

    // ------------------------------------------------------------------ actions

    private static boolean inWorld() {
        if (mc.player != null && mc.level != null) return true;
        GlobalStashTrackerModule.chatError("Join a world first.");
        return false;
    }

    private void create(String name) {
        if (!inWorld()) return;
        if (name.isEmpty()) {
            GlobalStashTrackerModule.chatError("Give the stash a name: .stash create <name>");
            return;
        }
        StashManager.Stash stash = StashManager.get().createStash(name, StashManager.dimensionName(mc.level),
            mc.player.getX(), mc.player.getY(), mc.player.getZ());
        if (stash == null) {
            GlobalStashTrackerModule.chatError("A stash called \"" + name + "\" already exists.");
            return;
        }
        GlobalStashTrackerModule.chat(Component.literal("Created stash [" + name + "] here. Containers you open within the "
            + "assign radius are filed under it.").withStyle(ChatFormatting.GREEN));
    }

    private String pendingDelete = null;
    private long pendingDeleteAt = 0;

    private void delete(String name) {
        boolean confirmed = name.equalsIgnoreCase(pendingDelete) && System.currentTimeMillis() - pendingDeleteAt < 15_000;
        if (!confirmed) {
            pendingDelete = name;
            pendingDeleteAt = System.currentTimeMillis();
            GlobalStashTrackerModule.chat(Component.literal("Run .stash delete " + name
                + " again within 15s to confirm. This removes everything indexed in it.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        pendingDelete = null;
        if (StashManager.get().deleteStash(name)) {
            GlobalStashTrackerModule.chat(Component.literal("Deleted stash [" + name + "].").withStyle(ChatFormatting.GREEN));
        } else {
            unknown(name);
        }
    }

    private void rename(String oldName, String newName) {
        if (newName.isEmpty()) {
            GlobalStashTrackerModule.chatError("Usage: .stash rename <old> <new>");
            return;
        }
        if (StashManager.get().renameStash(oldName, newName)) {
            GlobalStashTrackerModule.chat(Component.literal("Renamed [" + oldName + "] to [" + newName + "].").withStyle(ChatFormatting.GREEN));
        } else if (!StashManager.get().stashNames().stream().anyMatch(n -> n.equalsIgnoreCase(oldName))) {
            unknown(oldName);
        } else {
            GlobalStashTrackerModule.chatError("A stash called \"" + newName + "\" already exists.");
        }
    }

    private void move(String name) {
        if (!inWorld()) return;
        if (StashManager.get().moveStash(name, StashManager.dimensionName(mc.level), mc.player.getX(), mc.player.getY(), mc.player.getZ())) {
            GlobalStashTrackerModule.chat(Component.literal("Moved [" + name + "] to where you stand.").withStyle(ChatFormatting.GREEN));
        } else {
            unknown(name);
        }
    }

    private void clear(String name) {
        int removed = StashManager.get().clearStash(name);
        if (removed < 0) {
            unknown(name);
        } else {
            GlobalStashTrackerModule.chat(Component.literal("Forgot " + removed + " container(s) in [" + name + "].").withStyle(ChatFormatting.GREEN));
        }
    }

    private void info(String name) {
        String text = StashManager.get().query(stashes -> {
            for (StashManager.Stash stash : stashes) {
                if (!stash.stashName.equalsIgnoreCase(name)) continue;

                long items = 0;
                int shulkers = 0;
                for (StashManager.ContainerData container : stash.containers) {
                    for (StashManager.ItemEntry item : container.items) items += item.count;
                    shulkers += container.shulkers.size();
                }
                return "[" + stash.stashName + "] " + stash.dimension + " at "
                    + Math.round(stash.centerX) + " " + Math.round(stash.centerY) + " " + Math.round(stash.centerZ)
                    + " - " + stash.containers.size() + " container(s), " + items + " item(s), " + shulkers + " shulker box(es).";
            }
            return null;
        });
        if (text == null) unknown(name);
        else GlobalStashTrackerModule.chat(text);
    }

    private void list() {
        String text = StashManager.get().query(stashes -> {
            if (stashes.isEmpty()) return null;
            StringBuilder out = new StringBuilder();
            for (StashManager.Stash stash : stashes) {
                if (out.length() > 0) out.append('\n');
                out.append(" [").append(stash.stashName).append("] ").append(stash.dimension).append(" ")
                    .append(Math.round(stash.centerX)).append(' ').append(Math.round(stash.centerY)).append(' ')
                    .append(Math.round(stash.centerZ)).append(" - ").append(stash.containers.size()).append(" container(s)");
            }
            return out.toString();
        });
        if (text == null) {
            GlobalStashTrackerModule.chat("No stashes yet. Stand in one and type .stash create <name>.");
            return;
        }
        for (String line : text.split("\n")) GlobalStashTrackerModule.chat(line);
    }

    private void near() {
        if (!inWorld()) return;
        String dimension = StashManager.dimensionName(mc.level);
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();

        String text = StashManager.get().query(stashes -> {
            StashManager.Stash best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (StashManager.Stash stash : stashes) {
                if (!stash.dimension.equals(dimension)) continue;
                double dx = stash.centerX - px, dy = stash.centerY - py, dz = stash.centerZ - pz;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = stash;
                }
            }
            if (best == null) return null;
            return "Nearest: [" + best.stashName + "], " + Math.round(Math.sqrt(bestDistSq)) + " blocks away.";
        });
        if (text == null) GlobalStashTrackerModule.chat("No stash registered in this dimension.");
        else GlobalStashTrackerModule.chat(text);
    }

    private static void unknown(String name) {
        GlobalStashTrackerModule.chatError("No stash called \"" + name + "\". Use .stash list.");
    }
}