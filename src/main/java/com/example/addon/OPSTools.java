package pwn.noobs.trouserstreak;

import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import pwn.noobs.trouserstreak.modules.AutoWither;

public class Trouser extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category Main = new Category("OPS tools");

    @Override
    public void onInitialize() {
        LOG.info("Initializing OPS tools");

        // Modules
        Modules.get().add(new AutoWither());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(Main);
    }

    @Override
    public String getPackage() {
        return "pwn.noobs.trouserstreak";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("TrouserStreak", "TrouserStreak");
    }
}
