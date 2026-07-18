package net.mpy.compiler.qstr;

import java.util.HashMap;
import java.util.Map;

/**
 * The frozen static qstr table (GENERATED from py/makeqstrdata.py#static_qstr_list).
 *
 * These qstrs have IDs fixed by the .mpy format contract: the list order MUST NOT
 * change, since .mpy files reference them by ID. IDs run 1..165 in list order.
 * QSTR_LAST_STATIC = MP_QSTR_zip = id 165 (persistentcode.c:48).
 *
 * A qstr with id <= LAST_STATIC is serialized as a static reference (id<<1)|1;
 * all other qstrs are serialized inline as (len<<1) + bytes + NUL (see save_qstr).
 *
 * Do not hand-edit. Regenerate against the pinned upstream commit if it changes.
 */
public final class StaticQstrs {

    private StaticQstrs() {}

    /** id of MP_QSTR_zip; qstrs with id <= this are static references. */
    public static final int LAST_STATIC = 165;

    /** Static qstr names, index i (0-based) => id (i+1). */
    public static final String[] NAMES = {
        "",
        "__dir__",
        "\n",
        " ",
        "*",
        "/",
        "<module>",
        "_",
        "__call__",
        "__class__",
        "__delitem__",
        "__enter__",
        "__exit__",
        "__getattr__",
        "__getitem__",
        "__hash__",
        "__init__",
        "__int__",
        "__iter__",
        "__len__",
        "__main__",
        "__module__",
        "__name__",
        "__new__",
        "__next__",
        "__qualname__",
        "__repr__",
        "__setitem__",
        "__str__",
        "ArithmeticError",
        "AssertionError",
        "AttributeError",
        "BaseException",
        "EOFError",
        "Ellipsis",
        "Exception",
        "GeneratorExit",
        "ImportError",
        "IndentationError",
        "IndexError",
        "KeyError",
        "KeyboardInterrupt",
        "LookupError",
        "MemoryError",
        "NameError",
        "NoneType",
        "NotImplementedError",
        "OSError",
        "OverflowError",
        "RuntimeError",
        "StopIteration",
        "SyntaxError",
        "SystemExit",
        "TypeError",
        "ValueError",
        "ZeroDivisionError",
        "abs",
        "all",
        "any",
        "append",
        "args",
        "bool",
        "builtins",
        "bytearray",
        "bytecode",
        "bytes",
        "callable",
        "chr",
        "classmethod",
        "clear",
        "close",
        "const",
        "copy",
        "count",
        "dict",
        "dir",
        "divmod",
        "end",
        "endswith",
        "eval",
        "exec",
        "extend",
        "find",
        "format",
        "from_bytes",
        "get",
        "getattr",
        "globals",
        "hasattr",
        "hash",
        "id",
        "index",
        "insert",
        "int",
        "isalpha",
        "isdigit",
        "isinstance",
        "islower",
        "isspace",
        "issubclass",
        "isupper",
        "items",
        "iter",
        "join",
        "key",
        "keys",
        "len",
        "list",
        "little",
        "locals",
        "lower",
        "lstrip",
        "main",
        "map",
        "micropython",
        "next",
        "object",
        "open",
        "ord",
        "pop",
        "popitem",
        "pow",
        "print",
        "range",
        "read",
        "readinto",
        "readline",
        "remove",
        "replace",
        "repr",
        "reverse",
        "rfind",
        "rindex",
        "round",
        "rsplit",
        "rstrip",
        "self",
        "send",
        "sep",
        "set",
        "setattr",
        "setdefault",
        "sort",
        "sorted",
        "split",
        "start",
        "startswith",
        "staticmethod",
        "step",
        "stop",
        "str",
        "strip",
        "sum",
        "super",
        "throw",
        "to_bytes",
        "tuple",
        "type",
        "update",
        "upper",
        "utf-8",
        "value",
        "values",
        "write",
        "zip",
    };

    private static final Map<String, Integer> ID = new HashMap<>();
    static {
        for (int i = 0; i < NAMES.length; i++) ID.put(NAMES[i], i + 1);
    }

    /** @return static qstr id (1..LAST_STATIC) for {@code name}, or -1 if not static. */
    public static int idOf(String name) {
        Integer v = ID.get(name);
        return v == null ? -1 : v;
    }

    public static boolean isStatic(String name) { return ID.containsKey(name); }
}
