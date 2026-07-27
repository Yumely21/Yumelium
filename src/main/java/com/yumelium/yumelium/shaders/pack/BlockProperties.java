package com.yumelium.yumelium.shaders.pack;

import me.jellysquid.mods.sodium.client.SodiumClientMod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a shader pack's {@code block.properties} — the {@code block.<id> = <block> <block> ...} table that assigns each
 * block the id the pack sees as {@code mc_Entity.x}.
 *
 * <p>This is what lets Complementary identify materials at all: its IPBR keys smoothness/metalness/emission/porosity off
 * that id, its WSR voxelizer keys its per-material fixups and exclusion list off it, and its waving keys off it. Our port
 * previously hardcoded ~15 ids (light sources + foliage categories), so every other block arrived as id 0 → no material →
 * {@code smoothnessD = 0} → no gloss on obsidian/ice/metal and no deferred reflection on solids at all.</p>
 *
 * <p>Complementary ships ~1550 ids over ~1100 distinct vanilla block names. Entries may be bare ({@code obsidian}),
 * namespaced ({@code minecraft:obsidian}, or a modded namespace we ignore), property-qualified
 * ({@code sunflower:half=lower}), or a {@code %tag}/{@code tags_*} macro — tags are a 1.13+ concept with no 1.12.2
 * equivalent, so those are skipped. Lines continue with a trailing {@code \}.</p>
 *
 * <p>Names here are 1.13+ ("flattened"); turning them into 1.12.2 block states is {@link BlockIdMapper}'s job.</p>
 */
public final class BlockProperties {

    /** One parsed entry: the pack id, the 1.13+ block name, and any {@code prop=value} qualifiers it carried. */
    public static final class Entry {
        public final int id;
        public final String name;                 // 1.13+ flattened name, no namespace
        public final Map<String, String> props;   // e.g. {half: lower}; empty when unqualified

        Entry(int id, String name, Map<String, String> props) {
            this.id = id;
            this.name = name;
            this.props = props;
        }

        @Override
        public String toString() {
            return this.name + (this.props.isEmpty() ? "" : this.props.toString()) + "=" + this.id;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public List<Entry> entries() {
        return this.entries;
    }

    /**
     * Parses the raw file text. Tolerant by design: an unparsable entry is skipped rather than failing the load, since a
     * partial mapping still beats the hardcoded handful.
     */
    public static BlockProperties parse(String raw) {
        return parse(raw, "block.");
    }

    /**
     * @param prefix the directive prefix — {@code "block."} for block.properties, {@code "item."} for item.properties.
     * The two files share this exact grammar (id, `\`-continuations, namespaced names, tag refs), so the shader pack's
     * item table is parsed by the same code rather than a near-copy that would drift.
     */
    public static BlockProperties parse(String raw, String prefix) {
        BlockProperties out = new BlockProperties();
        if (raw == null) {
            return out;
        }
        // Join `\` continuations first, so each logical directive is one string.
        String joined = raw.replace("\\\r\n", " ").replace("\\\n", " ").replace("\\\r", " ");
        int skippedTags = 0;
        int skippedModded = 0;
        for (String line : joined.split("\r?\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue; // comment, or a #define tag macro (tags don't exist in 1.12.2 — see the class note)
            }
            if (!s.startsWith(prefix)) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq < 0) {
                continue;
            }
            int id;
            try {
                id = Integer.parseInt(s.substring(prefix.length(), eq).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            for (String tok : s.substring(eq + 1).trim().split("\\s+")) {
                if (tok.isEmpty()) {
                    continue;
                }
                if (tok.startsWith("%") || tok.startsWith("tags_") || tok.startsWith("<")) {
                    skippedTags++;
                    continue;
                }
                // Token grammar: [namespace:]name[:prop=val]... — a ':' part is a NAMESPACE only if it is the first part
                // and the part after it carries no '=' (a property qualifier always does, e.g. `sunflower:half=lower`).
                String[] parts = tok.split(":");
                int nameIdx = 0;
                if (parts.length >= 2 && !parts[1].contains("=")) {
                    if (!"minecraft".equals(parts[0])) {
                        skippedModded++; // a modded namespace can't resolve on this instance
                        continue;
                    }
                    nameIdx = 1;
                }
                String name = parts[nameIdx];
                if (name.isEmpty()) {
                    continue;
                }
                Map<String, String> props = new LinkedHashMap<>();
                for (int i = nameIdx + 1; i < parts.length; i++) {
                    int pe = parts[i].indexOf('=');
                    if (pe > 0) {
                        props.put(parts[i].substring(0, pe), parts[i].substring(pe + 1));
                    }
                }
                out.entries.add(new Entry(id, name, props));
            }
        }
        SodiumClientMod.logger().info("[Iris " + prefix + "properties] parsed " + out.entries.size()
                + " vanilla entries (skipped " + skippedTags + " tag refs, " + skippedModded + " modded)");
        return out;
    }
}
