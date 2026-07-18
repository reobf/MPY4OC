package net.mpy.compiler.compile;

import net.mpy.compiler.emit.Emitter;
import net.mpy.compiler.emit.Operators;
import net.mpy.compiler.emit.Prelude;
import net.mpy.compiler.io.ByteBuf;
import net.mpy.compiler.parse.Grammar;
import net.mpy.compiler.parse.ParseNode;
import net.mpy.compiler.parse.Parser;
import net.mpy.compiler.persist.CompiledModule;
import net.mpy.compiler.persist.MpySerializer;
import net.mpy.compiler.persist.RawCode;
import net.mpy.compiler.qstr.QstrTable;

import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end compiler: module + top-level functions with params, name assigns,
 * small-int/string constants, binary arithmetic, function calls, and return.
 * Produces a complete .mpy verified byte-identical to mpy-cross.
 *
 * qstr interning replicates mpy-cross's two-phase order (found by tracing
 * mp_emit_common_use_qstr): during the SCOPE pass, each scope's simple_name and
 * its STRING/const qstrs are interned (all scopes in order); then during the
 * emit passes, each scope's arg names and NAME qstrs (LOAD/STORE_NAME/GLOBAL)
 * are interned. So order = source, [per scope: simple_name, string consts],
 * [per scope: arg names, name qstrs].
 */
public final class ModuleCompiler {

    private enum Phase { INTERN_CONST, INTERN_NAME, EMIT }

    private final QstrTable qstrs = new QstrTable();
    private final List<net.mpy.compiler.model.MpConst> objs = new ArrayList<>();
    private ScopeAnalyzer analysis;
    private final List<int[]> loopStack = new ArrayList<>(); // {breakLabel, continueLabel, isFor}

    private final int R_file_input = Grammar.number("file_input");
    private final int R_file_input_2 = Grammar.number("file_input_2");
    private final int R_expr_stmt = Grammar.number("expr_stmt");
    private final int R_funcdef = Grammar.number("funcdef");
    private final int R_lambdef = Grammar.number("lambdef");
    private final int R_classdef = Grammar.number("classdef");
    private final int R_classdef_2 = Grammar.number("classdef_2");
    private final int R_return_stmt = Grammar.number("return_stmt");
    private final int R_suite = Grammar.number("suite_block_stmts");
    private final int R_simple_stmt_2 = Grammar.number("simple_stmt_2");
    private final int R_pass_stmt = Grammar.number("pass_stmt");
    private final int R_arith_expr = Grammar.number("arith_expr");
    private final int R_term = Grammar.number("term");
    private final int R_atom_expr_normal = Grammar.number("atom_expr_normal");
    private final int R_trailer_paren = Grammar.number("trailer_paren");
    private final int R_arglist = Grammar.number("arglist");
    private final int R_if_stmt = Grammar.number("if_stmt");
    private final int R_if_stmt_elif = Grammar.number("if_stmt_elif");
    private final int R_if_stmt_elif_list = Grammar.number("if_stmt_elif_list");
    private final int R_while_stmt = Grammar.number("while_stmt");
    private final int R_for_stmt = Grammar.number("for_stmt");
    private final int R_break_stmt = Grammar.number("break_stmt");
    private final int R_continue_stmt = Grammar.number("continue_stmt");
    private final int R_with_stmt = Grammar.number("with_stmt");
    private final int R_with_stmt_list = Grammar.number("with_stmt_list");
    private final int R_with_item = Grammar.number("with_item");
    private final int R_try_stmt = Grammar.number("try_stmt");
    private final int R_try_stmt_except = Grammar.number("try_stmt_except");
    private final int R_try_stmt_except_and_more = Grammar.number("try_stmt_except_and_more");
    private final int R_try_stmt_except_list = Grammar.number("try_stmt_except_list");
    private final int R_try_stmt_as_name = Grammar.number("try_stmt_as_name");
    private final int R_try_stmt_finally = Grammar.number("try_stmt_finally");
    private final int R_expr_stmt_augassign = Grammar.number("expr_stmt_augassign");
    private final int R_annassign = Grammar.number("annassign");
    private final int R_comparison = Grammar.number("comparison");
    private final int R_comp_op_not_in = Grammar.number("comp_op_not_in");
    private final int R_comp_op_is = Grammar.number("comp_op_is");
    private final int R_raise_stmt = Grammar.number("raise_stmt");
    private final int R_assert_stmt = Grammar.number("assert_stmt");
    private final int R_del_stmt = Grammar.number("del_stmt");
    private final int R_yield_stmt = Grammar.number("yield_stmt");
    private final int R_yield_expr = Grammar.number("yield_expr");
    private final int R_async_stmt = Grammar.number("async_stmt");
    private final int R_async_funcdef = Grammar.number("async_funcdef");
    private final int R_atom_expr_await = Grammar.number("atom_expr_await");
    private final int R_yield_arg_from = Grammar.number("yield_arg_from");
    private final int R_import_name = Grammar.number("import_name");
    private final int R_import_from = Grammar.number("import_from");
    private final int R_dotted_as_names = Grammar.number("dotted_as_names");
    private final int R_dotted_as_name = Grammar.number("dotted_as_name");
    private final int R_import_as_names = Grammar.number("import_as_names");
    private final int R_import_from_2b = Grammar.number("import_from_2b");
    private final int R_one_or_more_period_or_ellipsis = Grammar.number("one_or_more_period_or_ellipsis");
    private final int R_exprlist = Grammar.number("exprlist");
    private final int R_star_expr = Grammar.number("star_expr");
    private final int R_testlist = Grammar.number("testlist");
    private final int R_test_if_expr = Grammar.number("test_if_expr");
    private final int R_namedexpr = Grammar.number("namedexpr_test");
    private final int R_subscriptlist = Grammar.number("subscriptlist");
    private final int R_subscript_2 = Grammar.number("subscript_2");
    private final int R_subscript_3 = Grammar.number("subscript_3");
    private final int R_subscript_3c = Grammar.number("subscript_3c");
    private final int R_subscript_3d = Grammar.number("subscript_3d");
    private final int R_raise_stmt_arg = Grammar.number("raise_stmt_arg");
    private final int R_expr = Grammar.number("expr");
    private final int R_xor_expr = Grammar.number("xor_expr");
    private final int R_and_expr = Grammar.number("and_expr");
    private final int R_power = Grammar.number("power");
    private final int R_shift_expr = Grammar.number("shift_expr");
    private final int R_and_test = Grammar.number("and_test");
    private final int R_or_test = Grammar.number("or_test");
    private final int R_not_test_2 = Grammar.number("not_test_2");
    private final int R_factor = Grammar.number("factor_2");
    private final int R_testlist_star_expr = Grammar.number("testlist_star_expr");
    private final int R_expr_stmt_assign = Grammar.number("expr_stmt_assign_list");
    private final int R_atom_bracket = Grammar.number("atom_bracket");
    private final int R_atom_paren = Grammar.number("atom_paren");
    private final int R_atom_brace = Grammar.number("atom_brace");
    private final int R_testlist_comp = Grammar.number("testlist_comp");
    private final int R_dictorsetmaker = Grammar.number("dictorsetmaker");
    private final int R_dictorsetmaker_item = Grammar.number("dictorsetmaker_item");
    private final int R_comp_if = Grammar.number("comp_if");
    private final int R_comp_for = Grammar.number("comp_for");
    private final int R_dictorsetmaker_list = Grammar.number("dictorsetmaker_list");
    private final int R_dictorsetmaker_list2 = Grammar.number("dictorsetmaker_list2");
    private final int R_argument = Grammar.number("argument");
    private final int R_argument_3 = Grammar.number("argument_3");
    private static final int SMALL_INT_BITS = 31; // MP_SMALL_INT_BITS for a 32-bit small int
    private final int R_arglist_star = Grammar.number("arglist_star");
    private final int R_arglist_dbl_star = Grammar.number("arglist_dbl_star");
    private final int R_typedargslist = Grammar.number("typedargslist");
    private final int R_typedargslist_name = Grammar.number("typedargslist_name");
    private final int R_varargslist = Grammar.number("varargslist");
    private final int R_varargslist_dbl_star = Grammar.number("varargslist_dbl_star");
    private final int R_varargslist_star = Grammar.number("varargslist_star");
    private final int R_typedargslist_dbl_star = Grammar.number("typedargslist_dbl_star");
    private final int R_typedargslist_star = Grammar.number("typedargslist_star");
    private final int R_varargslist_name = Grammar.number("varargslist_name");
    private final int R_decorated = Grammar.number("decorated");
    private final int R_decorator = Grammar.number("decorator");
    private final int R_decorators = Grammar.number("decorators");
    private final int R_dotted_name = Grammar.number("dotted_name");
    private final int R_trailer_period = Grammar.number("trailer_period");
    private final int R_trailer_bracket = Grammar.number("trailer_bracket");
    private final int R_atom_expr_trailers = Grammar.number("atom_expr_trailers");

    /** A compilation unit: one scope + its statement list. */
    private final class Unit {
        final Scope scope;
        final String name;
        final List<ParseNode> stmts;
        final Unit parent;
        final List<Unit> children = new ArrayList<>();
        boolean isLambda = false;
        boolean isClass = false;
        String className = null;
        int classSrcLine = 1;
        ParseNode lambdaBody = null;
        int lambdaSrcLine = 1;
        boolean isComp = false;
        ParseNode.Struct compAtom = null;
        Scope.Kind compKind = null;
        Emitter emit;
        RawCode rawCode;
        Unit(Scope scope, String name, List<ParseNode> stmts, Unit parent) {
            this.scope = scope; this.name = name; this.stmts = stmts; this.parent = parent;
        }
    }

    private final List<Unit> units = new ArrayList<>(); // in creation order (module first)

    public static byte[] compile(String source, String sourceName) {
        return new ModuleCompiler().run(Parser.parseFile(source, sourceName), sourceName);
    }

    private byte[] run(ParseNode root, String sourceName) {
        analysis = ScopeAnalyzer.run(root);

        // build units (module + functions) in creation order
        List<ParseNode> moduleStmts = new ArrayList<>();
        collectStmts(root, moduleStmts);
        Unit module = new Unit(analysis.moduleScope(), "<module>", moduleStmts, null);
        units.add(module);
        buildChildUnits(module);

        // Order units by the analyzer's scope-creation order (MicroPython's scope chain),
        // which is what determines qstr interning order.
        List<Unit> ordered = new ArrayList<>();
        for (Scope sc : analysis.scopes()) {
            for (Unit u : units) if (u.scope == sc) { ordered.add(u); break; }
        }
        for (Unit u : units) if (!ordered.contains(u)) ordered.add(u);

        // qstr phase A: source, then per scope simple_name + string consts
        qstrs.intern(sourceName);
        for (Unit u : ordered) {
            qstrs.intern(u.name);
            if (u.isLambda) walkExpr(u, u.lambdaBody, Phase.INTERN_CONST);
            else if (u.isComp) walkCompBody(u, Phase.INTERN_CONST);
            else for (ParseNode st : u.stmts) walkStmt(u, st, Phase.INTERN_CONST);
        }
        // qstr phase B: per scope arg names + name qstrs
        for (Unit u : ordered) {
            for (int i = 0; i < u.scope.numPosArgs + u.scope.numKwonlyArgs; i++)
                qstrs.intern(argName(u.scope, i));
            if (u.isLambda) walkExpr(u, u.lambdaBody, Phase.INTERN_NAME);
            else if (u.isComp) walkCompBody(u, Phase.INTERN_NAME);
            else {
                if (u.isClass) { // class body prologue names come first
                    internName(u, "__name__");
                    internName(u, "__module__");
                    internName(u, "__qualname__");
                }
                for (ParseNode st : u.stmts) walkStmt(u, st, Phase.INTERN_NAME);
            }
        }

        // emit phase: bytecode per unit
        for (Unit u : units) emitUnit(u);
        // link children (already in creation order == MAKE_FUNCTION index order)
        for (Unit u : units) {
            for (Unit c : u.children) u.rawCode.children.add(c.rawCode);
        }
        return MpySerializer.serialize(new CompiledModule(qstrs, objs, module.rawCode));
    }

    private void buildChildUnits(Unit parent) {
        for (ParseNode st : parent.stmts) buildUnits(parent, st);
    }

    /** Recursively create child units at funcdef/lambdef boundaries (anywhere in the tree). */
    private void buildUnits(Unit parent, ParseNode node) {
        if (!(node instanceof ParseNode.Struct s)) return;
        if (s.ruleId == R_decorated) {
            buildUnits(parent, s.nodes[1]);
            return;
        }
        if (s.ruleId == R_async_stmt || s.ruleId == R_async_funcdef) {
            buildUnits(parent, s.nodes[0]);
            return;
        }
        if (s.ruleId == R_classdef) {
            String name = ((ParseNode.Id) s.nodes[0]).name;
            Scope cscope = analysis.funcScope(s);
            List<ParseNode> body = new ArrayList<>();
            collectSuite(s.nodes.length > 2 ? s.nodes[2] : ParseNode.Null.INSTANCE, body);
            Unit child = new Unit(cscope, name, body, parent);
            child.isClass = true;
            child.className = name;
            child.classSrcLine = s.srcLine;
            units.add(child);
            parent.children.add(child);
            for (ParseNode st : body) buildUnits(child, st);
            if (s.nodes.length > 1) buildUnits(parent, s.nodes[1]); // parent-class exprs
            return;
        }
        if (s.ruleId == R_funcdef) {
            // Child indices follow EMIT order, and default values are emitted before
            // the enclosing function's MAKE_FUNCTION, so any lambda/comprehension in
            // a default becomes a child of the parent first.
            buildUnits(parent, s.nodes.length > 1 ? s.nodes[1] : ParseNode.Null.INSTANCE);
            String name = ((ParseNode.Id) s.nodes[0]).name;
            Scope fscope = analysis.funcScope(s);
            List<ParseNode> body = new ArrayList<>();
            ParseNode suite = s.nodes.length > 3 ? s.nodes[3] : ParseNode.Null.INSTANCE;
            collectSuite(suite, body);
            Unit child = new Unit(fscope, name, body, parent);
            units.add(child);
            parent.children.add(child);
            for (ParseNode st : body) buildUnits(child, st);   // nested scopes in the body
            return;
        }
        if (s.ruleId == R_lambdef) {
            Scope lscope = analysis.funcScope(s);
            Unit child = new Unit(lscope, "<lambda>", new ArrayList<>(), parent);
            child.isLambda = true;
            child.lambdaBody = s.nodes[1];
            child.lambdaSrcLine = s.srcLine; // lambdef line (start of the lambda)
            buildUnits(parent, s.nodes[0]);     // lambda param defaults come first
            units.add(child);
            parent.children.add(child);
            buildUnits(child, s.nodes[1]);      // nested scopes in the lambda body
            return;
        }
        Scope.Kind ck = analysis.comprehensionKind(s);
        if (ck != null) {
            Scope cscope = analysis.funcScope(s);
            Unit child = new Unit(cscope, analysis.compSimpleNameFor(ck), new ArrayList<>(), parent);
            child.isComp = true;
            child.compAtom = s;
            child.compKind = ck;
            units.add(child);
            parent.children.add(child);
            // nested scopes inside the comp body (inner expr, nested iterables) belong to the comp;
            // the FIRST iterable belongs to the parent
            ParseNode.Struct compFor = analysis.comprehensionCompFor(s);
            buildUnits(parent, compFor.nodes[1]);           // first iterable (parent scope)
            buildUnits(child, analysis.comprehensionInner(s)); // inner expr (comp scope)
            for (int k = 2; k < compFor.nodes.length; k++) buildUnits(child, compFor.nodes[k]);
            return;
        }
        for (ParseNode c : s.nodes) buildUnits(parent, c);
    }

    // --- unit emission ---

    private void emitUnit(Unit u) {
        u.emit = new Emitter(qstrs);
        int[] prev = null;
        for (int pass = 0; pass < 12; pass++) {
            if (pass > 0) u.emit.nextPass();
            u.emit.resetForPass();
            if (u.isLambda) {
                u.emit.setSourceLine(u.lambdaSrcLine);
                walkExpr(u, u.lambdaBody, Phase.EMIT);
                if ((u.scope.scopeFlags & Scope.FLAG_GENERATOR) != 0) {
                    // a generator lambda returns None, not the body's value
                    u.emit.popTop();
                    u.emit.loadConstNone();
                }
                u.emit.returnValue();
            } else if (u.isComp) {
                walkCompBody(u, Phase.EMIT);
            } else if (u.isClass) {
                // class body prologue: __module__ = __name__; __qualname__ = "C"
                emitLoadOrIntern(u, "__name__", Phase.EMIT);
                emitStore(u, "__module__");
                u.emit.loadConstString(u.className);
                emitStore(u, "__qualname__");
                for (ParseNode st : u.stmts) walkStmt(u, st, Phase.EMIT);
                // epilogue: return __class__ (None if it never became a cell)
                Scope.IdInfo cls = u.scope.find("__class__");
                if (cls != null && cls.kind == Scope.IdKind.LOCAL) u.emit.loadConstNone();
                else if (cls != null) u.emit.loadFast(cls.localNum);
                else u.emit.loadConstNone();
                u.emit.returnValue();
            } else {
                for (ParseNode st : u.stmts) walkStmt(u, st, Phase.EMIT);
                u.emit.loadConstNone();
                u.emit.returnValue();
            }
            int[] now = u.emit.labelSnapshot();
            if (prev != null && java.util.Arrays.equals(prev, now)) break;
            prev = now;
        }

        int nState = u.scope.numLocals + u.emit.maxStackSize();
        if (nState == 0) nState = 1;

        ByteBuf codeInfoBody = new ByteBuf();
        codeInfoBody.vuint(qstrs.intern(u.name));
        for (int i = 0; i < u.scope.numPosArgs + u.scope.numKwonlyArgs; i++)
            codeInfoBody.vuint(qstrs.intern(argName(u.scope, i)));
        codeInfoBody.bytes(u.emit.lineInfo());
        int nInfo = codeInfoBody.size();

        // closure section: one byte per CELL id (its local_num)
        ByteBuf cellInfo = new ByteBuf();
        int nCell = 0;
        for (Scope.IdInfo id : u.scope.idInfo) {
            if (id.kind == Scope.IdKind.CELL) { cellInfo.u8(id.localNum); nCell++; }
        }

        ByteBuf funData = new ByteBuf();
        Prelude.encodeSig(funData, nState, u.emit.maxExcStackSize(), u.scope.scopeFlags, u.scope.numPosArgs, u.scope.numKwonlyArgs, u.scope.numDefPosArgs);
        Prelude.encodeSize(funData, nInfo, nCell);
        funData.bytes(codeInfoBody.toByteArray());
        funData.bytes(cellInfo.toByteArray());
        funData.bytes(u.emit.bytecode());
        u.rawCode = new RawCode(funData.toByteArray());
    }

    // --- unified walk: interns or emits depending on phase ---

    private void walkStmt(Unit u, ParseNode st, Phase ph) {
        if (!(st instanceof ParseNode.Struct s)) return;
        if (ph == Phase.EMIT) u.emit.setSourceLine(s.srcLine);
        if (s.ruleId == R_expr_stmt) {
            if (s.nodes.length == 2 && s.nodes[1] instanceof ParseNode.Struct aug && aug.ruleId == R_expr_stmt_augassign) {
                // target OP= value. For attribute/subscript targets the base (and
                // index) are evaluated once and kept on the stack, as c_assign does
                // with ASSIGN_AUG_LOAD / ASSIGN_AUG_STORE.
                boolean trailerTarget = s.nodes[0] instanceof ParseNode.Struct t0
                        && t0.ruleId == R_atom_expr_normal;
                if (trailerTarget) {
                    walkAugTarget(u, (ParseNode.Struct) s.nodes[0], ph, true);   // AUG_LOAD
                } else {
                    walkExpr(u, s.nodes[0], ph);
                }
                walkExpr(u, aug.nodes[1], ph);    // value
                if (ph == Phase.EMIT) u.emit.binaryOp(augBinaryOpIndex(aug.nodes[0]));
                if (trailerTarget) {
                    walkAugTarget(u, (ParseNode.Struct) s.nodes[0], ph, false);  // AUG_STORE
                } else {
                    walkStore(u, s.nodes[0], ph);
                }
            } else if (s.nodes.length == 2 && s.nodes[1] instanceof ParseNode.Struct ann
                    && ann.ruleId == R_annassign) {
                // "x: T" declares only; "x: T = v" is a plain assignment (annotation ignored)
                if (ann.nodes.length > 1 && !(ann.nodes[1] instanceof ParseNode.Null)) {
                    walkExpr(u, ann.nodes[1], ph);
                    walkStore(u, s.nodes[0], ph);
                }
            } else if (s.nodes.length == 2 && s.nodes[1] instanceof ParseNode.Struct ch && ch.ruleId == R_expr_stmt_assign) {
                // x = y = ... = value : assign_list is flat [t1..tn, value]
                List<ParseNode> targets = new ArrayList<>();
                targets.add(s.nodes[0]);
                for (int i = 0; i < ch.nodes.length - 1; i++) targets.add(ch.nodes[i]);
                ParseNode value = ch.nodes[ch.nodes.length - 1];
                walkExpr(u, value, ph);
                for (int i = 0; i < targets.size(); i++) {
                    if (ph == Phase.EMIT && i < targets.size() - 1) u.emit.dupTop();
                    walkStore(u, targets.get(i), ph);
                }
            } else if (s.nodes.length == 2 && !(s.nodes[1] instanceof ParseNode.Null)) {
                if (!walkTupleToTupleAssign(u, s.nodes[0], s.nodes[1], ph)) {
                    walkExpr(u, s.nodes[1], ph);
                    walkStore(u, s.nodes[0], ph);
                }
            } else {
                walkExpr(u, s.nodes[0], ph);
                if (ph == Phase.EMIT) u.emit.popTop();
            }
        } else if (s.ruleId == R_funcdef) {
            makeFunctionObject(u, s, ph);
            if (ph == Phase.EMIT) emitStore(u, ((ParseNode.Id) s.nodes[0]).name);
            else if (ph == Phase.INTERN_NAME) internName(u, ((ParseNode.Id) s.nodes[0]).name);
        } else if (s.ruleId == R_decorated) {
            walkDecorated(u, s, ph);
        } else if (s.ruleId == R_classdef) {
            makeClassObject(u, s, ph);
            String name = ((ParseNode.Id) s.nodes[0]).name;
            if (ph == Phase.EMIT) emitStore(u, name);
            else if (ph == Phase.INTERN_NAME) internName(u, name);
        } else if (s.ruleId == R_import_name) {
            walkImportName(u, s, ph);
        } else if (s.ruleId == R_import_from) {
            walkImportFrom(u, s, ph);
        } else if (s.ruleId == R_async_stmt) {
            ParseNode inner = s.nodes[0];
            if (inner instanceof ParseNode.Struct fs && fs.ruleId == R_for_stmt) {
                walkAsyncFor(u, fs, ph);
            } else if (inner instanceof ParseNode.Struct ws && ws.ruleId == R_with_stmt) {
                List<ParseNode> items = new ArrayList<>();
                collectWithItems(ws.nodes[0], items);
                walkAsyncWithItems(u, items, 0, ws.nodes[ws.nodes.length - 1], ph);
            } else {
                walkStmt(u, inner, ph);
            }
        } else if (s.ruleId == R_yield_stmt) {
            for (ParseNode c : s.nodes) walkExpr(u, c, ph);
            if (ph == Phase.EMIT) u.emit.popTop();     // statement form discards the value
        } else if (s.ruleId == R_del_stmt) {
            for (ParseNode c : s.nodes) walkDel(u, c, ph);
        } else if (s.ruleId == R_assert_stmt) {
            // assert cond[, msg]  ->  jump past on truth, else raise AssertionError
            ParseNode msg = s.nodes.length > 1 ? s.nodes[1] : ParseNode.Null.INSTANCE;
            if (ph != Phase.EMIT) {
                walkExpr(u, s.nodes[0], ph);
                if (ph == Phase.INTERN_CONST) qstrs.intern("AssertionError");
                if (!(msg instanceof ParseNode.Null)) walkExpr(u, msg, ph);
            } else {
                int lEnd = u.emit.newLabel();
                cIfCond(u, s.nodes[0], true, lEnd);
                // LOAD_GLOBAL (not load_id), to match CPython
                u.emit.loadGlobalKind("AssertionError", 1);
                if (!(msg instanceof ParseNode.Null)) {
                    walkExpr(u, msg, ph);
                    u.emit.callFunction(1, 0);
                }
                u.emit.raiseVarargs(1);
                u.emit.labelAssign(lEnd);
            }
        } else if (s.ruleId == R_raise_stmt) {
            ParseNode arg = s.nodes.length > 0 ? s.nodes[0] : ParseNode.Null.INSTANCE;
            if (arg instanceof ParseNode.Null) {
                if (ph == Phase.EMIT) u.emit.raiseVarargs(0);            // bare raise
            } else if (arg instanceof ParseNode.Struct ra && ra.ruleId == R_raise_stmt_arg) {
                walkExpr(u, ra.nodes[0], ph);                            // raise X from Y
                walkExpr(u, ra.nodes[1], ph);
                if (ph == Phase.EMIT) u.emit.raiseVarargs(2);
            } else {
                walkExpr(u, arg, ph);                                    // raise X
                if (ph == Phase.EMIT) u.emit.raiseVarargs(1);
            }
        } else if (s.ruleId == R_return_stmt) {
            if (u.scope.kind != Scope.Kind.FUNCTION) {
                throw new net.mpy.compiler.MpCompileException("'return' outside function");
            }
            ParseNode rv = s.nodes.length > 0 ? s.nodes[0] : ParseNode.Null.INSTANCE;
            if (rv instanceof ParseNode.Null) {
                if (ph == Phase.EMIT) u.emit.loadConstNone();   // bare "return"
                if (ph == Phase.EMIT) u.emit.returnValue();
            } else if (rv instanceof ParseNode.Struct te && te.ruleId == R_test_if_expr
                    && ph == Phase.EMIT) {
                // MICROPY_COMP_RETURN_IF_EXPR: "return a if c else b" pushes the
                // return into both arms instead of jumping over the else branch
                ParseNode.Struct ifElse = (ParseNode.Struct) te.nodes[1];
                int lFail = u.emit.newLabel();
                cIfCond(u, ifElse.nodes[0], false, lFail);
                walkExpr(u, te.nodes[0], ph);
                u.emit.returnValue();
                u.emit.labelAssign(lFail);
                walkExpr(u, ifElse.nodes[1], ph);
                u.emit.returnValue();
            } else {
                walkExpr(u, rv, ph);
                if (ph == Phase.EMIT) u.emit.returnValue();
            }
        } else if (s.ruleId == R_if_stmt) {
            walkIf(u, s, ph);
        } else if (s.ruleId == R_while_stmt) {
            walkWhile(u, s, ph);
        } else if (s.ruleId == R_for_stmt) {
            walkFor(u, s, ph);
        } else if (s.ruleId == R_with_stmt) {
            walkWith(u, s, ph);
        } else if (s.ruleId == R_try_stmt) {
            walkTry(u, s, ph);
        } else if (s.ruleId == R_break_stmt) {
            // loopStack is only maintained in the emit pass, so check there
            if (ph == Phase.EMIT && loopStack.isEmpty()) {
                throw new net.mpy.compiler.MpCompileException("'break'/'continue' outside loop");
            }
            if (ph == Phase.EMIT && !loopStack.isEmpty()) {
                int[] ctx = loopStack.get(loopStack.size() - 1);
                u.emit.unwindJump(ctx[0], u.emit.excLevel() - ctx[3], ctx[2] == 1);
            }
        } else if (s.ruleId == R_continue_stmt) {
            if (ph == Phase.EMIT && loopStack.isEmpty()) {
                throw new net.mpy.compiler.MpCompileException("'break'/'continue' outside loop");
            }
            if (ph == Phase.EMIT && !loopStack.isEmpty()) {
                int[] ctx = loopStack.get(loopStack.size() - 1);
                u.emit.unwindJump(ctx[1], u.emit.excLevel() - ctx[3], false);
            }
        } else if (s.ruleId == R_suite || s.ruleId == R_simple_stmt_2) {
            // suite body, or several statements separated by ";"
            for (ParseNode c : s.nodes) walkStmt(u, c, ph);
        } else if (s.ruleId == R_pass_stmt) {
            // nothing to emit
        } else {
            // Unknown statement wrapper: recurse rather than silently dropping it.
            for (ParseNode c : s.nodes) walkStmt(u, c, ph);
        }
    }

    /** if cond: body (elif cond: body)* [else: body]  -- faithful compile_if_stmt. */
    private void walkIf(Unit u, ParseNode.Struct s, Phase ph) {
        ParseNode cond = s.nodes[0];
        ParseNode body = s.nodes[1];
        ParseNode elifNode = s.nodes.length > 2 ? s.nodes[2] : ParseNode.Null.INSTANCE;
        ParseNode elseBody = s.nodes.length > 3 ? s.nodes[3] : ParseNode.Null.INSTANCE;
        List<ParseNode> elifs = new ArrayList<>();
        collectElifs(elifNode, elifs);
        boolean hasElse = !(elseBody instanceof ParseNode.Null);
        boolean emit = ph == Phase.EMIT;

        int lEnd = emit ? u.emit.newLabel() : -1;

        // optimisation: emit nothing at all for "if False"
        if (!isConstFalse(cond)) {
            int lFail = emit ? u.emit.newLabel() : -1;
            if (emit) cIfCond(u, cond, false, lFail);
            // c_if_cond returns immediately for a constant condition, and that same
            // code runs in BOTH passes in CPython/MicroPython -- so a constant
            // condition's expression is never visited. Walking it here would intern
            // its literal into the qstr table even though no bytecode references it,
            // shifting every later index and breaking byte-identity.
            else if (constTruthiness(cond) == null) walkExpr(u, cond, ph);
            walkStmt(u, body, ph);
            if (isConstTrue(cond)) return;      // "if True": nothing else can run
            if (!elifs.isEmpty() || hasElse) {
                if (emit) u.emit.jumpTo(lEnd);
            }
            if (emit) u.emit.labelAssign(lFail);
        }

        for (ParseNode e : elifs) {
            ParseNode.Struct es = (ParseNode.Struct) e;
            if (isConstFalse(es.nodes[0])) continue;   // "elif False": emit nothing
            int lFail2 = emit ? u.emit.newLabel() : -1;
            if (emit) cIfCond(u, es.nodes[0], false, lFail2);
            else if (constTruthiness(es.nodes[0]) == null) walkExpr(u, es.nodes[0], ph);
            walkStmt(u, es.nodes[1], ph);
            if (isConstTrue(es.nodes[0])) return;      // "elif True": nothing else runs
            if (emit) { u.emit.jumpTo(lEnd); u.emit.labelAssign(lFail2); }
        }

        if (hasElse) walkStmt(u, elseBody, ph);
        if (emit) u.emit.labelAssign(lEnd);
    }

    /** Flatten nodes[2] of an if_stmt into individual if_stmt_elif nodes. */
    private void collectElifs(ParseNode n, List<ParseNode> out) {
        if (n instanceof ParseNode.Struct s) {
            if (s.ruleId == R_if_stmt_elif) {
                out.add(s);
            } else if (s.ruleId == R_if_stmt_elif_list) {
                for (ParseNode c : s.nodes) collectElifs(c, out);
            }
        }
    }

    /** while cond: body  (else clause deferred). */
    /**
     * compile_for_stmt_optimised_range: "for x in range(...)" becomes an explicitly
     * incremented variable, which allocates nothing on the heap. Returns false if
     * the loop doesn't match the pattern.
     */
    private boolean tryOptimisedRangeFor(Unit u, ParseNode.Struct s, Phase ph) {
        if (!(s.nodes[0] instanceof ParseNode.Id)) return false;
        if (!(s.nodes[1] instanceof ParseNode.Struct it) || it.ruleId != R_atom_expr_normal) return false;
        if (!(it.nodes[0] instanceof ParseNode.Id fn) || !fn.name.equals("range")) return false;
        if (!(it.nodes[1] instanceof ParseNode.Struct tp) || tp.ruleId != R_trailer_paren) return false;

        List<ParseNode> args = new ArrayList<>();
        ParseNode argsNode = tp.nodes.length > 0 ? tp.nodes[0] : ParseNode.Null.INSTANCE;
        if (argsNode instanceof ParseNode.Struct al && al.ruleId == R_arglist) {
            for (ParseNode c : al.nodes) args.add(c);
        } else if (!(argsNode instanceof ParseNode.Null)) {
            args.add(argsNode);
        }
        if (args.isEmpty() || args.size() > 3) return false;

        ParseNode start, end, step;
        if (args.size() == 1) {
            start = new ParseNode.SmallInt(0); end = args.get(0); step = new ParseNode.SmallInt(1);
        } else if (args.size() == 2) {
            start = args.get(0); end = args.get(1); step = new ParseNode.SmallInt(1);
        } else {
            start = args.get(0); end = args.get(1); step = args.get(2);
            if (!(step instanceof ParseNode.SmallInt si) || si.value == 0) return false;
        }
        for (ParseNode a : new ParseNode[]{start, end}) {
            if (a instanceof ParseNode.Struct k
                    && (k.ruleId == R_arglist_star || k.ruleId == R_arglist_dbl_star || k.ruleId == R_argument)) {
                return false;
            }
        }

        ParseNode target = s.nodes[0];
        ParseNode body = s.nodes[2];
        ParseNode elseBody = s.nodes.length > 3 ? s.nodes[3] : ParseNode.Null.INSTANCE;
        if (ph != Phase.EMIT) {
            walkExpr(u, end, ph);
            walkExpr(u, start, ph);
            walkStore(u, target, ph);
            walkStmt(u, body, ph);
            walkExpr(u, step, ph);
            if (!(elseBody instanceof ParseNode.Null)) walkStmt(u, elseBody, ph);
            return true;
        }

        int breakLabel = u.emit.newLabel();
        int continueLabel = u.emit.newLabel();
        loopStack.add(new int[]{breakLabel, continueLabel, 0, u.emit.excLevel()});
        int topLabel = u.emit.newLabel();
        int entryLabel = u.emit.newLabel();

        boolean endOnStack = !(end instanceof ParseNode.SmallInt);
        if (endOnStack) walkExpr(u, end, ph);
        walkExpr(u, start, ph);
        u.emit.jumpTo(entryLabel);
        u.emit.labelAssign(topLabel);
        u.emit.dupTop();
        walkStore(u, target, ph);
        walkStmt(u, body, ph);
        u.emit.labelAssign(continueLabel);
        walkExpr(u, step, ph);
        u.emit.binaryOp(14);                       // __iadd__
        u.emit.labelAssign(entryLabel);
        if (endOnStack) {
            u.emit.dupTopTwo();
            u.emit.rotTwo();
        } else {
            u.emit.dupTop();
            walkExpr(u, end, ph);
        }
        long stepVal = ((ParseNode.SmallInt) step).value;
        u.emit.binaryOp(stepVal >= 0 ? 0 : 1);     // __lt__ / __gt__
        u.emit.popJumpIfTrue(topLabel);
        loopStack.remove(loopStack.size() - 1);

        int endLabel = -1;
        if (!(elseBody instanceof ParseNode.Null)) {
            u.emit.popTop();                        // discard the loop variable
            if (endOnStack) u.emit.popTop();
            walkStmt(u, elseBody, ph);
            endLabel = u.emit.newLabel();
            u.emit.jumpTo(endLabel);
            u.emit.adjustStackSize(1 + (endOnStack ? 1 : 0));
        }
        u.emit.labelAssign(breakLabel);
        u.emit.popTop();                            // discard the failed loop value
        if (endOnStack) u.emit.popTop();
        if (endLabel >= 0) u.emit.labelAssign(endLabel);
        return true;
    }

    /** for target in iter: body  (else clause deferred). */
    /**
     * Detect "for <name> in range(...)" with 1-3 plain arguments (and a non-zero
     * constant step), which MicroPython compiles as an incremented variable rather
     * than an iterator. Returns {start, end, step} or null.
     */
    private ParseNode[] optimisedRangeArgs(ParseNode.Struct s) {
        if (!(s.nodes[0] instanceof ParseNode.Id)) return null;
        if (!(s.nodes[1] instanceof ParseNode.Struct it) || it.ruleId != R_atom_expr_normal) return null;
        if (!(it.nodes[0] instanceof ParseNode.Id fn) || !fn.name.equals("range")) return null;
        if (!(it.nodes[1] instanceof ParseNode.Struct tp) || tp.ruleId != R_trailer_paren) return null;
        List<ParseNode> args = new ArrayList<>();
        ParseNode an = tp.nodes.length > 0 ? tp.nodes[0] : ParseNode.Null.INSTANCE;
        if (an instanceof ParseNode.Null) {
            // no args
        } else if (an instanceof ParseNode.Struct al && al.ruleId == R_arglist) {
            for (ParseNode c : al.nodes) args.add(c);
        } else {
            args.add(an);
        }
        if (args.isEmpty() || args.size() > 3) return null;
        ParseNode start, end, step;
        if (args.size() == 1) {
            start = new ParseNode.SmallInt(0); end = args.get(0); step = new ParseNode.SmallInt(1);
        } else if (args.size() == 2) {
            start = args.get(0); end = args.get(1); step = new ParseNode.SmallInt(1);
        } else {
            start = args.get(0); end = args.get(1); step = args.get(2);
            if (!(step instanceof ParseNode.SmallInt si) || si.value == 0) return null;
        }
        for (ParseNode p2 : new ParseNode[]{start, end}) {
            if (p2 instanceof ParseNode.Struct k
                    && (k.ruleId == R_arglist_star || k.ruleId == R_arglist_dbl_star
                        || k.ruleId == R_argument)) {
                return null;
            }
        }
        return new ParseNode[]{start, end, step};
    }

    /** compile_for_stmt_optimised_range. */
    private void walkForRange(Unit u, ParseNode.Struct s, ParseNode[] rng, Phase ph) {
        ParseNode var = s.nodes[0], body = s.nodes[2];
        ParseNode elseBody = s.nodes.length > 3 ? s.nodes[3] : ParseNode.Null.INSTANCE;
        ParseNode start = rng[0], end = rng[1], step = rng[2];
        boolean endOnStack = !(end instanceof ParseNode.SmallInt);

        if (ph != Phase.EMIT) {
            if (endOnStack) walkExpr(u, end, ph);
            walkExpr(u, start, ph);
            walkStore(u, var, ph);
            walkStmt(u, body, ph);
            walkExpr(u, step, ph);
            if (!endOnStack) walkExpr(u, end, ph);
            if (!(elseBody instanceof ParseNode.Null)) walkStmt(u, elseBody, ph);
            return;
        }

        int breakLabel = u.emit.newLabel();
        int continueLabel = u.emit.newLabel();
        loopStack.add(new int[]{breakLabel, continueLabel, 0, u.emit.excLevel()});
        int topLabel = u.emit.newLabel();
        int entryLabel = u.emit.newLabel();

        if (endOnStack) walkExpr(u, end, ph);
        walkExpr(u, start, ph);
        u.emit.jumpTo(entryLabel);
        u.emit.labelAssign(topLabel);
        u.emit.dupTop();
        walkStore(u, var, ph);
        walkStmt(u, body, ph);
        u.emit.labelAssign(continueLabel);
        walkExpr(u, step, ph);
        u.emit.binaryOp(14);                 // __iadd__
        u.emit.labelAssign(entryLabel);
        if (endOnStack) {
            u.emit.dupTopTwo();
            u.emit.rotTwo();
        } else {
            u.emit.dupTop();
            walkExpr(u, end, ph);
        }
        long stepVal = ((ParseNode.SmallInt) step).value;
        u.emit.binaryOp(stepVal >= 0 ? 0 : 1);   // __lt__ : __gt__
        u.emit.popJumpIfTrue(topLabel);
        loopStack.remove(loopStack.size() - 1);

        int endLabel = -1;
        if (!(elseBody instanceof ParseNode.Null)) {
            u.emit.popTop();
            if (endOnStack) u.emit.popTop();
            walkStmt(u, elseBody, ph);
            endLabel = u.emit.newLabel();
            u.emit.jumpTo(endLabel);
            u.emit.adjustStackSize(1 + (endOnStack ? 1 : 0));
        }
        u.emit.labelAssign(breakLabel);
        u.emit.popTop();
        if (endOnStack) u.emit.popTop();
        if (endLabel >= 0) u.emit.labelAssign(endLabel);
    }

    private void walkFor(Unit u, ParseNode.Struct s, Phase ph) {
        ParseNode[] rng = optimisedRangeArgs(s);
        if (rng != null) { walkForRange(u, s, rng, ph); return; }
        if (tryOptimisedRangeFor(u, s, ph)) return;
        ParseNode target = s.nodes[0];
        ParseNode iter = s.nodes[1];
        ParseNode body = s.nodes[2];
        if (ph != Phase.EMIT) {
            walkExpr(u, iter, ph);      // iterable loaded first
            walkStore(u, target, ph);   // then loop-variable store
            walkStmt(u, body, ph);
            if (s.nodes.length > 3 && !(s.nodes[3] instanceof ParseNode.Null)) walkStmt(u, s.nodes[3], ph);
            return;
        }
        walkExpr(u, iter, ph);
        u.emit.getIterStack();
        int breakLabel = u.emit.newLabel();
        int continueLabel = u.emit.newLabel();
        loopStack.add(new int[]{breakLabel, continueLabel, 1, u.emit.excLevel()});
        int popLabel = u.emit.newLabel();
        u.emit.labelAssign(continueLabel);
        u.emit.forIter(popLabel);
        walkStore(u, target, ph);
        walkStmt(u, body, ph);
        u.emit.jumpTo(continueLabel);
        u.emit.labelAssign(popLabel);
        u.emit.forIterEnd();
        loopStack.remove(loopStack.size() - 1);   // else runs with the outer loop context
        ParseNode forElse = s.nodes.length > 3 ? s.nodes[3] : ParseNode.Null.INSTANCE;
        if (!(forElse instanceof ParseNode.Null)) walkStmt(u, forElse, ph);
        u.emit.labelAssign(breakLabel);
    }

    /** The comprehension scope body: BUILD_x 0, load arg, GET_ITER_STACK, comp_iter, RETURN. */
    /** compile_comprehension: MAKE_FUNCTION/closure(comp), eval first iterable, CALL 1. */
    private void emitComprehension(Unit u, ParseNode.Struct s, Phase ph) {
        Scope childScope = analysis.funcScope(s);
        ParseNode.Struct compFor = analysis.comprehensionCompFor(s);
        if (ph == Phase.EMIT) {
            emitMakeFunctionOrClosure(u, childScope, childIndexByScope(u, childScope), 0);
        }
        walkExpr(u, compFor.nodes[1], ph); // first iterable
        if (childScope.kind == Scope.Kind.GEN_EXPR && ph == Phase.EMIT) u.emit.getIter();
        if (ph == Phase.EMIT) u.emit.callFunction(1, 0);
    }

    /** The source line of the comprehension body node (scope->pn in compile.c). */
    private int comprehensionBodyLine(ParseNode.Struct atom) {
        if (atom.ruleId == R_argument) return atom.srcLine;       // bare f(x for x in y)
        ParseNode inner = atom.nodes.length > 0 ? atom.nodes[0] : ParseNode.Null.INSTANCE;
        if (inner instanceof ParseNode.Struct is) return is.srcLine;
        return atom.srcLine;
    }

    private void walkCompBody(Unit u, Phase ph) {
        ParseNode.Struct atom = u.compAtom;
        ParseNode.Struct compFor = analysis.comprehensionCompFor(atom);
        ParseNode inner = analysis.comprehensionInner(atom);
        Scope.Kind kind = u.compKind;
        if (ph != Phase.EMIT) {
            compIterIntern(u, compFor, inner, kind, ph);
            return;
        }
        // the line of the comprehension body itself (the testlist_comp /
        // dictorsetmaker node), not of the enclosing bracket
        u.emit.setSourceLine(comprehensionBodyLine(atom));
        if (kind == Scope.Kind.LIST_COMP) u.emit.buildList(0);
        else if (kind == Scope.Kind.DICT_COMP) u.emit.buildMap(0);
        else if (kind == Scope.Kind.SET_COMP) u.emit.buildSet(0);
        // the "*" iterator argument: free variables are numbered before it
        Scope.IdInfo iterArg = u.scope.find("*");
        int iterLocal = (iterArg != null) ? iterArg.localNum : 0;
        if (kind == Scope.Kind.GEN_EXPR) {
            // 4 iterator slots; the first NULL marks that the second holds the iterator
            u.emit.loadNull();
            u.emit.loadFast(iterLocal);
            u.emit.loadNull();
            u.emit.loadNull();
        } else {
            u.emit.loadFast(iterLocal);
            u.emit.getIterStack();
        }
        compIter(u, compFor, inner, kind, 0);
        if (kind == Scope.Kind.GEN_EXPR) u.emit.loadConstNone();
        u.emit.returnValue();
    }

    private void compIterIntern(Unit u, ParseNode.Struct compFor, ParseNode inner, Scope.Kind kind, Phase ph) {
        walkStore(u, compFor.nodes[0], ph);
        ParseNode iter = compFor.nodes[2];
        while (true) {
            if (iter instanceof ParseNode.Null) {
                if (kind == Scope.Kind.DICT_COMP && inner instanceof ParseNode.Struct it && it.ruleId == R_dictorsetmaker_item) {
                    walkExpr(u, it.nodes[1], ph); walkExpr(u, it.nodes[0], ph);
                } else walkExpr(u, inner, ph);
                return;
            } else if (iter instanceof ParseNode.Struct ci && ci.ruleId == R_comp_if) {
                walkExpr(u, ci.nodes[0], ph); iter = ci.nodes[1];
            } else if (iter instanceof ParseNode.Struct cf && cf.ruleId == R_comp_for) {
                walkExpr(u, cf.nodes[1], ph); walkStore(u, cf.nodes[0], ph); iter = cf.nodes[2];
            } else return;
        }
    }

    /** compile_scope_comp_iter: FOR_ITER loop with STORE_COMP at the innermost. */
    private void compIter(Unit u, ParseNode.Struct compFor, ParseNode inner, Scope.Kind kind, int forDepth) {
        int lTop = u.emit.newLabel();
        int lEnd = u.emit.newLabel();
        u.emit.labelAssign(lTop);
        u.emit.forIter(lEnd);
        walkStore(u, compFor.nodes[0], Phase.EMIT);
        ParseNode iter = compFor.nodes[2];
        while (true) {
            if (iter instanceof ParseNode.Null) {
                if (kind == Scope.Kind.DICT_COMP && inner instanceof ParseNode.Struct it && it.ruleId == R_dictorsetmaker_item) {
                    walkExpr(u, it.nodes[1], Phase.EMIT); // value
                    walkExpr(u, it.nodes[0], Phase.EMIT); // key
                } else {
                    walkExpr(u, inner, Phase.EMIT);
                }
                if (kind == Scope.Kind.GEN_EXPR) {
                    u.emit.yieldValue();
                    u.emit.popTop();
                } else {
                    u.emit.storeComp(kind, 4 * forDepth + 5);
                }
                break;
            } else if (iter instanceof ParseNode.Struct ci && ci.ruleId == R_comp_if) {
                cIfCond(u, ci.nodes[0], false, lTop);
                iter = ci.nodes[1];
            } else if (iter instanceof ParseNode.Struct cf && cf.ruleId == R_comp_for) {
                walkExpr(u, cf.nodes[1], Phase.EMIT);
                u.emit.getIterStack();
                compIter(u, cf, inner, kind, forDepth + 1);
                break;
            } else break;
        }
        u.emit.jumpTo(lTop);
        u.emit.labelAssign(lEnd);
        u.emit.forIterEnd();
    }

    /** with item [as tgt], ...: body  (compile_with_stmt_helper). */
    private void walkWith(Unit u, ParseNode.Struct s, Phase ph) {
        List<ParseNode> items = new ArrayList<>();
        collectWithItems(s.nodes[0], items);
        ParseNode body = s.nodes[s.nodes.length - 1];
        walkWithItems(u, items, 0, body, ph);
    }

    private void collectWithItems(ParseNode n, List<ParseNode> out) {
        if (n instanceof ParseNode.Struct s && s.ruleId == R_with_stmt_list) {
            for (ParseNode c : s.nodes) collectWithItems(c, out);
        } else {
            out.add(n);
        }
    }

    private void walkWithItems(Unit u, List<ParseNode> items, int i, ParseNode body, Phase ph) {
        if (i >= items.size()) { walkStmt(u, body, ph); return; }
        ParseNode item = items.get(i);
        if (ph != Phase.EMIT) {
            if (item instanceof ParseNode.Struct wi && wi.ruleId == R_with_item) {
                walkExpr(u, wi.nodes[0], ph);
                walkStore(u, wi.nodes[1], ph);
            } else {
                walkExpr(u, item, ph);
            }
            walkWithItems(u, items, i + 1, body, ph);
            return;
        }
        int lEnd = u.emit.newLabel();
        if (item instanceof ParseNode.Struct wi && wi.ruleId == R_with_item) {
            walkExpr(u, wi.nodes[0], ph);
            u.emit.setupBlock(lEnd, Emitter.SETUP_WITH);
            u.emit.pushExcept();
            walkStore(u, wi.nodes[1], ph);
        } else {
            walkExpr(u, item, ph);
            u.emit.setupBlock(lEnd, Emitter.SETUP_WITH);
            u.emit.pushExcept();
            u.emit.popTop();
        }
        walkWithItems(u, items, i + 1, body, ph);
        u.emit.withCleanup(lEnd);
        u.emit.popExcept();
        u.emit.endFinally();
    }

    /** try_stmt dispatcher: try/except[/else], try/finally, or both. */
    private void walkTry(Unit u, ParseNode.Struct s, Phase ph) {
        ParseNode body = s.nodes[0];
        ParseNode clause = s.nodes[1];
        List<ParseNode> excepts = new ArrayList<>();
        ParseNode elseNode = ParseNode.Null.INSTANCE;
        ParseNode finallyNode = ParseNode.Null.INSTANCE;

        if (clause instanceof ParseNode.Struct cs && cs.ruleId == R_try_stmt_finally) {
            finallyNode = cs.nodes[0];                       // just try-finally
        } else if (clause instanceof ParseNode.Struct cm && cm.ruleId == R_try_stmt_except_and_more) {
            collectExcepts(cm.nodes[0], excepts);
            elseNode = cm.nodes[1];
            if (cm.nodes.length > 2 && cm.nodes[2] instanceof ParseNode.Struct fs
                    && fs.ruleId == R_try_stmt_finally) {
                finallyNode = fs.nodes[0];
            }
        } else {
            collectExcepts(clause, excepts);                 // just try-except
        }

        if (!(finallyNode instanceof ParseNode.Null)) {
            walkTryFinally(u, body, excepts, elseNode, finallyNode, ph);
        } else {
            walkTryExcept(u, body, excepts, elseNode, ph);
        }
    }

    /** compile_try_finally. */
    private void walkTryFinally(Unit u, ParseNode body, List<ParseNode> excepts,
                                ParseNode elseNode, ParseNode finallyNode, Phase ph) {
        if (ph != Phase.EMIT) {
            if (excepts.isEmpty()) walkStmt(u, body, ph);
            else walkTryExcept(u, body, excepts, elseNode, ph);
            walkStmt(u, finallyNode, ph);
            return;
        }
        int lFinally = u.emit.newLabel();
        u.emit.setupBlock(lFinally, Emitter.SETUP_FINALLY);
        u.emit.pushExcept();
        if (excepts.isEmpty()) {
            u.emit.adjustStackSize(3);      // room for a possible UNWIND_JUMP state
            walkStmt(u, body, ph);
            u.emit.adjustStackSize(-3);
        } else {
            walkTryExcept(u, body, excepts, elseNode, ph);
        }
        u.emit.loadConstNone();             // normal exit marker
        u.emit.labelAssign(lFinally);
        u.emit.adjustStackSize(1);          // a return value may sit on the stack
        walkStmt(u, finallyNode, ph);
        u.emit.adjustStackSize(-1);
        u.emit.popExcept();
        u.emit.endFinally();
    }

    /** compile_try_except (with optional else). */
    private void walkTryExcept(Unit u, ParseNode body, List<ParseNode> excepts,
                               ParseNode elseNode, Phase ph) {
        if (ph != Phase.EMIT) {
            walkStmt(u, body, ph);
            for (ParseNode e : excepts) {
                ParseNode.Struct es = (ParseNode.Struct) e;
                ParseNode spec = es.nodes[0];
                if (spec instanceof ParseNode.Struct as && as.ruleId == R_try_stmt_as_name) {
                    walkExpr(u, as.nodes[0], ph);
                    walkStore(u, as.nodes[1], ph);
                } else if (!(spec instanceof ParseNode.Null)) {
                    walkExpr(u, spec, ph);
                }
                walkStmt(u, es.nodes[1], ph);
            }
            if (!(elseNode instanceof ParseNode.Null)) walkStmt(u, elseNode, ph);
            return;
        }

        int l1 = u.emit.newLabel();
        int successLabel = u.emit.newLabel();
        u.emit.setupBlock(l1, Emitter.SETUP_EXCEPT);
        u.emit.pushExcept();
        walkStmt(u, body, ph);
        u.emit.popExceptJump(successLabel);
        u.emit.labelAssign(l1);
        u.emit.startExceptHandler();
        int l2 = u.emit.newLabel();

        for (ParseNode e : excepts) {
            ParseNode.Struct es = (ParseNode.Struct) e;
            int endFinallyLabel = u.emit.newLabel();
            ParseNode spec = es.nodes[0];
            String asName = null;
            ParseNode typeExpr = null;
            if (spec instanceof ParseNode.Struct as && as.ruleId == R_try_stmt_as_name) {
                typeExpr = as.nodes[0];
                asName = ((ParseNode.Id) as.nodes[1]).name;
            } else if (!(spec instanceof ParseNode.Null)) {
                typeExpr = spec;
            }
            if (typeExpr != null) {
                u.emit.dupTop();
                walkExpr(u, typeExpr, ph);
                u.emit.binaryOp(8); // MP_BINARY_OP_EXCEPTION_MATCH
                u.emit.popJumpIfFalse(endFinallyLabel);
            }
            int l3 = -1;
            if (asName == null) {
                u.emit.popTop();
            } else {
                emitStore(u, asName);
                l3 = u.emit.newLabel();
                u.emit.setupBlock(l3, Emitter.SETUP_FINALLY);
                u.emit.pushExcept();
            }
            walkStmt(u, es.nodes[1], ph);
            if (asName != null) {
                u.emit.loadConstNone();
                u.emit.labelAssign(l3);
                u.emit.adjustStackSize(1);
                u.emit.loadConstNone();
                emitStore(u, asName);
                emitDelete(u, asName);
                u.emit.adjustStackSize(-1);
                u.emit.popExcept();
                u.emit.endFinally();
            }
            u.emit.popExceptJump(l2);
            u.emit.labelAssign(endFinallyLabel);
            u.emit.adjustStackSize(1);
        }
        u.emit.popExcept();
        u.emit.endFinally();
        u.emit.endExceptHandler();
        u.emit.labelAssign(successLabel);
        if (!(elseNode instanceof ParseNode.Null)) walkStmt(u, elseNode, ph);
        u.emit.labelAssign(l2);
    }

    private void collectExcepts(ParseNode n, List<ParseNode> out) {
        if (n instanceof ParseNode.Struct s) {
            if (s.ruleId == R_try_stmt_except) out.add(s);
            else if (s.ruleId == R_try_stmt_except_and_more || s.ruleId == R_try_stmt_except_list)
                for (ParseNode c : s.nodes) collectExcepts(c, out);
            else out.add(s);
        }
    }

    private void walkWhile(Unit u, ParseNode.Struct s, Phase ph) {
        ParseNode cond = s.nodes[0];
        ParseNode body = s.nodes[1];
        ParseNode elseBody = s.nodes.length > 2 ? s.nodes[2] : ParseNode.Null.INSTANCE;
        if (ph != Phase.EMIT) {
            // Mirror the emit path exactly: "while False" emits nothing at all (not
            // even the body), and a constant condition is never visited by c_if_cond.
            // Visiting either here would intern names/literals that no bytecode
            // references, shifting the qstr table.
            if (!isConstFalse(cond)) {
                walkStmt(u, body, ph);   // body is emitted first (loop inversion), so intern first
                if (constTruthiness(cond) == null) walkExpr(u, cond, ph);
            }
            if (!(elseBody instanceof ParseNode.Null)) walkStmt(u, elseBody, ph);
            return;
        }
        int breakLabel = u.emit.newLabel();
        int continueLabel = u.emit.newLabel();
        loopStack.add(new int[]{breakLabel, continueLabel, 0, u.emit.excLevel()});
        if (!isConstFalse(cond)) {                 // "while False" emits nothing
            int topLabel = u.emit.newLabel();
            if (!isConstTrue(cond)) {              // "while True" needs no jump to the test
                u.emit.jumpTo(continueLabel);
            }
            u.emit.labelAssign(topLabel);
            walkStmt(u, body, ph);
            u.emit.labelAssign(continueLabel);
            cIfCond(u, cond, true, topLabel);
        }
        loopStack.remove(loopStack.size() - 1);    // break/continue in else apply to the outer loop
        if (!(elseBody instanceof ParseNode.Null)) walkStmt(u, elseBody, ph);
        u.emit.labelAssign(breakLabel);
    }

    /** parse_node_is_const_bool: is this a constant whose truthiness equals {@code value}? */
    private boolean isConstBool(ParseNode n, boolean value) {
        Boolean t = constTruthiness(n);
        return t != null && t == value;
    }

    private boolean isConstTrue(ParseNode n) { return isConstBool(n, true); }
    private boolean isConstFalse(ParseNode n) { return isConstBool(n, false); }

    /** The truthiness of a constant parse node, or null if it isn't a constant. */
    private Boolean constTruthiness(ParseNode n) {
        // mp_parse_node_is_const also accepts RULE_atom_paren whose first node is
        // NULL -- that is the empty tuple "()", a constant, and a falsy one. Lists
        // and dicts are deliberately NOT constants there, so "if []" still emits.
        if (n instanceof ParseNode.Struct ap && ap.ruleId == R_atom_paren
                && (ap.nodes.length == 0 || ap.nodes[0] instanceof ParseNode.Null)) {
            return false;
        }
        if (n instanceof ParseNode.SmallInt si) return si.value != 0;
        if (n instanceof ParseNode.Str st) return !st.name.isEmpty();
        if (n instanceof ParseNode.Token t) {
            if (t.tok == net.mpy.compiler.lex.Tok.KW_TRUE) return true;
            if (t.tok == net.mpy.compiler.lex.Tok.KW_FALSE) return false;
            if (t.tok == net.mpy.compiler.lex.Tok.KW_NONE) return false;
            if (t.tok == net.mpy.compiler.lex.Tok.ELLIPSIS) return true;
        }
        if (n instanceof ParseNode.Const c) {
            net.mpy.compiler.model.MpConst v = c.value;
            if (v instanceof net.mpy.compiler.model.MpConst.Int i) return i.value().signum() != 0;
            if (v instanceof net.mpy.compiler.model.MpConst.Float f) return f.value() != 0.0;
            if (v instanceof net.mpy.compiler.model.MpConst.Str st2) return !st2.value().isEmpty();
            if (v instanceof net.mpy.compiler.model.MpConst.Bytes b) return b.value().length != 0;
            if (v instanceof net.mpy.compiler.model.MpConst.Tuple tp) return !tp.items().isEmpty();
            if (v instanceof net.mpy.compiler.model.MpConst.Bool bo) return bo.value();
            if (v instanceof net.mpy.compiler.model.MpConst.None) return false;
            if (v instanceof net.mpy.compiler.model.MpConst.Ellipsis) return true;
            if (v instanceof net.mpy.compiler.model.MpConst.Complex cx) return cx.real() != 0.0 || cx.imag() != 0.0;
        }
        return null;
    }

    /**
     * c_if_cond (compile.c): jump to {@code label} when {@code cond} evaluates to
     * {@code jumpIf}, using short-circuit POP_JUMP chains for and/or/not instead of
     * the boolean value form. EMIT phase only.
     */
    private void cIfCond(Unit u, ParseNode pn, boolean jumpIf, int label) {
        Boolean constVal = constTruthiness(pn);
        if (constVal != null) {
            if (constVal == jumpIf) u.emit.jumpTo(label);
            return;
        }
        if (pn instanceof ParseNode.Struct s) {
            int n = s.nodes.length;
            if (s.ruleId == R_or_test) {
                if (!jumpIf) {
                    int label2 = u.emit.newLabel();
                    for (int i = 0; i < n - 1; i++) cIfCond(u, s.nodes[i], !jumpIf, label2);
                    cIfCond(u, s.nodes[n - 1], jumpIf, label);
                    u.emit.labelAssign(label2);
                } else {
                    for (int i = 0; i < n; i++) cIfCond(u, s.nodes[i], jumpIf, label);
                }
                return;
            } else if (s.ruleId == R_and_test) {
                if (!jumpIf) {
                    for (int i = 0; i < n; i++) cIfCond(u, s.nodes[i], jumpIf, label);
                } else {
                    int label2 = u.emit.newLabel();
                    for (int i = 0; i < n - 1; i++) cIfCond(u, s.nodes[i], !jumpIf, label2);
                    cIfCond(u, s.nodes[n - 1], jumpIf, label);
                    u.emit.labelAssign(label2);
                }
                return;
            } else if (s.ruleId == R_not_test_2) {
                cIfCond(u, s.nodes[0], !jumpIf, label);
                return;
            }
        }
        walkExpr(u, pn, Phase.EMIT);
        if (jumpIf) u.emit.popJumpIfTrue(label);
        else u.emit.popJumpIfFalse(label);
    }

    /** Emit the function object for a funcdef (defaults + MAKE_FUNCTION), no store. */
    /** compile_classdef_helper: LOAD_BUILD_CLASS, class-body function, name, bases, CALL. */
    private void makeClassObject(Unit u, ParseNode.Struct classdef, Phase ph) {
        String name = ((ParseNode.Id) classdef.nodes[0]).name;
        Scope cscope = analysis.funcScope(classdef);
        if (ph == Phase.INTERN_CONST) {
            qstrs.intern(name); // class name is also a string constant
        }
        if (ph == Phase.EMIT) {
            u.emit.loadBuildClass();
            emitMakeFunctionOrClosure(u, cscope, childIndexByScope(u, cscope), 0);
            u.emit.loadConstString(name);
        }
        // bases: compiled as call args with 2 extra positional (the function + name)
        ParseNode parents = classdef.nodes.length > 1 ? classdef.nodes[1] : ParseNode.Null.INSTANCE;
        if (parents instanceof ParseNode.Struct ps && ps.ruleId == R_classdef_2) {
            parents = ParseNode.Null.INSTANCE; // empty parens: class C():
        }
        int[] nc = compileArgList(u, parents, ph, 2);
        if (ph == Phase.EMIT) {
            if (nc[2] == 1) u.emit.callFunctionVarKw(nc[0], nc[1]);
            else u.emit.callFunction(nc[0], nc[1]);
        }
    }

    private void makeFunctionObject(Unit u, ParseNode.Struct funcdef, Phase ph) {
        ParseNode params = funcdef.nodes.length > 1 ? funcdef.nodes[1] : ParseNode.Null.INSTANCE;
        Scope childScope = analysis.funcScope(funcdef);
        int nDict = emitParamDefaults(u, params, ph);
        if (ph == Phase.EMIT) {
            emitMakeFunctionOrClosure(u, childScope, childIndexByScope(u, childScope),
                    childScope == null ? 0 : childScope.numDefPosArgs, nDict);
        }
    }

    /**
     * compile_funcdef_lambdef_param: positional defaults are pushed and later
     * wrapped in a tuple; keyword-only defaults (those after a bare/named star)
     * go into a map. Returns the number of keyword-only defaults.
     */
    private int emitParamDefaults(Unit u, ParseNode params, Phase ph) {
        List<ParseNode[]> items = new ArrayList<>();   // {nameNode, defaultNode} in order
        boolean[] star = new boolean[]{false};
        collectParamItems(params, items, star);
        int nDefault = 0, nDict = 0;
        boolean haveStar = false;
        for (ParseNode[] it : items) {
            if (it[0] == null) { haveStar = true; continue; }   // star marker
            ParseNode def = it[1];
            if (def == null) continue;
            if (haveStar) {
                nDict++;
                if (ph == Phase.EMIT && nDict == 1) {
                    if (nDefault > 0) u.emit.buildTuple(nDefault);
                    else u.emit.loadNull();     // no positional defaults
                    u.emit.buildMap(0);
                }
                walkExpr(u, def, ph);
                String nm = ((ParseNode.Id) it[0]).name;
                if (ph == Phase.INTERN_CONST) qstrs.intern(nm);
                else if (ph == Phase.EMIT) { u.emit.loadConstString(nm); u.emit.storeMap(); }
            } else {
                nDefault++;
                walkExpr(u, def, ph);
            }
        }
        return nDict;
    }

    /** Flatten a parameter list into {nameNode, defaultNode} pairs; star markers use a null name. */
    private void collectParamItems(ParseNode params, List<ParseNode[]> out, boolean[] star) {
        if (params instanceof ParseNode.Id) { out.add(new ParseNode[]{params, null}); return; }
        if (!(params instanceof ParseNode.Struct st)) return;
        if (st.ruleId == R_typedargslist || st.ruleId == R_varargslist) {
            for (ParseNode p : st.nodes) collectParamItems(p, out, star);
        } else if (st.ruleId == R_typedargslist_name) {
            ParseNode def = (st.nodes.length > 2 && !(st.nodes[2] instanceof ParseNode.Null)) ? st.nodes[2] : null;
            out.add(new ParseNode[]{st.nodes[0], def});
        } else if (st.ruleId == R_varargslist_name) {
            ParseNode def = (st.nodes.length > 1 && !(st.nodes[1] instanceof ParseNode.Null)) ? st.nodes[1] : null;
            out.add(new ParseNode[]{st.nodes[0], def});
        } else if (st.ruleId == R_typedargslist_star || st.ruleId == R_varargslist_star) {
            out.add(new ParseNode[]{null, null});     // marks the start of keyword-only params
        }
        // dbl-star params carry no default
    }

    /** close_over_variables_etc: load parent cells the child closes over, then MAKE_FUNCTION/CLOSURE. */
    private void emitMakeFunctionOrClosure(Unit u, Scope childScope, int childIdx, int nDefaults) {
        emitMakeFunctionOrClosure(u, childScope, childIdx, nDefaults, 0);
    }

    private void emitMakeFunctionOrClosure(Unit u, Scope childScope, int childIdx,
                                           int nDefaults, int nKwDefaults) {
        // slot 0 = positional-defaults tuple (or NULL), slot 1 = kw-defaults map (or NULL)
        if (nKwDefaults > 0) {
            // the tuple/NULL and the map were already emitted while walking the defaults
            if (childScope != null) childScope.scopeFlags |= Scope.FLAG_DEFKWARGS;
            nDefaults = 1;                       // treat as "has defargs"
        } else if (nDefaults > 0) {
            u.emit.buildTuple(nDefaults);
            u.emit.loadNull();
        }
        int nfree = 0;
        if (u.scope.kind != Scope.Kind.MODULE) {
            for (Scope.IdInfo pid : u.scope.idInfo) {
                if (pid.kind == Scope.IdKind.CELL || pid.kind == Scope.IdKind.FREE) {
                    for (Scope.IdInfo id2 : childScope.idInfo) {
                        if (id2.kind == Scope.IdKind.FREE && pid.qst.equals(id2.qst)) {
                            u.emit.loadFast(pid.localNum); // closures load via LOAD_FAST
                            nfree++;
                        }
                    }
                }
            }
        }
        if (nfree == 0) {
            if (nDefaults == 0) u.emit.makeFunction(childIdx);
            else u.emit.makeFunctionDefargs(childIdx);
        } else {
            if (nDefaults == 0) u.emit.makeClosure(childIdx, nfree);
            else u.emit.makeClosureDefargs(childIdx, nfree);
        }
    }

    /** decorated: @dec1 @dec2 def f(): ...  (decorators without arguments). */
    private void walkDecorated(Unit u, ParseNode.Struct s, Phase ph) {
        List<ParseNode> decorators = new ArrayList<>();
        collectDecorators(s.nodes[0], decorators);
        ParseNode.Struct funcdef = (ParseNode.Struct) s.nodes[1];
        if (funcdef.ruleId == R_async_stmt || funcdef.ruleId == R_async_funcdef) {
            funcdef = (ParseNode.Struct) funcdef.nodes[0];   // @dec async def ...
        }

        // "@micropython.xxx" decorators only select emit options; they are not applied
        int applied = 0;
        for (ParseNode d : decorators) {
            ParseNode.Struct dec = (ParseNode.Struct) d;
            if (isBuiltInDecorator(dec.nodes[0])) continue;
            applied++;
            walkDecoratorName(u, dec.nodes[0], ph);
            // decorator arguments: @deco(args) -> call deco with args now
            if (dec.nodes.length > 1 && dec.nodes[1] instanceof ParseNode.Struct tp && tp.ruleId == R_trailer_paren) {
                if (ph == Phase.EMIT) u.emit.setSourceLine(tp.srcLine); // compile_node line for the trailer struct
                int[] nc = compileCallArgs(u, tp, ph);
                if (ph == Phase.EMIT) {
                    if (nc[2] == 1) u.emit.callFunctionVarKw(nc[0], nc[1]);
                    else u.emit.callFunction(nc[0], nc[1]);
                }
            }
        }
        // build the function or class object
        if (funcdef.ruleId == R_classdef) makeClassObject(u, funcdef, ph);
        else makeFunctionObject(u, funcdef, ph);
        // apply decorators
        if (ph == Phase.EMIT) for (int i = 0; i < applied; i++) u.emit.callFunction(1, 0);
        // store into the function name
        String name = ((ParseNode.Id) funcdef.nodes[0]).name;
        if (ph == Phase.EMIT) emitStore(u, name);
        else if (ph == Phase.INTERN_NAME) internName(u, name);
    }

    /** "@micropython.bytecode" and friends are compiler directives, not real decorators. */
    private boolean isBuiltInDecorator(ParseNode name) {
        return name instanceof ParseNode.Struct dn && dn.ruleId == R_dotted_name
                && dn.nodes.length == 2
                && dn.nodes[0] instanceof ParseNode.Id first
                && first.name.equals("micropython");
    }

    /** A decorator's dotted name: id [.attr]* -> LOAD_NAME then LOAD_ATTR chain. */
    private void walkDecoratorName(Unit u, ParseNode name, Phase ph) {
        if (name instanceof ParseNode.Id id) {
            emitLoadOrIntern(u, id.name, ph);
        } else if (name instanceof ParseNode.Struct st && st.ruleId == R_dotted_name) {
            emitLoadOrIntern(u, ((ParseNode.Id) st.nodes[0]).name, ph);
            for (int i = 1; i < st.nodes.length; i++) {
                String attr = ((ParseNode.Id) st.nodes[i]).name;
                if (ph == Phase.INTERN_CONST) qstrs.intern(attr);
                else if (ph == Phase.EMIT) u.emit.loadAttr(attr);
            }
        }
    }

    private void collectDecorators(ParseNode n, List<ParseNode> out) {
        if (n instanceof ParseNode.Struct s) {
            if (s.ruleId == R_decorator) out.add(s);
            else if (s.ruleId == R_decorators) for (ParseNode c : s.nodes) collectDecorators(c, out);
        }
    }

    /** Collect positional-parameter default value expressions in order. */
    private void collectParamDefaults(ParseNode params, List<ParseNode> out) {
        if (params instanceof ParseNode.Struct st) {
            if (st.ruleId == R_typedargslist || st.ruleId == R_varargslist) {
                for (ParseNode p : st.nodes) collectParamDefaults(p, out);
            } else if (st.ruleId == R_typedargslist_name) {
                // def: [name, annotation, default]
                if (st.nodes.length > 2 && !(st.nodes[2] instanceof ParseNode.Null)) out.add(st.nodes[2]);
            } else if (st.ruleId == R_varargslist_name) {
                // lambda: [name, default]
                if (st.nodes.length > 1 && !(st.nodes[1] instanceof ParseNode.Null)) out.add(st.nodes[1]);
            }
        }
    }

    private int childIndexOf(Unit u, ParseNode funcdef) {
        return childIndexByScope(u, analysis.funcScope(funcdef));
    }

    private int childIndexByScope(Unit u, Scope scope) {
        for (int i = 0; i < u.children.size(); i++) if (u.children.get(i).scope == scope) return i;
        return 0;
    }

    private int lambdaLine(Unit u) {
        return u.lambdaBody instanceof ParseNode.Struct s ? s.srcLine : 1;
    }

    private void walkExpr(Unit u, ParseNode n, Phase ph) {
        if (n instanceof ParseNode.Id id) {
            emitLoadOrIntern(u, id.name, ph);
        } else if (n instanceof ParseNode.SmallInt si) {
            if (ph == Phase.EMIT) u.emit.loadConstSmallInt(si.value);
        } else if (n instanceof ParseNode.Const cn) {
            // const object (float/complex/big int): a struct node, so it sets the source line
            if (ph == Phase.INTERN_CONST) internObj(cn.value);
            else if (ph == Phase.EMIT) {
                u.emit.setSourceLine(cn.srcLine);
                u.emit.loadConstObj(internObj(cn.value));
            }
        } else if (n instanceof ParseNode.Str str) {
            if (ph == Phase.INTERN_CONST) qstrs.intern(str.name);
            else if (ph == Phase.EMIT) u.emit.loadConstString(str.name);
        } else if (n instanceof ParseNode.Token t) {
            // False / None / True keyword constants
            if (t.tok == net.mpy.compiler.lex.Tok.ELLIPSIS) {
                if (ph == Phase.INTERN_CONST) internObj(net.mpy.compiler.model.MpConst.Ellipsis.INSTANCE);
                else if (ph == Phase.EMIT) u.emit.loadConstObj(internObj(net.mpy.compiler.model.MpConst.Ellipsis.INSTANCE));
            } else if (ph == Phase.EMIT) {
                int kwFalse = net.mpy.compiler.lex.Tok.KW_FALSE.ordinal();
                if (t.tok == net.mpy.compiler.lex.Tok.KW_FALSE || t.tok == net.mpy.compiler.lex.Tok.KW_NONE || t.tok == net.mpy.compiler.lex.Tok.KW_TRUE) {
                    u.emit.loadConstTok(t.tok.ordinal() - kwFalse);
                }
            }
        } else if (n instanceof ParseNode.Struct s) {
            if (ph == Phase.EMIT) u.emit.setSourceLine(s.srcLine); // compile_node sets line for every struct
            if (s.ruleId == R_lambdef) {
                // lambda uses exactly the same default-argument machinery as def:
                // positional defaults become a tuple, keyword-only defaults a map
                Scope childScope = analysis.funcScope(s);
                int nDict = emitParamDefaults(u, s.nodes[0], ph);
                if (ph == Phase.EMIT) {
                    emitMakeFunctionOrClosure(u, childScope, childIndexByScope(u, childScope),
                            childScope == null ? 0 : childScope.numDefPosArgs, nDict);
                }
            } else if (analysis.comprehensionKind(s) != null) {
                emitComprehension(u, s, ph);
            } else if (s.ruleId == R_expr || s.ruleId == R_xor_expr
                    || s.ruleId == R_and_expr || s.ruleId == R_power) {
                // fixed-operator rules: | ^ & **  (operands only, no op tokens)
                walkExpr(u, s.nodes[0], ph);
                for (int i = 1; i < s.nodes.length; i++) {
                    walkExpr(u, s.nodes[i], ph);
                    if (ph == Phase.EMIT) u.emit.binaryOp(fixedOpIndex(s.ruleId));
                }
            } else if (s.ruleId == R_shift_expr) {
                walkExpr(u, s.nodes[0], ph);
                for (int i = 1; i + 1 < s.nodes.length; i += 2) {
                    walkExpr(u, s.nodes[i + 1], ph);
                    if (ph == Phase.EMIT) u.emit.binaryOp(binaryOpIndex(s.nodes[i]));
                }
            } else if (s.ruleId == R_arith_expr || s.ruleId == R_term) {
                walkExpr(u, s.nodes[0], ph);
                for (int i = 1; i + 1 < s.nodes.length; i += 2) {
                    walkExpr(u, s.nodes[i + 1], ph);
                    if (ph == Phase.EMIT) u.emit.binaryOp(binaryOpIndex(s.nodes[i]));
                }
            } else if (s.ruleId == R_comparison) {
                walkComparison(u, s, ph);
            } else if (s.ruleId == R_atom_expr_await) {
                // await X  ==  (X) then yield-from
                walkAwait(u, s, ph);
            } else if (s.ruleId == R_yield_expr) {
                if (u.scope.kind != Scope.Kind.FUNCTION && u.scope.kind != Scope.Kind.LAMBDA) {
                    throw new net.mpy.compiler.MpCompileException("'yield' outside function");
                }
                ParseNode arg = s.nodes.length > 0 ? s.nodes[0] : ParseNode.Null.INSTANCE;
                if (arg instanceof ParseNode.Null) {
                    if (ph == Phase.EMIT) { u.emit.loadConstNone(); u.emit.yieldValue(); }
                } else if (arg instanceof ParseNode.Struct yf && yf.ruleId == R_yield_arg_from) {
                    walkExpr(u, yf.nodes[0], ph);                 // yield from X
                    if (ph == Phase.EMIT) {
                        u.emit.getIter();
                        u.emit.loadConstNone();
                        u.emit.yieldFrom();
                    }
                } else {
                    walkExpr(u, arg, ph);                          // yield X
                    if (ph == Phase.EMIT) u.emit.yieldValue();
                }
            } else if (s.ruleId == R_testlist_star_expr || s.ruleId == R_testlist
                    || s.ruleId == R_exprlist) {
                // bare tuple in value position: a, b  ->  BUILD_TUPLE
                for (ParseNode c : s.nodes) walkExpr(u, c, ph);
                if (ph == Phase.EMIT) u.emit.buildTuple(s.nodes.length);
            } else if (s.ruleId == R_namedexpr) {
                // walrus:  (name := value)  -> value, DUP_TOP, STORE name
                walkExpr(u, s.nodes[1], ph);
                if (ph == Phase.EMIT) u.emit.dupTop();
                walkStore(u, s.nodes[0], ph);
            } else if (s.ruleId == R_test_if_expr) {
                walkIfExpr(u, s, ph);
            } else if (s.ruleId == R_and_test) {
                walkBoolOp(u, s, ph, false); // and -> JUMP_IF_FALSE_OR_POP
            } else if (s.ruleId == R_or_test) {
                walkBoolOp(u, s, ph, true);  // or  -> JUMP_IF_TRUE_OR_POP
            } else if (s.ruleId == R_factor) {
                // unary +/-/~ : operand then UNARY_OP(tok - OP_PLUS)
                walkExpr(u, s.nodes[1], ph);
                if (ph == Phase.EMIT) {
                    int idx = 0;
                    if (s.nodes[0] instanceof ParseNode.Token t) {
                        if (t.tok == net.mpy.compiler.lex.Tok.OP_TILDE) idx = 2; // __invert__
                        else idx = t.tok.ordinal() - net.mpy.compiler.lex.Tok.OP_PLUS.ordinal(); // + -> 0, - -> 1
                    }
                    u.emit.unaryOp(idx);
                }
            } else if (s.ruleId == R_not_test_2) {
                // `not x` as a value -> UNARY_OP 3
                walkExpr(u, s.nodes[0], ph);
                if (ph == Phase.EMIT) u.emit.unaryOp(3);
            } else if (s.ruleId == R_atom_expr_normal) {
                walkAtomExpr(u, s, ph);
            } else if (s.ruleId == R_atom_bracket) {
                walkList(u, s, ph);
            } else if (s.ruleId == R_atom_paren) {
                walkParen(u, s, ph);
            } else if (s.ruleId == R_atom_brace) {
                walkDict(u, s, ph);
            } else {
                for (ParseNode c : s.nodes) walkExpr(u, c, ph);
            }
        }
    }

    /** Extract the element list from a bracket/paren content node. */
    private List<ParseNode> elements(ParseNode content) {
        List<ParseNode> out = new ArrayList<>();
        if (content instanceof ParseNode.Null) {
            // empty
        } else if (content instanceof ParseNode.Struct s && s.ruleId == R_testlist_comp) {
            for (ParseNode c : s.nodes) out.add(c);
        } else {
            out.add(content);
        }
        return out;
    }

    private void walkList(Unit u, ParseNode.Struct s, Phase ph) {
        List<ParseNode> items = elements(s.nodes.length > 0 ? s.nodes[0] : ParseNode.Null.INSTANCE);
        for (ParseNode it : items) walkExpr(u, it, ph);
        if (ph == Phase.EMIT) u.emit.buildList(items.size());
    }

    private void walkParen(Unit u, ParseNode.Struct s, Phase ph) {
        ParseNode content = s.nodes.length > 0 ? s.nodes[0] : ParseNode.Null.INSTANCE;
        if (content instanceof ParseNode.Struct cs && cs.ruleId == R_testlist_comp) {
            // tuple
            for (ParseNode c : cs.nodes) walkExpr(u, c, ph);
            if (ph == Phase.EMIT) u.emit.buildTuple(cs.nodes.length);
        } else if (content instanceof ParseNode.Null) {
            if (ph == Phase.EMIT) u.emit.buildTuple(0); // empty tuple ()
        } else {
            walkExpr(u, content, ph); // (expr) grouping
        }
    }

    private void walkDict(Unit u, ParseNode.Struct s, Phase ph) {
        ParseNode content = s.nodes.length > 0 ? s.nodes[0] : ParseNode.Null.INSTANCE;
        List<ParseNode> items = new ArrayList<>();
        collectBraceItems(content, items);
        boolean isDict = !items.isEmpty() && items.get(0) instanceof ParseNode.Struct fs
                && fs.ruleId == R_dictorsetmaker_item;
        if (isDict) {
            if (ph == Phase.EMIT) u.emit.buildMap(items.size());
            for (ParseNode pr : items) {
                if (pr instanceof ParseNode.Struct item && item.ruleId == R_dictorsetmaker_item) {
                    // compile_node sets the line for every struct, so a multi-line
                    // dict literal records a line per entry
                    if (ph == Phase.EMIT) u.emit.setSourceLine(item.srcLine);
                    walkExpr(u, item.nodes[1], ph); // value first
                    walkExpr(u, item.nodes[0], ph); // then key
                    if (ph == Phase.EMIT) u.emit.storeMap();
                }
            }
        } else if (items.isEmpty()) {
            if (ph == Phase.EMIT) u.emit.buildMap(0); // {} is an empty dict
        } else {
            // set literal: load each element, BUILD_SET
            for (ParseNode it : items) walkExpr(u, it, ph);
            if (ph == Phase.EMIT) u.emit.buildSet(items.size());
        }
    }

    /** Flatten brace entries (dictorsetmaker + continuation) into items. */
    private void collectBraceItems(ParseNode n, List<ParseNode> out) {
        if (n instanceof ParseNode.Null) return;
        if (n instanceof ParseNode.Struct s) {
            if (s.ruleId == R_dictorsetmaker_item) { out.add(s); return; }
            if (s.ruleId == R_dictorsetmaker || s.ruleId == R_dictorsetmaker_list
                    || s.ruleId == R_dictorsetmaker_list2) {
                for (ParseNode c : s.nodes) collectBraceItems(c, out);
                return;
            }
        }
        out.add(n); // a plain expression element (set)
    }

    /** atom_expr_normal: [atom, trailer | atom_expr_trailers] -> apply trailers. */
    private void walkAtomExpr(Unit u, ParseNode.Struct s, Phase ph) {
        walkAtomExprParts(u, s, s.nodes[0], s.nodes[1], ph);
    }

    /** Apply a trailer chain to a base atom (shared by atom_expr_normal and await). */
    private void walkAtomExprParts(Unit u, ParseNode.Struct s, ParseNode base,
                                   ParseNode trailerNode, Phase ph) {
        List<ParseNode> trailers = new ArrayList<>();
        ParseNode t0 = trailerNode;
        if (t0 instanceof ParseNode.Struct ts0 && ts0.ruleId == R_atom_expr_trailers) {
            for (ParseNode c : ts0.nodes) trailers.add(c);
        } else {
            trailers.add(t0);
        }

        int startTrailer = 0;
        if (s != null && analysis.isBareSuperCall(s) && u.scope.kind.isFuncLike()) {
            // super(): load the `super` builtin, then __class__ and the first parameter (self)
            walkExpr(u, s.nodes[0], ph);
            emitLoadOrIntern(u, "__class__", ph);
            String selfName = firstParamName(u.scope);
            if (selfName != null) emitLoadOrIntern(u, selfName, ph);
            boolean methodCall = trailers.size() >= 3
                    && trailers.get(1) instanceof ParseNode.Struct p1 && p1.ruleId == R_trailer_period
                    && trailers.get(2) instanceof ParseNode.Struct p2 && p2.ruleId == R_trailer_paren;
            if (methodCall) {
                ParseNode.Struct period = (ParseNode.Struct) trailers.get(1);
                String mname = ((ParseNode.Id) period.nodes[0]).name;
                if (ph == Phase.INTERN_CONST) qstrs.intern(mname);
                else if (ph == Phase.EMIT) u.emit.loadSuperMethod(mname);
                int[] nc = compileCallArgs(u, (ParseNode.Struct) trailers.get(2), ph);
                if (ph == Phase.EMIT) {
                    if (nc[2] == 1) u.emit.callMethodVarKw(nc[0], nc[1]);
                    else u.emit.callMethod(nc[0], nc[1]);
                }
                startTrailer = 3;
            } else {
                if (ph == Phase.EMIT) u.emit.callFunction(2, 0);
                startTrailer = 1;
            }
        } else {
            walkExpr(u, base, ph); // the base atom
        }

        for (int i = startTrailer; i < trailers.size(); i++) {
            ParseNode tr = trailers.get(i);
            if (!(tr instanceof ParseNode.Struct trs)) continue;
            boolean methodCall = trs.ruleId == R_trailer_period
                    && i + 1 < trailers.size()
                    && trailers.get(i + 1) instanceof ParseNode.Struct nx
                    && nx.ruleId == R_trailer_paren;
            // compile_atom_expr_normal runs an ordinary trailer through compile_node,
            // which sets the source line from that node -- but the method-call pair
            // (period + paren) is emitted inline via compile_trailer_paren_helper and
            // therefore sets NO line. Both halves matter: skipping the line entirely
            // stalls the record when a trailer spans lines ("f(\n 1\n)()"), while
            // setting it for a method call diverges on ".format(...)", which is what
            // every f-string compiles to.
            if (ph == Phase.EMIT && !methodCall) {
                u.emit.setSourceLine(trs.srcLine);
            }
            if (methodCall) {
                String name = ((ParseNode.Id) trs.nodes[0]).name;
                if (ph == Phase.INTERN_CONST) qstrs.intern(name);
                else if (ph == Phase.EMIT) u.emit.loadMethod(name);
                int[] nc = compileCallArgs(u, (ParseNode.Struct) trailers.get(i + 1), ph);
                if (ph == Phase.EMIT) {
                    if (nc[2] == 1) u.emit.callMethodVarKw(nc[0], nc[1]);
                    else u.emit.callMethod(nc[0], nc[1]);
                }
                i++; // consumed the paren too
            } else if (trs.ruleId == R_trailer_period) {
                String name = ((ParseNode.Id) trs.nodes[0]).name;
                if (ph == Phase.INTERN_CONST) qstrs.intern(name);
                else if (ph == Phase.EMIT) u.emit.loadAttr(name);
            } else if (trs.ruleId == R_trailer_bracket) {
                walkSubscriptIndex(u, trs.nodes[0], ph);
                if (ph == Phase.EMIT) u.emit.loadSubscr();
            } else if (trs.ruleId == R_trailer_paren) {
                int[] nc = compileCallArgs(u, trs, ph);
                if (ph == Phase.EMIT) {
                    if (nc[2] == 1) u.emit.callFunctionVarKw(nc[0], nc[1]);
                    else u.emit.callFunction(nc[0], nc[1]);
                }
            }
        }
    }

    /** Emit call args from a trailer_paren; returns {nPos, nKw, hasStars}. */
    private int[] compileCallArgs(Unit u, ParseNode.Struct trailerParen, Phase ph) {
        ParseNode argsNode = trailerParen.nodes.length > 0 ? trailerParen.nodes[0] : ParseNode.Null.INSTANCE;
        return compileArgList(u, argsNode, ph, 0);
    }

    /** compile_trailer_paren_helper: emit an arg list; returns {nPos, nKw, hasStars}. */
    private int[] compileArgList(Unit u, ParseNode argsNode, Phase ph, int nPositionalExtra) {
        List<ParseNode> raw = new ArrayList<>();
        if (argsNode instanceof ParseNode.Null) {
            // none
        } else if (argsNode instanceof ParseNode.Struct as && as.ruleId == R_arglist) {
            for (ParseNode c : as.nodes) raw.add(c);
        } else {
            raw.add(argsNode);
        }
        int nPos = nPositionalExtra, nKw = 0;
        long starArgs = 0;
        boolean hasStars = false;
        // args in source order, matching compile_trailer_paren_helper
        for (int i = 0; i < raw.size(); i++) {
            ParseNode a = raw.get(i);
            if (a instanceof ParseNode.Struct arg && arg.ruleId == R_arglist_star) {
                if (nKw > 0) {
                    // MicroPython omits CPython's support for *arg after a keyword arg
                    throw new net.mpy.compiler.MpCompileException("* arg after kwarg");
                }
                if (i >= SMALL_INT_BITS - 1) {
                    // the star-args bitmap must fit in a small int
                    throw new net.mpy.compiler.MpCompileException("too many args");
                }
                hasStars = true;
                starArgs |= 1L << i;
                walkExpr(u, arg.nodes[0], ph);
                nPos++;
            } else if (a instanceof ParseNode.Struct arg2 && arg2.ruleId == R_arglist_dbl_star) {
                hasStars = true;
                if (ph == Phase.EMIT) u.emit.loadNull(); // **kwargs stored as kw with key None
                walkExpr(u, arg2.nodes[0], ph);
                nKw++;
            } else if (a instanceof ParseNode.Struct ge && ge.ruleId == R_argument
                    && analysis.comprehensionKind(ge) != null) {
                // Bare generator expression argument. compile_comprehension does not
                // set a source line for it, so emit it without going through the
                // per-struct setSourceLine in walkExpr.
                emitComprehension(u, ge, ph);
                nPos++;
            } else if (a instanceof ParseNode.Struct na && na.ruleId == R_argument
                    && na.nodes.length > 1 && na.nodes[1] instanceof ParseNode.Struct a3
                    && a3.ruleId == R_argument_3) {
                // walrus in an argument position: f(x := v)
                walkExpr(u, a3.nodes[0], ph);
                if (ph == Phase.EMIT) u.emit.dupTop();
                walkStore(u, na.nodes[0], ph);
                nPos++;
            } else if (a instanceof ParseNode.Struct arg3 && arg3.ruleId == R_argument) {
                String name = ((ParseNode.Id) arg3.nodes[0]).name;
                if (ph == Phase.INTERN_CONST) qstrs.intern(name);
                else if (ph == Phase.EMIT) u.emit.loadConstString(name);
                walkExpr(u, arg3.nodes[1], ph);
                nKw++;
            } else {
                walkExpr(u, a, ph);
                nPos++;
            }
        }
        if (hasStars && ph == Phase.EMIT) u.emit.loadConstSmallInt(starArgs); // star-args bitmap
        return new int[]{nPos, nKw, hasStars ? 1 : 0};
    }

    private void walkCall(Unit u, ParseNode.Struct s, Phase ph) {
        walkExpr(u, s.nodes[0], ph); // callee
        List<ParseNode> args = new ArrayList<>();
        ParseNode trailer = s.nodes[1];
        if (trailer instanceof ParseNode.Struct tr && tr.ruleId == R_trailer_paren) {
            ParseNode argsNode = tr.nodes.length > 0 ? tr.nodes[0] : ParseNode.Null.INSTANCE;
            if (argsNode instanceof ParseNode.Null) {
                // none
            } else if (argsNode instanceof ParseNode.Struct as && as.ruleId == R_arglist) {
                for (ParseNode c : as.nodes) args.add(c);
            } else {
                args.add(argsNode);
            }
        }
        for (ParseNode a : args) walkExpr(u, a, ph);
        if (ph == Phase.EMIT) u.emit.callFunction(args.size(), 0);
    }

    private void walkStore(Unit u, ParseNode n, Phase ph) {
        checkAssignable(n);
        if (n instanceof ParseNode.Id id) {
            if (ph == Phase.INTERN_NAME) internName(u, id.name);
            else if (ph == Phase.EMIT) emitStore(u, id.name);
        } else if (n instanceof ParseNode.Struct par
                && (par.ruleId == R_atom_paren || par.ruleId == R_atom_bracket)) {
            // (a, b) = ... / [a, b] = ...
            ParseNode inner = par.nodes.length > 0 ? par.nodes[0] : ParseNode.Null.INSTANCE;
            if (inner instanceof ParseNode.Struct tc && tc.ruleId == R_testlist_comp) {
                walkStoreTuple(u, tc.nodes, ph);
            } else if (!(inner instanceof ParseNode.Null)) {
                walkStoreTuple(u, new ParseNode[]{inner}, ph);   // brackets around one item
            } else {
                walkStoreTuple(u, new ParseNode[0], ph);          // empty list target
            }
        } else if (n instanceof ParseNode.Struct s
                && (s.ruleId == R_testlist_star_expr || s.ruleId == R_exprlist)) {
            // a, b = ... / a, *b = ...  -> UNPACK_SEQUENCE or UNPACK_EX, then store each
            walkStoreTuple(u, s.nodes, ph);
        } else if (n instanceof ParseNode.Struct s2 && s2.ruleId == R_atom_expr_normal) {
            // attribute / subscript store target: obj.attr = / obj[i] =
            walkStoreTrailer(u, s2, ph);
        }
    }

    /**
     * Optimisation for "a, b = c, d" and "a, b, c = d, e, f": push the values and
     * rotate instead of building and unpacking a tuple. Only applies when both
     * sides are 2- or 3-element tuples with no star targets.
     */
    private boolean walkTupleToTupleAssign(Unit u, ParseNode lhs, ParseNode rhs, Phase ph) {
        if (!(lhs instanceof ParseNode.Struct l) || l.ruleId != R_testlist_star_expr) return false;
        if (!(rhs instanceof ParseNode.Struct r) || r.ruleId != R_testlist_star_expr) return false;
        int n = l.nodes.length;
        if (n != r.nodes.length || (n != 2 && n != 3)) return false;
        for (ParseNode t : l.nodes) {
            if (t instanceof ParseNode.Struct st && st.ruleId == R_star_expr) return false;
        }
        walkExpr(u, r.nodes[0], ph);
        walkExpr(u, r.nodes[1], ph);
        if (n == 3) {
            walkExpr(u, r.nodes[2], ph);
            if (ph == Phase.EMIT) u.emit.rotThree();
        }
        if (ph == Phase.EMIT) u.emit.rotTwo();
        for (ParseNode t : l.nodes) walkStore(u, t, ph);
        return true;
    }

    /**
     * c_assign_atom_expr for augmented assignment: on the load pass the base (and
     * index) are evaluated and duplicated; on the store pass they are already on
     * the stack, so only a rotate plus the store is needed.
     */
    private void walkAugTarget(Unit u, ParseNode.Struct s, Phase ph, boolean isLoad) {
        List<ParseNode> trailers = new ArrayList<>();
        ParseNode t = s.nodes[1];
        if (t instanceof ParseNode.Struct ts && ts.ruleId == R_atom_expr_trailers) {
            for (ParseNode c : ts.nodes) trailers.add(c);
        } else {
            trailers.add(t);
        }
        ParseNode lastNode = trailers.get(trailers.size() - 1);
        if (!(lastNode instanceof ParseNode.Struct last)) return;

        if (isLoad) {
            walkExpr(u, s.nodes[0], ph);                    // base object
            for (int i = 0; i < trailers.size() - 1; i++) { // intermediate loads
                if (!(trailers.get(i) instanceof ParseNode.Struct tr)) continue;
                if (tr.ruleId == R_trailer_period) {
                    String nm = ((ParseNode.Id) tr.nodes[0]).name;
                    if (ph == Phase.INTERN_CONST) qstrs.intern(nm);
                    else if (ph == Phase.EMIT) u.emit.loadAttr(nm);
                } else if (tr.ruleId == R_trailer_bracket) {
                    walkSubscriptIndex(u, tr.nodes[0], ph);
                    if (ph == Phase.EMIT) u.emit.loadSubscr();
                } else if (tr.ruleId == R_trailer_paren) {
                    int[] nc = compileCallArgs(u, tr, ph);
                    if (ph == Phase.EMIT) {
                        if (nc[2] == 1) u.emit.callFunctionVarKw(nc[0], nc[1]);
                        else u.emit.callFunction(nc[0], nc[1]);
                    }
                }
            }
            if (last.ruleId == R_trailer_bracket) {
                walkSubscriptIndex(u, last.nodes[0], ph);
                if (ph == Phase.EMIT) { u.emit.dupTopTwo(); u.emit.loadSubscr(); }
            } else if (last.ruleId == R_trailer_period) {
                String nm = ((ParseNode.Id) last.nodes[0]).name;
                if (ph == Phase.INTERN_CONST) qstrs.intern(nm);
                else if (ph == Phase.EMIT) { u.emit.dupTop(); u.emit.loadAttr(nm); }
            }
            return;
        }
        if (ph != Phase.EMIT) return;
        if (last.ruleId == R_trailer_bracket) {
            u.emit.rotThree();
            u.emit.storeSubscr();
        } else if (last.ruleId == R_trailer_period) {
            u.emit.rotTwo();
            u.emit.storeAttr(((ParseNode.Id) last.nodes[0]).name);
        }
    }

    /**
     * c_assign_atom_expr: the last trailer of an assignment target must be a
     * subscript or an attribute. "f() = 1" ends in a call and is not assignable.
     */
    private void checkStoreTrailer(ParseNode.Struct s) {
        ParseNode t = s.nodes[1];
        ParseNode last = t;
        if (t instanceof ParseNode.Struct ts && ts.ruleId == R_atom_expr_trailers) {
            last = ts.nodes[ts.nodes.length - 1];
        }
        if (last instanceof ParseNode.Struct ls
                && (ls.ruleId == R_trailer_bracket || ls.ruleId == R_trailer_period)) {
            return;
        }
        throw new net.mpy.compiler.MpCompileException("can't assign to expression");
    }

    /** c_del_stmt: only names, subscripts and attributes can be deleted. */
    private void checkDeletable(ParseNode n) {
        if (n instanceof ParseNode.Id) return;
        if (n instanceof ParseNode.Struct s) {
            if (s.ruleId == R_exprlist || s.ruleId == R_testlist_star_expr
                    || s.ruleId == R_atom_paren || s.ruleId == R_testlist_comp) {
                return;
            }
            if (s.ruleId == R_atom_expr_normal) {
                ParseNode t = s.nodes[1];
                ParseNode last = t;
                if (t instanceof ParseNode.Struct ts && ts.ruleId == R_atom_expr_trailers) {
                    last = ts.nodes[ts.nodes.length - 1];
                }
                if (last instanceof ParseNode.Struct ls
                        && (ls.ruleId == R_trailer_bracket || ls.ruleId == R_trailer_period)) {
                    return;
                }
            }
        }
        throw new net.mpy.compiler.MpCompileException("can't delete expression");
    }

    /** c_assign: reject targets that cannot be assigned to. */
    private void checkAssignable(ParseNode n) {
        if (n instanceof ParseNode.Id) return;
        if (n instanceof ParseNode.Struct s) {
            if (s.ruleId == R_atom_expr_normal || s.ruleId == R_testlist_star_expr
                    || s.ruleId == R_exprlist || s.ruleId == R_atom_paren
                    || s.ruleId == R_atom_bracket || s.ruleId == R_star_expr
                    || s.ruleId == R_testlist_comp) {
                return;
            }
        }
        throw new net.mpy.compiler.MpCompileException("can't assign to expression");
    }

    /** c_assign_tuple: UNPACK_SEQUENCE / UNPACK_EX then store each target. */
    private void walkStoreTuple(Unit u, ParseNode[] targets, Phase ph) {
        int starIndex = -1;
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] instanceof ParseNode.Struct st && st.ruleId == R_star_expr) {
                if (starIndex >= 0) {
                    throw new net.mpy.compiler.MpCompileException("multiple *x in assignment");
                }
                starIndex = i;
            }
        }
        if (ph == Phase.EMIT) {
            if (starIndex >= 0) u.emit.unpackEx(starIndex, targets.length - starIndex - 1);
            else u.emit.unpackSequence(targets.length);
        }
        for (int i = 0; i < targets.length; i++) {
            ParseNode tgt = targets[i];
            if (i == starIndex) tgt = ((ParseNode.Struct) tgt).nodes[0];
            walkStore(u, tgt, ph);
        }
    }

    /** Store to an attribute or subscript target, including multi-trailer bases. */
    private void walkStoreTrailer(Unit u, ParseNode.Struct s, Phase ph) {
        checkStoreTrailer(s);
        walkExpr(u, s.nodes[0], ph);                     // base object
        List<ParseNode> trailers = new ArrayList<>();
        ParseNode t = s.nodes[1];
        if (t instanceof ParseNode.Struct ts && ts.ruleId == R_atom_expr_trailers) {
            for (ParseNode c : ts.nodes) trailers.add(c);
        } else {
            trailers.add(t);
        }
        // all but the last trailer are loads
        for (int i = 0; i < trailers.size() - 1; i++) {
            if (!(trailers.get(i) instanceof ParseNode.Struct tr)) continue;
            if (tr.ruleId == R_trailer_period) {
                String nm = ((ParseNode.Id) tr.nodes[0]).name;
                if (ph == Phase.INTERN_CONST) qstrs.intern(nm);
                else if (ph == Phase.EMIT) u.emit.loadAttr(nm);
            } else if (tr.ruleId == R_trailer_bracket) {
                walkSubscriptIndex(u, tr.nodes[0], ph);
                if (ph == Phase.EMIT) u.emit.loadSubscr();
            } else if (tr.ruleId == R_trailer_paren) {
                int[] nc = compileCallArgs(u, tr, ph);
                if (ph == Phase.EMIT) {
                    if (nc[2] == 1) u.emit.callFunctionVarKw(nc[0], nc[1]);
                    else u.emit.callFunction(nc[0], nc[1]);
                }
            }
        }
        ParseNode lastNode = trailers.get(trailers.size() - 1);
        if (!(lastNode instanceof ParseNode.Struct last)) return;
        if (last.ruleId == R_trailer_period) {
            String nm = ((ParseNode.Id) last.nodes[0]).name;
            if (ph == Phase.INTERN_CONST) qstrs.intern(nm);
            else if (ph == Phase.EMIT) u.emit.storeAttr(nm);
        } else if (last.ruleId == R_trailer_bracket) {
            walkSubscriptIndex(u, last.nodes[0], ph);
            if (ph == Phase.EMIT) u.emit.storeSubscr();
        }
    }

    // --- name interning / emission by scope classification ---

    /** Intern a const object, returning its index in the object table. */
    private int internObj(net.mpy.compiler.model.MpConst c) {
        for (int i = 0; i < objs.size(); i++) if (sameConstObj(objs.get(i), c)) return i;
        objs.add(c);
        return objs.size() - 1;
    }

    /**
     * Object-table identity. The records' own equals() is right for everything except
     * floats: Double.equals canonicalises every NaN to a single value, so "nan" and
     * "-nan" would share one slot -- but mpy-cross keeps them in separate slots, since
     * they differ in the sign bit. Comparing raw bits reproduces that (and likewise
     * keeps 0.0 and -0.0 apart).
     */
    private static boolean sameConstObj(net.mpy.compiler.model.MpConst a, net.mpy.compiler.model.MpConst b) {
        if (a instanceof net.mpy.compiler.model.MpConst.Float fa && b instanceof net.mpy.compiler.model.MpConst.Float fb) {
            return Double.doubleToRawLongBits(fa.value()) == Double.doubleToRawLongBits(fb.value());
        }
        if (a instanceof net.mpy.compiler.model.MpConst.Complex ca && b instanceof net.mpy.compiler.model.MpConst.Complex cb) {
            return Double.doubleToRawLongBits(ca.real()) == Double.doubleToRawLongBits(cb.real())
                && Double.doubleToRawLongBits(ca.imag()) == Double.doubleToRawLongBits(cb.imag());
        }
        return a.equals(b);
    }

    private void internName(Unit u, String name) {
        Scope.IdInfo id = u.scope.find(name);
        // LOAD_FAST/STORE_FAST locals and DEREF cells/frees carry no qstr
        if (id != null && (id.kind == Scope.IdKind.LOCAL || id.kind == Scope.IdKind.CELL
                || id.kind == Scope.IdKind.FREE)) return;
        qstrs.intern(name);
    }

    private void emitLoadOrIntern(Unit u, String name, Phase ph) {
        Scope.IdInfo id = u.scope.find(name);
        boolean deref = id != null && (id.kind == Scope.IdKind.CELL || id.kind == Scope.IdKind.FREE);
        if (ph == Phase.INTERN_NAME) { if (!deref) internName(u, name); return; }
        if (ph != Phase.EMIT) return;
        if (deref) u.emit.loadDeref(id.localNum);
        else if (id != null && id.kind == Scope.IdKind.LOCAL) u.emit.loadFast(id.localNum);
        else if (id != null && id.kind == Scope.IdKind.GLOBAL_EXPLICIT) u.emit.loadGlobalKind(name, 1);
        else u.emit.loadGlobalKind(name, 0);
    }

    private void emitStore(Unit u, String name) {
        Scope.IdInfo id = u.scope.find(name);
        if (id != null && (id.kind == Scope.IdKind.CELL || id.kind == Scope.IdKind.FREE)) u.emit.storeDeref(id.localNum);
        else if (id != null && id.kind == Scope.IdKind.LOCAL) u.emit.storeFast(id.localNum);
        else if (id != null && id.kind == Scope.IdKind.GLOBAL_EXPLICIT) u.emit.storeGlobalKind(name, 1);
        else u.emit.storeGlobalKind(name, 0);
    }

    private void emitDelete(Unit u, String name) {
        Scope.IdInfo id = u.scope.find(name);
        if (id != null && (id.kind == Scope.IdKind.CELL || id.kind == Scope.IdKind.FREE))
            u.emit.deleteDeref(id.localNum);
        else if (id != null && id.kind == Scope.IdKind.LOCAL) u.emit.deleteFast(id.localNum);
        else if (id != null && id.kind == Scope.IdKind.GLOBAL_EXPLICIT) u.emit.deleteGlobalKind(name, 1);
        else u.emit.deleteGlobalKind(name, 0);
    }

    /** Binary-op index for the fixed-operator rules: | ^ & ** */
    private int fixedOpIndex(int ruleId) {
        if (ruleId == R_expr) return 22;       // __or__
        if (ruleId == R_xor_expr) return 23;   // __xor__
        if (ruleId == R_and_expr) return 24;   // __and__
        return 34;                             // __pow__
    }

    /**
     * compile_comparison: a OP b [OP c ...], supporting the symbolic operators,
     * {@code in}/{@code not in}/{@code is}/{@code is not}, and chained comparisons.
     */
    private void walkComparison(Unit u, ParseNode.Struct s, Phase ph) {
        int n = s.nodes.length;
        walkExpr(u, s.nodes[0], ph);
        boolean multi = n > 3;
        if (ph != Phase.EMIT) {
            for (int i = 1; i + 1 < n; i += 2) walkExpr(u, s.nodes[i + 1], ph);
            return;
        }
        int lFail = multi ? u.emit.newLabel() : -1;
        for (int i = 1; i + 1 < n; i += 2) {
            walkExpr(u, s.nodes[i + 1], ph);
            if (i + 2 < n) {
                u.emit.dupTop();
                u.emit.rotThree();
            }
            int[] opInfo = comparisonOp(s.nodes[i]); // {opIndex, invert}
            u.emit.binaryOp(opInfo[0]);
            if (opInfo[1] == 1) u.emit.unaryOp(3); // NOT_IN / IS_NOT are synthesised
            if (i + 2 < n) u.emit.jumpIfFalseOrPop(lFail);
        }
        if (multi) {
            int lEnd = u.emit.newLabel();
            u.emit.jumpTo(lEnd);
            u.emit.labelAssign(lFail);
            u.emit.adjustStackSize(1);
            u.emit.rotTwo();
            u.emit.popTop();
            u.emit.labelAssign(lEnd);
        }
    }

    /** Resolve a comparison operator node to {opIndex, invert}. */
    private int[] comparisonOp(ParseNode opNode) {
        if (opNode instanceof ParseNode.Token t) {
            if (t.tok == net.mpy.compiler.lex.Tok.KW_IN) return new int[]{6, 0};       // <in>
            return new int[]{t.tok.ordinal() - net.mpy.compiler.lex.Tok.OP_LESS.ordinal(), 0};
        }
        if (opNode instanceof ParseNode.Struct st) {
            if (st.ruleId == R_comp_op_not_in) return new int[]{6, 1};    // IN + NOT
            if (st.ruleId == R_comp_op_is) {
                boolean isNot = st.nodes.length > 0 && !(st.nodes[0] instanceof ParseNode.Null);
                return new int[]{7, isNot ? 1 : 0};                       // IS [+ NOT]
            }
        }
        return new int[]{0, 0};
    }


    /** Index inside x[...]: either a plain expression or a slice (compile_subscript). */
    private void walkSubscriptIndex(Unit u, ParseNode n, Phase ph) {
        // x[a:b, c:d] -> a tuple of subscripts
        if (n instanceof ParseNode.Struct sub && sub.ruleId == R_subscriptlist) {
            for (ParseNode c : sub.nodes) walkSubscriptIndex(u, c, ph);
            if (ph == Phase.EMIT) u.emit.buildTuple(sub.nodes.length);
            return;
        }
        if (!(n instanceof ParseNode.Struct sl)
                || (sl.ruleId != R_subscript_2 && sl.ruleId != R_subscript_3)) {
            walkExpr(u, n, ph);
            return;
        }
        ParseNode.Struct three;
        if (sl.ruleId == R_subscript_2) {
            walkExpr(u, sl.nodes[0], ph);                 // start of slice
            three = (ParseNode.Struct) sl.nodes[1];
        } else {
            if (ph == Phase.EMIT) u.emit.loadConstNone(); // no start
            three = sl;
        }
        ParseNode pn = three.nodes[0];
        if (pn instanceof ParseNode.Null) {
            if (ph == Phase.EMIT) { u.emit.loadConstNone(); u.emit.buildSlice(2); }   // [?:]
        } else if (pn instanceof ParseNode.Struct ps && ps.ruleId == R_subscript_3c) {
            if (ph == Phase.EMIT) u.emit.loadConstNone();
            ParseNode step = ps.nodes[0];
            if (step instanceof ParseNode.Null) {
                if (ph == Phase.EMIT) u.emit.buildSlice(2);                            // [?::]
            } else {
                walkExpr(u, step, ph);
                if (ph == Phase.EMIT) u.emit.buildSlice(3);                            // [?::x]
            }
        } else if (pn instanceof ParseNode.Struct pd && pd.ruleId == R_subscript_3d) {
            walkExpr(u, pd.nodes[0], ph);
            ParseNode.Struct sliceop = (ParseNode.Struct) pd.nodes[1];
            if (sliceop.nodes.length == 0 || sliceop.nodes[0] instanceof ParseNode.Null) {
                if (ph == Phase.EMIT) u.emit.buildSlice(2);                            // [?:x:]
            } else {
                walkExpr(u, sliceop.nodes[0], ph);
                if (ph == Phase.EMIT) u.emit.buildSlice(3);                            // [?:x:x]
            }
        } else {
            walkExpr(u, pn, ph);
            if (ph == Phase.EMIT) u.emit.buildSlice(2);                                // [?:x]
        }
    }


    /** compile_test_if_expr: obj.method() then yield-from. */
    private void awaitObjectMethod(Unit u, String method, Phase ph) {
        if (ph == Phase.INTERN_CONST) { qstrs.intern(method); return; }
        if (ph != Phase.EMIT) return;
        u.emit.loadMethod(method);
        u.emit.callMethod(0, 0);
        u.emit.getIter();
        u.emit.loadConstNone();
        u.emit.yieldFrom();
    }


    /** compile_async_with_stmt_helper: __aenter__/__aexit__ with a try-finally. */
    private void walkAsyncWithItems(Unit u, List<ParseNode> items, int i, ParseNode body, Phase ph) {
        if (i >= items.size()) { walkStmt(u, body, ph); return; }
        ParseNode item = items.get(i);
        if (ph != Phase.EMIT) {
            if (item instanceof ParseNode.Struct wi && wi.ruleId == R_with_item) {
                walkExpr(u, wi.nodes[0], ph);
                awaitObjectMethod(u, "__aenter__", ph);
                walkStore(u, wi.nodes[1], ph);
            } else {
                walkExpr(u, item, ph);
                awaitObjectMethod(u, "__aenter__", ph);
            }
            // the body comes next, then the finally block's qstrs in emit order:
            // BaseException (direct emit, scope pass), __aexit__, __class__, type
            walkAsyncWithItems(u, items, i + 1, body, ph);
            if (ph == Phase.INTERN_CONST) {
                qstrs.intern("BaseException");
                qstrs.intern("__aexit__");
                qstrs.intern("__class__");
            }
            return;
        }

        int lFinallyBlock = u.emit.newLabel();
        int lAexitNoExc = u.emit.newLabel();
        int lRetUnwindJump = u.emit.newLabel();
        int lEnd = u.emit.newLabel();

        if (item instanceof ParseNode.Struct wi && wi.ruleId == R_with_item) {
            walkExpr(u, wi.nodes[0], ph);
            u.emit.dupTop();
            awaitObjectMethod(u, "__aenter__", ph);
            walkStore(u, wi.nodes[1], ph);
        } else {
            walkExpr(u, item, ph);
            u.emit.dupTop();
            awaitObjectMethod(u, "__aenter__", ph);
            u.emit.popTop();
        }

        u.emit.setupBlock(lFinallyBlock, Emitter.SETUP_FINALLY);
        u.emit.pushExcept();
        u.emit.adjustStackSize(3);            // room for a possible UNWIND_JUMP state
        walkAsyncWithItems(u, items, i + 1, body, ph);
        u.emit.adjustStackSize(-3);

        // case 1: no exception -- fall through and call __aexit__
        u.emit.loadConstNone();
        u.emit.rotTwo();
        u.emit.jumpTo(lAexitNoExc);

        // the "finally" block: entered by an exception, a return, or an unwind jump
        u.emit.labelAssign(lFinallyBlock);
        u.emit.dupTop();
        u.emit.loadGlobalKind("BaseException", 1);   // direct EMIT_LOAD_GLOBAL
        u.emit.binaryOp(8);                   // exception match
        u.emit.popJumpIfFalse(lRetUnwindJump);

        // case 2: an exception -- call __aexit__(type(exc), exc, None)
        u.emit.dupTop();
        u.emit.rotThree();
        u.emit.rotTwo();
        u.emit.loadMethod("__aexit__");
        u.emit.rotThree();
        u.emit.rotThree();
        u.emit.dupTop();
        u.emit.loadAttr("__class__");         // type(exc)
        u.emit.rotTwo();
        u.emit.loadConstNone();               // dummy traceback
        u.emit.callMethod(3, 0);
        u.emit.getIter();
        u.emit.loadConstNone();
        u.emit.yieldFrom();
        u.emit.popJumpIfFalse(lEnd);
        u.emit.popTop();                      // swallow the exception
        u.emit.loadConstNone();
        u.emit.jumpTo(lEnd);
        u.emit.adjustStackSize(2);

        // case 3: return or unwind jump
        u.emit.labelAssign(lRetUnwindJump);
        u.emit.rotThree();
        u.emit.rotThree();
        u.emit.labelAssign(lAexitNoExc);
        u.emit.loadMethod("__aexit__");
        u.emit.loadConstNone();
        u.emit.dupTop();
        u.emit.dupTop();
        u.emit.callMethod(3, 0);
        u.emit.getIter();
        u.emit.loadConstNone();
        u.emit.yieldFrom();
        u.emit.popTop();
        u.emit.adjustStackSize(-1);

        u.emit.labelAssign(lEnd);
        u.emit.popExcept();
        u.emit.endFinally();
    }

    /** compile_async_for_stmt: iterate an async iterable via __aiter__/__anext__. */
    private void walkAsyncFor(Unit u, ParseNode.Struct s, Phase ph) {
        ParseNode target = s.nodes[0];
        ParseNode iter = s.nodes[1];
        ParseNode body = s.nodes[2];
        ParseNode elseBody = s.nodes.length > 3 ? s.nodes[3] : ParseNode.Null.INSTANCE;
        if (ph != Phase.EMIT) {
            walkExpr(u, iter, ph);
            awaitObjectMethod(u, "__aiter__", ph);
            awaitObjectMethod(u, "__anext__", ph);
            walkStore(u, target, ph);
            // EMIT_LOAD_GLOBAL is a direct emit, so this qstr is interned in the
            // scope pass rather than with ordinary identifiers
            if (ph == Phase.INTERN_CONST) qstrs.intern("StopAsyncIteration");
            walkStmt(u, body, ph);
            if (!(elseBody instanceof ParseNode.Null)) walkStmt(u, elseBody, ph);
            return;
        }
        int whileElse = u.emit.newLabel();
        int tryException = u.emit.newLabel();
        int tryElse = u.emit.newLabel();
        int tryFinally = u.emit.newLabel();

        walkExpr(u, iter, ph);
        u.emit.loadMethod("__aiter__");
        u.emit.callMethod(0, 0);

        int breakLabel = u.emit.newLabel();
        int continueLabel = u.emit.newLabel();
        loopStack.add(new int[]{breakLabel, continueLabel, 0, u.emit.excLevel()});
        u.emit.labelAssign(continueLabel);

        u.emit.setupBlock(tryException, Emitter.SETUP_EXCEPT);
        u.emit.pushExcept();
        u.emit.dupTop();
        awaitObjectMethod(u, "__anext__", ph);
        walkStore(u, target, ph);
        u.emit.popExceptJump(tryElse);

        u.emit.labelAssign(tryException);
        u.emit.startExceptHandler();
        u.emit.dupTop();
        u.emit.loadGlobalKind("StopAsyncIteration", 1);   // direct EMIT_LOAD_GLOBAL
        u.emit.binaryOp(8);                       // exception match
        u.emit.popJumpIfFalse(tryFinally);
        u.emit.popTop();                          // discard the exception instance
        u.emit.popExceptJump(whileElse);

        u.emit.labelAssign(tryFinally);
        u.emit.adjustStackSize(1);                // the exception is on the stack here
        u.emit.popExcept();
        u.emit.endFinally();
        u.emit.endExceptHandler();

        u.emit.labelAssign(tryElse);
        walkStmt(u, body, ph);
        u.emit.jumpTo(continueLabel);
        loopStack.remove(loopStack.size() - 1);

        u.emit.labelAssign(whileElse);
        if (!(elseBody instanceof ParseNode.Null)) walkStmt(u, elseBody, ph);
        u.emit.labelAssign(breakLabel);
        u.emit.popTop();                          // drop the async iterable
    }

    /** compile_test_if_expr: value_if_true if cond else value_if_false. */
    private void walkIfExpr(Unit u, ParseNode.Struct s, Phase ph) {
        ParseNode.Struct ifElse = (ParseNode.Struct) s.nodes[1];
        if (ph != Phase.EMIT) {
            // Same rule as walkIf: a constant condition is never visited, in either
            // pass, so it must not intern anything.
            if (constTruthiness(ifElse.nodes[0]) == null) walkExpr(u, ifElse.nodes[0], ph);
            walkExpr(u, s.nodes[0], ph);       // success value
            walkExpr(u, ifElse.nodes[1], ph);  // failure value
            return;
        }
        int lFail = u.emit.newLabel();
        int lEnd = u.emit.newLabel();
        cIfCond(u, ifElse.nodes[0], false, lFail);
        walkExpr(u, s.nodes[0], ph);
        u.emit.jumpTo(lEnd);
        u.emit.labelAssign(lFail);
        u.emit.adjustStackSize(-1);
        walkExpr(u, ifElse.nodes[1], ph);
        u.emit.labelAssign(lEnd);
    }

    /** c_del_stmt: del name | del obj[i] | del obj.attr | del (a, b). */
    private void walkDel(Unit u, ParseNode n, Phase ph) {
        checkDeletable(n);
        if (n instanceof ParseNode.Id id) {
            if (ph == Phase.INTERN_NAME) internName(u, id.name);
            else if (ph == Phase.EMIT) emitDelete(u, id.name);
            return;
        }
        if (n instanceof ParseNode.Struct s) {
            if (s.ruleId == R_exprlist || s.ruleId == R_testlist_star_expr) {
                for (ParseNode c : s.nodes) walkDel(u, c, ph);
                return;
            }
            if (s.ruleId == R_atom_paren && s.nodes.length > 0
                    && s.nodes[0] instanceof ParseNode.Struct tc && tc.ruleId == R_testlist_comp) {
                for (ParseNode c : tc.nodes) walkDel(u, c, ph);
                return;
            }
            if (s.ruleId == R_atom_expr_normal) {
                walkExpr(u, s.nodes[0], ph);                 // base object
                List<ParseNode> trailers = new ArrayList<>();
                ParseNode t = s.nodes[1];
                if (t instanceof ParseNode.Struct ts && ts.ruleId == R_atom_expr_trailers) {
                    for (ParseNode c : ts.nodes) trailers.add(c);
                } else {
                    trailers.add(t);
                }
                for (int i = 0; i < trailers.size() - 1; i++) {   // all but the last are loads
                    ParseNode.Struct tr = (ParseNode.Struct) trailers.get(i);
                    if (tr.ruleId == R_trailer_period) {
                        String nm = ((ParseNode.Id) tr.nodes[0]).name;
                        if (ph == Phase.INTERN_CONST) qstrs.intern(nm);
                        else if (ph == Phase.EMIT) u.emit.loadAttr(nm);
                    } else if (tr.ruleId == R_trailer_bracket) {
                        walkSubscriptIndex(u, tr.nodes[0], ph);
                        if (ph == Phase.EMIT) u.emit.loadSubscr();
                    } else if (tr.ruleId == R_trailer_paren) {
                        int[] nc = compileCallArgs(u, tr, ph);
                        if (ph == Phase.EMIT) {
                            if (nc[2] == 1) u.emit.callFunctionVarKw(nc[0], nc[1]);
                            else u.emit.callFunction(nc[0], nc[1]);
                        }
                    }
                }
                ParseNode.Struct last = (ParseNode.Struct) trailers.get(trailers.size() - 1);
                if (last.ruleId == R_trailer_bracket) {
                    walkSubscriptIndex(u, last.nodes[0], ph);
                    if (ph == Phase.EMIT) {                       // delete subscript
                        u.emit.loadNull();
                        u.emit.rotThree();
                        u.emit.storeSubscr();
                    }
                } else if (last.ruleId == R_trailer_period) {
                    String nm = ((ParseNode.Id) last.nodes[0]).name;
                    if (ph == Phase.INTERN_CONST) qstrs.intern(nm);
                    else if (ph == Phase.EMIT) {                   // delete attribute
                        u.emit.loadNull();
                        u.emit.rotTwo();
                        u.emit.storeAttr(nm);
                    }
                }
            }
        }
    }


    // ---- import ----

    /** import a[.b][ as x][, ...]  (compile_import_name / compile_dotted_as_name). */
    private void walkImportName(Unit u, ParseNode.Struct s, Phase ph) {
        List<ParseNode> names = new ArrayList<>();
        ParseNode n0 = s.nodes[0];
        if (n0 instanceof ParseNode.Struct ds && ds.ruleId == R_dotted_as_names) {
            for (ParseNode c : ds.nodes) names.add(c);
        } else {
            names.add(n0);
        }
        for (ParseNode nm : names) {
            if (ph == Phase.EMIT) { u.emit.loadConstSmallInt(0); u.emit.loadConstNone(); }
            String base = doImportName(u, nm, ph);
            if (ph == Phase.EMIT) emitStore(u, base);
            else if (ph == Phase.INTERN_NAME) internName(u, base);
        }
    }

    /**
     * do_import_name: emit IMPORT_NAME for the (possibly dotted) module and return
     * the name to bind. For "a.b.c" the bound name is "a"; for "x as y" it is "y"
     * and the dotted attributes are walked with LOAD_ATTR.
     */
    private String doImportName(Unit u, ParseNode pn, Phase ph) {
        boolean isAs = false;
        String asName = null;
        if (pn instanceof ParseNode.Struct das && das.ruleId == R_dotted_as_name) {
            asName = ((ParseNode.Id) das.nodes[1]).name;
            pn = das.nodes[0];
            isAs = true;
        }
        if (pn instanceof ParseNode.Null) {
            if (ph == Phase.INTERN_CONST) qstrs.intern("");
            else if (ph == Phase.EMIT) u.emit.importName("");
            return isAs ? asName : "";
        }
        if (pn instanceof ParseNode.Id id) {
            if (ph == Phase.INTERN_CONST) qstrs.intern(id.name);
            else if (ph == Phase.EMIT) u.emit.importName(id.name);
            return isAs ? asName : id.name;
        }
        ParseNode.Struct dn = (ParseNode.Struct) pn;   // dotted_name
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < dn.nodes.length; i++) {
            if (i > 0) full.append('.');
            full.append(((ParseNode.Id) dn.nodes[i]).name);
        }
        if (ph == Phase.INTERN_CONST) qstrs.intern(full.toString());
        else if (ph == Phase.EMIT) u.emit.importName(full.toString());
        if (isAs) {
            for (int i = 1; i < dn.nodes.length; i++) {
                String attr = ((ParseNode.Id) dn.nodes[i]).name;
                if (ph == Phase.INTERN_CONST) qstrs.intern(attr);
                else if (ph == Phase.EMIT) u.emit.loadAttr(attr);
            }
            return asName;
        }
        return ((ParseNode.Id) dn.nodes[0]).name;
    }

    /** from m import x[, y] | from m import *  (compile_import_from). */
    private void walkImportFrom(Unit u, ParseNode.Struct s, Phase ph) {
        ParseNode src = s.nodes[0];
        int level = 0;
        // relative imports: leading dots
        while (true) {
            if (src instanceof ParseNode.Token t) {
                level += periodWeight(t); src = ParseNode.Null.INSTANCE; break;
            } else if (src instanceof ParseNode.Struct pe && pe.ruleId == R_one_or_more_period_or_ellipsis) {
                for (ParseNode c : pe.nodes) if (c instanceof ParseNode.Token t2) level += periodWeight(t2);
                src = ParseNode.Null.INSTANCE; break;
            } else if (src instanceof ParseNode.Struct b2 && b2.ruleId == R_import_from_2b) {
                ParseNode rel = b2.nodes[0];
                if (rel instanceof ParseNode.Token t3) level += periodWeight(t3);
                else if (rel instanceof ParseNode.Struct rs) for (ParseNode c : rs.nodes)
                    if (c instanceof ParseNode.Token t4) level += periodWeight(t4);
                src = b2.nodes[1];
                continue;
            }
            break;
        }

        ParseNode what = s.nodes[1];
        boolean star = what instanceof ParseNode.Token t && t.tok == net.mpy.compiler.lex.Tok.OP_STAR;
        if (star) {
            if (ph == Phase.INTERN_CONST) qstrs.intern("*");
            else if (ph == Phase.EMIT) {
                u.emit.loadConstSmallInt(level);
                u.emit.loadConstString("*");
                u.emit.buildTuple(1);
            }
            doImportName(u, src, ph);
            if (ph == Phase.EMIT) u.emit.importStar();
            return;
        }

        List<ParseNode> items = new ArrayList<>();
        if (what instanceof ParseNode.Struct ias && ias.ruleId == R_import_as_names) {
            for (ParseNode c : ias.nodes) items.add(c);
        } else {
            items.add(what);
        }
        if (ph == Phase.EMIT) u.emit.loadConstSmallInt(level);
        for (ParseNode it : items) {
            String nm = ((ParseNode.Id) ((ParseNode.Struct) it).nodes[0]).name;
            if (ph == Phase.INTERN_CONST) qstrs.intern(nm);
            else if (ph == Phase.EMIT) u.emit.loadConstString(nm);
        }
        if (ph == Phase.EMIT) u.emit.buildTuple(items.size());
        doImportName(u, src, ph);
        for (ParseNode it : items) {
            ParseNode.Struct ian = (ParseNode.Struct) it;
            String nm = ((ParseNode.Id) ian.nodes[0]).name;
            String bind = (ian.nodes.length > 1 && ian.nodes[1] instanceof ParseNode.Id b) ? b.name : nm;
            if (ph == Phase.INTERN_CONST) qstrs.intern(nm);
            else if (ph == Phase.EMIT) { u.emit.importFrom(nm); emitStore(u, bind); }
            if (ph == Phase.INTERN_NAME) internName(u, bind);
        }
        if (ph == Phase.EMIT) u.emit.popTop();
    }

    private int periodWeight(ParseNode.Token t) {
        return t.tok == net.mpy.compiler.lex.Tok.ELLIPSIS ? 3 : 1;
    }

    /** The name of the first positional parameter of a scope (used by super()). */
    private String firstParamName(Scope sc) {
        for (Scope.IdInfo id : sc.idInfo) {
            if ((id.flags & Scope.ID_FLAG_IS_PARAM) != 0) return id.qst;
        }
        return null;
    }

    /** compile_atom_expr_await: compile the atom+trailers, then yield from it. */
    private void walkAwait(Unit u, ParseNode.Struct s, Phase ph) {
        if (u.scope.kind != Scope.Kind.FUNCTION && u.scope.kind != Scope.Kind.LAMBDA) {
            throw new net.mpy.compiler.MpCompileException("'await' outside function");
        }
        // atom_expr_await = [atom, opt trailers]  (the await keyword is not a node)
        ParseNode atom = s.nodes[0];
        ParseNode trailers = s.nodes.length > 1 ? s.nodes[1] : ParseNode.Null.INSTANCE;
        if (trailers instanceof ParseNode.Null) {
            walkExpr(u, atom, ph);
        } else {
            walkAtomExprParts(u, null, atom, trailers, ph);
        }
        if (ph == Phase.EMIT) {
            u.emit.getIter();
            u.emit.loadConstNone();
            u.emit.yieldFrom();
        }
    }

    /** Comparison op index = __lt__(0) + (tok - OP_LESS). Single-operator subset. */
    private int comparisonOpIndex(ParseNode opTok) {
        int opLess = net.mpy.compiler.lex.Tok.OP_LESS.ordinal();
        if (opTok instanceof ParseNode.Token t) return t.tok.ordinal() - opLess;
        return 0;
    }

    /** and_test / or_test value: short-circuit with JUMP_IF_{FALSE,TRUE}_OR_POP. */
    private void walkBoolOp(Unit u, ParseNode.Struct s, Phase ph, boolean isOr) {
        if (ph != Phase.EMIT) {
            for (ParseNode c : s.nodes) walkExpr(u, c, ph);
            return;
        }
        int end = u.emit.newLabel();
        for (int i = 0; i < s.nodes.length; i++) {
            walkExpr(u, s.nodes[i], ph);
            if (i < s.nodes.length - 1) {
                if (isOr) u.emit.jumpIfTrueOrPop(end);
                else u.emit.jumpIfFalseOrPop(end);
            }
        }
        u.emit.labelAssign(end);
    }

    /** Map an augassign op token to its inplace binary-op index. */
    private int augBinaryOpIndex(ParseNode opTok) {
        // op = __ior__index + (tok - DEL_PIPE_EQUAL)  (compile.c)
        int iorIndex = 9;
        int pipeEqual = net.mpy.compiler.lex.Tok.DEL_PIPE_EQUAL.ordinal();
        if (opTok instanceof ParseNode.Token t) {
            return iorIndex + (t.tok.ordinal() - pipeEqual);
        }
        return 14; // fallback __iadd__
    }

    private int binaryOpIndex(ParseNode opTok) {
        String dunder = "__add__";
        if (opTok instanceof ParseNode.Token t) {
            switch (t.tok) {
                case OP_PLUS: dunder = "__add__"; break;
                case OP_MINUS: dunder = "__sub__"; break;
                case OP_STAR: dunder = "__mul__"; break;
                case OP_SLASH: dunder = "__truediv__"; break;
                case OP_DBL_SLASH: dunder = "__floordiv__"; break;
                case OP_PERCENT: dunder = "__mod__"; break;
                case OP_DBL_LESS: dunder = "__lshift__"; break;
                case OP_DBL_MORE: dunder = "__rshift__"; break;
                case OP_AT: dunder = "__matmul__"; break;
                default: break;
            }
        }
        for (int i = 0; i < Operators.BINARY_METHOD.length; i++)
            if (Operators.BINARY_METHOD[i].equals(dunder)) return i;
        return 0;
    }

    private String argName(Scope scope, int localNum) {
        for (Scope.IdInfo id : scope.idInfo)
            if ((id.flags & Scope.ID_FLAG_IS_PARAM) != 0 && id.localNum == localNum) return id.qst;
        return "*";
    }

    private void collectStmts(ParseNode n, List<ParseNode> out) {
        if (n instanceof ParseNode.Struct s && (s.ruleId == R_file_input || s.ruleId == R_file_input_2)) {
            for (ParseNode c : s.nodes) collectStmts(c, out);
        } else {
            out.add(n);
        }
    }

    private void collectSuite(ParseNode suite, List<ParseNode> out) {
        if (suite instanceof ParseNode.Struct s && s.ruleId == R_suite) {
            for (ParseNode c : s.nodes) out.add(c);
        } else {
            out.add(suite);
        }
    }
}
