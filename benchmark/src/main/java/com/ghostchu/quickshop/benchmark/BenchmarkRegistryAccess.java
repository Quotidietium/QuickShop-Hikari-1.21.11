package com.ghostchu.quickshop.benchmark;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockType;
import org.bukkit.inventory.ItemType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * ServiceLoader provider that keeps {@code Material.isAir()} (and the other registry
 * lookups Paper's enum bridges run) alive without a live server:
 * {@code Registry.<clinit>} resolves {@link RegistryAccess} through a ServiceLoader and
 * throws "No RegistryAccess implementation found" otherwise, which takes the whole
 * {@code Registry} class down with it. The BLOCK registry answers the air question for
 * real material keys; every other registry returns null lookups (the call sites treat
 * null as "not found", which is what a bare-API environment means).
 * <p>
 * Implemented with plain JDK proxies (no Mockito): the ServiceLoader can trigger this
 * class's initialization in the middle of an unrelated test's Mockito session, and
 * mock creation there would fail the whole {@code Registry} class permanently.
 * <p>
 * Registered through {@code META-INF/services/io.papermc.paper.registry.RegistryAccess}
 * in this module's resources; identical source on both A/B sides, never shipped.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class BenchmarkRegistryAccess implements RegistryAccess {

  private static final Object AIR_BLOCK = blockType(true);
  private static final Object NOT_AIR_BLOCK = blockType(false);
  private static final Object ITEM_TYPE = proxy(ItemType.class, BenchmarkRegistryAccess::defaultAnswer);
  private static final Registry<?> BLOCKS = blockRegistry();
  private static final Registry<?> ITEM_REGISTRY = itemRegistry();
  private static final Registry<?> EMPTY =
          (Registry<?>)proxy(Registry.class, BenchmarkRegistryAccess::defaultAnswer);

  private static boolean isAirKey(final NamespacedKey key) {

    final String value = key.getKey();
    return "air".equals(value) || "cave_air".equals(value) || "void_air".equals(value);
  }

  private static Object blockType(final boolean air) {

    return proxy(BlockType.class, (p, method, args) ->
            method.getName().equals("isAir")? air : defaultAnswer(p, method, args));
  }

  /** BLOCK registry answering {@code get(key)} with the two cached block types. */
  private static Registry<?> blockRegistry() {

    return (Registry<?>)proxy(Registry.class, (p, method, args) -> {
      if(method.getName().equals("get") && args[0] instanceof final NamespacedKey key) {
        return isAirKey(key)? AIR_BLOCK : NOT_AIR_BLOCK;
      }
      return defaultAnswer(p, method, args);
    });
  }

  /** ITEM registry handing out one cached item-type proxy. */
  private static Registry<?> itemRegistry() {

    return (Registry<?>)proxy(Registry.class, (p, method, args) -> {
      if(method.getName().equals("get")) {
        return ITEM_TYPE;
      }
      return defaultAnswer(p, method, args);
    });
  }

  private static Object proxy(final Class<?> type, final InvocationHandler handler) {

    return Proxy.newProxyInstance(BenchmarkRegistryAccess.class.getClassLoader(),
                                  new Class<?>[]{type}, handler);
  }

  /** Neutral default for unimplemented interface methods (null / primitive zero). */
  private static Object defaultAnswer(final Object proxy, final Method method, final Object[] args) {

    return switch(method.getName()) {
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      case "toString" -> "registry-benchmark-proxy";
      default -> defaultValue(method.getReturnType());
    };
  }

  private static Object defaultValue(final Class<?> type) {

    if(!type.isPrimitive() || type == void.class) {
      return null;
    }
    if(type == boolean.class) {
      return Boolean.FALSE;
    }
    if(type == long.class) {
      return 0L;
    }
    if(type == double.class) {
      return 0D;
    }
    if(type == float.class) {
      return 0F;
    }
    if(type == int.class) {
      return 0;
    }
    if(type == short.class) {
      return (short)0;
    }
    if(type == byte.class) {
      return (byte)0;
    }
    return (char)0;
  }

  @Override
  public <T extends Keyed> Registry<T> getRegistry(final RegistryKey<T> key) {

    if(key == RegistryKey.BLOCK) {
      return (Registry<T>)BLOCKS;
    }
    if(key == RegistryKey.ITEM) {
      return (Registry<T>)ITEM_REGISTRY;
    }
    return (Registry<T>)EMPTY;
  }

  @Override
  public <T extends Keyed> Registry<T> getRegistry(final Class<T> type) {

    return (Registry<T>)EMPTY;
  }
}
