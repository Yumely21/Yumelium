package com.yumelium.yumelium.shaders.pack;

import me.jellysquid.mods.sodium.client.SodiumClientMod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris/Oculus port (M8 v2) — a shader pack's configurable options, in the OptiFine/Iris format that real packs
 * (BSL, Complementary, …) use. Discovered from {@code shaders.properties}:
 * <ul>
 *   <li>{@code screen}/{@code screen.NAME} directives build a tree of setting pages; tokens are option names,
 *       {@code [SUBSCREEN]} links, {@code <empty>} spacers, and {@code <profile>} (rendered as a spacer for now).</li>
 *   <li>Each option name is matched to a {@code #define NAME [value]} (or {@code const type NAME = value;}) in the shader
 *       sources: no value = a BOOLEAN toggle (on = uncommented); a value with an allowed-value list in its trailing
 *       {@code // [a b c]} comment = a VALUE option that cycles through those values.</li>
 *   <li>{@code lang/en_us.lang} supplies display labels ({@code option.NAME}, {@code value.NAME.VAL}, {@code screen.NAME})
 *       and tooltips ({@code option.NAME.comment}).</li>
 * </ul>
 * {@link #apply} rewrites the matching {@code #define}/{@code const} lines to the current values before compilation.
 */
public final class ShaderOptionSet {
    /** A single option: a boolean toggle, or a value that cycles through {@link #values}. */
    public static final class Option {
        public final String name;
        public final boolean bool;        // true = boolean toggle; false = value option
        public final boolean constStyle;  // const type NAME = v;  vs  #define NAME v
        public final String defaultValue; // "true"/"false" for boolean, else the value string
        public final List<String> values; // allowed values for a value option (may be empty = not user-editable)
        public String value;
        public String label;
        public String comment = "";
        final Map<String, String> valueLabels = new HashMap<>();

        Option(String name, boolean bool, boolean constStyle, String defaultValue, List<String> values) {
            this.name = name;
            this.bool = bool;
            this.constStyle = constStyle;
            this.defaultValue = defaultValue;
            this.values = values;
            this.value = defaultValue;
            this.label = prettify(name);
        }

        public boolean isOn() {
            return "true".equals(this.value);
        }

        public boolean editable() {
            return this.bool || this.values.size() > 1;
        }

        /** Toggles a boolean, or advances a value option to the next allowed value. */
        public void cycle() {
            if (this.bool) {
                this.value = isOn() ? "false" : "true";
            } else if (this.values.size() > 1) {
                int i = this.values.indexOf(this.value);
                this.value = this.values.get((i + 1 + this.values.size()) % this.values.size());
            }
        }

        /** The label to show for the current value (a boolean shows ON/OFF; a value uses its lang label if any). */
        public String displayValue() {
            if (this.bool) {
                return isOn() ? "ON" : "OFF";
            }
            return this.valueLabels.getOrDefault(this.value, this.value);
        }
    }

    public enum Kind {OPTION, LINK, EMPTY}

    /** One row of a settings page: an option, a link into a sub-page, or a spacer. */
    public static final class Entry {
        public final Kind kind;
        public final Option option; // OPTION
        public final Screen target; // LINK

        Entry(Kind kind, Option option, Screen target) {
            this.kind = kind;
            this.option = option;
            this.target = target;
        }
    }

    /** A settings page (the root screen, or a named sub-screen). */
    public static final class Screen {
        public final String id;
        public String title;
        public final List<Entry> entries = new ArrayList<>();

        Screen(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private final Map<String, Option> byName = new LinkedHashMap<>();
    private final List<Option> all = new ArrayList<>();
    private final Map<String, Screen> screens = new LinkedHashMap<>();
    private Screen root = new Screen("", "Options");

    public Screen rootScreen() {
        return this.root;
    }

    /** @return the current value string of the named option (as it would be written to the {@code #define}/{@code const}),
     * or {@code null} if the pack declares no such option. Lets the pipeline read a pack setting (e.g. SUN_ANGLE) it needs
     * to reproduce host-side (e.g. the celestial sun-path tilt). */
    public String optionValue(String name) {
        Option o = this.byName.get(name);
        return o == null ? null : o.value;
    }

    public boolean isEmpty() {
        return this.all.isEmpty();
    }

    public List<Option> allOptions() {
        return this.all;
    }

    // ---- discovery ------------------------------------------------------------------------------------------------

    public static ShaderOptionSet discover(ShaderPack pack, String language) {
        ShaderOptionSet set = new ShaderOptionSet();
        if (pack == null) {
            return set;
        }
        try {
            Map<String, RawDef> defs = scanDefines(readAllShaderText(pack));
            set.buildScreens(pack.properties(), defs);
            set.applyLang(readLang(pack, language));
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris] shader option discovery failed", t);
        }
        return set;
    }

    /**
     * Reads + concatenates every shader-source file under the pack's {@code shaders/} directory (programs in
     * {@code program/}/{@code world*}/ subfolders AND the {@code lib/} includes where real packs declare their option
     * {@code #define}s). Falls back to the discovered top-level programs for a classpath pack that can't list files.
     */
    private static String readAllShaderText(ShaderPack pack) {
        StringBuilder sb = new StringBuilder();
        List<String> files = pack.source().listFiles(pack.shadersRoot());
        if (!files.isEmpty()) {
            for (String f : files) {
                if (isShaderSource(f)) {
                    String txt = pack.source().readText(f);
                    if (txt != null) {
                        sb.append(txt).append('\n');
                    }
                }
            }
        } else {
            for (ProgramSource ps : pack.programSet().programs().values()) {
                if (ps.vertex() != null) sb.append(ps.vertex()).append('\n');
                if (ps.fragment() != null) sb.append(ps.fragment()).append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean isShaderSource(String path) {
        String p = path.toLowerCase();
        return p.endsWith(".glsl") || p.endsWith(".vsh") || p.endsWith(".fsh")
                || p.endsWith(".gsh") || p.endsWith(".csh") || p.endsWith(".inc");
    }

    /** A parsed {@code #define}/{@code const} declaration found in the shader sources. */
    private static final class RawDef {
        boolean bool;
        boolean constStyle;
        boolean commented;
        String value;          // for value defines
        List<String> values;   // allowed values from // [..]
    }

    private static final Pattern DEFINE = Pattern.compile("(?m)^[ \\t]*(//[ \\t]*)?#define[ \\t]+(\\w+)([^\\n]*)$");
    private static final Pattern CONST = Pattern.compile(
            "(?m)^[ \\t]*const[ \\t]+(?:int|float|bool)[ \\t]+(\\w+)[ \\t]*=[ \\t]*([^;]+);([^\\n]*)$");
    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]*)\\]");

    private static Map<String, RawDef> scanDefines(String src) {
        Map<String, RawDef> defs = new HashMap<>();
        Matcher m = DEFINE.matcher(src);
        while (m.find()) {
            String name = m.group(2);
            if (defs.containsKey(name)) {
                continue;
            }
            String rest = m.group(3);
            String beforeComment = stripComment(rest).trim();
            RawDef d = new RawDef();
            d.commented = m.group(1) != null;
            d.bool = beforeComment.isEmpty();
            d.value = d.bool ? null : beforeComment;
            d.values = parseValues(rest);
            defs.put(name, d);
        }
        Matcher c = CONST.matcher(src);
        while (c.find()) {
            String name = c.group(1);
            if (defs.containsKey(name)) {
                continue;
            }
            RawDef d = new RawDef();
            d.constStyle = true;
            d.bool = false;
            d.value = c.group(2).trim();
            d.values = parseValues(c.group(3));
            defs.put(name, d);
        }
        return defs;
    }

    private static String stripComment(String s) {
        int i = s.indexOf("//");
        return i < 0 ? s : s.substring(0, i);
    }

    private static List<String> parseValues(String commentPart) {
        List<String> out = new ArrayList<>();
        Matcher b = BRACKET.matcher(commentPart);
        if (b.find()) {
            for (String v : b.group(1).trim().split("\\s+")) {
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private void buildScreens(ShaderProperties props, Map<String, RawDef> defs) {
        // Pass 1: create a Screen shell for the root and every screen.NAME so links resolve.
        this.screens.put("", this.root);
        for (String key : props.asMap().keySet()) {
            if (key.startsWith("screen.") && !key.contains(".columns")) {
                String id = key.substring("screen.".length());
                this.screens.put(id, new Screen(id, prettify(id)));
            }
        }
        // Pass 2: fill each screen's entries from its token list.
        for (Map.Entry<String, String> e : props.asMap().entrySet()) {
            String key = e.getKey();
            Screen screen;
            if (key.equals("screen")) {
                screen = this.root;
            } else if (key.startsWith("screen.") && !key.contains(".columns")) {
                screen = this.screens.get(key.substring("screen.".length()));
            } else {
                continue;
            }
            if (screen == null) {
                continue;
            }
            for (String token : e.getValue().trim().split("\\s+")) {
                addEntry(screen, token, defs);
            }
        }
    }

    private void addEntry(Screen screen, String token, Map<String, RawDef> defs) {
        if (token.isEmpty() || token.equals("<profile>")) {
            return;
        }
        if (token.equals("<empty>")) {
            screen.entries.add(new Entry(Kind.EMPTY, null, null));
            return;
        }
        if (token.startsWith("[") && token.endsWith("]")) {
            Screen target = this.screens.get(token.substring(1, token.length() - 1));
            if (target != null) {
                screen.entries.add(new Entry(Kind.LINK, null, target));
            }
            return;
        }
        Option opt = this.byName.get(token);
        if (opt == null) {
            RawDef d = defs.get(token);
            if (d == null) {
                return; // referenced name isn't a define/const → not a real option, skip
            }
            String def = d.bool ? (d.commented ? "false" : "true") : d.value;
            // The source default must be selectable. Some options define a default OUTSIDE their listed cycle values —
            // e.g. `#define COLORED_LIGHTING 0 //[128 192 256 ...]` where 0 = off isn't in the list — so a user could
            // never cycle back to off. Prepend the default so it's reachable (OptiFine/Iris do the same).
            if (!d.bool && def != null && !d.values.isEmpty() && !d.values.contains(def)) {
                d.values.add(0, def);
            }
            opt = new Option(token, d.bool, d.constStyle, def, d.values);
            this.byName.put(token, opt);
            this.all.add(opt);
        }
        screen.entries.add(new Entry(Kind.OPTION, opt, null));
    }

    // ---- lang -----------------------------------------------------------------------------------------------------

    private static Map<String, String> readLang(ShaderPack pack, String language) {
        Map<String, String> map = new HashMap<>();
        // English base first, then overlay the user's language so untranslated keys fall back to English. Packs name the
        // files with an upper-case region (en_US.lang, ja_JP.lang) while MC's setting is lower-case (ja_jp) — try both.
        mergeLang(map, pack, "en_US");
        mergeLang(map, pack, "en_us");
        if (language != null && !language.isEmpty() && !language.toLowerCase().startsWith("en_")) {
            mergeLang(map, pack, capsRegion(language));
            mergeLang(map, pack, language.toLowerCase());
        }
        return map;
    }

    private static void mergeLang(Map<String, String> map, ShaderPack pack, String code) {
        String text = pack.source().readText(pack.shadersRoot() + "lang/" + code + ".lang");
        if (text == null) {
            return;
        }
        for (String line : text.split("\n")) {
            String t = line.replace("\r", "").trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq > 0) {
                map.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
        }
    }

    /** "ja_jp" → "ja_JP" (the upper-case-region file naming most shader packs use). */
    private static String capsRegion(String code) {
        int u = code.indexOf('_');
        if (u < 0) {
            return code;
        }
        return code.substring(0, u).toLowerCase() + "_" + code.substring(u + 1).toUpperCase();
    }

    private void applyLang(Map<String, String> lang) {
        if (lang.isEmpty()) {
            return;
        }
        for (Screen s : this.screens.values()) {
            String title = lang.get("screen." + s.id);
            if (title != null) {
                s.title = title;
            }
        }
        for (Option opt : this.all) {
            String label = lang.get("option." + opt.name);
            if (label == null) {
                label = lang.get(opt.name); // some packs omit the "option." prefix
            }
            if (label != null) {
                opt.label = label;
            }
            String comment = lang.get("option." + opt.name + ".comment");
            if (comment != null) {
                opt.comment = comment;
            }
            for (String v : opt.values) {
                String vl = lang.get("value." + opt.name + "." + v);
                if (vl != null) {
                    opt.valueLabels.put(v, vl);
                }
            }
        }
    }

    // ---- apply to shader source ----------------------------------------------------------------------------------

    public String apply(String source) {
        String out = source;
        for (Option opt : this.all) {
            // The source already holds each option's default; only rewrite the ones the user actually changed. This keeps
            // apply() nearly free for big packs (hundreds of options × many programs) when only a few options differ.
            if (opt.value.equals(opt.defaultValue)) {
                continue;
            }
            if (opt.bool) {
                Pattern p = Pattern.compile("(?m)^([ \\t]*)(?://[ \\t]*)?#define[ \\t]+" + Pattern.quote(opt.name) + "\\b([^\\n]*)$");
                String define = (opt.isOn() ? "#define " : "//#define ") + opt.name;
                out = p.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(define) + "$2");
            } else if (opt.constStyle) {
                Pattern p = Pattern.compile("(?m)^([ \\t]*const[ \\t]+(?:int|float|bool)[ \\t]+" + Pattern.quote(opt.name)
                        + "[ \\t]*=[ \\t]*)([^;\\n]+)(;[^\\n]*)$");
                out = p.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(opt.value) + "$3");
            } else {
                Pattern p = Pattern.compile("(?m)^([ \\t]*#define[ \\t]+" + Pattern.quote(opt.name) + "[ \\t]+)(\\S+)([^\\n]*)$");
                out = p.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(opt.value) + "$3");
            }
        }
        return out;
    }

    // ---- persistence: shaderpacks/<pack>.txt, "NAME=value" lines --------------------------------------------------

    public void load(Path file) {
        try {
            if (!Files.exists(file)) {
                return;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                Option opt = this.byName.get(line.substring(0, eq).trim());
                String v = line.substring(eq + 1).trim();
                if (opt == null) {
                    continue;
                }
                if (opt.bool) {
                    if (v.equals("true") || v.equals("false")) {
                        opt.value = v;
                    }
                } else {
                    // Accept the value from the pack's own settings file even if its formatting differs from our parsed
                    // allowed-value list (cycling from an off-list value just jumps to the first allowed value).
                    opt.value = v;
                }
            }
        } catch (Exception e) {
            SodiumClientMod.logger().warn("[Iris] could not read shader options " + file, e);
        }
    }

    public void save(Path file) {
        try {
            // Preserve any keys already in the file (OptiFine/Euphoria markers + options we don't model), so we stay
            // interoperable with the pack's existing settings file instead of overwriting it with only our subset.
            Map<String, String> merged = new LinkedHashMap<>();
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) {
                        continue;
                    }
                    int eq = t.indexOf('=');
                    if (eq > 0) {
                        merged.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
                    }
                }
            }
            for (Option opt : this.all) {
                merged.put(opt.name, opt.value);
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : merged.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            SodiumClientMod.logger().warn("[Iris] could not save shader options " + file, e);
        }
    }

    static String prettify(String name) {
        String s = name.replace('_', ' ').toLowerCase();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
