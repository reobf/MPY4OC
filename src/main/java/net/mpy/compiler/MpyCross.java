package net.mpy.compiler;

import net.mpy.compiler.compile.ModuleCompiler;

/**
 * Pure-Java reimplementation of MicroPython's {@code mpy-cross}: compiles Python
 * source to a {@code .mpy} bytecode module, byte-identical to the C compiler
 * (mpy version 6.3, bytecode emitter; native/viper emitters are out of scope).
 *
 * <pre>{@code
 * byte[] mpy = MpyCross.compile("def add(a, b):\n    return a + b\n");
 * }</pre>
 */
public final class MpyCross {
    private MpyCross() {}

    /** Compile source using a default source name ("&lt;stdin&gt;"). */
    public static byte[] compile(String source) {
        return compile(source, "<stdin>");
    }

    /**
     * Compile source. The {@code sourceName} is embedded in the .mpy (it is the
     * first qstr), so it must match what mpy-cross was given to be byte-identical.
     */
    public static byte[] compile(String source, String sourceName) {
        return ModuleCompiler.compile(source, sourceName);
    }
}
