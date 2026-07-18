package net.mpy.compiler.persist;

import java.util.ArrayList;
import java.util.List;

/**
 * One raw-code element: a single function/scope in the nested code hierarchy.
 *
 * Key boundary decision (Step 11): {@code funData} is treated as an OPAQUE byte
 * blob here. It is the concatenation of the encoded prelude and the bytecode,
 * exactly as the emitter (emitbc.c, Step 9) builds it, and persistentcode.c
 * writes it verbatim via {@code mp_print_bytes(rc->fun_data, rc->fun_data_len)}.
 * The serializer never looks inside it, so the (hairy) prelude bit-packing lives
 * entirely in the emitter, not here.
 *
 * We only ever produce {@code kind == MP_CODE_BYTECODE}, so the kind bits of the
 * raw-code header are always 0 (native/viper are out of scope).
 */
public final class RawCode {

    /** prelude + bytecode, opaque to the serializer. */
    public final byte[] funData;

    /** nested scopes (methods, comprehensions, nested defs, lambdas). */
    public final List<RawCode> children;

    public RawCode(byte[] funData) {
        this(funData, new ArrayList<>());
    }

    public RawCode(byte[] funData, List<RawCode> children) {
        this.funData = funData;
        this.children = children;
    }

    public RawCode addChild(RawCode child) {
        children.add(child);
        return this;
    }
}
