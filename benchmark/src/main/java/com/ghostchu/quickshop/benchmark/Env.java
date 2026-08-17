package com.ghostchu.quickshop.benchmark;

import dev.dejvokep.boostedyaml.YamlDocument;
import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.QuickShopProvider;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Installs a worker-thread-safe fake server environment for benchmarks.
 * <p>
 * Mockito static mocks are thread-local, which makes them unusable for executor-based code
 * (EasySQL worker threads, virtual threads). Instead this class mirrors the approach of
 * TradeLoadSmokeTest: the {@code Bukkit.server} and {@code QuickShop.instance} static
 * fields are written reflectively with plain mocks, so every thread observes the same
 * environment. Config lookups resolve through a plain map so benchmarks can tweak values
 * without Mockito argument-matcher tricks.
 */
public final class Env {

  private static final Map<String, Object> CONFIGS = new ConcurrentHashMap<>();
  /**
   * Bukkit Location keeps its World in a WeakReference, so a mock World held only by a
   * suite-local variable can be collected mid-benchmark and every later getWorld() throws
   * "World unloaded". Suites pin their world mocks here.
   */
  private static final List<Object> PINS = new CopyOnWriteArrayList<>();

  static {
    install();
  }

  /** Keeps a strong reference for the whole benchmark JVM (returns the argument). */
  public static <T> T pin(final T object) {

    PINS.add(object);
    return object;
  }

  private static final class Holder {

    private static final File DATA_FOLDER = createTempFolder();
    private static final PluginBundle BUNDLE = createPlugin();
    private static final QuickShop PLUGIN = BUNDLE.plugin();
    private static final com.ghostchu.quickshop.QuickShopBukkit BUKKIT_PLUGIN = BUNDLE.bukkitPlugin();
    private static final Server SERVER = createServer(PLUGIN, BUKKIT_PLUGIN);

    private record PluginBundle(QuickShop plugin, com.ghostchu.quickshop.QuickShopBukkit bukkitPlugin) {

    }

    private static File createTempFolder() {

      try {
        final File dir = Files.createTempDirectory("qs-benchmark").toFile();
        dir.deleteOnExit();
        return dir;
      } catch(final IOException e) {
        throw new IllegalStateException("Failed to create benchmark temp folder", e);
      }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Server createServer(final QuickShop plugin, final com.ghostchu.quickshop.QuickShopBukkit bukkitPlugin) {

      final Server server = mock(Server.class);
      when(server.isPrimaryThread()).thenReturn(true);
      when(server.getPluginManager()).thenReturn(mock(PluginManager.class));

      final QuickShopProvider provider = mock(QuickShopProvider.class);
      when(provider.getApiInstance()).thenReturn(plugin);
      when(provider.getInstance()).thenReturn(bukkitPlugin);
      final RegisteredServiceProvider rsp = mock(RegisteredServiceProvider.class);
      when(rsp.getProvider()).thenReturn(provider);
      when(rsp.getPlugin()).thenReturn(bukkitPlugin);
      final ServicesManager servicesManager = mock(ServicesManager.class);
      when(servicesManager.getRegistration(QuickShopProvider.class)).thenReturn(rsp);
      when(server.getServicesManager()).thenReturn(servicesManager);
      return server;
    }

    private static PluginBundle createPlugin() {

      final QuickShop plugin = mock(QuickShop.class);
      Mockito.lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
      Mockito.lenient().when(plugin.getDataFolder()).thenReturn(DATA_FOLDER);
      // QuickShopBukkit's PluginBase.getName()/getPluginMeta() are final and read the
      // JavaPlugin.pluginMeta field (null on a mock). Mockito mocks skip constructors, so
      // the field can simply be injected reflectively to make the final methods work.
      final com.ghostchu.quickshop.QuickShopBukkit bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
      Mockito.lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
      final io.papermc.paper.plugin.configuration.PluginMeta pluginMeta =
              mock(io.papermc.paper.plugin.configuration.PluginMeta.class);
      when(pluginMeta.getName()).thenReturn("QuickShop-Hikari");
      Mockito.lenient().when(pluginMeta.getVersion()).thenReturn("6.3.0.0");
      injectSuperclassField(bukkitPlugin, "pluginMeta", pluginMeta);
      Mockito.lenient().when(bukkitPlugin.getResource(anyString())).thenAnswer(inv -> {
        final String resource = inv.getArgument(0);
        if("color-scheme.yml".equals(resource)) {
          return stream("color-scheme:\n  price: '#FFAA00'\n  trading: '#55FF55'\n  item: '#FFFFFF'\n");
        }
        if(resource != null && resource.startsWith("lang/")) {
          return stream("signs:\n  price: 'Price: {0}'\n");
        }
        // other bundled resources (price-restriction.yml etc.) fall back to empty YAML
        return stream("");
      });

      final YamlDocument config = mock(YamlDocument.class);
      Mockito.lenient().when(config.getBoolean(anyString())).thenAnswer(inv -> {
        final Object value = CONFIGS.get(inv.getArgument(0, String.class));
        return value instanceof final Boolean bool && bool;
      });
      Mockito.lenient().when(config.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> {
        final Object value = CONFIGS.get(inv.getArgument(0, String.class));
        return value instanceof final Boolean bool && bool;
      });
      Mockito.lenient().when(config.getInt(anyString())).thenAnswer(inv -> {
        final Object value = CONFIGS.get(inv.getArgument(0, String.class));
        return value instanceof final Integer integer ? integer : 0;
      });
      Mockito.lenient().when(config.getInt(anyString(), any(Integer.class))).thenAnswer(inv -> {
        final Object value = CONFIGS.get(inv.getArgument(0, String.class));
        if(value instanceof final Integer integer) {
          return integer;
        }
        return inv.getArgument(1, Integer.class);
      });
      Mockito.lenient().when(config.getDouble(anyString())).thenAnswer(inv -> {
        final Object value = CONFIGS.get(inv.getArgument(0, String.class));
        return value instanceof final Double decimal ? decimal : 0.0d;
      });
      Mockito.lenient().when(config.getDouble(anyString(), any(Double.class))).thenAnswer(inv -> {
        final Object value = CONFIGS.get(inv.getArgument(0, String.class));
        if(value instanceof final Double decimal) {
          return decimal;
        }
        return inv.getArgument(1, Double.class);
      });
      Mockito.lenient().when(config.getString(anyString())).thenAnswer(inv -> {
        final Object value = CONFIGS.get(inv.getArgument(0, String.class));
        return value instanceof final String str ? str : null;
      });
      Mockito.lenient().when(config.getString(anyString(), any(String.class))).thenAnswer(inv -> {
        final Object value = CONFIGS.get(inv.getArgument(0, String.class));
        if(value instanceof final String str) {
          return str;
        }
        return inv.getArgument(1, String.class);
      });
      Mockito.lenient().when(config.getStringList(anyString())).thenReturn(new java.util.ArrayList<>());
      Mockito.lenient().when(plugin.getConfig()).thenReturn(config);
      Mockito.lenient().when(plugin.getPasteManager())
              .thenReturn(mock(com.ghostchu.quickshop.util.paste.PasteManager.class));

      // deterministic player finder used by every QUser round-trip
      final com.ghostchu.quickshop.api.shop.PlayerFinder finder =
              mock(com.ghostchu.quickshop.api.shop.PlayerFinder.class);
      Mockito.lenient().when(finder.uuid2NameFuture(any(java.util.UUID.class), any(Boolean.class), any()))
              .thenAnswer(inv -> java.util.concurrent.CompletableFuture.completedFuture("user"));
      Mockito.lenient().when(finder.name2Uuid(anyString(), any(Boolean.class), any()))
              .thenAnswer(inv -> java.util.UUID.nameUUIDFromBytes(
                      inv.getArgument(0, String.class).getBytes(StandardCharsets.UTF_8)));
      Mockito.lenient().when(plugin.getPlayerFinder()).thenReturn(finder);
      Mockito.lenient().when(plugin.getReloadManager())
              .thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
      return new PluginBundle(plugin, bukkitPlugin);
    }

    private static InputStream stream(final String content) {

      return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
  }

  private Env() {

  }

  private static void install() {

    // Bukkit.setServer() reads the Paper build manifest, so write the field directly
    // (same technique as TradeLoadSmokeTest).
    try {
      final java.lang.reflect.Field serverField = Bukkit.class.getDeclaredField("server");
      serverField.setAccessible(true);
      serverField.set(null, Holder.SERVER);

      final java.lang.reflect.Field instanceField = QuickShop.class.getDeclaredField("instance");
      instanceField.setAccessible(true);
      instanceField.set(null, Holder.PLUGIN);
      // Util.plugin is captured by an explicit initialize() call (onEnable does this on a
      // real server); canBeShop() and friends would NPE without it
      com.ghostchu.quickshop.util.Util.initialize();
    } catch(final ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to install benchmark statics", e);
    }
  }

  public static QuickShop plugin() {

    return Holder.PLUGIN;
  }

  public static Server server() {

    return Holder.SERVER;
  }

  public static File dataFolder() {

    return Holder.DATA_FOLDER;
  }

  public static void setConfig(final String path, final Object value) {

    CONFIGS.put(path, value);
  }

  private static void injectSuperclassField(final Object target, final String field, final Object value) {

    for(Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        final java.lang.reflect.Field declared = type.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
        return;
      } catch(final NoSuchFieldException ignored) {
        // walk up the hierarchy
      } catch(final ReflectiveOperationException e) {
        throw new IllegalStateException("Failed to inject field " + field, e);
      }
    }
    throw new IllegalStateException("Field " + field + " not found on " + target.getClass());
  }
}
