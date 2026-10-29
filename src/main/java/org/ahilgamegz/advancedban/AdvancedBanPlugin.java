
package org.ahilgamegz.advancedban;

import org.bukkit.plugin.java.JavaPlugin;
import org.spicord.SpicordLoader;

public final class AdvancedBanPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        AdvancedBanAddon addon = new AdvancedBanAddon();

        SpicordLoader.addStartupListener(spicord -> {
            spicord.getAddonManager().registerAddon(addon);
            getLogger().info(
                "Registered Spicord AdvancedBan addon."
            );
        });
    }
}
