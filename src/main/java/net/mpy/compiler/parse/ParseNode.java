package net.mpy.compiler.parse;

import net.mpy.compiler.lex.Tok;
import net.mpy.compiler.model.MpConst;

/**
 * Parse-tree node — the Java analogue of mp_parse_node_t.
 *
 * The C encoding packs everything into a tagged uintptr_t; here we use a small
 * class hierarchy. The dump() format matches oracle/parsedump exactly so trees
 * can be diffed:
 *   Null        -> "NULL"
 *   SmallInt(v) -> "int(v)"
 *   Id(name)    -> "id(name)"
 *   Str(name)   -> "str(name)"
 *   Token(k)    -> "tok(ordinal)"
 *   Const(c)    -> printed as a struct rule(RULE_CONST_OBJECT) by the oracle;
 *                  represented here so folding (Step 10) can consume it.
 *   Struct(...) -> "rule(kind) n=count" then children indented +2
 */
public abstract class ParseNode {

    public abstract void dump(StringBuilder sb, int indent);

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append(' ');
    }

    public final String dump() {
        StringBuilder sb = new StringBuilder();
        dump(sb, 0);
        return sb.toString();
    }

    /** MP_PARSE_NODE_NULL. */
    public static final class Null extends ParseNode {
        public static final Null INSTANCE = new Null();
        @Override public void dump(StringBuilder sb, int indent) { pad(sb, indent); sb.append("NULL\n"); }
    }

    /** Small-int leaf (value fits target 31-bit small int). */
    public static final class SmallInt extends ParseNode {
        public final long value;
        public SmallInt(long value) { this.value = value; }
        @Override public void dump(StringBuilder sb, int indent) { pad(sb, indent); sb.append("int(").append(value).append(")\n"); }
    }

    /** Identifier leaf (MP_PARSE_NODE_ID); arg is the qstr name. */
    public static final class Id extends ParseNode {
        public final String name;
        public Id(String name) { this.name = name; }
        @Override public void dump(StringBuilder sb, int indent) { pad(sb, indent); sb.append("id(").append(name).append(")\n"); }
    }

    /** Interned-string leaf (MP_PARSE_NODE_STRING); arg is the qstr name. */
    public static final class Str extends ParseNode {
        public final String name;
        public Str(String name) { this.name = name; }
        @Override public void dump(StringBuilder sb, int indent) { pad(sb, indent); sb.append("str(").append(name).append(")\n"); }
    }

    /** Bare-token leaf (MP_PARSE_NODE_TOKEN); arg is the token kind. */
    public static final class Token extends ParseNode {
        public final Tok tok;
        public Token(Tok tok) { this.tok = tok; }
        @Override public void dump(StringBuilder sb, int indent) { pad(sb, indent); sb.append("tok(").append(tok.ordinal()).append(")\n"); }
    }

    /**
     * Constant object (float/bytes/large-int/long-string) — RULE_const_object.
     * Deferred cases; carried so Step 10 folding/const-table can consume it.
     */
    public static final class Const extends ParseNode {
        public final MpConst value;
        public final int srcLine;
        public Const(int srcLine, MpConst value) { this.srcLine = srcLine; this.value = value; }
        @Override public void dump(StringBuilder sb, int indent) {
            // The oracle prints this as a struct rule(RULE_CONST_OBJECT); mirror that
            // shape so structural diffs line up (payload compared separately in Step 10).
            pad(sb, indent); sb.append("rule(").append(Grammar.RULE_CONST_OBJECT).append(") n=2\n");
        }
    }

    /** Rule struct node (mp_parse_node_struct_t): a rule kind + child nodes. */
    public static final class Struct extends ParseNode {
        public int ruleId; // mutable: parse.c mutates kind in place for testlist_comp_3c conversion
        public final int srcLine;
        public final ParseNode[] nodes;
        public Struct(int ruleId, int srcLine, ParseNode[] nodes) {
            this.ruleId = ruleId; this.srcLine = srcLine; this.nodes = nodes;
        }
        @Override public void dump(StringBuilder sb, int indent) {
            pad(sb, indent);
            sb.append("rule(").append(ruleId).append(") n=").append(nodes.length).append('\n');
            for (ParseNode n : nodes) n.dump(sb, indent + 2);
        }
    }
}
