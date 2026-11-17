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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Registers UNT WorldGuard state flags and tracks their effective changes through WorldGuard sessions. */
public final class WorldGuardRegionVisibilityIntegration implements RegionVisibilityIntegration, Listener {

    public static final String FLAG_NAME = "unt-see-other-nametags";
    public static final String OWN_FLAG_NAME = "unt-see-own-nametag";

    private final UnlimitedNameTags plugin;
    // WorldGuard stores one handler per concrete handler class in each Session. Keep
    // these factories/classes distinct or registering one flag replaces the other.
    private final Handler.Factory<OtherNametagVisibilityHandler> otherHandlerFactory = new Handler.Factory<>() {
        @Override
        public OtherNametagVisibilityHandler create(Session session) {
            return new OtherNametagVisibilityHandler(session);
        }
    };
    private final Handler.Factory<OwnNametagVisibilityHandler> ownHandlerFactory = new Handler.Factory<>() {
        @Override
        public OwnNametagVisibilityHandler create(Session session) {
            return new OwnNametagVisibilityHandler(session);
        }
    };

    private StateFlag flag;
    private StateFlag ownFlag;
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

        try {
            ownFlag = new StateFlag(OWN_FLAG_NAME, true);
            registry.register(ownFlag);
        } catch (FlagConflictException exception) {
            final Flag<?> existing = registry.get(OWN_FLAG_NAME);
            if (existing instanceof StateFlag stateFlag) {
                ownFlag = stateFlag;
            } else {
                ownFlag = null;
                plugin.getLogger().log(Level.SEVERE,
                        "WorldGuard flag '" + OWN_FLAG_NAME + "' is already registered with an incompatible type. "
                                + "The own-nametag region rule is unavailable.", exception);
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
        if (!sessionManager.registerHandler(otherHandlerFactory, null)) {
            plugin.getLogger().warning("WorldGuard did not register the UNT other-nametag visibility handler.");
            sessionManager = null;
            return false;
        }
        if (ownFlag != null && !sessionManager.registerHandler(ownHandlerFactory, null)) {
            sessionManager.unregisterHandler(otherHandlerFactory);
            plugin.getLogger().warning("WorldGuard did not register the UNT own-nametag visibility handler.");
            sessionManager = null;
            return false;
        }
        enabled = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Also initialize sessions for players already online when UNT is enabled or reloaded.
        sessionManager.resetAllStates();
        plugin.getLogger().info("WorldGuard region nametag visibility enabled (flags=" + FLAG_NAME
                + (ownFlag == null ? "" : ", " + OWN_FLAG_NAME) + ").");
        return true;
    }

    @Override
    public void disable() {
        final SessionManager manager = sessionManager;
        if (manager != null) {
            manager.unregisterHandler(otherHandlerFactory);
            if (ownFlag != null) {
                manager.unregisterHandler(ownHandlerFactory);
            }
            // Remove this handler from already-created sessions and restore its runtime state.
            manager.resetAllStates();
        }
        sessionManager = null;
        enabled = false;
        if (plugin.getNametagManager() != null) {
            plugin.getNametagManager().clearRegionHiddenViewers();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerWorldGuardCommand(@NotNull PlayerCommandPreprocessEvent event) {
        if (isFlagCommand(event.getMessage())) {
            scheduleSessionReset();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerWorldGuardCommand(@NotNull ServerCommandEvent event) {
        if (isFlagCommand('/' + event.getCommand())) {
            scheduleSessionReset();
        }
    }

    private boolean isFlagCommand(@NotNull String rawCommand) {
        final String command = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;
        final String[] parts = command.trim().split("\\s+");
        return parts.length >= 2
                && (parts[0].equalsIgnoreCase("rg") || parts[0].equalsIgnoreCase("region"))
                && parts[1].equalsIgnoreCase("flag");
    }

    private void scheduleSessionReset() {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            final SessionManager current = sessionManager;
            if (enabled && current != null) {
                current.resetAllStates();
            }
        });
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
        return setRegionFlag(world, regionId, action, flag, FLAG_NAME);
    }

    @Override
    public CompletableFuture<UpdateResult> setRegionOwnNametagFlag(
            @NotNull World world,
            @NotNull String regionId,
            @NotNull Action action) {
        if (ownFlag == null) {
            return CompletableFuture.completedFuture(UpdateResult.UNAVAILABLE);
        }
        return setRegionFlag(world, regionId, action, ownFlag, OWN_FLAG_NAME);
    }

    private CompletableFuture<UpdateResult> setRegionFlag(
            @NotNull World world,
            @NotNull String regionId,
            @NotNull Action action,
            @NotNull StateFlag targetFlag,
            @NotNull String targetFlagName) {
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
        if (Objects.equals(region.getFlag(targetFlag), value)) {
            return CompletableFuture.completedFuture(UpdateResult.UNCHANGED);
        }

        region.setFlag(targetFlag, value);
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
                        "Could not save WorldGuard flag '" + targetFlagName + "' for region '" + regionId + "'.", exception);
                return UpdateResult.SAVE_FAILED;
            }
        });
    }

    private abstract class NametagVisibilityHandler extends FlagValueChangeHandler<StateFlag.State> {

        private NametagVisibilityHandler(@NotNull Session session, @NotNull StateFlag targetFlag) {
            super(session, targetFlag);
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
                apply(player, value == StateFlag.State.DENY);
            }
        }

        protected abstract void apply(Player player, boolean hidden);
    }

    private final class OtherNametagVisibilityHandler extends NametagVisibilityHandler {

        private OtherNametagVisibilityHandler(@NotNull Session session) {
            super(session, flag);
        }

        @Override
        protected void apply(Player player, boolean hidden) {
            plugin.getNametagManager().setRegionHiddenOtherNametags(player, hidden);
        }
    }

    private final class OwnNametagVisibilityHandler extends NametagVisibilityHandler {

        private OwnNametagVisibilityHandler(@NotNull Session session) {
            super(session, ownFlag);
        }

        @Override
        protected void apply(Player player, boolean hidden) {
            plugin.getNametagManager().setRegionHiddenOwnNametagFromSelf(player, hidden);
        }
    }
}
