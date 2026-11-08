package org.alexdev.unlimitednametags.integration.worldguard;

import org.bukkit.World;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** WorldGuard-free contract used by UNT so WorldGuard remains an optional runtime dependency. */
public interface RegionVisibilityIntegration {

    enum Action {
        HIDE,
        SHOW,
        CLEAR
    }

    enum UpdateResult {
        UPDATED,
        UNCHANGED,
        WORLD_NOT_READY,
        REGION_NOT_FOUND,
        SAVE_FAILED,
        UNAVAILABLE
    }

    boolean registerFlag();

    boolean enable();

    void disable();

    boolean isAvailable();

    List<String> regionIds(World world);

    CompletableFuture<UpdateResult> setRegionFlag(World world, String regionId, Action action);

    CompletableFuture<UpdateResult> setRegionOwnNametagFlag(World world, String regionId, Action action);
}
