package me.tofaa.entitylib.spigot;

import com.github.retrooper.packetevents.PacketEventsAPI;
import io.github.retrooper.packetevents.bstats.bukkit.Metrics;
import io.github.retrooper.packetevents.bstats.charts.SimplePie;
import me.tofaa.entitylib.APIConfig;
import me.tofaa.entitylib.EntityLib;
import me.tofaa.entitylib.event.EventHandler;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;
import java.util.function.IntSupplier;

/** Upstream 596cc58/6111e19 adapter: avoid the removed no-world entity-ID API at initialization. */
public final class PinacEntityLibPlatform extends SpigotEntityLibPlatform {
    private SpigotEntityLibAPI api;
    private EventHandler eventHandler;
    private final IntSupplier entityIds;

    public PinacEntityLibPlatform(JavaPlugin plugin, IntSupplier entityIds) {
        super(plugin);
        this.entityIds = entityIds;
    }

    @Override
    public void setupApi(APIConfig settings) {
        // The superclass would instantiate SpigotEntityIdProvider and link nextEntityId() before replacement.
        eventHandler = EventHandler.create();
        setEntityUuidProvider(new me.tofaa.entitylib.EntityUuidProvider.DefaultEntityUuidProvider());
        logger = settings.shouldUsePlatformLogger() ? handle.getLogger() : Logger.getLogger("EntityLib");
        api = new SpigotEntityLibAPI(this, settings);
        setEntityIdProvider((uuid, type) -> entityIds.getAsInt());
        api.onLoad();
        api.onEnable();
        if (settings.shouldUseBstats()) {
            final PacketEventsAPI<Plugin> packets = (PacketEventsAPI<Plugin>) api.getPacketEvents();
            final Metrics metrics = new Metrics(packets.getPlugin(), 21916);
            metrics.addCustomChart(new SimplePie("entitylib-version", () -> EntityLib.getVersion().toString()));
        }
    }

    @Override
    public SpigotEntityLibAPI getAPI() { return api; }

    @Override
    public EventHandler getEventHandler() { return eventHandler; }
}
