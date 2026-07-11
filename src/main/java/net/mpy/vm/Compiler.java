package net.mpy.vm;

/**
 * Host-supplied bridge from Python source to .mpy bytecode, enabling compile()
 * and exec()/eval(). The VM cannot compile Python itself (that needs the full
 * mpy-cross toolchain), so the embedding application provides this — typically a
 * wrapper around mpy-cross. When no compiler is set, compile()/exec()/eval()
 * raise NotImplementedError.
 */
@FunctionalInterface
public interface Compiler {
    /**
     * Compile Python source to .mpy bytecode.
     * @param source   the Python source text
     * @param filename a display name for tracebacks (e.g. "&lt;string&gt;")
     * @param mode     "exec" (statements) or "eval" (a single expression)
     * @return the .mpy bytecode bytes
     * @throws Exception if compilation fails (surfaced as a Python SyntaxError)
     */
    byte[] compile(String source, String filename, String mode) throws Exception;
}
