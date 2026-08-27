package com.ghostchu.quickshop.benchmark.suite;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.benchmark.Env;
import com.ghostchu.quickshop.localization.text.SimpleTextManager;
import com.ghostchu.quickshop.platform.Platform;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.World;

import static com.ghostchu.quickshop.benchmark.BenchHarness.consume;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Measures the translation pipeline that backs every sign line and chat message:
 * YAML key lookup, MiniMessage placeholder fill, MiniMessage parse and post-processing.
 */
public final class TextBench {

  private TextBench() {

  }

  public static void run(final com.ghostchu.quickshop.benchmark.BenchHarness harness) throws Exception {

    Env.setConfig("use-crowdin-ota", false);
    Env.setConfig("lang-processor.papi-post-process", false);
    Env.setConfig("lang-processor.fix-item-always-italic", false);
    Env.setConfig("lang-processor.replace-filller-post-process", false);

    final QuickShop plugin = Env.plugin();
    final Platform platform = Env.hotMock(Platform.class);
    when(platform.miniMessage()).thenReturn(MiniMessage.miniMessage());
    when(plugin.platform()).thenReturn(platform);

    final SimpleTextManager textManager = new SimpleTextManager(plugin);
    when(plugin.text()).thenReturn(textManager);

    textManager.register("en_us", "signs.price", "<color_scheme:price><bold>{0}</bold></color_scheme> per stack");
    textManager.register("en_us", "signs.unlimited", "<color_scheme:trading>Unlimited</color_scheme>");
    textManager.register("en_us", "signs.item-left", "<color_scheme:item>");
    textManager.register("en_us", "signs.item-right", "</color_scheme>");

    harness.bench("text/forLocaleWithArgs", ctx -> {
      ctx.index++;
      consume(textManager.of("signs.price", Component.text("12.34")).forLocale("en_us"));
    });

    harness.bench("text/forLocaleNoArgs", ctx -> {
      ctx.index++;
      consume(textManager.of("signs.unlimited").forLocale("en_us"));
    });

    harness.bench("text/findRelativeLanguages", ctx -> {
      ctx.index++;
      consume(textManager.findRelativeLanguages("en_us"));
    });

    // the two item-name gates themselves (Util.useEnchantmentForEnchantedBook et al):
    // consulted per getItemStackName call server-wide (sign lines, receipts, menu
    // icons). The R28 candidate returns a volatile snapshot; the baseline walks the
    // config tree every call. Same body on both sides — behavior differs by jar.
    harness.bench("text/itemNameFlags", ctx -> {
      ctx.index++;
      consume(com.ghostchu.quickshop.util.Util.useEnchantmentForEnchantedBook()? 1 : 0);
    });
  }
}
