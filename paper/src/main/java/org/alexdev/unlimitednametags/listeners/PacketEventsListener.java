package org.alexdev.unlimitednametags.listeners;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.google.common.collect.Maps;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.alexdev.unlimitednametags.UnlimitedNameTags;
import org.alexdev.unlimitednametags.data.TeamData;
import org.alexdev.unlimitednametags.packet.PacketNameTag;
import org.alexdev.unlimitednametags.packet.PaperNametagRow;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class PacketEventsListener extends PacketListenerAbstract {

    private static final String VANILLA_FALLBACK_TEAM_PREFIX = "unt_vanilla_hide_";
    private final UnlimitedNameTags plugin;
    private final Map<UUID, Map<String, TeamData>> teams;
    private final Map<UUID, Map<UUID, String>> vanillaFallbackTeams;
    private final PacketListenerAbstract passengerObserver;

    public PacketEventsListener(UnlimitedNameTags plugin) {
        super(PacketListenerPriority.HIGHEST);
        this.plugin = plugin;
        this.teams = Maps.newConcurrentMap();
        this.vanillaFallbackTeams = Maps.newConcurrentMap();
        this.passengerObserver = new PacketListenerAbstract(PacketListenerPriority.MONITOR) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.isCancelled() || plugin.getPacketManager() == null
                        || event.getPacketType() != PacketType.Play.Server.SET_PASSENGERS) return;
                final WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
                // Cache accepted writes before the next packet, even if socket completion is delayed.
                plugin.getPlayerListener().getPlayerFromEntityId(packet.getEntityId()).ifPresent(owner ->
                        plugin.getPacketManager().observePassengers(event.getUser(), owner, packet.getPassengers()));
            }
        };
    }

    public void onEnable() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
        PacketEvents.getAPI().getEventManager().registerListener(passengerObserver);
    }

    @NotNull
    public Map<String, TeamData> getTeams(@NotNull UUID player) {
        return teams.computeIfAbsent(player, p -> Maps.newConcurrentMap());
    }

    public void onPacketSend(@NotNull PacketSendEvent event) {
        if (event.isCancelled() || plugin.getPacketManager() == null || plugin.getNametagManager() == null) return;
        observeEntities(event);
        if (event.getPacketType() == PacketType.Play.Server.TEAMS) {
            handleTeams(event);
        } else if (event.getPacketType() == PacketType.Play.Server.SET_PASSENGERS) {
            handlePassengers(event);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
            handleMetaData(event);
        } else if (event.getPacketType() == PacketType.Play.Server.CAMERA) {
            handleCamera(event);
        }
    }

    private void handleCamera(@NotNull PacketSendEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (!plugin.getNametagManager().isEffectiveShowOwnNametag(player)) {
            return;
        }

        final WrapperPlayServerCamera camera = new WrapperPlayServerCamera(event);
        if (camera.getCameraId() == player.getEntityId()) {
            plugin.getNametagManager().getPacketDisplays(player).forEach(PaperNametagRow::showForOwner);
        } else {
            plugin.getNametagManager().getPacketDisplays(player).forEach(PaperNametagRow::hideForOwner);
        }
    }

    @Override
    public void onPacketReceive(@NotNull PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            handleUseEntity(event);
        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_INPUT) {
            handlePlayerInput(event);
        }
    }

    private void handleUseEntity(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        final WrapperPlayClientEntityAction packet = new WrapperPlayClientEntityAction(event);
        final Optional<? extends Player> player = plugin.getPlayerListener().getPlayerFromEntityId(packet.getEntityId());
        if (player.isEmpty()) {
            return;
        }


        switch (packet.getAction()) {
            case START_SNEAKING -> plugin.getNametagManager().updateSneaking(player.get(), true);
            case STOP_SNEAKING -> plugin.getNametagManager().updateSneaking(player.get(), false);
            case START_FLYING_WITH_ELYTRA -> plugin.getPlayerListener().logicElytra(player.get());
        }
    }

    private void handlePlayerInput(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        final WrapperPlayClientPlayerInput packet = new WrapperPlayClientPlayerInput(event);
        final Collection<PaperNametagRow> packetNameTags = plugin.getNametagManager().getPacketDisplays(player);

        boolean updateSneaking = packetNameTags.stream()
                .anyMatch(packetNameTag -> packet.isShift() != packetNameTag.isSneaking());

        if (updateSneaking) {
            plugin.getNametagManager().updateSneaking(player, packet.isShift());
        }
    }

    private void handlePassengers(@NotNull PacketSendEvent event) {
        final WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
        final Optional<? extends Player> owner = plugin.getPlayerListener().getPlayerFromEntityId(packet.getEntityId());
        if (owner.isEmpty()) return;
        final int[] original = packet.getPassengers();
        if (!plugin.getPacketManager().knowsOwner(event.getUser(), owner.get())
                && Arrays.stream(original).anyMatch(plugin.getPacketManager()::isRow)) {
            event.setCancelled(true);
            return;
        }
        final int[] passengers = plugin.getPacketManager().passengers(event.getUser(), owner.get(), original);
        if (!Arrays.equals(original, passengers)) {
            packet.setPassengers(passengers);
            event.markForReEncode(true);
        }
    }

    private void observeEntities(@NotNull PacketSendEvent event) {
        final User user = event.getUser();
        if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY
                || event.getPacketType() == PacketType.Play.Server.SPAWN_PLAYER) {
            final int id = event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY
                    ? new WrapperPlayServerSpawnEntity(event).getEntityId()
                    : new WrapperPlayServerSpawnPlayer(event).getEntityId();
            if (plugin.getPacketManager().isRow(id)) {
                final Optional<PaperNametagRow> row = plugin.getNametagManager().getPacketDisplayByEntityId(id);
                if (row.isEmpty() || row.get().getOwner() == null
                        || !plugin.getPacketManager().knowsOwner(user, row.get().getOwner())
                        || !((PacketNameTag) row.get()).canViewerSee(user.getUUID())) {
                    event.setCancelled(true);
                    return;
                }
            }
            final Runnable spawned = plugin.getPacketManager().trackSpawn(user, id);
            event.getTasksAfterSend().add(() -> {
                if (!event.isCancelled()) spawned.run();
            });
        } else if (event.getPacketType() == PacketType.Play.Server.DESTROY_ENTITIES) {
            // Invalidate at encoding time: later writes must not mount an owner awaiting socket flush.
            for (int id : new WrapperPlayServerDestroyEntities(event).getEntityIds()) {
                plugin.getPacketManager().destroyed(user, id);
                if (plugin.getPacketManager().isCurrent(user)) {
                    plugin.getPlayerListener().getPlayerFromEntityId(id).ifPresent(owner ->
                            plugin.getNametagManager().getPacketDisplays(owner).forEach(row ->
                                    ((PacketNameTag) row).hideFromViewer(user.getUUID())));
                }
            }
        } else if (event.getPacketType() == PacketType.Play.Server.RESPAWN) {
            plugin.getPacketManager().reset(user);
            if (plugin.getPacketManager().isCurrent(user)) {
                plugin.getNametagManager().removeViewer(user.getUUID());
            }
        }
    }


    private boolean shouldHideVanillaNametags(@NotNull Player viewer) {
        return plugin.getNametagManager().isOtherNametagsHiddenForViewer(viewer.getUniqueId())
                || (plugin.getConfigManager().getSettings().getBehavior().isDisableDefaultNameTag()
                && plugin.getConfigManager().getSettings().getWorlds().isEnabled(viewer.getWorld().getName()));
    }

    private void handleTeams(@NotNull PacketSendEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        final WrapperPlayServerTeams packet = new WrapperPlayServerTeams(event);
        if (isVanillaFallbackTeam(packet.getTeamName())) return;
        // Track real team membership even while vanilla names are allowed, so a later
        // viewer restriction can update existing teams without stealing their members.
        final Map<String, TeamData> teams = getTeams(event.getUser().getUUID());
        final String teamName = packet.getTeamName();

        switch (packet.getTeamMode()) {
            case ADD_ENTITIES -> handleAddEntities(event, packet, teams, teamName);
            case REMOVE_ENTITIES -> {
                handleRemoveEntities(packet, teams, teamName);
                scheduleVanillaRefresh(event);
            }
            case CREATE -> handleCreateTeam(event, packet, teams, teamName);
            case UPDATE -> handleUpdateTeam(event, packet, teams, teamName);
            case REMOVE -> {
                teams.remove(teamName);
                scheduleVanillaRefresh(event);
            }
        }
    }

    public void ensureVanillaNametagHidden(@NotNull Player viewer, @NotNull Player target) {
        if (!shouldHideVanillaNametags(viewer)
                || viewer.getUniqueId().equals(target.getUniqueId())
                || isPlayerAlreadyInTeam(viewer.getUniqueId(), target.getName())) {
            return;
        }

        final User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (user == null || user.getChannel() == null) {
            return;
        }

        final UUID viewerId = viewer.getUniqueId();
        final UUID targetId = target.getUniqueId();
        final Map<UUID, String> fallbackByTarget = vanillaFallbackTeams.computeIfAbsent(viewerId, ignored -> Maps.newConcurrentMap());
        if (fallbackByTarget.containsKey(targetId)) {
            return;
        }

        final String teamName = buildVanillaFallbackTeamName(viewerId, targetId);
        fallbackByTarget.put(targetId, teamName);
        final WrapperPlayServerTeams.ScoreBoardTeamInfo info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                Component.empty(), Component.empty(), Component.empty(),
                WrapperPlayServerTeams.NameTagVisibility.NEVER,
                WrapperPlayServerTeams.CollisionRule.ALWAYS,
                NamedTextColor.WHITE,
                WrapperPlayServerTeams.OptionData.NONE
        );
        user.sendPacket(new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.CREATE, info, List.of(target.getName())));
    }

    public void removeVanillaFallbackNametagHidden(@NotNull Player viewer, @NotNull Player target) {
        final Map<UUID, String> fallbackByTarget = vanillaFallbackTeams.get(viewer.getUniqueId());
        if (fallbackByTarget == null) {
            return;
        }
        final String teamName = fallbackByTarget.remove(target.getUniqueId());
        if (teamName == null) {
            return;
        }
        if (fallbackByTarget.isEmpty()) {
            vanillaFallbackTeams.remove(viewer.getUniqueId(), fallbackByTarget);
        }
        final User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (user != null && user.getChannel() != null) {
            user.sendPacket(new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.REMOVE,
                    Optional.<WrapperPlayServerTeams.ScoreBoardTeamInfo>empty(), List.of()));
        }
    }

    private boolean isPlayerAlreadyInTeam(@NotNull UUID viewerId, @NotNull String playerName) {
        final Map<String, TeamData> viewerTeams = teams.get(viewerId);
        return viewerTeams != null && viewerTeams.values().stream()
                .anyMatch(team -> team.getMembers().contains(playerName));
    }

    private boolean isVanillaFallbackTeam(@NotNull String teamName) {
        return teamName.startsWith(VANILLA_FALLBACK_TEAM_PREFIX);
    }

    @NotNull
    private String buildVanillaFallbackTeamName(@NotNull UUID viewerId, @NotNull UUID targetId) {
        final String viewer = viewerId.toString().replace("-", "").substring(0, 8);
        final String target = targetId.toString().replace("-", "").substring(0, 8);
        return VANILLA_FALLBACK_TEAM_PREFIX + viewer + "_" + target;
    }

    private void scheduleVanillaRefresh(@NotNull PacketSendEvent event) {
        final Player viewer = (Player) event.getPlayer();
        event.getTasksAfterSend().add(() -> viewer.getScheduler().run(plugin, task -> onWorldChange(viewer), null));
    }

    private void handleAddEntities(@NotNull PacketSendEvent event, @NotNull WrapperPlayServerTeams packet,
                                   @NotNull Map<String, TeamData> teams, @NotNull String teamName) {
        final Optional<TeamData> teamDataOpt = Optional.ofNullable(teams.get(teamName));
        if (teamDataOpt.isEmpty()) {
            return;
        }

        final TeamData teamData = teamDataOpt.get();
        teamData.getMembers().addAll(packet.getPlayers());

        if (shouldHideVanillaNametags((Player) event.getPlayer())
                && !teamData.isChangedVisibility() && packet.getPlayers().stream().anyMatch(this::existsPlayer)) {
            teamData.setChangedVisibility(true);
            final WrapperPlayServerTeams.ScoreBoardTeamInfo teamInfo = teamData.getTeamInfo();
            teamInfo.setTagVisibility(WrapperPlayServerTeams.NameTagVisibility.NEVER);
            if (teamData.getTeamInfo() != null) {
                event.getUser().sendPacketSilently(new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.UPDATE, teamData.getTeamInfo(), teamData.getMembers()));
            }
        }

        if (event.getPlayer() instanceof Player viewer) {
            packet.getPlayers().stream().map(Bukkit::getPlayerExact).filter(Objects::nonNull)
                    .forEach(target -> removeVanillaFallbackNametagHidden(viewer, target));
        }
    }

    private void handleRemoveEntities(@NotNull WrapperPlayServerTeams packet, @NotNull Map<String, TeamData> teams, @NotNull String teamName) {
        final Optional<TeamData> teamDataOpt = Optional.ofNullable(teams.get(teamName));
        teamDataOpt.ifPresent(teamData -> teamData.getMembers().removeAll(packet.getPlayers()));
    }

    private void handleCreateTeam(@NotNull PacketSendEvent event, @NotNull WrapperPlayServerTeams packet,
                                  @NotNull Map<String, TeamData> teams, @NotNull String teamName) {
        if (teams.containsKey(teamName)) {
            return;
        }

        packet.getTeamInfo().ifPresent(teamInfo -> {
            final TeamData teamData = new TeamData(teamName, teamInfo, Set.copyOf(packet.getPlayers()));
            teams.put(teamName, teamData);

            if (shouldHideVanillaNametags((Player) event.getPlayer())
                    && (plugin.getConfigManager().getSettings().getBehavior().isForceDisableDefaultNameTag()
                    || teamData.getMembers().stream().anyMatch(this::existsPlayer))) {
                teamData.setChangedVisibility(true);
                // Ensure teamInfo is not null before setting visibility
                if (teamData.getTeamInfo() != null) {
                    teamData.getTeamInfo().setTagVisibility(WrapperPlayServerTeams.NameTagVisibility.NEVER);
                    event.markForReEncode(true);
                }
            }
        });

        if (event.getPlayer() instanceof Player viewer) {
            packet.getPlayers().stream().map(Bukkit::getPlayerExact).filter(Objects::nonNull)
                    .forEach(target -> removeVanillaFallbackNametagHidden(viewer, target));
        }
    }

    private void handleUpdateTeam(@NotNull PacketSendEvent event, @NotNull WrapperPlayServerTeams packet, @NotNull Map<String, TeamData> teams, @NotNull String teamName) {
        final Optional<TeamData> teamDataOpt = Optional.ofNullable(teams.get(teamName));
        if (teamDataOpt.isEmpty()) {
            return;
        }

        final TeamData teamData = teamDataOpt.get();
        packet.getTeamInfo().ifPresent(teamInfoFromPacket -> {
            teamData.setOriginalVisibility(teamInfoFromPacket.getTagVisibility());
            final boolean hide = shouldHideVanillaNametags((Player) event.getPlayer())
                    && (plugin.getConfigManager().getSettings().getBehavior().isForceDisableDefaultNameTag()
                    || teamData.getMembers().stream().anyMatch(this::existsPlayer));
            teamData.setChangedVisibility(hide);
            if (hide && teamInfoFromPacket.getTagVisibility() != WrapperPlayServerTeams.NameTagVisibility.NEVER) {
                teamInfoFromPacket.setTagVisibility(WrapperPlayServerTeams.NameTagVisibility.NEVER);
                event.markForReEncode(true);
            }
            teamData.setTeamInfo(teamInfoFromPacket);
        });
    }

    public void removePlayerData(@NotNull Player player) {
        teams.remove(player.getUniqueId());
        vanillaFallbackTeams.remove(player.getUniqueId());
        final UUID targetId = player.getUniqueId();
        vanillaFallbackTeams.forEach((viewerId, byTarget) -> {
            if (!byTarget.containsKey(targetId)) {
                return;
            }
            final Player viewer = plugin.getPlayerListener().getPlayer(viewerId);
            if (viewer != null) {
                removeVanillaFallbackNametagHidden(viewer, player);
            } else {
                byTarget.remove(targetId);
            }
            if (byTarget.isEmpty()) {
                vanillaFallbackTeams.remove(viewerId, byTarget);
            }
        });
    }

    public void onWorldChange(@NotNull Player player) {
        final Map<String, TeamData> viewerTeams = getTeams(player.getUniqueId());
        final boolean enabled = shouldHideVanillaNametags(player);
        final User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user == null) {
            return;
        }
        viewerTeams.forEach((teamName, teamData) -> {
            final boolean shouldHide = enabled
                    && (plugin.getConfigManager().getSettings().getBehavior().isForceDisableDefaultNameTag()
                    || teamData.getMembers().stream().anyMatch(this::existsPlayer));
            if (shouldHide == teamData.isChangedVisibility() || teamData.getTeamInfo() == null) {
                return;
            }
            teamData.getTeamInfo().setTagVisibility(shouldHide
                    ? WrapperPlayServerTeams.NameTagVisibility.NEVER
                    : teamData.getOriginalVisibility());
            teamData.setChangedVisibility(shouldHide);
            user.sendPacketSilently(new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.UPDATE,
                    teamData.getTeamInfo(), teamData.getMembers()));
        });
        final Map<UUID, String> fallbackByTarget = vanillaFallbackTeams.get(player.getUniqueId());
        if (!enabled && fallbackByTarget != null) {
            final Set<UUID> targets = Set.copyOf(fallbackByTarget.keySet());
            for (UUID targetId : targets) {
                final Player target = plugin.getPlayerListener().getPlayer(targetId);
                if (target != null) removeVanillaFallbackNametagHidden(player, target);
            }
        } else if (enabled) {
            plugin.getTrackerManager().getTrackedPlayers(player.getUniqueId()).stream()
                    .map(plugin.getPlayerListener()::getPlayer)
                    .filter(Objects::nonNull)
                    .forEach(target -> ensureVanillaNametagHidden(player, target));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void handleMetaData(@NotNull PacketSendEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        int protocol = event.getUser().getClientVersion().getProtocolVersion();
        //handle metadata for : bedrock players && client with version 1.20.1 or lower
        if (protocol >= 764) {
            return;
        }

        final WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
        final Optional<PaperNametagRow> textDisplay = plugin.getNametagManager().getPacketDisplayByEntityId(packet.getEntityId());
        if (textDisplay.isEmpty()) {
            return;
        }

        for (final EntityData eData : packet.getEntityMetadata()) {
            if (eData.getIndex() == 11) {
                final Vector3f old = (Vector3f) eData.getValue();
                final Vector3f newV = new Vector3f(old.getX(), old.getY() + 0.45f, old.getZ());
                eData.setValue(newV);
                event.markForReEncode(true);
                return;
            }
        }
    }

    public boolean existsPlayer(@NotNull String name) {
        return plugin.getPlayerListener().getPlayerNameId().containsKey(name);
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        if (plugin.getPacketManager() != null) plugin.getPacketManager().reset(event.getUser());
    }

    public void onDisable() {
        PacketEvents.getAPI().getEventManager().unregisterListener(passengerObserver);
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        vanillaFallbackTeams.clear();
    }
}
