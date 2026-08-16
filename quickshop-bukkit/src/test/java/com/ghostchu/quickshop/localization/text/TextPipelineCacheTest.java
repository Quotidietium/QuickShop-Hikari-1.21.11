package com.ghostchu.quickshop.localization.text;

import com.ghostchu.quickshop.QuickShop;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the text pipeline caches: raw template read-through, the shared
 * component for argument-less texts, and invalidation when a locale is re-registered.
 */
class TextPipelineCacheTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private QuickShop plugin;
  private SimpleTextManager manager;

  @TempDir
  File dataFolder;

  @BeforeEach
  void setUp() {

    quickShopStatic = mockStatic(QuickShop.class);
    bukkitStatic = mockStatic(Bukkit.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);

    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getDataFolder()).thenReturn(dataFolder);
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(bukkitPlugin.getResource(anyString())).thenAnswer(inv -> {
      final String resource = inv.getArgument(0, String.class);
      if("color-scheme.yml".equals(resource)) {
        return new java.io.ByteArrayInputStream("color-scheme:\n  price: '#FFAA00'\n".getBytes());
      }
      if(resource != null && resource.startsWith("lang/")) {
        return new java.io.ByteArrayInputStream("signs:\n  price: 'Price: {0}'\n".getBytes());
      }
      return new java.io.ByteArrayInputStream(new byte[0]);
    });
    final dev.dejvokep.boostedyaml.YamlDocument config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(anyString())).thenReturn(false);
    lenient().when(config.getString(anyString())).thenReturn("en_us");
    lenient().when(plugin.getConfig()).thenReturn(config);
    lenient().when(plugin.getPasteManager()).thenReturn(mock(com.ghostchu.quickshop.util.paste.PasteManager.class));
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    final var finder = mock(com.ghostchu.quickshop.api.shop.PlayerFinder.class);
    lenient().when(plugin.getPlayerFinder()).thenReturn(finder);

    final var platform = mock(com.ghostchu.quickshop.platform.Platform.class);
    lenient().when(platform.miniMessage()).thenReturn(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage());
    lenient().when(plugin.platform()).thenReturn(platform);

    manager = new SimpleTextManager(plugin);
    lenient().when(plugin.text()).thenReturn(manager);
    manager.register("en_us", "signs.price", "<color_scheme:price><bold>{0}</bold></color_scheme>");
    manager.register("en_us", "signs.unlimited", "<color_scheme:price>Unlimited</color_scheme>");
  }

  @AfterEach
  void tearDown() {

    bukkitStatic.close();
    quickShopStatic.close();
  }

  @Test
  void argumentlessTextsShareOneComponentInstance() {

    final var first = manager.of("signs.unlimited").forLocale("en_us");
    final var second = manager.of("signs.unlimited").forLocale("en_us");
    assertNotNull(first);
    // post processing may rebuild component instances per call; the parse cache itself
    // must hand out the identical immutable component
    assertEquals(first, second);
    final var resolvers = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[0];
    final var cached = manager.staticComponent("en_us", "signs.unlimited", "ignored-on-hit", resolvers);
    final var cachedAgain = manager.staticComponent("en_us", "signs.unlimited", "ignored-on-hit", resolvers);
    assertSame(cached, cachedAgain, "the parse cache must reuse the stored component");
  }

  @Test
  void parameterizedTextsParsePerCall() {

    final var first = manager.of("signs.price", net.kyori.adventure.text.Component.text("1")).forLocale("en_us");
    final var second = manager.of("signs.price", net.kyori.adventure.text.Component.text("2")).forLocale("en_us");
    assertNotSame(first, second, "texts with arguments must not share components");
  }

  @Test
  void runtimeRegistrationInvalidatesTheStaticComponent() {

    final var serializer = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
    final var before = serializer.serialize(manager.of("signs.unlimited").forLocale("en_us"));
    manager.register("en_us", "signs.unlimited", "<color_scheme:price>Infinity</color_scheme>");
    final var after = serializer.serialize(manager.of("signs.unlimited").forLocale("en_us"));
    assertEquals("Unlimited", before);
    assertFalse(before.equals(after), "re-registered templates must not serve the stale component");
    assertEquals("Infinity", after);
  }

  @Test
  void resolvedLocaleIsCachedPerLanguageCode() {

    final var first = manager.findRelativeLanguages("en_us");
    final var second = manager.findRelativeLanguages("en_us");
    assertSame(first, second);
    assertEquals("en_us", first.getLocale());
  }
}
