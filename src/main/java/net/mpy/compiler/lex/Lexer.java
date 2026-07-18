package net.mpy.compiler.lex;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Faithful Java port of MicroPython's py/lexer.c (mp_lexer_to_next).
 *
 * Matches the C tokeniser's behaviour so the token stream is byte-identical to
 * the tokdump oracle: 3-byte lookahead with CR/CRLF -> LF translation and an
 * implicit trailing newline at EOF; TAB_SIZE=8 column arithmetic; INDENT/DEDENT/
 * NEWLINE via an emit_dent counter and indent stack; comments and backslash line
 * continuation; adjacent string literal concatenation; the compact tok_enc
 * operator state machine; the sorted-keyword strcmp early-out.
 *
 * SCOPE (Step 6): f-strings and t-strings are NOT handled yet (their {..}
 * injection machinery is deferred to a later hardening pass, verified with the
 * same oracle). A leading f/t/rf/rt prefix therefore currently tokenises as a
 * plain string prefix would; those inputs are excluded from the diff corpus.
 */
public final class Lexer {

    private static final int TAB_SIZE = 8;
    private static final int EOF = 0;              // MP_LEXER_EOF ('\0')
    private static final int INVALID_BYTE = 0xff;  // MP_LEXER_INVALID_BYTE

    // Keyword table — MUST match lexer.h enum order (KW_FALSE..KW_YIELD) and be
    // sorted by strcmp, matching tok_kw[] in lexer.c.
    private static final String[] TOK_KW = {
        "False", "None", "True", "__debug__", "and", "as", "assert",
        "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "finally", "for", "from", "global",
        "if", "import", "in", "is", "lambda", "nonlocal", "not", "or",
        "pass", "raise", "return", "try", "while", "with", "yield",
    };

    // tok_enc operator state machine (verbatim from lexer.c).
    private static final String TOK_ENC =
        "()[]{},;~" + ":e=" + "<e=c<e=" + ">e=c>e=" + "*e=c*e=" + "+e=" +
        "-e=e>" + "&e=" + "|e=" + "/e=c/e=" + "%e=" + "^e=" + "@e=" + "=e=" + "!.";

    private static final Tok[] TOK_ENC_KIND = {
        Tok.DEL_PAREN_OPEN, Tok.DEL_PAREN_CLOSE,
        Tok.DEL_BRACKET_OPEN, Tok.DEL_BRACKET_CLOSE,
        Tok.DEL_BRACE_OPEN, Tok.DEL_BRACE_CLOSE,
        Tok.DEL_COMMA, Tok.DEL_SEMICOLON, Tok.OP_TILDE,
        Tok.DEL_COLON, Tok.OP_ASSIGN,
        Tok.OP_LESS, Tok.OP_LESS_EQUAL, Tok.OP_DBL_LESS, Tok.DEL_DBL_LESS_EQUAL,
        Tok.OP_MORE, Tok.OP_MORE_EQUAL, Tok.OP_DBL_MORE, Tok.DEL_DBL_MORE_EQUAL,
        Tok.OP_STAR, Tok.DEL_STAR_EQUAL, Tok.OP_DBL_STAR, Tok.DEL_DBL_STAR_EQUAL,
        Tok.OP_PLUS, Tok.DEL_PLUS_EQUAL,
        Tok.OP_MINUS, Tok.DEL_MINUS_EQUAL, Tok.DEL_MINUS_MORE,
        Tok.OP_AMPERSAND, Tok.DEL_AMPERSAND_EQUAL,
        Tok.OP_PIPE, Tok.DEL_PIPE_EQUAL,
        Tok.OP_SLASH, Tok.DEL_SLASH_EQUAL, Tok.OP_DBL_SLASH, Tok.DEL_DBL_SLASH_EQUAL,
        Tok.OP_PERCENT, Tok.DEL_PERCENT_EQUAL,
        Tok.OP_CARET, Tok.DEL_CARET_EQUAL,
        Tok.OP_AT, Tok.DEL_AT_EQUAL,
        Tok.DEL_EQUAL, Tok.OP_DBL_EQUAL,
    };

    // --- input state: 3-char lookahead (chr0 is current) ---
    private final byte[] src;
    private int pos;
    private int chr0, chr1, chr2;

    private int line = 1, column = 1;
    private int emitDent = 0;
    private int nestedBracketLevel = 0;
    private final java.util.ArrayList<Integer> indent = new java.util.ArrayList<>();

    // --- current token ---
    public Tok kind;
    public int tokLine, tokColumn;
    private final ByteArrayOutputStream vstr = new ByteArrayOutputStream();

    public Lexer(String source) {
        this.src = source.getBytes(StandardCharsets.UTF_8);
        this.pos = 0;
        indent.add(0); // indent_level[0] = 0 (matches lexer.c); num_spaces = column-1
        // prime the 3-char queue exactly like mp_lexer_new
        chr0 = readByte();
        chr1 = readByte();
        chr2 = readByte();
        if (chr0 == '\r') { chr0 = '\n'; if (chr1 == '\n') { chr1 = chr2; chr2 = readByte(); } }
        if (chr1 == '\r') { chr1 = '\n'; if (chr2 == '\n') { chr2 = readByte(); } }
        if (chr2 == EOF && chr1 != EOF && chr1 != '\n') { chr2 = '\n'; }
        if (chr1 == EOF && chr0 != EOF && chr0 != '\n') { chr1 = '\n'; }
        toNext();
    }

    // f-string support: characters injected ahead of the real source (the
    // ".format(args)" text synthesised for an f-string literal), mirroring
    // lexer.c's inject_chrs buffer.
    private java.util.ArrayDeque<Integer> injectQueue = new java.util.ArrayDeque<>();

    /**
     * Inject text to be lexed next. The three already-buffered lookahead chars are
     * pushed back behind the injected text and the lookahead is re-primed, exactly
     * like lexer.c does when an f-string's ".format(...)" call is synthesised.
     */
    private void injectText(String text) {
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        byte[] enc = net.mpy.compiler.io.Utf8.encode(text);
        // Injected text is synthetic: it re-renders source already scanned (a t-string
        // carries its whole literal into the __template__ call). Counting its newlines
        // again would advance the line number twice for one physical line -- except
        // for "\"+newline continuations, which mpy-cross DOES count twice (it re-lexes
        // the part and its lexer increments again). Add those back so the line table
        // matches byte for byte.
        injectedPending += enc.length;
        pendingLineBump += tstringContinuations;
        tstringContinuations = 0;
        for (byte b : enc) q.add(b & 0xff);
        q.add(chr0); q.add(chr1); q.add(chr2);
        q.addAll(injectQueue);
        injectQueue = q;
        chr0 = readByte();
        chr1 = readByte();
        chr2 = readByte();
    }

    private int readByte() {
        if (!injectQueue.isEmpty()) return injectQueue.poll();
        if (pos >= src.length) return EOF;
        int b = src[pos++] & 0xff;
        if (b == EOF) b = INVALID_BYTE; // NUL in input is not end-of-stream
        return b;
    }

    /** Token text (for NAME/INTEGER/FLOAT_OR_IMAG/STRING/BYTES). */
    public String text() { return net.mpy.compiler.io.Utf8.decode(vstr.toByteArray()); }
    public byte[] textBytes() { return vstr.toByteArray(); }

    private int cur() { return chr0; }
    private boolean isEnd() { return chr0 == EOF; }
    private boolean isChar(int c) { return chr0 == c; }
    private boolean isCharFollowing(int c) { return chr1 == c; }
    private boolean isCharAnd(int c1, int c2) { return chr0 == c1 && chr1 == c2; }
    private boolean isCharOr(int c1, int c2) { return chr0 == c1 || chr0 == c2; }

    /** Bytes of synthetic (injected) text still to be consumed; see injectText. */
    private int injectedPending = 0;

    private void nextChar() {
        if (chr0 == '\n') {
            // Only real source newlines advance the line counter; injected text is a
            // rewrite of source already scanned (see injectText).
            if (injectedPending == 0) line++;
            column = 1;
        } else if (chr0 == '\t') { column = (((column - 1 + TAB_SIZE) / TAB_SIZE) * TAB_SIZE) + 1; }
        else { column++; }

        if (injectedPending > 0 && --injectedPending == 0 && pendingLineBump > 0) {
            // the replayed part is fully consumed: apply the continuations mpy-cross
            // counted a second time, so what follows the t-string lines up
            line += pendingLineBump;
            pendingLineBump = 0;
        }
        chr0 = chr1;
        chr1 = chr2;
        int c2 = readByte();
        if (chr1 == '\r') {
            chr1 = '\n';
            if (c2 == '\n') { c2 = readByte(); }
        }
        if (c2 == EOF && chr1 != EOF && chr1 != '\n') { c2 = '\n'; }
        chr2 = c2;
    }

    private boolean isPhysicalNewline() { return chr0 == '\n'; }
    private boolean isWhitespace(int c) { return c == ' ' || c == '\t' || c == '\r' || c == '\f' || c == 0x0b; }
    private boolean isDigit(int c) { return c >= '0' && c <= '9'; }
    private boolean isLetter(int c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
    private boolean isHeadOfIdentifier(int c) { return isLetter(c) || c == '_' || c >= 0x80; }
    private boolean isTailOfIdentifier(int c) { return isHeadOfIdentifier(c) || isDigit(c); }

    private int indentTop() { return indent.get(indent.size() - 1); }
    private void indentPush(int v) { indent.add(v); }
    private void indentPop() { indent.remove(indent.size() - 1); }

    /** skip whitespace/comments/continuations; returns true if a newline was crossed. */
    private boolean skipWhitespace(boolean stopAtNewline) {
        while (!isEnd()) {
            if (isPhysicalNewline()) {
                if (stopAtNewline && nestedBracketLevel == 0) return true;
                nextChar();
            } else if (isWhitespace(chr0)) {
                nextChar();
            } else if (isChar('#')) {
                nextChar();
                while (!isEnd() && !isPhysicalNewline()) nextChar();
            } else if (isCharAnd('\\', '\n')) {
                nextChar();
                nextChar();
            } else {
                break;
            }
        }
        return false;
    }

    /** Advance to the next token (mirrors mp_lexer_to_next). */
    public void toNext() {
        vstr.reset();
        boolean hadPhysicalNewline = skipWhitespace(true);
        tokLine = line;
        tokColumn = column;

        if (emitDent < 0) {
            kind = Tok.DEDENT;
            emitDent += 1;
        } else if (emitDent > 0) {
            kind = Tok.INDENT;
            emitDent -= 1;
        } else if (hadPhysicalNewline) {
            skipWhitespace(false);
            kind = Tok.NEWLINE;
            int numSpaces = column - 1;
            if (numSpaces == indentTop()) {
                // same level
            } else if (numSpaces > indentTop()) {
                indentPush(numSpaces);
                emitDent += 1;
            } else {
                while (numSpaces < indentTop()) {
                    indentPop();
                    emitDent -= 1;
                }
                if (numSpaces != indentTop()) kind = Tok.DEDENT_MISMATCH;
            }
        } else if (isEnd()) {
            kind = Tok.END;
        } else if (isStringOrBytes()) {
            lexStringOrBytes();
        } else if (isHeadOfIdentifier(chr0)) {
            lexName();
        } else if (isDigit(chr0) || (isChar('.') && isDigit(chr1))) {
            lexNumber();
        } else {
            lexOperator();
        }
    }

    private void lexName() {
        kind = Tok.NAME;
        vstr.write(chr0);
        nextChar();
        while (!isEnd() && isTailOfIdentifier(chr0)) { vstr.write(chr0); nextChar(); }
        String s = text();
        for (int i = 0; i < TOK_KW.length; i++) {
            int cmp = s.compareTo(TOK_KW[i]);
            if (cmp == 0) {
                kind = Tok.values()[Tok.KW_FALSE.ordinal() + i];
                if (kind == Tok.KW___DEBUG__) kind = Tok.KW_TRUE; // mp_optimise_value == 0 default
                break;
            } else if (cmp < 0) {
                break; // sorted table
            }
        }
    }

    private void lexNumber() {
        boolean forcedInteger = false;
        if (isChar('.')) {
            kind = Tok.FLOAT_OR_IMAG;
        } else {
            kind = Tok.INTEGER;
            if (isChar('0') && isFollowingBaseChar()) forcedInteger = true;
        }
        vstr.write(chr0);
        nextChar();
        while (!isEnd()) {
            if (!forcedInteger && isCharOr('e', 'E')) {
                kind = Tok.FLOAT_OR_IMAG;
                vstr.write('e');
                nextChar();
                if (isChar('+') || isChar('-')) { vstr.write(chr0); nextChar(); }
            } else if (isLetter(chr0) || isDigit(chr0) || isChar('.')) {
                if (isChar('.') || isChar('j') || isChar('J')) kind = Tok.FLOAT_OR_IMAG;
                vstr.write(chr0);
                nextChar();
            } else if (isChar('_')) {
                nextChar();
            } else {
                break;
            }
        }
    }

    private boolean isFollowingBaseChar() {
        int c = chr1 | 0x20; // lowercase
        return c == 'b' || c == 'o' || c == 'x';
    }

    private void lexOperator() {
        int t = 0;               // index into TOK_ENC
        int tokEncIndex = 0;
        while (t < TOK_ENC.length() && TOK_ENC.charAt(t) != chr0) {
            char ct = TOK_ENC.charAt(t);
            if (ct == 'e' || ct == 'c') t += 1;
            t += 1;
            tokEncIndex += 1;
        }
        nextChar();
        if (t >= TOK_ENC.length()) {
            kind = Tok.INVALID;
        } else if (TOK_ENC.charAt(t) == '!') {
            if (isChar('=')) { nextChar(); kind = Tok.OP_NOT_EQUAL; }
            else kind = Tok.INVALID;
        } else if (TOK_ENC.charAt(t) == '.') {
            if (isCharAnd('.', '.')) { nextChar(); nextChar(); kind = Tok.ELLIPSIS; }
            else kind = Tok.DEL_PERIOD;
        } else {
            t += 1;
            int tIndex = tokEncIndex;
            while (t < TOK_ENC.length() && (TOK_ENC.charAt(t) == 'c' || TOK_ENC.charAt(t) == 'e')) {
                tIndex += 1;
                char ct = TOK_ENC.charAt(t);
                if (t + 1 < TOK_ENC.length() && isChar(TOK_ENC.charAt(t + 1))) {
                    nextChar();
                    tokEncIndex = tIndex;
                    if (ct == 'e') break;
                } else if (ct == 'c') {
                    break;
                }
                t += 2;
            }
            kind = TOK_ENC_KIND[tokEncIndex];
            // bracket level for implicit line joining inside (), [], {}
            if (kind == Tok.DEL_PAREN_OPEN || kind == Tok.DEL_BRACKET_OPEN || kind == Tok.DEL_BRACE_OPEN) {
                nestedBracketLevel += 1;
            } else if (kind == Tok.DEL_PAREN_CLOSE || kind == Tok.DEL_BRACKET_CLOSE || kind == Tok.DEL_BRACE_CLOSE) {
                nestedBracketLevel -= 1;
            }
        }
    }

    // --- strings/bytes (no f/t strings in this step) ---

    private boolean isStringOrBytes() {
        if (isCharOr('\'', '"')) return true;
        int c0 = chr0 | 0x20;
        // single-prefix: u/b/r/f/t followed by quote  (f/t handled as plain here)
        if ((c0 == 'u' || c0 == 'b' || c0 == 'r' || c0 == 'f' || c0 == 't')
                && (chr1 == '\'' || chr1 == '"')) return true;
        // two-prefix combos: rb/br/rf/fr/rt/tr followed by quote
        int c1 = chr1 | 0x20;
        boolean two = (c0 == 'r' && c1 == 'b') || (c0 == 'b' && c1 == 'r')
                   || (c0 == 'r' && c1 == 'f') || (c0 == 'f' && c1 == 'r')
                   || (c0 == 'r' && c1 == 't') || (c0 == 't' && c1 == 'r');
        return two && (chr2 == '\'' || chr2 == '"');
    }

    private void lexStringOrBytes() {
        kind = Tok.END; // sentinel: first literal sets the real kind
        fstringArgs = null;
        do {
            boolean isRaw = false;
            boolean isFString = false;
            boolean isTString = false;
            Tok thisKind = Tok.STRING;
            int nChar = 0;
            int c0 = chr0 | 0x20;
            if (c0 == 'u') {
                nChar = 1;
            } else if (c0 == 'b') {
                thisKind = Tok.BYTES; nChar = 1;
                if ((chr1 | 0x20) == 'r') { isRaw = true; nChar = 2; }
            } else if (c0 == 'r') {
                isRaw = true; nChar = 1;
                int c1 = chr1 | 0x20;
                if (c1 == 'b') { thisKind = Tok.BYTES; nChar = 2; }
                else if (c1 == 'f') { isFString = true; nChar = 2; }   // rf"..."
                else if (c1 == 't') { isTString = true; nChar = 2; }   // rt"..." 
            } else if (c0 == 'f') {
                isFString = true; nChar = 1;
                if ((chr1 | 0x20) == 'r') { isRaw = true; nChar = 2; }
            } else if (c0 == 't') {
                isTString = true; nChar = 1;
                if ((chr1 | 0x20) == 'r') { isRaw = true; nChar = 2; }
            }

            if (kind == Tok.END) kind = thisKind;
            else if (kind != thisKind) break; // can't concat str with bytes

            for (int i = 0; i < nChar; i++) nextChar();

            if (isTString) hadTString = true;
            if (isFString && fstringArgs == null) fstringArgs = new StringBuilder(".format(");
            if (isTString && fstringArgs == null) fstringArgs = new StringBuilder();
            parseStringLiteral(isRaw, isFString, isTString);
            skipWhitespace(true);
        } while (isStringOrBytes());

        // a t-string becomes  __template__((parts,), value, "src", conv, spec, ...)
        if (hadTString) {
            vstr.write(',');
            vstr.write(')');
            vstr.write(',');
            fstringArgs.insert(0, net.mpy.compiler.io.Utf8.decode(vstr.toByteArray()));
            kind = Tok.NAME;
            vstr.reset();
            for (byte b : "__template__".getBytes(StandardCharsets.UTF_8)) vstr.write(b);
            hadTString = false;
        }
        // an f-string becomes  "...".format(args)  -- inject the call for re-lexing
        if (fstringArgs != null) {
            fstringArgs.append(')');
            injectText(fstringArgs.toString());
            fstringArgs = null;
        }
    }

    /** Accumulated ".format(...)" / __template__ argument text for the literal being lexed. */
    private StringBuilder fstringArgs = null;
    private boolean hadTString = false;
    private int tstringContinuations = 0;
    private int pendingLineBump = 0;

    /** Current vstr length (used to truncate a t-string format spec back off). */
    private int vstrLen() { return vstr.size(); }

    private void vstrTruncate(int n) {
        byte[] cur = vstr.toByteArray();
        vstr.reset();
        vstr.write(cur, 0, Math.min(n, cur.length));
    }

    private String vstrSubstring(int from) {
        byte[] cur = vstr.toByteArray();
        if (from >= cur.length) return "";
        return net.mpy.compiler.io.Utf8.decode(cur, from, cur.length - from);
    }

    private void writeQuotes(int quote, int numQuotes) {
        for (int q = 0; q < numQuotes; q++) vstr.write(quote);
    }

    private void parseStringLiteral(boolean isRaw, boolean isFString, boolean isTString) {
        int quote = chr0;
        int numQuotes;
        nextChar();
        if (isChar(quote) && isCharFollowing(quote)) {
            nextChar(); nextChar();
            numQuotes = 3;
        } else {
            numQuotes = 1;
        }
        if (isTString && fstringArgs.length() == 0) {
            vstr.write('(');
            vstr.write('(');
            writeQuotes(quote, numQuotes);
        }
        int nestedFmt = 0;
        int endOfFormatIndex = 0;
        boolean needsFString = false;
        int nClosing = 0;
        while (!isEnd() && (numQuotes > 1 || !isChar('\n')) && nClosing < numQuotes) {
            if (isChar(quote)) {
                nClosing++;
                vstr.write(chr0);
            } else {
                nClosing = 0;
                while ((isFString || isTString) && isChar('{')) {
                    if (nestedFmt > 0) { nestedFmt++; break; }   // inside a t-string format spec
                    nextChar();
                    if (isChar('{')) {
                        // "{{": f-strings keep both braces for str.format to unescape
                        if (!isTString) vstr.write('{');
                        nextChar();
                    } else {
                        fstringArgs.append('(');
                        int argStart = fstringArgs.length();
                        int nested = 0;
                        while (!isEnd() && (nested != 0
                                || !(isChar(':') || isChar('}')
                                     || (isChar('!') && (chr1 == 'r' || chr1 == 's')
                                         && (chr2 == ':' || chr2 == '}'))))) {
                            int c = chr0;
                            if (c == '[' || c == '{') nested++;
                            else if (c == ']' || c == '}') nested--;
                            fstringArgs.append((char) c);
                            nextChar();
                        }
                        boolean wasDebug = false;
                        if (fstringArgs.length() > argStart
                                && fstringArgs.charAt(fstringArgs.length() - 1) == '=') {
                            // f"{a=}" -> "a={}".format(a)
                            vstr.write(fstringArgs.substring(argStart, fstringArgs.length())
                                    .getBytes(StandardCharsets.ISO_8859_1), 0,
                                    fstringArgs.length() - argStart);
                            fstringArgs.setLength(fstringArgs.length() - 1);
                            wasDebug = true;
                        }
                        if (isTString) {
                            while (fstringArgs.length() > argStart
                                    && Character.isWhitespace(fstringArgs.charAt(fstringArgs.length() - 1))) {
                                fstringArgs.setLength(fstringArgs.length() - 1);
                            }
                        }
                        if (fstringArgs.length() == argStart) {
                            kind = Tok.INVALID;   // empty {} is not valid in MicroPython
                            return;
                        }
                        fstringArgs.append("),");
                        if (isTString) {
                            // the interpolation's source text, escaped
                            String src = fstringArgs.substring(argStart, fstringArgs.length() - 2);
                            fstringArgs.append((char) quote);
                            for (int k = 0; k < src.length(); k++) {
                                char b = src.charAt(k);
                                if (b == quote || b == '\\') fstringArgs.append('\\');
                                fstringArgs.append(b);
                            }
                            fstringArgs.append((char) quote).append(',');
                            // close this literal part and open the next
                            writeQuotes(quote, numQuotes);
                            vstr.write(',');
                            writeQuotes(quote, numQuotes);
                            // conversion specifier
                            if (isChar('!')) {
                                nextChar();
                                fstringArgs.append((char) quote).append((char) chr0)
                                           .append((char) quote).append(',');
                                nextChar();
                            } else if (wasDebug && !isChar(':')) {
                                fstringArgs.append("'r',");
                            } else {
                                fstringArgs.append("None,");
                            }
                            if (isChar(':')) nextChar();
                            nestedFmt = 1;
                            endOfFormatIndex = vstrLen();
                        }
                    }
                    vstr.write('{');
                    if (isEnd()) break;
                }
                if (isChar(quote)) { nClosing++; vstr.write(chr0); nextChar(); continue; }
                if (isTString && nestedFmt > 0 && isChar('}')) {
                    if (--nestedFmt > 0) {
                        needsFString = true;
                        vstr.write(chr0);
                    } else {
                        // the interpolation is complete: move its format spec into the args
                        if (needsFString) { fstringArgs.append('f'); needsFString = false; }
                        fstringArgs.append((char) quote)
                                   .append(vstrSubstring(endOfFormatIndex + 1))
                                   .append((char) quote).append(',');
                        vstrTruncate(endOfFormatIndex);
                    }
                    nextChar();
                    continue;
                }
                if (isTString && nestedFmt == 0 && isChar('}') && chr1 == '}') {
                    // "}}" in a t-string literal segment is an escaped brace and is
                    // unescaped here, mirroring how "{{" is handled just above. An
                    // f-string instead keeps both braces, because str.format() does the
                    // unescaping later -- a t-string has no such later step, so leaving
                    // "}}" intact would put a stray brace in the literal.
                    vstr.write('}');
                    nextChar();
                    nextChar();
                    continue;
                }
                if (isTString && isChar('\\')) {
                    // escapes are passed through: the part is re-lexed as a string
                    vstr.write('\\');
                    if (isRaw) {
                        vstr.write('\\');
                    } else {
                        nextChar();
                        // A "\"+newline passed through here is scanned once now and
                        // once more when the part is re-lexed, and mpy-cross counts
                        // BOTH -- its line number for whatever follows the t-string is
                        // one higher per continuation. Record them so injectText can
                        // reproduce that; see the note there.
                        if (chr0 == '\n') tstringContinuations++;
                        vstr.write(chr0);
                    }
                    nextChar();
                    continue;
                }
                if (isChar('\\')) {
                    nextChar();
                    int c = chr0;
                    if (isRaw) {
                        // raw strings allow escaping quotes, but the backslash is emitted too
                        vstr.write('\\');
                        writeChar(c);
                        nextChar();
                        continue;
                    }
                    switch (c) {
                        case '\n': c = -1; break;      // line continuation inside string
                        case '\\': c = '\\'; break;
                        case '\'': c = '\''; break;
                        case '"': c = '"'; break;
                        case 'a': c = 0x07; break;
                        case 'b': c = 0x08; break;
                        case 't': c = 0x09; break;
                        case 'n': c = 0x0a; break;
                        case 'v': c = 0x0b; break;
                        case 'f': c = 0x0c; break;
                        case 'r': c = 0x0d; break;
                        case 'x': { int v = getHex(2); if (v < 0) { kind = Tok.INVALID; return; } c = v; break; }
                        case 'N':
                            if (kind != Tok.BYTES) {
                                // MicroPython does not implement unicode name escapes
                                kind = Tok.INVALID;
                                return;
                            }
                            vstr.write('\\');
                            break;
                        case 'u':
                        case 'U': {
                            if (kind == Tok.BYTES) {
                                vstr.write('\\');   // b'\u1234' == b'\\u1234'
                                break;
                            }
                            int v = getHex(c == 'u' ? 4 : 8);
                            if (v < 0) { kind = Tok.INVALID; return; }
                            c = v;
                            break;
                        }
                        default:
                            if (c >= '0' && c <= '7') {
                                int digits = 3, num = c - '0';
                                while (isFollowingOdigit() && --digits != 0) { nextChar(); num = num * 8 + (chr0 - '0'); }
                                c = num;
                            } else {
                                vstr.write('\\'); // unrecognised escape kept verbatim
                            }
                            break;
                    }
                    if (c >= 0) writeChar(c);
                } else {
                    vstr.write(chr0);
                }
            }
            nextChar();
        }
        if (nClosing < numQuotes) { kind = Tok.LONELY_STRING_OPEN; }
        else if (isTString) {
            // keep the closing quotes: they terminate the final literal part
        } else {
            // cut off the trailing close quotes we appended
            byte[] cur = vstr.toByteArray();
            vstr.reset();
            vstr.write(cur, 0, cur.length - nClosing);
        }
    }

    private void writeChar(int c) {
        if (kind == Tok.BYTES) {
            if (c < 0x100) vstr.write(c);
        } else {
            // str: encode the code point directly, as vstr_add_char does. Java's
            // String -> UTF-8 would replace a lone surrogate with '?', but escapes
            // such as "\ud800" must survive verbatim.
            if (c < 0x80) {
                vstr.write(c);
            } else if (c < 0x800) {
                vstr.write((c >> 6) | 0xC0);
                vstr.write((c & 0x3F) | 0x80);
            } else if (c < 0x10000) {
                vstr.write((c >> 12) | 0xE0);
                vstr.write(((c >> 6) & 0x3F) | 0x80);
                vstr.write((c & 0x3F) | 0x80);
            } else {
                vstr.write((c >> 18) | 0xF0);
                vstr.write(((c >> 12) & 0x3F) | 0x80);
                vstr.write(((c >> 6) & 0x3F) | 0x80);
                vstr.write((c & 0x3F) | 0x80);
            }
        }
    }

    private boolean isFollowingOdigit() { return chr1 >= '0' && chr1 <= '7'; }

    private int getHex(int numDigits) {
        int num = 0;
        while (numDigits-- != 0) {
            nextChar();
            int c = chr0;
            int d;
            if (c >= '0' && c <= '9') d = c - '0';
            else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
            else return -1;
            num = (num << 4) + d;
        }
        return num;
    }
}
