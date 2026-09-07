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
 * A template pre-parsed once into a compiled render skeleton: a component tree with
 * private-use sentinel markers in place of the <code>{0}</code>-style placeholders is
 * analyzed at parse time — every split position, literal piece and argument slot is
 * recorded — so a render only validates the arguments and assembles the output tree.
 * The per-render walk, content re-scan and split re-derivation of the original
 * implementation are gone; immutable literal pieces and untouched subtrees are shared
 * between renders (components are immutable, so shared references cannot drift).
 *
 * <p>Safety model: if a template cannot be represented this way (a placeholder inside a
 * MiniMessage tag argument leaves an unconsumed sentinel, a broken sentinel, or an
 * unexpected structure), {@link #parse} yields the broken marker and {@link #render}
 * returns null so the caller falls back to the legacy string path — rendering differs
 * from legacy only never: it either produces the expansion or hands the exact original
 * inputs to the legacy code. Parse additionally validates the skeleton once with dummy
 * arguments, freezing every argument-independent abort decision.</p>
 */
final class PreParsedTemplate {

  /** Marks a placeholder hole; private-use characters survive MiniMessage parsing as text. */
  static final char SENTINEL_START = '\uE400';
  static final char SENTINEL_END = '\uE401';
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

  private static final PreParsedTemplate BROKEN = new PreParsedTemplate(null, -1);

  private final @Nullable PermNode root;
  /** Highest argument index any slot references; renders need strictly more args. */
  private final int maxIndex;

  private PreParsedTemplate(@Nullable final PermNode root, final int maxIndex) {

    this.root = root;
    this.maxIndex = maxIndex;
  }

  /**
   * Parses and compiles a raw template: placeholders become sentinels, one MiniMessage
   * parse, then the skeleton derivation plus a full dummy-argument validation render.
   *
   * @return a template, or the broken marker (render always falls back) when the raw
   *         template has no placeholders, the parse fails, or validation declines
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
    final CompileResult compiled;
    try {
      compiled = compile(parsed);
    } catch(final Throwable t) {
      return BROKEN;
    }
    if(compiled == null) {
      return BROKEN;
    }
    // freeze every argument-independent abort: run the exact render path once with
    // plain dummies and require the same sentinel-free result the per-render check
    // used to demand — a sentinel the split cannot consume (e.g. a hole nested under
    // another split node's original children, or inside a tag argument) made every
    // render fall back, which the broken marker now records once
    final Component[] dummies = new Component[compiled.maxIndex + 1];
    for(int i = 0; i < dummies.length; i++) {
      dummies[i] = Component.text("");
    }
    try {
      final Component validated = new PreParsedTemplate(compiled.root, compiled.maxIndex).render(dummies);
      if(validated == null || containsSentinel(validated)) {
        return BROKEN;
      }
    } catch(final Throwable t) {
      return BROKEN;
    }
    return new PreParsedTemplate(compiled.root, compiled.maxIndex);
  }

  static boolean isBroken(@NotNull final PreParsedTemplate template) {

    return template.root == null;
  }

  /**
   * Renders the template with arguments.
   *
   * @return the rendered component, or null when this template must take the legacy path
   *         (broken template, styled argument, missing argument, an argument carrying
   *         sentinel characters, or an unexpected structure)
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
    if(args.length <= maxIndex) {
      return null; // missing argument: legacy path decides the rendering
    }
    final Component rendered;
    try {
      rendered = assemble(root, args);
    } catch(final Throwable t) {
      return null;
    }
    if(rendered == null) {
      return null;
    }
    // the skeleton was proven sentinel-free at parse time; only the argument subtrees
    // (a placeholder inside a MiniMessage tag argument lands its sentinel in the event
    // value, outside children()) can still introduce one
    for(final Component arg : args) {
      if(containsSentinel(arg)) {
        return null;
      }
    }
    return rendered;
  }

  // ---- skeleton ----

  /** A compiled template subtree; the three shapes mirror the original walk's decisions. */
  private sealed interface PermNode permits Leaf, Split, Branch {

  }

  /** A subtree without sentinels below it: shared by reference across renders. */
  private record Leaf(Component original) implements PermNode {

  }

  /**
   * A text node whose content carries sentinel holes. {@code plan} entries are shared
   * literal pieces (Component) or argument slots (Integer index); a render copies the
   * list substituting live arguments under a fresh container that carries the node's
   * style — pieces stay style-less (they inherit the container's scope) and unstyled
   * argument components render inside the surrounding style, exactly like the legacy
   * inline string splice.
   */
  private record Split(TextComponent original, List<Object> plan) implements PermNode {

  }

  /** An interior node on the path to at least one split; rebuilt per render. */
  private record Branch(Component original, List<PermNode> children) implements PermNode {

  }

  private record CompileResult(PermNode root, int maxIndex) {

  }

  /**
   * Derives the render skeleton, or null when the tree cannot be represented (the same
   * structural aborts the per-render walk used to take: malformed sentinel, an
   * unparsable hole index, or a hole that produced no pieces).
   */
  private static @Nullable CompileResult compile(@NotNull final Component node) {

    final int[] maxIndex = {-1};
    final PermNode root = compileNode(node, maxIndex);
    if(root == null) {
      return null;
    }
    return new CompileResult(root, maxIndex[0]);
  }

  /** Returns the compiled subtree, or null on abort. */
  @Nullable private static PermNode compileNode(@NotNull final Component node, final int[] maxIndex) {

    if(node instanceof final TextComponent text && text.content().indexOf(SENTINEL_START) >= 0) {
      return compileSplit(text, maxIndex);
    }
    final List<Component> children = node.children();
    if(children.isEmpty()) {
      return new Leaf(node);
    }
    final List<PermNode> compiledChildren = new ArrayList<>(children.size());
    for(final Component child : children) {
      final PermNode compiled = compileNode(child, maxIndex);
      if(compiled == null) {
        return null;
      }
      compiledChildren.add(compiled);
    }
    return new Branch(node, List.copyOf(compiledChildren));
  }

  /**
   * Expands a sentinel-bearing text node into its piece plan: literal pieces (including
   * the explicit tail that carries the node's original children) become shared immutable
   * components, holes become argument slots.
   */
  @Nullable private static Split compileSplit(@NotNull final TextComponent node, final int[] maxIndex) {

    final String content = node.content();
    final List<Object> plan = new ArrayList<>(4);
    final StringBuilder literal = new StringBuilder();
    int scan = 0;
    while(scan < content.length()) {
      if(content.charAt(scan) == SENTINEL_START) {
        final int end = content.indexOf(SENTINEL_END, scan + 1);
        if(end < 0) {
          return null; // malformed sentinel: legacy path
        }
        final int index;
        try {
          index = Integer.parseInt(content, scan + 1, end, 10);
        } catch(final NumberFormatException e) {
          return null; // malformed sentinel: legacy path
        }
        if(index < 0) {
          return null; // malformed sentinel: legacy path
        }
        if(literal.length() > 0) {
          plan.add(Component.text(literal.toString()));
          literal.setLength(0);
        }
        plan.add(index);
        if(index > maxIndex[0]) {
          maxIndex[0] = index;
        }
        scan = end + 1;
      } else {
        literal.append(content.charAt(scan));
        scan++;
      }
    }
    if(plan.isEmpty()) {
      return null;
    }
    // original children follow the whole content: they ride on an explicit tail piece
    // (empty text when the content ends with a placeholder) so they never become
    // children of an argument component; the tail is argument-independent and shared
    if(literal.length() > 0 || !node.children().isEmpty()) {
      final Component tailPiece = Component.text(literal.toString());
      plan.add(node.children().isEmpty()? tailPiece : tailPiece.children(node.children()));
    }
    return new Split(node, List.copyOf(plan));
  }

  // ---- assembly ----

  /** Returns the rendered subtree (fresh container nodes, shared leaves), or null when an argument slot holds null. */
  @Nullable private static Component assemble(@NotNull final PermNode node, @NotNull final Component... args) {

    if(node instanceof final Leaf leaf) {
      return leaf.original();
    }
    if(node instanceof final Split split) {
      final List<Object> plan = split.plan();
      final List<Component> pieces = new ArrayList<>(plan.size());
      for(final Object piece : plan) {
        if(piece instanceof final Component literalPiece) {
          pieces.add(literalPiece);
        } else {
          final Component arg = args[(Integer)piece];
          if(arg == null) {
            return null; // missing argument: legacy path decides the rendering
          }
          pieces.add(arg);
        }
      }
      return Component.text("", split.original().style()).children(pieces);
    }
    final Branch branch = (Branch)node;
    final List<PermNode> children = branch.children();
    final List<Component> newChildren = new ArrayList<>(children.size());
    boolean changed = false;
    for(int i = 0; i < children.size(); i++) {
      final PermNode child = children.get(i);
      final Component original = childOriginal(child);
      final Component assembled = assemble(child, args);
      if(assembled == null) {
        return null;
      }
      changed |= assembled != original;
      newChildren.add(assembled);
    }
    if(!changed) {
      return branch.original();
    }
    return branch.original().children(newChildren);
  }

  private static @Nullable Component childOriginal(@NotNull final PermNode node) {

    if(node instanceof final Leaf leaf) {
      return leaf.original();
    }
    if(node instanceof final Split split) {
      return split.original();
    }
    return ((Branch)node).original();
  }

  // ---- argument validation ----

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
