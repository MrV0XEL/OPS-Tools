package com.mrvoxel.opstools.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mrvoxel.opstools.modules.GlobalStashTrackerModule;
import com.mrvoxel.opstools.stash.FuzzySearchUtil;
import com.mrvoxel.opstools.stash.StashManager;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * {@code .find <item>} - fuzzy search over everything the Global Stash Tracker has indexed (loose items, items inside
 * shulker boxes, and shulker names). Prints the best matches and highlights those containers in the world for 30 seconds.
 * The search runs on a worker thread; results are printed back on the client thread.
 */
public class FindCommand extends Command {
    private static final int MAX_RESULTS = 10;
    private static final int MAX_HIGHLIGHTS = 500;
    private static final int MIN_QUERY_LENGTH = 2;

    public FindCommand() {
        super("find", "Searches your indexed stashes for an item (fuzzy match).");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.then(RequiredArgumentBuilder.<SharedSuggestionProvider, String>argument("item", StringArgumentType.greedyString())
            .executes(context -> {
                run(StringArgumentType.getString(context, "item"));
                return 1;
            })
        );
    }

    private void run(String query) {
        if (FuzzySearchUtil.normalize(query).length() < MIN_QUERY_LENGTH) {
            GlobalStashTrackerModule.chatError("Query too short.");
            return;
        }

        GlobalStashTrackerModule module = Modules.get().get(GlobalStashTrackerModule.class);
        double threshold = module != null ? module.fuzzyThreshold() : FuzzySearchUtil.DEFAULT_THRESHOLD;

        CompletableFuture
            .supplyAsync(() -> StashManager.get().search(query, threshold))
            .thenAcceptAsync(hits -> show(query, hits, module), mc::execute) // back on the client thread
            .exceptionally(error -> {
                mc.execute(() -> GlobalStashTrackerModule.chatError("Search failed: " + error.getMessage()));
                return null;
            });
    }

    private void show(String query, List<StashManager.SearchHit> hits, GlobalStashTrackerModule module) {
        if (hits.isEmpty()) {
            GlobalStashTrackerModule.chatError("Nothing matching \"" + query + "\" in your indexed stashes.");
            return;
        }

        String currentDimension = mc.level != null ? StashManager.dimensionName(mc.level) : "";

        GlobalStashTrackerModule.chat(Component.literal(hits.size() + " match" + (hits.size() == 1 ? "" : "es")
            + " for \"" + query + "\":").withStyle(ChatFormatting.GRAY));

        Set<BlockPos> marked = new LinkedHashSet<>();
        int shown = 0;
        for (StashManager.SearchHit hit : hits) {
            boolean sameDimension = hit.stash().dimension.equals(currentDimension);

            // Only highlight containers in the dimension the player is in (coordinates mean nothing elsewhere).
            if (sameDimension && marked.size() < MAX_HIGHLIGHTS) {
                marked.add(new BlockPos(hit.container().x, hit.container().y, hit.container().z));
            }
            if (shown++ >= MAX_RESULTS) continue; // still highlight, only print the best few

            String name = hit.customName() != null
                ? hit.customName() + " (" + FuzzySearchUtil.stripNamespace(hit.itemId()) + ")"
                : FuzzySearchUtil.stripNamespace(hit.itemId());
            String where = "[" + hit.stash().stashName + " / " + hit.stash().dimension + "] " + hit.container().type
                + " at " + hit.container().x + " " + hit.container().y + " " + hit.container().z
                + (sameDimension ? distance(hit.container()) : "");

            Component line = Component.literal(" " + hit.count() + "x " + name).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(hit.inShulker() ? " (in a shulker box)" : "").withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(" - " + where).withStyle(ChatFormatting.GRAY));
            GlobalStashTrackerModule.chat(line);
        }
        if (hits.size() > MAX_RESULTS) {
            GlobalStashTrackerModule.chat("... and " + (hits.size() - MAX_RESULTS) + " more.");
        }
        if (marked.size() >= MAX_HIGHLIGHTS) {
            GlobalStashTrackerModule.chat("Highlighting the first " + MAX_HIGHLIGHTS + " containers in this dimension.");
        }

        if (module != null) {
            if (!module.isActive()) {
                GlobalStashTrackerModule.chat("Enable the Global Stash Tracker module to see the highlights.");
            }
            module.highlight(new ArrayList<>(marked));
        }
    }

    private static String distance(StashManager.ContainerData container) {
        if (mc.player == null) return "";
        double dx = container.x + 0.5 - mc.player.getX();
        double dy = container.y + 0.5 - mc.player.getY();
        double dz = container.z + 0.5 - mc.player.getZ();
        return ", " + Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) + " blocks away";
    }
}