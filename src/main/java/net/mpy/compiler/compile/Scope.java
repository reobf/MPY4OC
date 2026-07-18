package net.mpy.compiler.compile;

import java.util.ArrayList;
import java.util.List;

/**
 * Scope ("block") and identifier-info structures — Java port of py/scope.h.
 *
 * The SCOPE pass ({@link ScopeAnalyzer}) walks the parse tree, creates one Scope
 * per module/function/class/comprehension, and classifies every identifier into
 * an {@link IdKind}. {@code scopeComputeThings()} then assigns each local its
 * index (local_num). Verified against the SCOPEDUMP oracle.
 */
public final class Scope {

    /** scope_kind_t (values must match scope.h for the oracle). */
    public enum Kind {
        MODULE, CLASS, LAMBDA, LIST_COMP, DICT_COMP, SET_COMP, GEN_EXPR, FUNCTION;
        /** SCOPE_IS_FUNC_LIKE(s) = s >= SCOPE_LAMBDA. */
        public boolean isFuncLike() { return ordinal() >= LAMBDA.ordinal(); }
    }

    /** id_info_kind_t (values must match scope.h). */
    public enum IdKind {
        UNDECIDED, GLOBAL_IMPLICIT, GLOBAL_IMPLICIT_ASSIGNED, GLOBAL_EXPLICIT,
        LOCAL, CELL, FREE;
    }

    // ID_FLAG_* bit flags
    public static final int ID_FLAG_IS_PARAM = 0x01;
    public static final int ID_FLAG_IS_STAR_PARAM = 0x02;
    public static final int ID_FLAG_IS_DBL_STAR_PARAM = 0x04;

    // MP_SCOPE_FLAG_* (low 4 bits form the prelude signature)
    public static final int FLAG_GENERATOR = 0x01;
    public static final int FLAG_VARKEYWORDS = 0x02;
    public static final int FLAG_VARARGS = 0x04;
    public static final int FLAG_DEFKWARGS = 0x08;
    public static final int FLAG_REFGLOBALS = 0x10;
    public static final int FLAG_HASCONSTS = 0x20;

    public static final class IdInfo {
        public IdKind kind;
        public int flags;
        public int localNum;
        public final String qst;
        IdInfo(String qst, IdKind kind) { this.qst = qst; this.kind = kind; this.flags = 0; this.localNum = 0; }
    }

    public final Kind kind;
    public final Scope parent;
    public String simpleName = "";
    public int scopeFlags = 0;
    public int numPosArgs = 0;
    public int numKwonlyArgs = 0;
    public int numDefPosArgs = 0;
    public int numLocals = 0;
    public final List<IdInfo> idInfo = new ArrayList<>();

    public Scope(Kind kind, Scope parent) { this.kind = kind; this.parent = parent; }

    /** scope_find: linear lookup by name. */
    public IdInfo find(String qst) {
        for (IdInfo id : idInfo) if (id.qst.equals(qst)) return id;
        return null;
    }

    /** scope_find_or_add_id: append at end preserving encounter order. */
    public IdInfo findOrAdd(String qst, IdKind kind) {
        IdInfo id = find(qst);
        if (id != null) return id;
        id = new IdInfo(qst, kind);
        idInfo.add(id);
        return id;
    }
}
