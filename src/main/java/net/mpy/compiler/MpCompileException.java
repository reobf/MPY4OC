package net.mpy.compiler;

/**
 * Compile-time error, thrown where the C compiler would do {@code nlr_raise}
 * (via {@code compile_syntax_error} etc.). This is the structural replacement
 * for MicroPython's NLR (non-local return / setjmp-longjmp) mechanism: every
 * {@code nlr_raise} maps to a {@code throw}, and the top-level {@code nlr_push}/
 * {@code nlr_pop} in mpy-cross main becomes a single try/catch in the public API.
 *
 * The line number mirrors the source line the C reporter would attach; 0 means
 * "unknown / not yet located".
 */
public class MpCompileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 1-based source line, or 0 if unknown. */
    public final int line;

    public MpCompileException(int line, String message) {
        super(message);
        this.line = line;
    }

    public MpCompileException(String message) {
        this(0, message);
    }

    @Override
    public String getMessage() {
        return line > 0 ? "line " + line + ": " + super.getMessage() : super.getMessage();
    }
}
