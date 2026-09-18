package com.ghostchu.quickshop.addon.discount;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.discount.type.CodeCreationResponse;
import com.ghostchu.quickshop.addon.discount.type.CodeType;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class DiscountCodeManager {

  public static final String NAME_REG_EXP = "[a-zA-Z0-9_]*";

  /**
   * Keyed by the lowercase code name. A plain HashSet keyed by {@code DiscountCode} identity
   * broke twice over: the code's hashCode() covers mutable fields (rate/maxUsage/scope/...),
   * so editing a code via {@code discount config} silently stranded it in the wrong bucket
   * (remove became a no-op and the code resurrected on every save), and synchronizedSet
   * iteration on the purchase path raced the async expiry cleanup into a CME.
   */
  private final ConcurrentHashMap<String, DiscountCode> codes = new ConcurrentHashMap<>();
  private final Pattern namePattern = Pattern.compile(NAME_REG_EXP);
  private final Main main;
  private final File file;
  private YamlConfiguration config;

  public DiscountCodeManager(final Main main) throws IOException {

    this.main = main;
    this.file = new File(main.getDataFolder(), "data.yml");
    initDatabase();
    cleanExpiredCodes();
  }

  private static String keyOf(@NotNull final String code) {

    return code.toLowerCase(Locale.ROOT);
  }

  private void initDatabase() throws IOException {

    if(!this.file.exists()) {
      this.file.createNewFile();
    }
    this.config = YamlConfiguration.loadConfiguration(this.file);
    this.codes.clear();
    this.config.getStringList("codes").stream()
            .map(DiscountCode::fromString)
            .filter(Objects::nonNull)
            .forEach(code->this.codes.put(keyOf(code.getCode()), code));
  }

  public void cleanExpiredCodes() {

    if(this.codes.values().removeIf(DiscountCode::isExpired)) {
      saveDatabase();
    }
  }

  public void saveDatabase() {

    try {
      this.config.set("codes", this.codes.values().stream().map(DiscountCode::saveToString).toList());
      this.config.save(this.file);
    } catch(IOException e) {
      main.getLogger().log(Level.WARNING, "Couldn't save the player discount codes status into database.", e);
    }
  }

  @Nullable
  public DiscountCode getCode(@NotNull final String code) {

    return this.codes.get(keyOf(code));
  }

  /**
   * Snapshot of all codes: callers iterate freely without holding any lock.
   */
  @NotNull
  public Set<DiscountCode> getCodes() {

    return Set.copyOf(this.codes.values());
  }

  public void removeCode(@NotNull final DiscountCode discountCode) {

    // key by name, not identity: the object's hash is unstable across config edits
    this.codes.remove(keyOf(discountCode.getCode()));
  }

  @NotNull
  public CodeCreationResponse createDiscountCode(@NotNull final CommandSender sender, @NotNull final UUID owner, @NotNull final String code, @NotNull final CodeType codeType, @NotNull final String rate, final int maxUsage, final double threshold, final long expiredTime) {

    if(!namePattern.matcher(code).matches()) {
      return CodeCreationResponse.REGEX_FAILURE;
    }
    if(this.codes.containsKey(keyOf(code))) {
      return CodeCreationResponse.CODE_EXISTS;
    }
    if(maxUsage != -1 && maxUsage < 1) {
      return CodeCreationResponse.INVALID_USAGE;
    }
    if(threshold != -1 && threshold < 1) {
      return CodeCreationResponse.INVALID_THRESHOLD;
    }
    if(expiredTime != -1 && expiredTime < 1) {
      return CodeCreationResponse.INVALID_EXPIRE_TIME;
    }
    final DiscountCode.DiscountRate discountRate = DiscountCode.toDiscountRate(rate);
    if(discountRate == null) {
      return CodeCreationResponse.INVALID_RATE;
    }
    if(!QuickShop.getPermissionManager().hasPermission(sender, "quickshopaddon.discount.create." + codeType.name().toLowerCase())) {
      return CodeCreationResponse.PERMISSION_DENIED;
    }
    final DiscountCode discountCode = new DiscountCode(owner, code, codeType, discountRate, maxUsage, threshold, expiredTime);
    if(this.codes.putIfAbsent(keyOf(code), discountCode) != null) {
      return CodeCreationResponse.CODE_EXISTS;
    }
    main.getCodeManager().saveDatabase();
    return CodeCreationResponse.SUCCESS;
  }

}
