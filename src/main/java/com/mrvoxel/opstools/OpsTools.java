package com.mrvoxel.opstools;

import com.mrvoxel.opstools.commands.FindCommand;
import com.mrvoxel.opstools.commands.StashCommand;
import com.mrvoxel.opstools.modules.*;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpsTools extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("OPS-Tools");
    public static final Category CATEGORY = new Category("OPS Tools", Items.OBSIDIAN.getDefaultInstance());

    @Override
    public void onInitialize() {
        LOG.info("Initializing OPS-Tools");

        Modules modules = Modules.get();
        modules.add(new AdvancedAutoWither());
        modules.add(new EnderChestFarmer());
        modules.add(new MapPlacer());
        modules.add(new Printer());
        modules.add(new KillEffects());
        modules.add(new CrystalAuraPlus());
        modules.add(new TotemPopTracker());
        modules.add(new SelfTrapPlus());
        modules.add(new HoleSnap());
        modules.add(new AnchorAuraPlus());
        modules.add(new AutoTrap());
        modules.add(new ContainerCleaner());
        modules.add(new GuiParticles());
        modules.add(new ChatSentinel());
        modules.add(new GlobalStashTrackerModule());

        Commands.add(new FindCommand());
        Commands.add(new StashCommand());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.mrvoxel.opstools";
    }
}