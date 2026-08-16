package com.ghostchu.quickshop.benchmark;

import com.ghostchu.quickshop.QuickShop;

import static org.mockito.Mockito.mock;

/**
 * Minimal reproduction for Mockito retransformation failures in the forked JVM.
 */
public final class MockProbe {

  private MockProbe() {

  }

  public static void main(final String[] args) throws Exception {

    System.out.println("QuickShop from: " + QuickShop.class.getProtectionDomain().getCodeSource());
    System.out.println("bytebuddy from : " + net.bytebuddy.ByteBuddy.class.getProtectionDomain().getCodeSource());
    System.out.println("mockito from   : " + org.mockito.Mockito.class.getProtectionDomain().getCodeSource());
    System.out.println("retransform-eligible classes with name QuickShop*:");
    for(final Class<?> loaded : java.util.stream.Stream.of(QuickShop.class.getDeclaredClasses()).toList()) {
      System.out.println("  nested: " + loaded.getName());
    }
    System.out.println("mocking QuickShop...");
    final QuickShop plugin = mock(QuickShop.class);
    System.out.println("ok: " + plugin);
    System.out.println("mocking QuickShopBukkit...");
    final com.ghostchu.quickshop.QuickShopBukkit bukkit = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    // Paper's PluginBase.toString() reads the real PluginMeta, which is null on a mock
    System.out.println("ok: " + (bukkit != null));
    System.out.println("PROBE PASSED");
  }
}
