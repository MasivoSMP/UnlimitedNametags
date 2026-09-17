import me.tofaa.entitylib.APIConfig;
import me.tofaa.entitylib.spigot.PinacEntityLibPlatform;
import org.alexdev.unlimitednametags.api.UNTAPI;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

/** No server or network: catches accidental return to EntityLib's removed no-world ID initialization. */
public final class MigrationCheck {
    public static void main(String[] args) throws Exception {
        var counter = new AtomicInteger(100);
        var platform = new PinacEntityLibPlatform(null, counter::incrementAndGet);
        var config = new APIConfig(null).disableBStats();
        platform.setupApi(config);
        assert platform.getAPI() != null;
        assert platform.getAPI().getSettings() == config;
        assert platform.getEventHandler() != null;
        assert platform.getEntityUuidProvider() != null;
        assert platform.getUserLocaleProvider() != null;
        assert platform.getEntityIdProvider().provide(UUID.randomUUID(), null) == 101;
        assert platform.getEntityIdProvider().provide(UUID.randomUUID(), null) == 102;
        var previousHandler = platform.getEventHandler();
        var previousUuidProvider = platform.getEntityUuidProvider();
        platform.setupApi(config);
        assert platform.getEventHandler() != previousHandler;
        assert platform.getEntityUuidProvider() != previousUuidProvider;
        assert UNTAPI.class.getMethod("getInstance").getReturnType() == UNTAPI.class;
        UNTAPI.class.getMethod("setForcedNametag", Player.class, Component.class);
        UNTAPI.class.getMethod("clearForcedNametag", Player.class);
        UNTAPI.class.getMethod("forceRefresh", Player.class, boolean.class);
        // Run the same startup path after Shadow relocation/minimization, with exact external target APIs.
        try (var loader = new java.net.URLClassLoader(new java.net.URL[] { new java.io.File(args[0]).toURI().toURL() }, MigrationCheck.class.getClassLoader())) {
            var prefix = "org.alexdev.unlimitednametags.libraries.entitylib.";
            var shadedPlatformClass = loader.loadClass(prefix + "spigot.PinacEntityLibPlatform");
            var shadedConfigClass = loader.loadClass(prefix + "APIConfig");
            var shadedConfig = shadedConfigClass.getConstructor(com.github.retrooper.packetevents.PacketEventsAPI.class).newInstance((Object) null);
            shadedConfigClass.getMethod("disableBStats").invoke(shadedConfig);
            var shadedPlatform = shadedPlatformClass.getConstructor(org.bukkit.plugin.java.JavaPlugin.class, java.util.function.IntSupplier.class)
                    .newInstance(null, (java.util.function.IntSupplier) counter::incrementAndGet);
            shadedPlatformClass.getMethod("setupApi", shadedConfigClass).invoke(shadedPlatform, shadedConfig);
            assert shadedPlatformClass.getMethod("getAPI").invoke(shadedPlatform) != null;
            assert shadedPlatformClass.getMethod("getEventHandler").invoke(shadedPlatform) != null;
            var provider = shadedPlatformClass.getMethod("getEntityIdProvider").invoke(shadedPlatform);
            assert (int) loader.loadClass(prefix + "EntityIdProvider").getMethod("provide", UUID.class,
                    com.github.retrooper.packetevents.protocol.entity.type.EntityType.class).invoke(provider, UUID.randomUUID(), null) == 103;
        }
        try (var jar = new ZipFile(args[0])) {
            assert jar.getEntry("org/alexdev/unlimitednametags/api/UNTAPI.class") != null;
            assert jar.getEntry("org/alexdev/unlimitednametags/libraries/entitylib/spigot/PinacEntityLibPlatform.class") != null;
            assert jar.stream().noneMatch(e -> e.getName().startsWith("org/bukkit/")
                    || e.getName().startsWith("net/kyori/adventure/")
                    || e.getName().startsWith("com/github/retrooper/packetevents/"));
            var descriptor = new String(jar.getInputStream(jar.getEntry("plugin.yml")).readAllBytes());
            assert descriptor.contains("api-version: '26.2'");
            assert descriptor.contains("folia-supported: true");
        }
        System.out.println("PASS: EntityLib startup/provider, legacy Aura signatures, target packaging");
    }
}
