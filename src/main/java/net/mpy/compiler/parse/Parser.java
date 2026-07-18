package net.mpy.compiler.parse;

import net.mpy.compiler.MpCompileException;
import net.mpy.compiler.lex.Lexer;
import net.mpy.compiler.lex.Tok;
import net.mpy.compiler.model.MpConst;
import net.mpy.compiler.model.SmallInt;

import java.math.BigInteger;
import java.util.ArrayList;

/**
 * Table-driven push-down parser — faithful Java port of py/parse.c's mp_parse.
 *
 * Consumes the generated {@link Grammar} table to build a {@link ParseNode} tree,
 * verified against oracle/parsedump. Covers the full OR/AND/LIST state machine
 * with backtracking, the rule-specific simplifications (atom_paren, testlist_comp,
 * testlist_comp_3c), leaf creation (id/int/str/token), small-int optimisation,
 * and the lonely-const-statement discard (docstrings off).
 *
 * DEFERRED (next hardening sub-step, overlaps Step 10):
 *   - constant folding during parse (fold_logical_constants / fold_constants):
 *     e.g. `1 + 2` -> `int(3)`. Without it, foldable constant expressions produce
 *     the unfolded rule tree, so the diff corpus avoids constant arithmetic.
 *   - const-tuple building (build_tuple).
 *   - MICROPY_COMP_CONST dynamic user constants.
 *   - f-string tokens (inherited lexer limitation).
 */
public final class Parser {

    // rule kinds we special-case (resolved from the generated table)
    private final int R_atom_paren = Grammar.number("atom_paren");
    private final int R_testlist_comp = Grammar.number("testlist_comp");
    private final int R_testlist_comp_3b = Grammar.number("testlist_comp_3b");
    private final int R_testlist_comp_3c = Grammar.number("testlist_comp_3c");
    private final int R_comp_for = Grammar.number("comp_for");
    private final int R_expr_stmt = Grammar.number("expr_stmt");
    private final int R_pass_stmt = Grammar.number("pass_stmt");
    private final int R_file_input = Grammar.number("file_input");

    // Grammar arg-kind / act encodings mirrored as ints for the hot loop.
    private static final int ACT_OR = 0x10, ACT_AND = 0x20, ACT_LIST = 0x30;

    private static final class RuleFrame {
        int ruleId, argI, srcLine;
        RuleFrame(int r, int a, int l) { ruleId = r; argI = a; srcLine = l; }
    }

    private final Lexer lex;
    private final ArrayList<RuleFrame> ruleStack = new ArrayList<>();
    private final ArrayList<ParseNode> resultStack = new ArrayList<>();

    private Parser(Lexer lex) { this.lex = lex; }

    /** Parse source as a file input, returning the root parse node. */
    public static ParseNode parseFile(String source) {
        return parseFile(source, null);
    }

    /**
     * Parse source as a file input. {@code sourceName} seeds the interned-string set:
     * mpy-cross interns the source file name in the real qstr pool before parsing, so
     * a string literal equal to it is found by qstr_find_strn and stays a qstr leaf
     * instead of becoming a const object. Without seeding, a file like "sys_path.py"
     * containing the literal "sys_path.py" would diverge.
     */
    public static ParseNode parseFile(String source, String sourceName) {
        Parser p = new Parser(new Lexer(source));
        if (sourceName != null) p.internedSoFar.add(sourceName);
        return p.run(p.R_file_input);
    }

    // --- stacks ---
    private void pushRule(int srcLine, int ruleId, int argI) { ruleStack.add(new RuleFrame(ruleId, argI, srcLine)); }
    private void pushRuleFromArg(Grammar.Arg arg) { pushRule(lex.tokLine, arg.rule, 0); }
    private RuleFrame popRule() { return ruleStack.remove(ruleStack.size() - 1); }

    private void pushResult(ParseNode pn) { resultStack.add(pn); }
    private ParseNode popResult() { return resultStack.remove(resultStack.size() - 1); }
    private ParseNode peekResult(int pos) { return resultStack.get(resultStack.size() - 1 - pos); }

    private int actKind(Grammar.Rule r) {
        switch (r.act) { case OR: return ACT_OR; case AND: return ACT_AND; case LIST: return ACT_LIST; default: return 0; }
    }

    private ParseNode run(int topRule) {
        pushRule(lex.tokLine, topRule, 0);
        boolean backtrack = false;

        outer:
        for (;;) {
            if (ruleStack.isEmpty()) break;
            RuleFrame f = popRule();
            int ruleId = f.ruleId;
            int i = f.argI;
            int ruleSrcLine = f.srcLine;
            Grammar.Rule rule = Grammar.RULES[ruleId];
            int n = rule.argCount;
            int act = actKind(rule);

            if (act == ACT_OR) {
                if (i > 0 && !backtrack) { continue; }
                else { backtrack = false; }
                boolean matched = false;
                for (; i < n; ++i) {
                    Grammar.Arg arg = rule.args[i];
                    if (arg.kind == Grammar.ArgKind.TOK) {
                        if (lex.kind.ordinal() == arg.tok) {
                            pushResultToken(ruleId);
                            lex.toNext();
                            matched = true; break;
                        }
                    } else {
                        if (i + 1 < n) pushRule(ruleSrcLine, ruleId, i + 1);
                        pushRuleFromArg(arg);
                        matched = true; break;
                    }
                }
                if (matched) continue;
                backtrack = true;
                continue;

            } else if (act == ACT_AND) {
                if (backtrack) {
                    if (rule.args[i - 1].kind == Grammar.ArgKind.OPT_RULE) {
                        pushResult(ParseNode.Null.INSTANCE);
                        backtrack = false;
                    } else {
                        if (i > 1) throw syntaxError();
                        else continue;
                    }
                }
                boolean pushedChild = false;
                for (; i < n; ++i) {
                    Grammar.Arg arg = rule.args[i];
                    if (arg.kind == Grammar.ArgKind.TOK) {
                        if (lex.kind.ordinal() == arg.tok) {
                            if (arg.tok == Tok.NAME.ordinal()) pushResultToken(ruleId);
                            lex.toNext();
                        } else {
                            if (i > 0) throw syntaxError();
                            else { backtrack = true; pushedChild = true; break; }
                        }
                    } else {
                        pushRule(ruleSrcLine, ruleId, i + 1);
                        pushRuleFromArg(arg);
                        pushedChild = true; break;
                    }
                }
                if (pushedChild) continue;

                // matched whole AND rule -> build node

                // discard lonely const statements (docstrings off)
                if (ruleId == R_expr_stmt && peekResult(0) instanceof ParseNode.Null) {
                    ParseNode p = peekResult(1);
                    boolean lonelyConst = (p instanceof ParseNode.SmallInt)
                            || (p instanceof ParseNode.Str)
                            || (p instanceof ParseNode.Token)
                            || (p instanceof ParseNode.Const);
                    if (lonelyConst) {
                        popResult(); // NULL
                        popResult(); // the const
                        pushResultRule(ruleSrcLine, R_pass_stmt, 0);
                        backtrack = false;
                        continue;
                    }
                }

                // count non-nil args
                i = 0;
                int numNotNil = 0;
                for (int x = n; x > 0;) {
                    --x;
                    Grammar.Arg arg = rule.args[x];
                    if (arg.kind == Grammar.ArgKind.TOK) {
                        if (arg.tok == Tok.NAME.ordinal()) { i += 1; numNotNil += 1; }
                    } else {
                        if (!(peekResult(i) instanceof ParseNode.Null)) numNotNil += 1;
                        i += 1;
                    }
                }

                if (numNotNil == 1 && rule.allowIdent) {
                    ParseNode pn = ParseNode.Null.INSTANCE;
                    for (int x = 0; x < i; ++x) {
                        ParseNode pn2 = popResult();
                        if (!(pn2 instanceof ParseNode.Null)) pn = pn2;
                    }
                    pushResult(pn);
                } else {
                    if (rule.addBlank) { pushResult(ParseNode.Null.INSTANCE); i += 1; }
                    pushResultRule(ruleSrcLine, ruleId, i);
                }
                backtrack = false;
                continue;

            } else { // ACT_LIST
                boolean hadTrailingSep = false;
                boolean toBacktrack = backtrack; // entered via a failed child rule?

                if (!toBacktrack) {
                    // try to consume list items/separators
                    boolean pushedChild = false;
                    for (;;) {
                        Grammar.Arg arg = rule.args[i & 1 & n];
                        if (arg.kind == Grammar.ArgKind.TOK) {
                            if (lex.kind.ordinal() == arg.tok) {
                                if ((i & 1 & n) != 0) { /* separator token: not pushed */ }
                                else pushResultToken(ruleId);
                                lex.toNext();
                                i += 1;
                            } else {
                                i += 1;
                                backtrack = true;   // real backtrack, for propagation
                                toBacktrack = true; // goto list_backtrack
                                break;
                            }
                        } else {
                            pushRule(ruleSrcLine, ruleId, i + 1); // save this list-rule
                            pushRuleFromArg(arg);                 // push child of list-rule
                            pushedChild = true;
                            break;
                        }
                    }
                    if (pushedChild) continue; // next_rule
                }

                // list_backtrack: propagate backtrack or finish the list
                if (toBacktrack) {
                    hadTrailingSep = false;
                    if (n == 2) {
                        if (i == 1) { continue; }      // fail on first item: propagate
                        else { backtrack = false; }    // fail in later round: finish
                    } else {
                        if (i == 1) { continue; }
                        else if ((i & 1) == 1) {
                            if (n == 3) { hadTrailingSep = true; backtrack = false; }
                            else throw syntaxError();
                        } else {
                            backtrack = false;          // fail on separator: finish
                        }
                    }
                }

                // build list result
                i -= 1;
                if ((n & 1) != 0 && rule.args[1].kind == Grammar.ArgKind.TOK) {
                    i = (i + 1) / 2;
                }
                if (i == 1) {
                    if (hadTrailingSep) pushResultRule(ruleSrcLine, ruleId, i);
                    // else leave single item on stack
                } else {
                    pushResultRule(ruleSrcLine, ruleId, i);
                }
                backtrack = false;
                continue;
            }
        }

        if (lex.kind != Tok.END || resultStack.isEmpty()) throw syntaxError();
        return resultStack.get(0);
    }

    private MpCompileException syntaxError() {
        if (lex.kind == Tok.INDENT) return new MpCompileException(lex.tokLine, "unexpected indent");
        if (lex.kind == Tok.DEDENT_MISMATCH) return new MpCompileException(lex.tokLine, "unindent doesn't match any outer indent level");
        return new MpCompileException(lex.tokLine, "invalid syntax");
    }

    // --- leaf creation (push_result_token) ---
    private void pushResultToken(int ruleId) {
        ParseNode pn;
        switch (lex.kind) {
            case NAME: {
                String nm = lex.text();
                internedSoFar.add(nm);   // identifiers are interned as they are parsed
                MpConst cv = (ruleId == R_atom_c) ? consts.get(nm) : null;
                pn = (cv != null) ? constNode(lex.tokLine, cv) : new ParseNode.Id(nm);
                break;
            }
            case INTEGER:
                pn = makeIntNode(lex.tokLine, parseInteger(lex.text()));
                break;
            case STRING: {
                // Don't intern every string: doc strings are usually large and get
                // discarded, so only short ones become qstr leaves (parse.c uses
                // MICROPY_ALLOC_PARSE_INTERN_STRING_LEN = 10). Longer strings become
                // const objects unless they are already interned.
                String text = lex.text();
                byte[] raw = lex.textBytes();
                if (raw.length <= INTERN_STRING_LEN) {
                    internedSoFar.add(text);
                    pn = new ParseNode.Str(text);
                } else if (net.mpy.compiler.qstr.StaticQstrs.isStatic(text) || internedSoFar.contains(text)) {
                    // qstr_find_strn: already in the pool (e.g. used as an identifier)
                    pn = new ParseNode.Str(text);
                } else {
                    pn = new ParseNode.Const(lex.tokLine, new MpConst.Str(text));
                }
                break;
            }
            case FLOAT_OR_IMAG: {
                String num = lex.text();
                if (num.endsWith("j") || num.endsWith("J")) {
                    double im = Double.parseDouble(num.substring(0, num.length() - 1));
                    pn = new ParseNode.Const(lex.tokLine, new MpConst.Complex(0.0, im));
                } else {
                    pn = new ParseNode.Const(lex.tokLine, new MpConst.Float(Double.parseDouble(num)));
                }
                break;
            }
            case BYTES:
                pn = new ParseNode.Const(lex.tokLine, new MpConst.Bytes(lex.textBytes()));
                break;
            default:
                pn = new ParseNode.Token(lex.kind);
        }
        pushResult(pn);
    }

    // rule numbers for folding
    private final int R_expr = Grammar.number("expr");
    private final int R_xor_expr = Grammar.number("xor_expr");
    private final int R_and_expr = Grammar.number("and_expr");
    private final int R_power = Grammar.number("power");
    private final int R_shift_expr = Grammar.number("shift_expr");
    private final int R_arith_expr = Grammar.number("arith_expr");
    private final int R_term = Grammar.number("term");
    private final int R_factor_2 = Grammar.number("factor_2");

    /** parse.c fold_constants for integer numeric expressions (floats deferred). */
    private boolean foldConstants(int ruleId, int numArgs) {
        Object arg0;
        if (ruleId == R_expr || ruleId == R_xor_expr || ruleId == R_and_expr || ruleId == R_power) {
            // fixed op over operands: | ^ & **
            arg0 = numberOf(peekResult(numArgs - 1));
            if (arg0 == null) return false;
            for (int i = numArgs - 2; i >= 0; --i) {
                Object arg1 = numberOf(peekResult(i));
                if (arg1 == null) return false;
                arg0 = applyFixed(ruleId, arg0, arg1);
                if (arg0 == null) return false;
            }
        } else if (ruleId == R_shift_expr || ruleId == R_arith_expr || ruleId == R_term) {
            // operator tokens between operands: << >> + - * / % //
            arg0 = numberOf(peekResult(numArgs - 1));
            if (arg0 == null) return false;
            for (int i = numArgs - 2; i >= 1; i -= 2) {
                Object arg1 = numberOf(peekResult(i - 1));
                if (arg1 == null) return false;
                ParseNode opNode = peekResult(i);
                if (!(opNode instanceof ParseNode.Token t)) return false;
                arg0 = applyBinary(t.tok, arg0, arg1);
                if (arg0 == null) return false;
            }
        } else if (ruleId == R_factor_2) {
            // unary + - ~
            arg0 = numberOf(peekResult(0));
            if (arg0 == null) return false;
            ParseNode opNode = peekResult(1);
            if (!(opNode instanceof ParseNode.Token t)) return false;
            if (t.tok == Tok.OP_TILDE) {
                if (isFloat(arg0)) return false; // ~ is int-only
                arg0 = ((BigInteger) arg0).not();
            } else if (t.tok == Tok.OP_MINUS) {
                arg0 = isFloat(arg0) ? (Object) (-(Double) arg0) : (Object) ((BigInteger) arg0).negate();
            } else if (t.tok == Tok.OP_PLUS) { /* unchanged */ }
            else return false;
        } else if (ruleId == R_atom_expr_normal_c && numArgs == 2) {
            // MICROPY_COMP_MODULE_CONST: "mod.attr" where mod is in the compiler's
            // constants table and attr resolves to an int or float is replaced by that
            // constant outright (parse.c, RULE_atom_expr_normal). Only `math` is in the
            // table for this build -- probed against mpy-cross: pi/e/tau/inf/nan fold,
            // everything else (math.sqrt, errno.*, uctypes.*, os.sep, ...) does not.
            // Note this is purely syntactic: MicroPython does not check that `math`
            // actually refers to the module, and neither do we, on purpose.
            Double mc = moduleConst(peekResult(1), peekResult(0));
            if (mc == null) return false;
            arg0 = mc;
        } else {
            return false;
        }
        // success: pop the args, push the folded constant
        for (int i = 0; i < numArgs; i++) popResult();
        if (isFloat(arg0)) pushResult(new ParseNode.Const(0, new MpConst.Float((Double) arg0)));
        else pushResult(makeIntNode(0, (BigInteger) arg0));
        return true;
    }

    /**
     * mp_constants_table lookup: the numeric value of {@code base.attr}, or null when
     * that pair is not a compile-time constant.
     */
    private Double moduleConst(ParseNode base, ParseNode trailer) {
        if (!(base instanceof ParseNode.Id id) || !id.name.equals("math")) return null;
        if (!(trailer instanceof ParseNode.Struct tr) || tr.ruleId != R_trailer_period_c) return null;
        if (tr.nodes.length == 0 || !(tr.nodes[0] instanceof ParseNode.Id attr)) return null;
        switch (attr.name) {
            case "pi":  return Math.PI;
            case "e":   return Math.E;
            case "tau": return 2 * Math.PI;
            case "inf": return Double.POSITIVE_INFINITY;
            case "nan": return Double.NaN;
            default:    return null;   // functions and everything else stay dynamic
        }
    }

    /** A foldable numeric operand: BigInteger for ints, Double for floats. */
    private Object numberOf(ParseNode n) {
        if (n instanceof ParseNode.SmallInt si) return BigInteger.valueOf(si.value);
        if (n instanceof ParseNode.Const c) {
            if (c.value instanceof MpConst.Int i) return i.value();
            if (c.value instanceof MpConst.Float f) return f.value();
        }
        return null;
    }

    private static double toDouble(Object o) {
        return (o instanceof BigInteger b) ? b.doubleValue() : (Double) o;
    }
    private static boolean isFloat(Object o) { return o instanceof Double; }

    private Object applyFixed(int ruleId, Object a, Object b) {
        if (isFloat(a) || isFloat(b)) {
            // bitwise ops are int-only; only ** folds for floats
            if (ruleId != R_power) return null;
            double base = toDouble(a), exp = toDouble(b);
            if (base == 0.0 && exp < 0) return null;   // 0.0**-1 raises at runtime
            // a negative base with a fractional exponent yields a complex number
            if (base < 0 && exp != Math.floor(exp)) return null;
            return Math.pow(base, exp);
        }
        BigInteger x = (BigInteger) a, y = (BigInteger) b;
        if (ruleId == R_expr) return x.or(y);
        if (ruleId == R_xor_expr) return x.xor(y);
        if (ruleId == R_and_expr) return x.and(y);
        if (ruleId == R_power) {
            if (y.signum() < 0) {
                // int ** negative int is a FLOAT in MicroPython (mp_binary_op returns
                // one on a float-enabled build), and the parser folds it like any other
                // numeric op -- so "2**-2" becomes the constant 0.25, not a runtime
                // power. Only 0 ** negative is left alone: it raises, and
                // binary_op_maybe declines to fold anything that raises.
                if (x.signum() == 0) return null;
                return Math.pow(x.doubleValue(), y.doubleValue());
            }
            // A huge exponent would blow up here; mpy-cross gives up on those too.
            if (y.bitLength() > 31) return null;
            return x.pow(y.intValueExact());
        }
        return null;
    }

    private Object applyBinary(Tok tok, Object a, Object b) {
        if (isFloat(a) || isFloat(b)) {
            double x = toDouble(a), y = toDouble(b);
            switch (tok) {
                case OP_PLUS: return x + y;
                case OP_MINUS: return x - y;
                case OP_STAR: return x * y;
                case OP_SLASH: return y == 0.0 ? null : x / y;
                case OP_DBL_SLASH: return y == 0.0 ? null : Math.floor(x / y);
                case OP_PERCENT: return y == 0.0 ? null : pyFmod(x, y);
                default: return null; // shifts are int-only
            }
        }
        BigInteger x = (BigInteger) a, y = (BigInteger) b;
        switch (tok) {
            case OP_PLUS: return x.add(y);
            case OP_MINUS: return x.subtract(y);
            case OP_STAR: return x.multiply(y);
            // negative shift counts raise at runtime, so don't fold them
            case OP_DBL_LESS: return y.signum() < 0 ? null : x.shiftLeft(y.intValueExact());
            case OP_DBL_MORE: return y.signum() < 0 ? null : x.shiftRight(y.intValueExact());
            case OP_DBL_SLASH: return y.signum() == 0 ? null : floorDiv(x, y);
            case OP_PERCENT: return y.signum() == 0 ? null : floorMod(x, y);
            case OP_SLASH: { // true divide always produces a float
                if (y.signum() == 0) return null;
                return x.doubleValue() / y.doubleValue();
            }
            default: return null;
        }
    }

    /** Python's float % (result takes the sign of the divisor). */
    private static double pyFmod(double x, double y) {
        double r = x % y;
        if (r != 0.0 && ((r < 0) != (y < 0))) r += y;
        return r;
    }

    private BigInteger floorDiv(BigInteger a, BigInteger b) {
        BigInteger[] qr = a.divideAndRemainder(b);
        BigInteger q = qr[0];
        if (qr[1].signum() != 0 && qr[1].signum() != b.signum()) q = q.subtract(BigInteger.ONE);
        return q;
    }

    private BigInteger floorMod(BigInteger a, BigInteger b) {
        BigInteger r = a.mod(b.abs());
        return b.signum() < 0 && r.signum() != 0 ? r.subtract(b.abs()) : r;
    }



    /** Table of compile-time constants declared with micropython.const(). */
    private final java.util.HashMap<String, MpConst> consts = new java.util.HashMap<>();
    private final int R_atom_c = Grammar.number("atom");
    private final int R_pass_stmt_c = Grammar.number("pass_stmt");
    private final int R_atom_expr_normal_c = Grammar.number("atom_expr_normal");
    private final int R_trailer_paren_c = Grammar.number("trailer_paren");

    private ParseNode constNode(int srcLine, MpConst c) {
        if (c instanceof MpConst.Int i && SmallInt.fits(i.value())) {
            return new ParseNode.SmallInt(i.value().longValue());
        }
        return new ParseNode.Const(srcLine, c);
    }

    /**
     * parse.c: recognise "id = const(value)". The value is recorded in the const
     * table; a leading underscore makes it private, replacing the statement with
     * "pass" so nothing is stored at runtime.
     */
    private boolean declareConst() {
        ParseNode value = peekResult(0);   // RHS
        ParseNode target = peekResult(1);  // LHS
        if (!(target instanceof ParseNode.Id id)) return false;
        // "x: T = const(v)" arrives wrapped in an annassign node; the value to look at
        // is its second child. mpy-cross treats the annotated and plain forms
        // identically -- the annotation is dropped entirely -- so unwrap and carry on.
        if (value instanceof ParseNode.Struct ann && ann.ruleId == R_annassign_c
                && ann.nodes.length > 1) {
            value = ann.nodes[1];
        }
        if (!(value instanceof ParseNode.Struct call) || call.ruleId != R_atom_expr_normal_c) return false;
        if (!(call.nodes[0] instanceof ParseNode.Id fn) || !fn.name.equals("const")) return false;
        if (!(call.nodes[1] instanceof ParseNode.Struct tp) || tp.ruleId != R_trailer_paren_c) return false;
        ParseNode arg = tp.nodes.length > 0 ? tp.nodes[0] : ParseNode.Null.INSTANCE;
        MpConst cv = constValueOf(arg);
        if (cv == null) return false;      // "not a constant" -> leave as a normal call
        consts.put(id.name, cv);
        if (id.name.startsWith("_")) {
            popResult(); popResult();      // private constant: emit nothing
            pushResultRule(0, R_pass_stmt_c, 0);
            return true;
        }
        // public constant: keep the assignment but replace const(v) with v. The
        // original parse node is reused, so a short string stays a qstr leaf rather
        // than becoming a const object.
        popResult();
        pushResult(arg);
        return false;
    }

    /** Names and short strings interned so far, mirroring MicroPython's growing qstr pool. */
    private final java.util.HashSet<String> internedSoFar = new java.util.HashSet<>();

    private static final int INTERN_STRING_LEN = 10; // MICROPY_ALLOC_PARSE_INTERN_STRING_LEN


    private final int R_trailer_period_c = Grammar.number("trailer_period");
    private final int R_annassign_c = Grammar.number("annassign");
    private final int R_atom_paren_f = Grammar.number("atom_paren");
    private final int R_or_test_f = Grammar.number("or_test");
    private final int R_and_test_f = Grammar.number("and_test");
    private final int R_not_test_2_f = Grammar.number("not_test_2");

    /**
     * parse.c fold_logical_constants: short-circuit and/or over constant operands,
     * and fold "not <const>". Returns true when the rule folded to a single node.
     */
    private boolean foldLogicalConstants(int ruleId, int[] numArgs) {
        if (ruleId == R_or_test_f || ruleId == R_and_test_f) {
            int n = numArgs[0];
            int copyTo = n;
            for (int i = copyTo; i > 0; ) {
                ParseNode pn = peekResult(--i);
                resultStack.set(resultStack.size() - copyTo, pn);
                if (i == 0) break;                      // always keep the last value
                if (ruleId == R_or_test_f) {
                    if (isConstTrue(pn)) break;
                    else if (!isConstFalse(pn)) copyTo -= 1;
                } else {
                    if (isConstFalse(pn)) break;
                    else if (!isConstTrue(pn)) copyTo -= 1;
                }
            }
            copyTo -= 1;                                // number of args to discard
            for (int i = 0; i < copyTo; i++) popResult();
            numArgs[0] -= copyTo;
            return numArgs[0] == 1;                     // fully folded
        }
        if (ruleId == R_not_test_2_f) {
            ParseNode pn = peekResult(0);
            ParseNode folded;
            if (isConstFalse(pn)) folded = new ParseNode.Token(Tok.KW_TRUE);
            else if (isConstTrue(pn)) folded = new ParseNode.Token(Tok.KW_FALSE);
            else return false;
            popResult();
            pushResult(folded);
            return true;
        }
        return false;
    }

    private boolean isConstTrue(ParseNode n) { return Boolean.TRUE.equals(constTruthiness(n)); }
    private boolean isConstFalse(ParseNode n) { return Boolean.FALSE.equals(constTruthiness(n)); }

    /** parse_node_is_const_bool: truthiness of a constant node, else null. */
    private Boolean constTruthiness(ParseNode n) {
        // mp_parse_node_is_const also accepts an atom_paren whose contents are NULL:
        // the empty tuple "()", which is a constant and falsy. Without this, parse-time
        // folding of "not ()" / "() or x" / "() and x" is skipped and the emitted code
        // diverges from mpy-cross.
        if (n instanceof ParseNode.Struct ap && ap.ruleId == R_atom_paren_f
                && (ap.nodes.length == 0 || ap.nodes[0] instanceof ParseNode.Null)) {
            return false;
        }
        if (n instanceof ParseNode.SmallInt si) return si.value != 0;
        if (n instanceof ParseNode.Str st) return !st.name.isEmpty();
        if (n instanceof ParseNode.Token t) {
            if (t.tok == Tok.KW_TRUE) return true;
            if (t.tok == Tok.KW_FALSE || t.tok == Tok.KW_NONE) return false;
            if (t.tok == Tok.ELLIPSIS) return true;
        }
        if (n instanceof ParseNode.Const c) {
            MpConst v = c.value;
            if (v instanceof MpConst.Int i) return i.value().signum() != 0;
            if (v instanceof MpConst.Float f) return f.value() != 0.0;
            if (v instanceof MpConst.Str s2) return !s2.value().isEmpty();
            if (v instanceof MpConst.Bytes b) return b.value().length != 0;
            if (v instanceof MpConst.Tuple tp) return !tp.items().isEmpty();
            if (v instanceof MpConst.Bool bo) return bo.value();
            if (v instanceof MpConst.None) return false;
            if (v instanceof MpConst.Ellipsis) return true;
            if (v instanceof MpConst.Complex cx) return cx.real() != 0.0 || cx.imag() != 0.0;
        }
        return null;
    }

    // rule numbers for const-tuple building
    private final int R_testlist_star_expr_t = Grammar.number("testlist_star_expr");
    private final int R_testlist_t = Grammar.number("testlist");
    private final int R_subscriptlist_t = Grammar.number("subscriptlist");
    private final int R_testlist_comp_t = Grammar.number("testlist_comp");
    private final int R_testlist_comp_3c_t = Grammar.number("testlist_comp_3c");
    private final int R_atom_paren_t = Grammar.number("atom_paren");

    private int peekRuleId(int pos) {
        int i = ruleStack.size() - 1 - pos;
        return (i >= 0) ? ruleStack.get(i).ruleId : -1;
    }

    /** parse.c build_tuple: fold an all-constant tuple into a single const object. */
    private boolean buildTuple(int srcLine, int ruleId, int numArgs) {
        if (ruleId == R_testlist_comp_t && peekRuleId(0) == R_atom_paren_t) {
            return buildTupleFromStack(srcLine, numArgs);          // "(a,)"
        }
        if (ruleId == R_testlist_comp_3c_t && peekRuleId(2) == R_atom_paren_t) {
            if (buildTupleFromStack(srcLine, numArgs)) {
                popRule(); popRule();                              // discard 2 rules
                return true;
            }
        }
        if (ruleId == R_testlist_star_expr_t || ruleId == R_testlist_t
                || ruleId == R_subscriptlist_t) {
            return buildTupleFromStack(srcLine, numArgs);          // "x = a, b" etc.
        }
        return false;
    }

    private boolean buildTupleFromStack(int srcLine, int numArgs) {
        for (int i = 0; i < numArgs; i++) {
            if (constValueOf(peekResult(i)) == null) return false;
        }
        MpConst[] items = new MpConst[numArgs];
        for (int i = numArgs; i > 0; ) {
            items[--i] = constValueOf(popResult());
        }
        pushResult(new ParseNode.Const(srcLine,
                new MpConst.Tuple(java.util.List.of(items))));
        return true;
    }

    /** The constant value of a parse node, or null if it is not a constant. */
    private MpConst constValueOf(ParseNode n) {
        if (n instanceof ParseNode.SmallInt si) return new MpConst.Int(BigInteger.valueOf(si.value));
        if (n instanceof ParseNode.Str st) return new MpConst.Str(st.name);
        if (n instanceof ParseNode.Const c) return c.value;
        if (n instanceof ParseNode.Token t) {
            if (t.tok == Tok.KW_NONE) return MpConst.None.INSTANCE;
            if (t.tok == Tok.KW_TRUE) return new MpConst.Bool(true);
            if (t.tok == Tok.KW_FALSE) return new MpConst.Bool(false);
            if (t.tok == Tok.ELLIPSIS) return MpConst.Ellipsis.INSTANCE;
        }
        // "()" is the empty-tuple constant
        if (n instanceof ParseNode.Struct ap && ap.ruleId == R_atom_paren_t
                && (ap.nodes.length == 0 || ap.nodes[0] instanceof ParseNode.Null)) {
            return new MpConst.Tuple(java.util.List.of());
        }
        return null;
    }

    /** make_node_const_object_optimised for integers: small-int leaf if it fits. */
    private ParseNode makeIntNode(int srcLine, BigInteger v) {
        if (SmallInt.fits(v)) return new ParseNode.SmallInt(v.longValue());
        return new ParseNode.Const(srcLine, new MpConst.Int(v));
    }

    /** Parse a Python integer literal (dec/hex/oct/bin, underscores) to BigInteger. */
    static BigInteger parseInteger(String s) {
        s = s.replace("_", "");
        int radix = 10, off = 0;
        if (s.length() >= 2 && s.charAt(0) == '0') {
            char c = Character.toLowerCase(s.charAt(1));
            if (c == 'x') { radix = 16; off = 2; }
            else if (c == 'o') { radix = 8; off = 2; }
            else if (c == 'b') { radix = 2; off = 2; }
        }
        String body = s.substring(off);
        if (body.isEmpty()) body = "0";
        return new BigInteger(body, radix);
    }

    // --- rule node building (push_result_rule; folding/tuple deferred) ---
    private void pushResultRule(int srcLine, int ruleId, int numArgs) {
        if (ruleId == R_atom_paren) {
            ParseNode pn = peekResult(0);
            if (pn instanceof ParseNode.Null) {
                // keep () 
            } else if (pn instanceof ParseNode.Struct s && s.ruleId == R_testlist_comp) {
                // keep (a, b, ...)
            } else {
                return; // (expr) -> expr
            }
        } else if (ruleId == R_testlist_comp) {
            ParseNode pn = peekResult(0);
            if (pn instanceof ParseNode.Struct s) {
                if (s.ruleId == R_testlist_comp_3b) {
                    popResult(); --numArgs;
                } else if (s.ruleId == R_testlist_comp_3c) {
                    // 3c pushed itself twice; pop the top duplicate and mutate the
                    // remaining (same) struct's kind in place, exactly like parse.c.
                    popResult();
                    ((ParseNode.Struct) peekResult(0)).ruleId = R_testlist_comp;
                    return;
                }
            }
        } else if (ruleId == R_testlist_comp_3c) {
            ++numArgs;
        }

        // logical constant folding (parse.c fold_logical_constants): "0 and x" -> 0
        int[] na = new int[]{numArgs};
        if (foldLogicalConstants(ruleId, na)) return;
        numArgs = na[0];

        // constant folding (parse.c fold_constants): 1 + 2 -> 3, etc.
        if (foldConstants(ruleId, numArgs)) return;

        // const tuple building (parse.c build_tuple): 1, 2 -> a const tuple object
        if (buildTuple(srcLine, ruleId, numArgs)) return;

        // MICROPY_COMP_CONST: id = const(value) declares a compile-time constant
        if (ruleId == R_expr_stmt && numArgs == 2 && declareConst()) return;

        ParseNode[] nodes = new ParseNode[numArgs];
        for (int k = numArgs; k > 0; k--) nodes[k - 1] = popResult();
        ParseNode.Struct st = new ParseNode.Struct(ruleId, srcLine, nodes);
        if (ruleId == R_testlist_comp_3c) pushResult(st);
        pushResult(st);
    }
}
