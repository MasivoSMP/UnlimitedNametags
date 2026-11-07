package org.alexdev.unlimitednametags.listeners;

import org.alexdev.unlimitednametags.UnlimitedNameTags;
import org.alexdev.unlimitednametags.api.event.PlayerNametagVisibilityEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

/** Keeps the administrator viewer restriction authoritative over personal preferences and packet replays. */
public final class AdminNametagVisibilityListener implements Listener {

    private final UnlimitedNameTags plugin;

    public AdminNametagVisibilityListener(@NotNull UnlimitedNameTags plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onNametagVisibility(@NotNull PlayerNametagVisibilityEvent event) {
        if (!event.isOwnerViewingOwnNametag()
                && (plugin.getNametagManager().isAdminHiddenOtherNametags(event.getViewer().getUniqueId())
                || plugin.getNametagManager().isRegionHiddenOtherNametags(event.getViewer().getUniqueId()))) {
            event.setVisible(false);
        }
    }
}
