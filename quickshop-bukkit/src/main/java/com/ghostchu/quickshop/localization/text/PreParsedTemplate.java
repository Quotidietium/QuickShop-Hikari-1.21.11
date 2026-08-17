package com.ghostchu.quickshop.localization.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A template pre-parsed once into a component tree with private-use sentinel markers in
 * place of the <code>{0}</code>-style placeholders. Rendering walks the immutable tree,
 * splits text nodes at sentinel positions and inserts the argument components directly —
 * no per-render serialization of the arguments and no re-parse of the whole filled
 * template (which the legacy {@link MiniMessageFiller} path pays tens of microseconds
 * for on every receipt/sign/chat render).
 *
 * <p>Safety model: if a template cannot be represented this way (a placeholder inside a
 * MiniMessage tag argument leaves an unconsumed sentinel, a missing argument, or an
 * unexpected structure), {@link #render(Component...)} returns null and the caller falls
 * back to the legacy string path — rendering differs from legacy only never: it either
 * produces the expansion or hands the exact original inputs to the legacy code.</p>
 */
final class PreParsedTemplate {

  /** Marks a placeholder hole; private-use characters survive MiniMessage parsing as text. */
  static final char SENTINEL_START = '\uE400';
  static final char SENTINEL_END = '\uE401';
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

  private static final PreParsedTemplate BROKEN = new PreParsedTemplate(null);

  private final @Nullable Component root;

  private PreParsedTemplate(@Nullable final Component root) {

    this.root = root;
  }

  /**
   * Parses a raw template: placeholders become sentinels, then one MiniMessage parse.
   *
   * @return a template, or the broken marker (render always falls back) when the raw
   *         template has no placeholders or the parse fails
   */
  static @NotNull PreParsedTemplate parse(@NotNull final String raw, @NotNull final MiniMessage miniMessage,
                                          @NotNull final TagResolver[] tagResolvers) {

    final Matcher probe = PLACEHOLDER.matcher(raw);
    if(!probe.find()) {
      return BROKEN;
    }
    probe.reset();
    final StringBuilder sentineled = new StringBuilder(raw.length() + 16);
    while(probe.find()) {
      probe.appendReplacement(sentineled, Matcher.quoteReplacement(
              String.valueOf(SENTINEL_START) + probe.group(1) + SENTINEL_END));
    }
    probe.appendTail(sentineled);
    final Component parsed;
    try {
      parsed = miniMessage.deserialize(sentineled.toString(), tagResolvers);
    } catch(final Throwable t) {
      return BROKEN;
    }
    return new PreParsedTemplate(parsed);
  }

  static boolean isBroken(@NotNull final PreParsedTemplate template) {

    return template.root == null;
  }

  /**
   * Renders the template with arguments.
   *
   * @return the rendered component, or null when this template must take the legacy path
   *         (broken template, missing argument, or a sentinel that would remain
   *         unconsumed — e.g. a placeholder inside a MiniMessage tag argument)
   */
  @Nullable Component render(@NotNull final Component... args) {

    if(root == null) {
      return null;
    }
    // legacy serializes each argument to a MiniMessage string and reparses the whole
    // template; argument styles leak onto the following template text (string round
    // trip without closing color tags). Direct insertion would NOT leak — a visible
    // difference — so any styled argument subtree must take the legacy path.
    for(final Component arg : args) {
      if(hasAnyStyle(arg)) {
        return null;
      }
    }
    try {
      final Component rendered = walk(root, args);
      if(rendered == null || containsSentinel(rendered)) {
        return null;
      }
      return rendered;
    } catch(final Throwable t) {
      return null;
    }
  }

  /** Returns the rebuilt subtree, the original reference when untouched, or null on abort. */
  @Nullable private static Component walk(@NotNull final Component node, @NotNull final Component... args) {

    if(node instanceof final TextComponent text && text.content().indexOf(SENTINEL_START) >= 0) {
      return splitTextNode(text, args);
    }
    final List<Component> children = node.children();
    if(children.isEmpty()) {
      return node;
    }
    boolean changed = false;
    final List<Component> newChildren = new ArrayList<>(children.size());
    for(final Component child : children) {
      final Component processed = walk(child, args);
      if(processed == null) {
        return null;
      }
      changed |= processed != child;
      newChildren.add(processed);
    }
    if(!changed) {
      return node;
    }
    return node.children(newChildren);
  }

  /**
   * Expands a text node containing sentinels into text/argument pieces under a container
   * that carries the node's style: pieces stay style-less (they inherit the container's
   * scope) and unstyled argument components render inside the surrounding style, exactly
   * like the legacy inline string splice. Without the container an argument piece placed
   * as a sibling would escape the node's style scope.
   */
  @Nullable private static Component splitTextNode(@NotNull final TextComponent node, @NotNull final Component... args) {

    final String content = node.content();
    final List<Component> pieces = new ArrayList<>(4);
    int cursor = 0;
    int scan = 0;
    while(scan < content.length()) {
      if(content.charAt(scan) == SENTINEL_START) {
        final int end = content.indexOf(SENTINEL_END, scan + 1);
        if(end < 0) {
          return null; // malformed sentinel: legacy path
        }
        final int index = Integer.parseInt(content, scan + 1, end, 10);
        if(index < 0 || index >= args.length || args[index] == null) {
          return null; // missing argument: legacy path decides the rendering
        }
        if(scan > cursor) {
          pieces.add(Component.text(content.substring(cursor, scan)));
        }
        pieces.add(args[index]);
        cursor = end + 1;
        scan = end + 1;
      } else {
        scan++;
      }
    }
    if(pieces.isEmpty()) {
      return null;
    }
    // original children follow the whole content: they ride on an explicit tail piece
    // (empty text when the content ends with a placeholder) so they never become
    // children of an argument component
    final String tail = cursor < content.length()? content.substring(cursor) : "";
    if(!tail.isEmpty() || !node.children().isEmpty()) {
      final Component tailPiece = Component.text(tail);
      pieces.add(node.children().isEmpty()? tailPiece : tailPiece.children(node.children()));
    }
    return Component.text("", node.style()).children(pieces);
  }

  private static boolean hasAnyStyle(@Nullable final Component node) {

    if(node == null) {
      return false;
    }
    if(!node.style().equals(net.kyori.adventure.text.format.Style.empty())) {
      return true;
    }
    for(final Component child : node.children()) {
      if(hasAnyStyle(child)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsSentinel(@NotNull final Component node) {

    if(node instanceof final TextComponent text && text.content().indexOf(SENTINEL_START) >= 0) {
      return true;
    }
    // a placeholder inside a MiniMessage tag argument (e.g. hover text) lands its
    // sentinel in the event value, outside children()
    final var hover = node.style().hoverEvent();
    if(hover != null) {
      final var value = hover.value();
      if(value instanceof final Component component && containsSentinel(component)) {
        return true;
      }
    }
    for(final Component child : node.children()) {
      if(containsSentinel(child)) {
        return true;
      }
    }
    return false;
  }
}
