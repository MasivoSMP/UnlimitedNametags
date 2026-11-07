package org.alexdev.unlimitednametags.integration.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.session.Session;
import com.sk89q.worldguard.session.SessionManager;
import com.sk89q.worldguard.session.handler.FlagValueChangeHandler;
import com.sk89q.worldguard.session.handler.Handler;
import org.alexdev.unlimitednametags.UnlimitedNameTags;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Registers a WorldGuard state flag and tracks effective flag changes through WorldGuard sessions. */
public final class WorldGuardRegionVisibilityIntegration implements RegionVisibilityIntegration {

    public static final String FLAG_NAME = "unt-see-other-nametags";

    private final UnlimitedNameTags plugin;
    private final Handler.Factory<NametagVisibilityHandler> handlerFactory = new Handler.Factory<>() {
        @Override
        public NametagVisibilityHandler create(Session session) {
            return new NametagVisibilityHandler(session);
        }
    };

    private StateFlag flag;
    private SessionManager sessionManager;
    private boolean enabled;

    public WorldGuardRegionVisibilityIntegration(@NotNull UnlimitedNameTags plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean registerFlag() {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
            return false;
        }

        final FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        try {
            // Unset regions retain normal nametag visibility; only explicit deny hides tags.
            flag = new StateFlag(FLAG_NAME, true);
            registry.register(flag);
        } catch (FlagConflictException exception) {
            final Flag<?> existing = registry.get(FLAG_NAME);
            if (existing instanceof StateFlag stateFlag) {
                flag = stateFlag;
            } else {
                plugin.getLogger().log(Level.SEVERE,
                        "WorldGuard flag '" + FLAG_NAME + "' is already registered with an incompatible type.", exception);
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean enable() {
        if (flag == null || Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
            return false;
        }

        sessionManager = WorldGuard.getInstance().getPlatform().getSessionManager();
        if (!sessionManager.registerHandler(handlerFactory, null)) {
            plugin.getLogger().warning("WorldGuard did not register the UNT nametag visibility handler.");
            sessionManager = null;
            return false;
        }

        enabled = true;
        // Also initialize sessions for players already online when UNT is enabled or reloaded.
        sessionManager.resetAllStates();
        plugin.getLogger().info("WorldGuard region nametag visibility enabled (flag=" + FLAG_NAME + ").");
        return true;
    }

    @Override
    public void disable() {
        final SessionManager manager = sessionManager;
        if (manager != null) {
            manager.unregisterHandler(handlerFactory);
            // Remove this handler from already-created sessions and restore its runtime state.
            manager.resetAllStates();
        }
        sessionManager = null;
        enabled = false;
        if (plugin.getNametagManager() != null) {
            plugin.getNametagManager().clearRegionHiddenViewers();
        }
    }

    @Override
    public boolean isAvailable() {
        return enabled && flag != null && sessionManager != null;
    }

    @Override
    public List<String> regionIds(@NotNull World world) {
        if (!isAvailable()) {
            return List.of();
        }

        final RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(world));
        if (manager == null) {
            return List.of();
        }
        return manager.getRegions().keySet().stream().sorted(Comparator.naturalOrder()).toList();
    }

    @Override
    public CompletableFuture<UpdateResult> setRegionFlag(
            @NotNull World world,
            @NotNull String regionId,
            @NotNull Action action) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(UpdateResult.UNAVAILABLE);
        }

        final com.sk89q.worldedit.world.World worldEditWorld = BukkitAdapter.adapt(world);
        final RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(worldEditWorld);
        if (manager == null) {
            return CompletableFuture.completedFuture(UpdateResult.WORLD_NOT_READY);
        }
        final ProtectedRegion region = manager.getRegion(regionId);
        if (region == null) {
            return CompletableFuture.completedFuture(UpdateResult.REGION_NOT_FOUND);
        }

        final StateFlag.State value = switch (action) {
            case HIDE -> StateFlag.State.DENY;
            case SHOW -> StateFlag.State.ALLOW;
            case CLEAR -> null;
        };
        if (Objects.equals(region.getFlag(flag), value)) {
            return CompletableFuture.completedFuture(UpdateResult.UNCHANGED);
        }

        region.setFlag(flag, value);
        // WorldGuard permits saves from any thread; do not block a Folia region or global tick on disk I/O.
        return CompletableFuture.supplyAsync(() -> {
            try {
                manager.saveChanges();
                // A flag edit does not necessarily produce a player movement event. Re-evaluate
                // the effective value for current sessions after admin changes take effect.
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    final SessionManager currentSessionManager = sessionManager;
                    if (enabled && currentSessionManager != null) {
                        currentSessionManager.resetAllStates();
                    }
                });
                return UpdateResult.UPDATED;
            } catch (Exception exception) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not save WorldGuard flag '" + FLAG_NAME + "' for region '" + regionId + "'.", exception);
                return UpdateResult.SAVE_FAILED;
            }
        });
    }

    private final class NametagVisibilityHandler extends FlagValueChangeHandler<StateFlag.State> {

        private NametagVisibilityHandler(@NotNull Session session) {
            super(session, flag);
        }

        @Override
        protected void onInitialValue(LocalPlayer player, ApplicableRegionSet set, StateFlag.State value) {
            apply(player, value);
        }

        @Override
        protected boolean onSetValue(
                LocalPlayer player,
                com.sk89q.worldedit.util.Location from,
                com.sk89q.worldedit.util.Location to,
                ApplicableRegionSet toSet,
                StateFlag.State currentValue,
                StateFlag.State lastValue,
                com.sk89q.worldguard.session.MoveType moveType) {
            apply(player, currentValue);
            return true;
        }

        @Override
        protected boolean onAbsentValue(
                LocalPlayer player,
                com.sk89q.worldedit.util.Location from,
                com.sk89q.worldedit.util.Location to,
                ApplicableRegionSet toSet,
                StateFlag.State lastValue,
                com.sk89q.worldguard.session.MoveType moveType) {
            apply(player, null);
            return true;
        }

        private void apply(LocalPlayer localPlayer, StateFlag.State value) {
            final Player player = Bukkit.getPlayer(localPlayer.getUniqueId());
            if (player != null) {
                plugin.getNametagManager().setRegionHiddenOtherNametags(player,
                        value == StateFlag.State.DENY);
            }
        }
    }
}
