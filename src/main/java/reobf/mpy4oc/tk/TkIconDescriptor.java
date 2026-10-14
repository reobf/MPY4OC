package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;

/** Immutable, bounded rendering data. No registry, world or inventory access on the server. */
public final class TkIconDescriptor {
    public static final int MAX_NAME_BYTES = 256, MAX_NBT_BYTES = 4096, MAX_NBT_DEPTH = 16;
    public static final int MAX_TREE_BYTES = 65536;
    public final boolean fluid;
    public final String name, nbt;
    public final int damage;
    private final NBTTagCompound tag;
    private final int bytes;

    public TkIconDescriptor(boolean fluid, String name, int damage, String nbt) {
        this.fluid = fluid;
        this.name = bounded(name, MAX_NAME_BYTES);
        // 1.7 registries include mixed-case mod names; do not lowercase them.
        if (!name.matches("[A-Za-z0-9_.-]+(?::[A-Za-z0-9_./-]+)?"))
            throw new IllegalArgumentException("invalid icon registry name");
        if (damage < 0 || damage > 65535 || fluid && damage != 0) throw new IllegalArgumentException("invalid icon damage");
        this.damage = damage;
        this.nbt = bounded(nbt, MAX_NBT_BYTES);
        if (fluid && !nbt.isEmpty()) throw new IllegalArgumentException("fluid icons take a name only");
        tag = nbt.isEmpty() ? null : parseTag(nbt);
        bytes = name.getBytes(StandardCharsets.UTF_8).length + nbt.getBytes(StandardCharsets.UTF_8).length + 4;
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.length() > maximum || value.getBytes(StandardCharsets.UTF_8).length > maximum)
            throw new IllegalArgumentException("icon descriptor is too long");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IllegalArgumentException("invalid icon Unicode");
            } else if (Character.isLowSurrogate(c) || c == 0) throw new IllegalArgumentException("invalid icon Unicode");
        }
        return value;
    }

    private static NBTTagCompound parseTag(String value) {
        String text = value.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) throw new IllegalArgumentException("icon nbt must be a compound string");
        // Bound recursion before Minecraft's recursive SNBT parser sees the input. Match its
        // 1.7 quotation rule (a quote immediately following a backslash is escaped).
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) quoted = !quoted;
            if (!quoted && (c == '{' || c == '[') && ++depth > MAX_NBT_DEPTH)
                throw new IllegalArgumentException("icon nbt is too deep");
            if (!quoted && (c == '}' || c == ']')) depth--;
        }
        try {
            NBTBase parsed = JsonToNBT.func_150315_a(text);
            if (!(parsed instanceof NBTTagCompound)) throw new IllegalArgumentException("icon nbt must be a compound");
            return (NBTTagCompound) parsed;
        } catch (NBTException | RuntimeException e) {
            throw new IllegalArgumentException("invalid icon nbt compound", e);
        }
    }

    /** OC's extra size/amount/label/id/tag fields are deliberately not retained. */
    static TkIconDescriptor parse(boolean fluid, Object value) {
        if (value == null || "".equals(value) || value instanceof byte[] && ((byte[]) value).length == 0) return null;
        if (!(value instanceof Map)) throw new IllegalArgumentException("icon descriptor must be a map or None");
        // Do not copy or walk the values of ignored OC fields (lore, enchantments, compressed tag...).
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
            Object key = e.getKey();
            if (!(key instanceof String || key instanceof byte[])) continue;
            String s = TkOptions.text(key);
            if (s.equals("name") || !fluid && (s.equals("damage") || s.equals("nbt"))) fields.put(s, e.getValue());
        }
        String name = TkOptions.text(fields.get("name"));
        int damage = fluid ? 0 : TkOptions.number(fields, "damage", 0, 65535);
        String nbt = fields.get("nbt") == null ? "" : TkOptions.text(fields.get("nbt"));
        return new TkIconDescriptor(fluid, name, damage, nbt);
    }

    public int bytes() { return bytes; }
    /** Each temporary client stack gets its own tag; renderers cannot mutate the descriptor. */
    public NBTTagCompound copyTag() { return tag == null ? null : (NBTTagCompound) tag.copy(); }
    public Map<String, Object> options() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        if (!fluid) { result.put("damage", damage); if (!nbt.isEmpty()) result.put("nbt", nbt); }
        return result;
    }

    NBTTagCompound save() {
        NBTTagCompound result = new NBTTagCompound();
        result.setString("name", name); result.setInteger("damage", damage); result.setString("nbt", nbt);
        return result;
    }
    static TkIconDescriptor load(boolean fluid, NBTTagCompound n) {
        if (!n.hasKey("name", 8) || !n.hasKey("damage", 3) || !n.hasKey("nbt", 8)) throw new IllegalArgumentException("invalid saved icon");
        return new TkIconDescriptor(fluid, n.getString("name"), n.getInteger("damage"), n.getString("nbt"));
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof TkIconDescriptor)) return false;
        TkIconDescriptor d = (TkIconDescriptor) other;
        return fluid == d.fluid && damage == d.damage && name.equals(d.name) && nbt.equals(d.nbt);
    }
    @Override public int hashCode() { return Objects.hash(fluid, name, damage, nbt); }
}
