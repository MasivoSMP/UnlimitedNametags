package org.alexdev.unlimitednametags.listeners;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.google.common.collect.Maps;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.alexdev.unlimitednametags.UnlimitedNameTags;
import org.alexdev.unlimitednametags.data.TeamData;
import org.alexdev.unlimitednametags.packet.PacketNameTag;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class PacketEventsListener extends PacketListenerAbstract {

    private static final String BEDROCK_FALLBACK_TEAM_PREFIX = "unt_bedrock_hide_";
    private final UnlimitedNameTags plugin;
    private final Map<UUID, Map<String, TeamData>> teams;
    private final Map<UUID, Map<UUID, String>> bedrockFallbackTeams;

    public PacketEventsListener(UnlimitedNameTags plugin) {
        this.plugin = plugin;
        this.teams = Maps.newConcurrentMap();
        this.bedrockFallbackTeams = Maps.newConcurrentMap();
    }

    public void onEnable() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @NotNull
    public Map<String, TeamData> getTeams(@NotNull UUID player) {
        return teams.computeIfAbsent(player, p -> Maps.newConcurrentMap());
    }

    public void onPacketSend(@NotNull PacketSendEvent event) {
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

        if (!plugin.getConfigManager().getSettings().isShowCurrentNameTag()) {
            return;
        }

        final WrapperPlayServerCamera camera = new WrapperPlayServerCamera(event);
        if (camera.getCameraId() == player.getEntityId()) {
            plugin.getNametagManager().getPacketDisplayText(player).ifPresent(PacketNameTag::showForOwner);
        } else {
            plugin.getNametagManager().getPacketDisplayText(player).ifPresent(PacketNameTag::hideForOwner);
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
        final Optional<PacketNameTag> optionalPacketDisplayText = plugin.getNametagManager().getPacketDisplayText(player);
        if (optionalPacketDisplayText.isEmpty()) {
            return;
        }

        if (packet.isShift() != optionalPacketDisplayText.get().isSneaking()) {
            plugin.getNametagManager().updateSneaking(player, packet.isShift());
        }
    }

    private void handlePassengers(@NotNull PacketSendEvent event) {
        final WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
        final Optional<? extends Player> player = plugin.getPlayerListener().getPlayerFromEntityId(packet.getEntityId());
        if (player.isEmpty()) {
            return;
        }

        final List<Integer> passengers = collectPassengers(packet.getPassengers());
        final Optional<PacketNameTag> optionalPacketDisplayText = plugin.getNametagManager().getPacketDisplayText(player.get());
        if (optionalPacketDisplayText.isEmpty()) {
            plugin.getPacketManager().setPassengers(player.get(), passengers);
            return;
        }

        if(!passengers.contains(optionalPacketDisplayText.get().getEntityId())) {
            passengers.add(optionalPacketDisplayText.get().getEntityId());
            passengers.sort(Comparator.naturalOrder());
            packet.setPassengers(passengers.stream().mapToInt(i -> i).toArray());
            event.markForReEncode(true);
        }

        plugin.getPacketManager().setPassengers(player.get(), passengers);
    }

    @NotNull
    private List<Integer> collectPassengers(int[] passengers) {
        final  List<Integer> passengerList = new ArrayList<>(passengers.length);
        for (int passenger : passengers) {
            passengerList.add(passenger);
        }

        return passengerList;
    }

    private boolean preTeamsChecks(@NotNull PacketSendEvent event, boolean bedrockViewer) {
        if (!plugin.getConfigManager().getSettings().isDisableDefaultNameTag()) {
            return false;
        }

        if (bedrockViewer) {
            return true;
        }

        return event.getUser().getClientVersion().isNewerThan(ClientVersion.V_1_19_3);
    }

    private void handleTeams(@NotNull PacketSendEvent event) {
        final boolean bedrockViewer = isBedrockViewer(event);
        if (!preTeamsChecks(event, bedrockViewer)) {
            return;
        }

        final WrapperPlayServerTeams packet = new WrapperPlayServerTeams(event);
        if (isBedrockFallbackTeam(packet.getTeamName())) {
            return;
        }

        if (handleForceDisableDefaultNameTag(event, packet)) {
            return;
        }

        if (bedrockViewer) {
            forceHideVanillaNametagForBedrock(event, packet);
        }

        final Map<String, TeamData> teams = getTeams(event.getUser().getUUID());
        final String teamName = packet.getTeamName();

        switch (packet.getTeamMode()) {
            case ADD_ENTITIES -> handleAddEntities(event, packet, teams, teamName);
            case REMOVE_ENTITIES -> handleRemoveEntities(packet, teams, teamName);
            case CREATE -> handleCreateTeam(event, packet, teams, teamName);
            case UPDATE -> handleUpdateTeam(event, packet, teams, teamName);
            case REMOVE -> teams.remove(teamName);
        }
    }

    private boolean isBedrockViewer(@NotNull PacketSendEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return false;
        }

        return plugin.isBedrockPlayer(player);
    }

    public void ensureBedrockFallbackNametagHidden(@NotNull Player viewer, @NotNull Player target) {
        if (!plugin.getConfigManager().getSettings().isDisableDefaultNameTag()) {
            return;
        }

        if (!plugin.isBedrockPlayer(viewer) || viewer.getUniqueId().equals(target.getUniqueId())) {
            return;
        }

        if (isPlayerAlreadyInTeam(viewer.getUniqueId(), target.getName())) {
            return;
        }

        final User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (user == null || user.getChannel() == null) {
            return;
        }

        final UUID viewerId = viewer.getUniqueId();
        final UUID targetId = target.getUniqueId();
        final Map<UUID, String> fallbackByTarget = bedrockFallbackTeams.computeIfAbsent(viewerId, u -> Maps.newConcurrentMap());
        if (fallbackByTarget.containsKey(targetId)) {
            return;
        }

        final String teamName = buildBedrockFallbackTeamName(viewerId, targetId);
        fallbackByTarget.put(targetId, teamName);

        final WrapperPlayServerTeams.ScoreBoardTeamInfo info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                Component.empty(),
                Component.empty(),
                Component.empty(),
                WrapperPlayServerTeams.NameTagVisibility.NEVER,
                WrapperPlayServerTeams.CollisionRule.ALWAYS,
                NamedTextColor.WHITE,
                WrapperPlayServerTeams.OptionData.NONE
        );

        user.sendPacket(new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.CREATE, info, List.of(target.getName())));

        if (plugin.getNametagManager() != null && plugin.getNametagManager().isDebug()) {
            plugin.getLogger().info("Created bedrock fallback team for viewer "
                    + viewer.getName() + " (" + viewerId + ") and target "
                    + target.getName() + " (" + targetId + ")");
        }
    }

    public void removeBedrockFallbackNametagHidden(@NotNull Player viewer, @NotNull Player target) {
        final Map<UUID, String> fallbackByTarget = bedrockFallbackTeams.get(viewer.getUniqueId());
        if (fallbackByTarget == null) {
            return;
        }

        final String teamName = fallbackByTarget.remove(target.getUniqueId());
        if (teamName == null) {
            return;
        }

        if (fallbackByTarget.isEmpty()) {
            bedrockFallbackTeams.remove(viewer.getUniqueId(), fallbackByTarget);
        }

        final User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (user == null || user.getChannel() == null) {
            return;
        }

        user.sendPacket(new WrapperPlayServerTeams(
                teamName,
                WrapperPlayServerTeams.TeamMode.REMOVE,
                Optional.<WrapperPlayServerTeams.ScoreBoardTeamInfo>empty(),
                List.of()
        ));

        if (plugin.getNametagManager() != null && plugin.getNametagManager().isDebug()) {
            plugin.getLogger().info("Removed bedrock fallback team " + teamName + " for viewer " + viewer.getName());
        }
    }

    private boolean isPlayerAlreadyInTeam(@NotNull UUID viewerId, @NotNull String playerName) {
        final Map<String, TeamData> viewerTeams = teams.get(viewerId);
        if (viewerTeams == null || viewerTeams.isEmpty()) {
            return false;
        }

        return viewerTeams.values().stream().anyMatch(team -> team.getMembers().contains(playerName));
    }

    private boolean isBedrockFallbackTeam(@NotNull String teamName) {
        return teamName.startsWith(BEDROCK_FALLBACK_TEAM_PREFIX);
    }

    @NotNull
    private String buildBedrockFallbackTeamName(@NotNull UUID viewerId, @NotNull UUID targetId) {
        final String viewer = viewerId.toString().replace("-", "").substring(0, 8);
        final String target = targetId.toString().replace("-", "").substring(0, 8);
        return BEDROCK_FALLBACK_TEAM_PREFIX + viewer + "_" + target;
    }

    private void forceHideVanillaNametagForBedrock(@NotNull PacketSendEvent event, @NotNull WrapperPlayServerTeams packet) {
        if (packet.getTeamMode() != WrapperPlayServerTeams.TeamMode.CREATE
                && packet.getTeamMode() != WrapperPlayServerTeams.TeamMode.UPDATE) {
            return;
        }

        packet.getTeamInfo().ifPresent(teamInfo -> {
            if (teamInfo.getTagVisibility() != WrapperPlayServerTeams.NameTagVisibility.NEVER) {
                teamInfo.setTagVisibility(WrapperPlayServerTeams.NameTagVisibility.NEVER);
                event.markForReEncode(true);
            }
        });

        if (plugin.getNametagManager() != null
                && plugin.getNametagManager().isDebug()
                && event.getPlayer() instanceof Player viewer) {
            plugin.getLogger().info("Forced bedrock team nametag visibility to NEVER for viewer "
                    + viewer.getName() + " (" + viewer.getUniqueId() + "), team "
                    + packet.getTeamName() + ", mode " + packet.getTeamMode());
        }
    }

    private boolean handleForceDisableDefaultNameTag(@NotNull PacketSendEvent event, @NotNull WrapperPlayServerTeams packet) {
        if (plugin.getConfigManager().getSettings().isForceDisableDefaultNameTag()) {
            if (packet.getTeamMode() == WrapperPlayServerTeams.TeamMode.CREATE || packet.getTeamMode() == WrapperPlayServerTeams.TeamMode.UPDATE) {
                packet.getTeamInfo().ifPresent(t -> t.setTagVisibility(WrapperPlayServerTeams.NameTagVisibility.NEVER));
                event.markForReEncode(true);
            }
            return true;
        }
        return false;
    }

    private void handleAddEntities(@NotNull PacketSendEvent event, @NotNull WrapperPlayServerTeams packet,
                                   @NotNull Map<String, TeamData> teams, @NotNull String teamName) {
        final Optional<TeamData> teamDataOpt = Optional.ofNullable(teams.get(teamName));
        if (teamDataOpt.isEmpty()) {
            return;
        }

        final TeamData teamData = teamDataOpt.get();
        teamData.getMembers().addAll(packet.getPlayers());

        if (!teamData.isChangedVisibility() && packet.getPlayers().stream().anyMatch(this::existsPlayer)) {
            teamData.setChangedVisibility(true);
            final WrapperPlayServerTeams.ScoreBoardTeamInfo teamInfo = teamData.getTeamInfo();
            teamInfo.setTagVisibility(WrapperPlayServerTeams.NameTagVisibility.NEVER);
            if (teamData.getTeamInfo() != null) {
                event.getUser().sendPacket(new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.UPDATE, teamData.getTeamInfo(), teamData.getMembers()));
            }
        }

        if (event.getPlayer() instanceof Player viewer && plugin.isBedrockPlayer(viewer)) {
            packet.getPlayers().stream()
                    .map(Bukkit::getPlayerExact)
                    .filter(Objects::nonNull)
                    .forEach(target -> removeBedrockFallbackNametagHidden(viewer, target));
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

            if (teamData.getMembers().stream().anyMatch(this::existsPlayer)) {
                teamData.setChangedVisibility(true);
                // Ensure teamInfo is not null before setting visibility
                if (teamData.getTeamInfo() != null) {
                    teamData.getTeamInfo().setTagVisibility(WrapperPlayServerTeams.NameTagVisibility.NEVER);
                    event.markForReEncode(true);
                }
            }
        });

        if (event.getPlayer() instanceof Player viewer && plugin.isBedrockPlayer(viewer)) {
            packet.getPlayers().stream()
                    .map(Bukkit::getPlayerExact)
                    .filter(Objects::nonNull)
                    .forEach(target -> removeBedrockFallbackNametagHidden(viewer, target));
        }
    }

    private void handleUpdateTeam(@NotNull PacketSendEvent event, @NotNull WrapperPlayServerTeams packet, @NotNull Map<String, TeamData> teams, @NotNull String teamName) {
        final Optional<TeamData> teamDataOpt = Optional.ofNullable(teams.get(teamName));
        if (teamDataOpt.isEmpty()) {
            return;
        }

        final TeamData teamData = teamDataOpt.get();
        packet.getTeamInfo().ifPresent(teamInfoFromPacket -> {
            if (teamData.isChangedVisibility() && teamInfoFromPacket.getTagVisibility() != WrapperPlayServerTeams.NameTagVisibility.NEVER) {
                teamInfoFromPacket.setTagVisibility(WrapperPlayServerTeams.NameTagVisibility.NEVER);
                event.markForReEncode(true);
            }
            teamData.setTeamInfo(teamInfoFromPacket);
        });
    }

    public void removePlayerData(@NotNull Player player) {
        teams.remove(player.getUniqueId());

        // Drop all fallback teams owned by this viewer.
        bedrockFallbackTeams.remove(player.getUniqueId());

        // Drop fallback references where this player is the target.
        final UUID targetId = player.getUniqueId();
        for (final Map.Entry<UUID, Map<UUID, String>> entry : bedrockFallbackTeams.entrySet()) {
            final Map<UUID, String> byTarget = entry.getValue();
            if (byTarget == null) {
                continue;
            }
            byTarget.remove(targetId);
            if (byTarget.isEmpty()) {
                bedrockFallbackTeams.remove(entry.getKey(), byTarget);
            }
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
        final Optional<PacketNameTag> textDisplay = plugin.getNametagManager().getPacketDisplayText(packet.getEntityId());
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
//        return plugin.getPlayerListener().getPlayerNameId().containsKey(name);
        return Bukkit.getPlayer(name) != null;
    }

    public void onDisable() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        bedrockFallbackTeams.clear();
    }
}
