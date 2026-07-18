package net.mpy.compiler.compile;

import net.mpy.compiler.parse.Grammar;
import net.mpy.compiler.parse.ParseNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The SCOPE pass (py/compile.c, MP_PASS_SCOPE) for the verifiable subset:
 * module scope + simple function scopes with positional params and local/global
 * name classification. Verified against the SCOPEDUMP oracle.
 *
 * Classification (mp_emit_common_get_id_for_modification + get_id_for_load):
 *   - store of a name: find-or-add GLOBAL_IMPLICIT; if it was GLOBAL_IMPLICIT
 *     then LOCAL in a func-like scope, else GLOBAL_IMPLICIT_ASSIGNED.
 *   - load of a name: find-or-add GLOBAL_IMPLICIT (stays that if new).
 *   - params: added LOCAL + IS_PARAM before the body walk.
 * scopeComputeThings() then numbers the locals.
 *
 * DEFERRED (next hardening): cells/free (closures), classes, comprehensions,
 * lambdas, global/nonlocal, augmented/multiple/tuple assignment targets,
 * *args/**kwargs, default args, decorators, generators.
 */
public final class ScopeAnalyzer {

    private final List<Scope> scopes = new ArrayList<>();
    private final java.util.Map<ParseNode, Scope> funcScopes = new java.util.IdentityHashMap<>();
    private Scope cur;

    // rule numbers resolved from the generated grammar
    private final int R_file_input = Grammar.number("file_input");
    private final int R_funcdef = Grammar.number("funcdef");
    private final int R_lambdef = Grammar.number("lambdef");
    private final int R_yield_expr = Grammar.number("yield_expr");
    private final int R_namedexpr = Grammar.number("namedexpr_test");
    private final int R_async_stmt = Grammar.number("async_stmt");
    private final int R_async_funcdef = Grammar.number("async_funcdef");
    private final int R_decorated = Grammar.number("decorated");
    private final int R_decorator = Grammar.number("decorator");
    private final int R_decorators = Grammar.number("decorators");
    private final int R_atom_expr_await = Grammar.number("atom_expr_await");
    private final int R_classdef = Grammar.number("classdef");
    private final int R_expr_stmt = Grammar.number("expr_stmt");
    private final int R_return_stmt = Grammar.number("return_stmt");
    private final int R_suite = Grammar.number("suite");
    private final int R_typedargslist = Grammar.number("typedargslist");
    private final int R_typedargslist_name = Grammar.number("typedargslist_name");
    private final int R_varargslist = Grammar.number("varargslist");
    private final int R_varargslist_dbl_star = Grammar.number("varargslist_dbl_star");
    private final int R_varargslist_star = Grammar.number("varargslist_star");
    private final int R_varargslist_name = Grammar.number("varargslist_name");
    private final int R_typedargslist_star = Grammar.number("typedargslist_star");
    private final int R_typedargslist_dbl_star = Grammar.number("typedargslist_dbl_star");
    private final int R_for_stmt = Grammar.number("for_stmt");
    private final int R_if_stmt = Grammar.number("if_stmt");
    private final int R_if_stmt_elif_list = Grammar.number("if_stmt_elif_list");
    private final int R_if_stmt_elif = Grammar.number("if_stmt_elif");
    private final int R_while_stmt = Grammar.number("while_stmt");
    private final int R_global_stmt = Grammar.number("global_stmt");
    private final int R_del_stmt = Grammar.number("del_stmt");
    private final int R_yield_stmt = Grammar.number("yield_stmt");
    private final int R_raise_stmt = Grammar.number("raise_stmt");
    private final int R_assert_stmt = Grammar.number("assert_stmt");
    private final int R_nonlocal_stmt = Grammar.number("nonlocal_stmt");
    private final int R_import_name = Grammar.number("import_name");
    private final int R_import_from = Grammar.number("import_from");
    private final int R_dotted_as_names = Grammar.number("dotted_as_names");
    private final int R_dotted_as_name = Grammar.number("dotted_as_name");
    private final int R_import_as_names = Grammar.number("import_as_names");
    private final int R_atom_paren = Grammar.number("atom_paren");
    private final int R_trailer_paren = Grammar.number("trailer_paren");
    private final int R_dotted_name = Grammar.number("dotted_name");
    private final int R_trailer_period = Grammar.number("trailer_period");
    private final int R_atom_expr_trailers = Grammar.number("atom_expr_trailers");
    private final int R_atom_expr_normal = Grammar.number("atom_expr_normal");
    private final int R_argument = Grammar.number("argument");
    private final int R_arglist_dbl_star = Grammar.number("arglist_dbl_star");
    private final int R_arglist_star = Grammar.number("arglist_star");
    private final int R_arglist = Grammar.number("arglist");
    private final int R_argument_3 = Grammar.number("argument_3");
    private final int R_atom_bracket = Grammar.number("atom_bracket");
    private final int R_atom_brace = Grammar.number("atom_brace");
    private final int R_testlist_comp = Grammar.number("testlist_comp");
    private final int R_dictorsetmaker = Grammar.number("dictorsetmaker");
    private final int R_dictorsetmaker_item = Grammar.number("dictorsetmaker_item");
    private final int R_comp_for = Grammar.number("comp_for");
    private final int R_comp_if = Grammar.number("comp_if");
    private final int R_with_stmt = Grammar.number("with_stmt");
    private final int R_with_stmt_list = Grammar.number("with_stmt_list");
    private final int R_with_item = Grammar.number("with_item");
    private final int R_try_stmt = Grammar.number("try_stmt");
    private final int R_try_stmt_except = Grammar.number("try_stmt_except");
    private final int R_try_stmt_except_and_more = Grammar.number("try_stmt_except_and_more");
    private final int R_try_stmt_except_list = Grammar.number("try_stmt_except_list");
    private final int R_try_stmt_as_name = Grammar.number("try_stmt_as_name");
    private final int R_try_stmt_finally = tryNum("try_stmt_finally");
    private final int R_try_stmt_else = tryNum("try_stmt_else");
    private final int R_testlist_star_expr = Grammar.number("testlist_star_expr");
    private final int R_exprlist = Grammar.number("exprlist");
    private final int R_star_expr = Grammar.number("star_expr");
    private final int R_expr_stmt_assign_list = Grammar.number("expr_stmt_assign_list");
    private final int R_expr_stmt_augassign = Grammar.number("expr_stmt_augassign");
    private final int R_annassign = Grammar.number("annassign");

    /** Analyze a module parse tree; returns all scopes (module first). */
    public static List<Scope> analyze(ParseNode root) {
        return run(root).scopes;
    }

    /** Full analysis; returns the analyzer with the funcdef->scope map. */
    public static ScopeAnalyzer run(ParseNode root) {
        ScopeAnalyzer a = new ScopeAnalyzer();
        Scope module = new Scope(Scope.Kind.MODULE, null);
        module.simpleName = "<module>";
        a.scopes.add(module);
        a.cur = module;
        a.walkModule(root);
        a.drainPending();
        // close-over pass: for each scope (in order), promote GLOBAL_IMPLICIT ids
        // that resolve to an enclosing function local into FREE (and parents to CELL)
        for (Scope s : a.scopes) {
            for (Scope.IdInfo id : new java.util.ArrayList<>(s.idInfo)) {
                if (id.kind == Scope.IdKind.GLOBAL_IMPLICIT) a.checkToCloseOver(s, id);
            }
        }
        for (Scope s : a.scopes) a.computeThings(s);
        return a;
    }

    public List<Scope> scopes() { return scopes; }
    public Scope moduleScope() { return scopes.get(0); }
    public Scope funcScope(ParseNode funcdefNode) { return funcScopes.get(funcdefNode); }

    private boolean isStruct(ParseNode n, int rule) {
        return n instanceof ParseNode.Struct s && s.ruleId == rule;
    }

    /** Deferred body walks: MicroPython processes scopes breadth-first (scope chain order). */
    private final java.util.ArrayDeque<Runnable> pending = new java.util.ArrayDeque<>();

    private void drainPending() {
        while (!pending.isEmpty()) pending.poll().run();
    }

    private void walkModule(ParseNode root) {
        // file_input -> list of statements (or a single statement)
        if (isStruct(root, R_file_input)) {
            for (ParseNode c : ((ParseNode.Struct) root).nodes) walkStmt(c);
        } else {
            walkStmt(root);
        }
    }

    private void walkStmt(ParseNode n) {
        if (!(n instanceof ParseNode.Struct s)) return;
        int r = s.ruleId;
        if (r == R_expr_stmt) {
            walkExprStmt(s);
        } else if (r == R_funcdef) {
            walkFuncdef(s);
        } else if (r == R_classdef) {
            walkClassdef(s);
        } else if (r == R_return_stmt) {
            for (ParseNode c : s.nodes) load(c);
        } else if (r == R_suite) {
            for (ParseNode c : s.nodes) walkStmt(c);
        } else if (r == R_for_stmt) {
            // for target in iter: body [else] -> iter is a load, target a store.
            // "for x in range(...)" is compiled as an incremented variable, so the
            // name "range" is never looked up.
            ParseNode[] rng = optimisedRangeArgs(s);
            if (rng != null) {
                load(rng[1]);           // end
                load(rng[0]);           // start
                store(s.nodes[0]);
                for (int i = 2; i < s.nodes.length; i++) walkStmt(s.nodes[i]);
                load(rng[2]);           // step
            } else {
                load(s.nodes[1]);
                store(s.nodes[0]);
                for (int i = 2; i < s.nodes.length; i++) walkStmt(s.nodes[i]);
            }
        } else if (r == R_with_stmt) {
            // with item [as tgt], ...: body -> ctx expr is a load, as-target a store
            for (int i = 0; i < s.nodes.length - 1; i++) walkWithItemScope(s.nodes[i]);
            walkStmt(s.nodes[s.nodes.length - 1]);
        } else if (r == R_try_stmt) {
            walkStmt(s.nodes[0]); // body
            for (int i = 1; i < s.nodes.length; i++) walkTryClauseScope(s.nodes[i]);
        } else if (r == R_if_stmt) {
            // [cond, body, elif-list, else]. compile_if_stmt drops "if False"
            // branches entirely, so their names must not enter the scope either.
            if (!isConstFalse(s.nodes[0])) {
                load(s.nodes[0]);
                if (s.nodes.length > 1) walkStmt(s.nodes[1]);
                if (isConstTrue(s.nodes[0])) return;
            }
            if (s.nodes.length > 2) walkElifs(s.nodes[2]);
            if (s.nodes.length > 3) walkStmt(s.nodes[3]);
        } else if (r == R_while_stmt) {
            // "while False" emits nothing, so its body contributes no names
            if (!isConstFalse(s.nodes[0])) {
                load(s.nodes[0]);
                if (s.nodes.length > 1) walkStmt(s.nodes[1]);
            }
            for (int i = 2; i < s.nodes.length; i++) walkStmt(s.nodes[i]);
        } else if (r == R_global_stmt) {
            // global x, y -> mark each name GLOBAL_EXPLICIT here and in the module scope
            Scope moduleScope = scopes.get(0);
            for (ParseNode nm : globalNames(s)) {
                String name = nm instanceof ParseNode.Id i ? i.name : "";
                Scope.IdInfo id = cur.findOrAdd(name, Scope.IdKind.UNDECIDED);
                if (id.kind != Scope.IdKind.UNDECIDED && id.kind != Scope.IdKind.GLOBAL_EXPLICIT) {
                    throw new net.mpy.compiler.MpCompileException("identifier redefined as global");
                }
                id.kind = Scope.IdKind.GLOBAL_EXPLICIT;
                // scope_find_global: promote the module-level id too, if it exists
                if (moduleScope != cur) {
                    Scope.IdInfo mid = moduleScope.find(name);
                    if (mid != null) mid.kind = Scope.IdKind.GLOBAL_EXPLICIT;
                }
            }
        } else if (r == R_import_name) {
            // import a[.b][ as x][, ...] -> binds a name in this scope
            ParseNode n0 = s.nodes[0];
            java.util.List<ParseNode> names = new java.util.ArrayList<>();
            if (n0 instanceof ParseNode.Struct ds && ds.ruleId == R_dotted_as_names) {
                for (ParseNode c : ds.nodes) names.add(c);
            } else {
                names.add(n0);
            }
            for (ParseNode nm : names) storeName(importBoundName(nm));
        } else if (r == R_import_from) {
            ParseNode what = s.nodes[1];
            if (!(what instanceof ParseNode.Token)) {          // not "import *"
                java.util.List<ParseNode> items = new java.util.ArrayList<>();
                if (what instanceof ParseNode.Struct ias && ias.ruleId == R_import_as_names) {
                    for (ParseNode c : ias.nodes) items.add(c);
                } else {
                    items.add(what);
                }
                for (ParseNode it : items) {
                    if (it instanceof ParseNode.Struct ian) {
                        String bind = (ian.nodes.length > 1 && ian.nodes[1] instanceof ParseNode.Id b)
                                ? b.name : idName(ian.nodes[0]);
                        if (bind != null) storeName(bind);
                    }
                }
            }
        } else if (r == R_async_funcdef) {
            // @dec async def ...  -> the funcdef scope must be flagged as a generator
            ParseNode inner = s.nodes[0];
            walkStmt(inner);
            if (inner instanceof ParseNode.Struct fs2 && fs2.ruleId == R_funcdef) {
                Scope fsc2 = funcScopes.get(fs2);
                if (fsc2 != null) fsc2.scopeFlags |= Scope.FLAG_GENERATOR;
            }
        } else if (r == R_async_stmt) {
            // async def / async for / async with
            ParseNode inner = s.nodes[0];
            if (inner instanceof ParseNode.Struct af && af.ruleId == R_for_stmt) {
                cur.findOrAdd("StopAsyncIteration", Scope.IdKind.GLOBAL_IMPLICIT);
            }
            walkStmt(inner);
            if (inner instanceof ParseNode.Struct fs && fs.ruleId == R_funcdef) {
                Scope fsc = funcScopes.get(fs);
                if (fsc != null) fsc.scopeFlags |= Scope.FLAG_GENERATOR;   // coroutines are generators
            }
        } else if (r == R_yield_expr) {
            cur.scopeFlags |= Scope.FLAG_GENERATOR;      // yield used as a statement
            for (ParseNode c : s.nodes) load(c);
        } else if (r == R_nonlocal_stmt) {
            if (cur.kind == Scope.Kind.MODULE) {
                throw new net.mpy.compiler.MpCompileException("can't declare nonlocal in outer code");
            }
            // nonlocal x, y -> declare FREE (close over an enclosing function local)
            for (ParseNode nm : globalNames(s)) {
                String name = nm instanceof ParseNode.Id i ? i.name : "";
                Scope.IdInfo id = cur.findOrAdd(name, Scope.IdKind.UNDECIDED);
                if (id.kind == Scope.IdKind.UNDECIDED) {
                    id.kind = Scope.IdKind.GLOBAL_IMPLICIT;
                    checkToCloseOver(cur, id);
                    if (id.kind == Scope.IdKind.GLOBAL_IMPLICIT) {
                        // nothing in an enclosing function to close over
                        throw new net.mpy.compiler.MpCompileException("no binding for nonlocal found");
                    }
                } else if (id.kind != Scope.IdKind.FREE) {
                    throw new net.mpy.compiler.MpCompileException("identifier redefined as nonlocal");
                }
            }
        } else if (r == R_assert_stmt || r == R_raise_stmt || r == R_yield_stmt) {
            // statements whose operands are expressions
            // AssertionError is a direct EMIT_LOAD_GLOBAL, so it never enters id_info
            for (ParseNode c : s.nodes) load(c);
        } else if (r == R_del_stmt) {
            for (ParseNode c : s.nodes) deleteTarget(c);
        } else if (r == R_decorated) {
            // decorator expressions are evaluated in this scope; a dotted name is a
            // load of its first component plus attribute accesses
            loadDecorators(s.nodes[0]);
            walkStmt(s.nodes[1]);
        } else {
            // Generic container (simple_stmt wrappers, suites, ...). Statement rules
            // recurse as statements; anything else is an expression, so route it
            // through load() -- otherwise nested lambdas and comprehensions inside
            // it never get a scope.
            for (ParseNode c : s.nodes) {
                if (c instanceof ParseNode.Struct cs && isStatementRule(cs.ruleId)) walkStmt(c);
                else load(c);
            }
        }
    }

    private void walkExprStmt(ParseNode.Struct s) {
        if (s.nodes.length == 2 && s.nodes[1] instanceof ParseNode.Struct r) {
            if (r.ruleId == R_annassign) {
                if (r.nodes.length > 1 && !(r.nodes[1] instanceof ParseNode.Null)) {
                    load(r.nodes[1]);
                    store(s.nodes[0]);
                } else if (cur.kind == Scope.Kind.FUNCTION && s.nodes[0] instanceof ParseNode.Id id) {
                    cur.findOrAdd(id.name, Scope.IdKind.LOCAL).kind = Scope.IdKind.LOCAL;
                }
                return;
            }
            if (r.ruleId == R_expr_stmt_assign_list) {
                // x = y = value : targets = nodes[0] + assign_list[0..n-2], value = last
                load(r.nodes[r.nodes.length - 1]);
                store(s.nodes[0]);
                for (int i = 0; i < r.nodes.length - 1; i++) store(r.nodes[i]);
                return;
            } else if (r.ruleId == R_expr_stmt_augassign) {
                // target OP= value : target is read+written -> treat as store
                store(s.nodes[0]);
                load(r.nodes[1]);
                return;
            }
        }
        if (s.nodes.length == 2 && !(s.nodes[1] instanceof ParseNode.Null)) {
            load(s.nodes[1]);
            store(s.nodes[0]);
        } else {
            for (ParseNode c : s.nodes) load(c);
        }
    }

    /** classdef nodes: [name, parents, body, scope-slot]. */
    private void walkClassdef(ParseNode.Struct s) {
        String name = idName(s.nodes[0]);
        if (name != null) storeName(name);
        cur.scopeFlags |= Scope.FLAG_REFGLOBALS | Scope.FLAG_HASCONSTS;

        // parent classes are evaluated in the enclosing scope
        if (s.nodes.length > 1) load(s.nodes[1]);

        Scope cscope = new Scope(Scope.Kind.CLASS, cur);
        cscope.simpleName = name;
        scopes.add(cscope);
        funcScopes.put(s, cscope);
        ParseNode body = s.nodes.length > 2 ? s.nodes[2] : ParseNode.Null.INSTANCE;
        pending.add(() -> {
            Scope prev = cur;
            cur = cscope;
            cur.findOrAdd("__class__", Scope.IdKind.LOCAL);
            // the class body starts with: __module__ = __name__
            cur.findOrAdd("__name__", Scope.IdKind.GLOBAL_IMPLICIT);
            storeName("__module__");
            storeName("__qualname__");
            walkStmt(body);
            cur = prev;
        });
    }

    private void walkFuncdef(ParseNode.Struct s) {
        // funcdef nodes: [name, params, retann, suite, blank]
        String name = idName(s.nodes[0]);
        if (name != null) storeName(name); // bind the function name in the current scope
        // close_over_variables_etc sets these on the enclosing scope (native-emitter flags)
        cur.scopeFlags |= Scope.FLAG_REFGLOBALS | Scope.FLAG_HASCONSTS;

        Scope fscope = new Scope(Scope.Kind.FUNCTION, cur);
        fscope.simpleName = name;
        scopes.add(fscope);
        funcScopes.put(s, fscope);
        ParseNode params = s.nodes.length > 1 ? s.nodes[1] : ParseNode.Null.INSTANCE;
        ParseNode body = s.nodes.length > 3 ? s.nodes[3] : ParseNode.Null.INSTANCE;
        loadParamDefaults(params);   // defaults are evaluated in the enclosing scope
        pending.add(() -> {
            Scope prev = cur;
            cur = fscope;
            addParams(params);   // params first (LOCAL + IS_PARAM), in order
            walkStmt(body);
            cur = prev;
        });
    }

    private boolean haveStar = false;

    private void addParams(ParseNode params) {
        haveStar = false;
        numDefaultsSeen = 0;
        addParamsRec(params);
    }

    private void addParamsRec(ParseNode params) {
        if (params instanceof ParseNode.Id id) {
            checkDefaultOrder(false);
            addNamedParam(id.name);
        } else if (params instanceof ParseNode.Struct st) {
            if (st.ruleId == R_typedargslist || st.ruleId == R_varargslist) {
                for (ParseNode p : st.nodes) addParamsRec(p);
            } else if (st.ruleId == R_typedargslist_name) {
                // def param: [name, annotation, default]
                if (st.nodes.length > 0 && st.nodes[0] instanceof ParseNode.Id id) {
                    boolean hasDefault = st.nodes.length > 2 && !(st.nodes[2] instanceof ParseNode.Null);
                    checkDefaultOrder(hasDefault);
                    addNamedParam(id.name);
                    if (hasDefault) countDefault();
                }
            } else if (st.ruleId == R_varargslist_name) {
                // lambda param: [name, default]
                if (st.nodes.length > 0 && st.nodes[0] instanceof ParseNode.Id id) {
                    boolean hasDefault = st.nodes.length > 1 && !(st.nodes[1] instanceof ParseNode.Null);
                    checkDefaultOrder(hasDefault);
                    addNamedParam(id.name);
                    if (hasDefault) countDefault();
                }
            } else if (st.ruleId == R_typedargslist_star || st.ruleId == R_varargslist_star) {
                if (haveStar) {
                    throw new net.mpy.compiler.MpCompileException("invalid syntax");   // more than one star
                }
                haveStar = true;
                ParseNode nameNode = st.nodes.length > 0 ? st.nodes[0] : ParseNode.Null.INSTANCE;
                if (!(nameNode instanceof ParseNode.Null)) {
                    // named *args: sets VARARGS and is a real parameter
                    cur.scopeFlags |= Scope.FLAG_VARARGS;
                    String nm = starName(nameNode);
                    if (nm != null) addStarParam(nm, Scope.ID_FLAG_IS_STAR_PARAM);
                }
                // a bare "*" only marks the start of keyword-only parameters
            } else if (st.ruleId == R_typedargslist_dbl_star || st.ruleId == R_varargslist_dbl_star) {
                cur.scopeFlags |= Scope.FLAG_VARKEYWORDS;
                if (st.nodes.length > 0 && st.nodes[0] instanceof ParseNode.Id id)
                    addStarParam(id.name, Scope.ID_FLAG_IS_DBL_STAR_PARAM);
            } else {
                for (ParseNode p : st.nodes) addParamsRec(p);
            }
        }
    }

    private int numDefaultsSeen = 0;

    /** A normal parameter: positional before a star, keyword-only after it. */
    private void addNamedParam(String name) {
        if (haveStar) cur.numKwonlyArgs++;
        else cur.numPosArgs++;
        Scope.IdInfo id = cur.findOrAdd(name, Scope.IdKind.UNDECIDED);
        if (id.kind != Scope.IdKind.UNDECIDED) {
            throw new net.mpy.compiler.MpCompileException("argument name reused");
        }
        id.kind = Scope.IdKind.LOCAL;
        id.flags |= Scope.ID_FLAG_IS_PARAM;
    }

    /**
     * A parameter without a default may not follow one that has a default,
     * unless a star has been seen (keyword-only parameters may be mandatory).
     */
    private void checkDefaultOrder(boolean hasDefault) {
        if (!hasDefault && !haveStar && numDefaultsSeen != 0) {
            throw new net.mpy.compiler.MpCompileException("non-default argument follows default argument");
        }
        if (hasDefault) numDefaultsSeen++;
    }

    private void countDefault() {
        if (!haveStar) cur.numDefPosArgs++;   // kw-only defaults live in a dict instead
    }

    private String starName(ParseNode n) {
        if (n instanceof ParseNode.Id i) return i.name;
        if (n instanceof ParseNode.Struct s2) {
            for (ParseNode c : s2.nodes) if (c instanceof ParseNode.Id i2) return i2.name;
        }
        return null;
    }

    /** Find the name id inside a typedargslist_star node. */
    private ParseNode starParamName(ParseNode.Struct star) {
        for (ParseNode n : star.nodes) {
            if (n instanceof ParseNode.Id) return n;
            if (n instanceof ParseNode.Struct s) for (ParseNode c : s.nodes) if (c instanceof ParseNode.Id) return c;
        }
        return ParseNode.Null.INSTANCE;
    }

    private void addStarParam(String name, int flag) {
        Scope.IdInfo id = cur.findOrAdd(name, Scope.IdKind.UNDECIDED);
        if (id.kind != Scope.IdKind.UNDECIDED) {
            throw new net.mpy.compiler.MpCompileException("argument name reused");
        }
        id.kind = Scope.IdKind.LOCAL;
        id.flags |= Scope.ID_FLAG_IS_PARAM | flag;
    }

    private void addParam(String name) {
        Scope.IdInfo id = cur.findOrAdd(name, Scope.IdKind.LOCAL);
        id.kind = Scope.IdKind.LOCAL;
        id.flags |= Scope.ID_FLAG_IS_PARAM;
        cur.numPosArgs++;
    }

    // --- store / load classification ---

    private void store(ParseNode n) {
        String name = idName(n);
        if (name != null) { storeName(name); return; }
        if (n instanceof ParseNode.Struct s) {
            if (s.ruleId == R_atom_paren || s.ruleId == R_atom_bracket) {
                ParseNode inner = s.nodes.length > 0 ? s.nodes[0] : ParseNode.Null.INSTANCE;
                if (inner instanceof ParseNode.Struct tc2 && tc2.ruleId == R_testlist_comp) {
                    for (ParseNode c : tc2.nodes) store(c);
                } else if (!(inner instanceof ParseNode.Null)) {
                    store(inner);
                }
            } else if (s.ruleId == R_star_expr) {
                store(s.nodes[0]);                    // *b binds b
            } else if (s.ruleId == R_testlist_star_expr || s.ruleId == R_exprlist) {
                for (ParseNode c : s.nodes) store(c); // tuple targets: each is a store
            } else {
                // attr/subscript target: base object is a load
                for (ParseNode c : s.nodes) load(c);
            }
        }
    }

    private void storeName(String name) {
        Scope.IdInfo id = cur.findOrAdd(name, Scope.IdKind.GLOBAL_IMPLICIT);
        if (id.kind == Scope.IdKind.GLOBAL_IMPLICIT) {
            id.kind = cur.kind.isFuncLike() ? Scope.IdKind.LOCAL : Scope.IdKind.GLOBAL_IMPLICIT_ASSIGNED;
        }
    }

    private void load(ParseNode n) {
        if (n instanceof ParseNode.Id id) {
            cur.findOrAdd(id.name, Scope.IdKind.GLOBAL_IMPLICIT);
        } else if (n instanceof ParseNode.Struct sup && isBareSuperCall(sup)) {
            // super() implicitly loads __class__ (which closes it over from the class scope)
            cur.findOrAdd("__class__", Scope.IdKind.GLOBAL_IMPLICIT);
            for (ParseNode c : sup.nodes) load(c);
        } else if (n instanceof ParseNode.Struct aw && aw.ruleId == R_atom_expr_await) {
            cur.scopeFlags |= Scope.FLAG_GENERATOR;   // await implies a coroutine
            for (ParseNode c : aw.nodes) load(c);
        } else if (n instanceof ParseNode.Struct na2 && na2.ruleId == R_argument
                && na2.nodes.length > 1 && na2.nodes[1] instanceof ParseNode.Struct a32
                && a32.ruleId == R_argument_3) {
            load(a32.nodes[0]);     // f(x := v) binds x
            store(na2.nodes[0]);
        } else if (n instanceof ParseNode.Struct ne && ne.ruleId == R_namedexpr) {
            load(ne.nodes[1]);      // value
            String tname = idName(ne.nodes[0]);
            if (tname != null && isCompLike(cur.kind) && cur.parent != null) {
                declareWalrusInComprehension(tname);   // PEP 572: binds in the enclosing scope
            } else {
                store(ne.nodes[0]);
            }
        } else if (n instanceof ParseNode.Struct ys && ys.ruleId == R_yield_expr) {
            cur.scopeFlags |= Scope.FLAG_GENERATOR;
            for (ParseNode c : ys.nodes) load(c);
        } else if (n instanceof ParseNode.Struct s && s.ruleId == R_lambdef) {
            // lambda params: body -> a LAMBDA scope
            Scope lam = new Scope(Scope.Kind.LAMBDA, cur);
            lam.simpleName = "<lambda>";
            scopes.add(lam);
            funcScopes.put(s, lam);
            loadParamDefaults(s.nodes[0]);   // lambda defaults live in the enclosing scope
            pending.add(() -> {
                Scope prev = cur;
                cur = lam;
                addParams(s.nodes[0]);
                load(s.nodes[1]); // body expression
                cur = prev;
            });
        } else if (n instanceof ParseNode.Struct cs && comprehensionKind(cs) != null) {
            loadComprehension(cs);
        } else if (n instanceof ParseNode.Struct kw && kw.ruleId == R_argument
                && kw.nodes.length > 1) {
            // f(name=value): the keyword name is a string constant, not an identifier
            load(kw.nodes[1]);
        } else if (n instanceof ParseNode.Struct tp
                && (tp.ruleId == R_trailer_period || tp.ruleId == R_dotted_name)) {
            // ".attr" / ".method" names are constants, not identifiers: they must not
            // be registered in the scope (that would shift local numbering)
        } else if (n instanceof ParseNode.Struct s) {
            for (ParseNode c : s.nodes) load(c);
        }
        // leaves that aren't ids (int/str/token/const) bind nothing
    }

    /** Determine comprehension kind for an atom node, or null if not a comprehension. */
    Scope.Kind comprehensionKind(ParseNode.Struct atom) {
        // bare generator expression as a call argument: f(x for x in y)
        if (atom.ruleId == R_argument && atom.nodes.length == 2 && isCompFor(atom.nodes[1])) {
            return Scope.Kind.GEN_EXPR;
        }
        if (atom.ruleId == R_atom_paren && atom.nodes.length == 1
                && atom.nodes[0] instanceof ParseNode.Struct tp && tp.ruleId == R_testlist_comp
                && tp.nodes.length == 2 && isCompFor(tp.nodes[1])) {
            return Scope.Kind.GEN_EXPR;
        }
        if (atom.ruleId == R_atom_bracket && atom.nodes.length == 1
                && atom.nodes[0] instanceof ParseNode.Struct tc && tc.ruleId == R_testlist_comp
                && tc.nodes.length == 2 && isCompFor(tc.nodes[1])) {
            return Scope.Kind.LIST_COMP;
        }
        if (atom.ruleId == R_atom_brace && atom.nodes.length == 1
                && atom.nodes[0] instanceof ParseNode.Struct dm && dm.ruleId == R_dictorsetmaker
                && dm.nodes.length == 2 && isCompFor(dm.nodes[1])) {
            return (dm.nodes[0] instanceof ParseNode.Struct it && it.ruleId == R_dictorsetmaker_item)
                    ? Scope.Kind.DICT_COMP : Scope.Kind.SET_COMP;
        }
        return null;
    }

    private boolean isCompFor(ParseNode n) {
        return n instanceof ParseNode.Struct s && s.ruleId == R_comp_for;
    }

    /** The [innerExpr, compFor] of a comprehension atom. */
    ParseNode comprehensionInner(ParseNode.Struct atom) {
        if (atom.ruleId == R_argument) return atom.nodes[0];
        return ((ParseNode.Struct) atom.nodes[0]).nodes[0];
    }
    ParseNode.Struct comprehensionCompFor(ParseNode.Struct atom) {
        if (atom.ruleId == R_argument) return (ParseNode.Struct) atom.nodes[1];
        return (ParseNode.Struct) ((ParseNode.Struct) atom.nodes[0]).nodes[1];
    }

    private void loadComprehension(ParseNode.Struct atom) {
        Scope.Kind kind = comprehensionKind(atom);
        ParseNode.Struct compFor = comprehensionCompFor(atom);
        // The comprehension's own scope is created first (compile_comprehension
        // does scope_new_and_link before compiling anything), so a nested
        // comprehension in the first iterable is linked after it, not before.
        Scope comp = new Scope(kind, cur);
        comp.simpleName = compSimpleName(kind);
        if (kind == Scope.Kind.GEN_EXPR) {
            comp.scopeFlags |= Scope.FLAG_GENERATOR;   // gen-exprs yield, so they are generators
        }
        scopes.add(comp);
        funcScopes.put(atom, comp);
        // the FIRST iterable is evaluated in the enclosing scope
        load(compFor.nodes[1]);
        pending.add(() -> {
            Scope prev = cur;
            cur = comp;
            addParam("*"); // the iterator argument (mpy-cross uses the qstr "*")
            walkCompForScope(compFor, comprehensionInner(atom), kind);
            cur = prev;
        });
    }

    private void walkCompForScope(ParseNode.Struct compFor, ParseNode innerExpr, Scope.Kind kind) {
        store(compFor.nodes[0]); // loop target
        ParseNode iter = compFor.nodes[2];
        while (true) {
            if (iter instanceof ParseNode.Null) {
                if (innerExpr instanceof ParseNode.Struct it && it.ruleId == R_dictorsetmaker_item) {
                    load(it.nodes[1]); load(it.nodes[0]);
                } else {
                    load(innerExpr);
                }
                return;
            } else if (iter instanceof ParseNode.Struct ci && ci.ruleId == R_comp_if) {
                load(ci.nodes[0]);
                iter = ci.nodes[1];
            } else if (iter instanceof ParseNode.Struct cf && cf.ruleId == R_comp_for) {
                load(cf.nodes[1]); // nested iterable (inside comp)
                store(cf.nodes[0]);
                iter = cf.nodes[2];
            } else return;
        }
    }

    public String compSimpleNameFor(Scope.Kind kind) { return compSimpleName(kind); }
    private String compSimpleName(Scope.Kind kind) {
        switch (kind) {
            case LIST_COMP: return "<listcomp>";
            case DICT_COMP: return "<dictcomp>";
            case SET_COMP: return "<setcomp>";
            default: return "<genexpr>";
        }
    }

    private static int tryNum(String n) { try { return Grammar.number(n); } catch (Exception e) { return -999; } }

    private String idName(ParseNode n) { return n instanceof ParseNode.Id id ? id.name : null; }

    /** Decorator list: bind the base name of each decorator, plus any arguments. */
    private void loadDecorators(ParseNode n) {
        if (!(n instanceof ParseNode.Struct s)) return;
        if (s.ruleId == R_decorator) {
            ParseNode name = s.nodes[0];
            if (name instanceof ParseNode.Struct dn && dn.ruleId == R_dotted_name) {
                load(dn.nodes[0]);            // only the first component is a name
            } else {
                load(name);
            }
            for (int i = 1; i < s.nodes.length; i++) load(s.nodes[i]);   // arguments
        } else if (s.ruleId == R_decorators) {
            for (ParseNode c : s.nodes) loadDecorators(c);
        } else {
            load(n);
        }
    }

    /** Mirrors ModuleCompiler.optimisedRangeArgs: {start, end, step} or null. */
    ParseNode[] optimisedRangeArgs(ParseNode.Struct s) {
        if (!(s.nodes[0] instanceof ParseNode.Id)) return null;
        if (!(s.nodes[1] instanceof ParseNode.Struct it) || it.ruleId != R_atom_expr_normal) return null;
        if (!(it.nodes[0] instanceof ParseNode.Id fn) || !fn.name.equals("range")) return null;
        if (!(it.nodes[1] instanceof ParseNode.Struct tp) || tp.ruleId != R_trailer_paren) return null;
        java.util.List<ParseNode> args = new java.util.ArrayList<>();
        ParseNode an = tp.nodes.length > 0 ? tp.nodes[0] : ParseNode.Null.INSTANCE;
        if (an instanceof ParseNode.Null) {
            // none
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

    private boolean isConstTrue(ParseNode n) { return Boolean.TRUE.equals(constTruthiness(n)); }
    private boolean isConstFalse(ParseNode n) { return Boolean.FALSE.equals(constTruthiness(n)); }

    /** parse_node_is_const_bool: truthiness of a constant node, else null. */
    private Boolean constTruthiness(ParseNode n) {
        if (n instanceof ParseNode.SmallInt si) return si.value != 0;
        if (n instanceof ParseNode.Str st) return !st.name.isEmpty();
        if (n instanceof ParseNode.Token t) {
            if (t.tok == net.mpy.compiler.lex.Tok.KW_TRUE) return true;
            if (t.tok == net.mpy.compiler.lex.Tok.KW_FALSE || t.tok == net.mpy.compiler.lex.Tok.KW_NONE) return false;
            if (t.tok == net.mpy.compiler.lex.Tok.ELLIPSIS) return true;
        }
        if (n instanceof ParseNode.Const c) {
            net.mpy.compiler.model.MpConst v = c.value;
            if (v instanceof net.mpy.compiler.model.MpConst.Int i) return i.value().signum() != 0;
            if (v instanceof net.mpy.compiler.model.MpConst.Float f) return f.value() != 0.0;
            if (v instanceof net.mpy.compiler.model.MpConst.Str s2) return !s2.value().isEmpty();
            if (v instanceof net.mpy.compiler.model.MpConst.Bytes b) return b.value().length != 0;
            if (v instanceof net.mpy.compiler.model.MpConst.Tuple tp) return !tp.items().isEmpty();
            if (v instanceof net.mpy.compiler.model.MpConst.Bool bo) return bo.value();
            if (v instanceof net.mpy.compiler.model.MpConst.None) return false;
            if (v instanceof net.mpy.compiler.model.MpConst.Ellipsis) return true;
            if (v instanceof net.mpy.compiler.model.MpConst.Complex cx) return cx.real() != 0.0 || cx.imag() != 0.0;
        }
        return null;
    }

    /** elif chains: each entry is [condition, body]. */
    private void walkElifs(ParseNode n) {
        if (!(n instanceof ParseNode.Struct s)) return;
        if (s.ruleId == R_if_stmt_elif) {
            if (isConstFalse(s.nodes[0])) return;   // "elif False": dropped
            load(s.nodes[0]);
            if (s.nodes.length > 1) walkStmt(s.nodes[1]);
        } else if (s.ruleId == R_if_stmt_elif_list) {
            for (ParseNode c : s.nodes) walkElifs(c);
        } else {
            walkStmt(n);
        }
    }

    /** Statement-level rules, which the generic fallback recurses into as statements. */
    private boolean isStatementRule(int r) {
        return STATEMENT_RULES.contains(r);
    }

    private final java.util.Set<Integer> STATEMENT_RULES = java.util.Set.of(
            R_expr_stmt, R_funcdef, R_classdef, R_if_stmt, R_while_stmt, R_for_stmt,
            R_try_stmt, R_with_stmt, R_return_stmt, R_global_stmt, R_nonlocal_stmt,
            R_import_name, R_import_from, R_async_stmt, R_async_funcdef, R_suite,
            R_assert_stmt, R_raise_stmt, R_yield_stmt, R_del_stmt, R_decorated,
            tryNum("simple_stmt_2"), tryNum("suite_block_stmts"), tryNum("file_input_2"),
            tryNum("pass_stmt"), tryNum("break_stmt"), tryNum("continue_stmt"));

    /**
     * del targets. In the scope pass compile_delete_id does exactly what
     * compile_store_id does (mp_emit_common_get_id_for_modification), so
     * "del name" is a binding occurrence and makes the name local in a function.
     */
    private void deleteTarget(ParseNode n) {
        String name = idName(n);
        if (name != null) { storeName(name); return; }
        if (n instanceof ParseNode.Struct s
                && (s.ruleId == R_exprlist || s.ruleId == R_testlist_star_expr)) {
            for (ParseNode c : s.nodes) deleteTarget(c);
            return;
        }
        load(n);   // del obj.attr / obj[i]: the base is only read
    }

    private static boolean isCompLike(Scope.Kind k) {
        return k == Scope.Kind.LIST_COMP || k == Scope.Kind.DICT_COMP
                || k == Scope.Kind.SET_COMP || k == Scope.Kind.GEN_EXPR;
    }

    /**
     * PEP 572: a := inside a comprehension binds in the enclosing scope, so the
     * target is declared global (comprehension at module level) or closed over.
     */
    private void declareWalrusInComprehension(String name) {
        Scope parent = cur.parent;
        Scope prev = cur;
        cur = parent;
        storeName(name);                       // the enclosing scope owns the binding
        cur = prev;
        Scope.IdInfo pid = parent.find(name);
        if (pid != null && pid.kind == Scope.IdKind.GLOBAL_EXPLICIT) {
            cur.findOrAdd(name, Scope.IdKind.GLOBAL_EXPLICIT).kind = Scope.IdKind.GLOBAL_EXPLICIT;
            return;
        }
        boolean isGlobal = parent.parent == null;   // comprehension defined at module level
        Scope.IdInfo id = cur.findOrAdd(name, Scope.IdKind.UNDECIDED);
        if (!isGlobal && id.kind == Scope.IdKind.GLOBAL_IMPLICIT) id.kind = Scope.IdKind.UNDECIDED;
        if (isGlobal) {
            id.kind = Scope.IdKind.GLOBAL_EXPLICIT;
            // compile_declare_global does a second step: "if the id exists in the
            // global scope, set its kind to EXPLICIT_GLOBAL". Without it the module
            // scope still sees an ordinary assigned name, and a later read there
            // emits LOAD_NAME where mpy-cross emits LOAD_GLOBAL.
            Scope g = cur;
            while (g.parent != null) g = g.parent;
            Scope.IdInfo gid = g.find(name);
            if (gid != null) gid.kind = Scope.IdKind.GLOBAL_EXPLICIT;
        } else if (id.kind == Scope.IdKind.UNDECIDED) {
            id.kind = Scope.IdKind.GLOBAL_IMPLICIT;
            checkToCloseOver(cur, id);
        }
    }

    /** Walk parameter default-value expressions in the current (enclosing) scope. */
    private void loadParamDefaults(ParseNode params) {
        if (!(params instanceof ParseNode.Struct st)) return;
        if (st.ruleId == R_typedargslist || st.ruleId == R_varargslist) {
            for (ParseNode p : st.nodes) loadParamDefaults(p);
        } else if (st.ruleId == R_typedargslist_name) {
            if (st.nodes.length > 2 && !(st.nodes[2] instanceof ParseNode.Null)) load(st.nodes[2]);
        } else if (st.ruleId == R_varargslist_name) {
            if (st.nodes.length > 1 && !(st.nodes[1] instanceof ParseNode.Null)) load(st.nodes[1]);
        }
    }

    /** Does this atom_expr start with a no-argument super() call? */
    boolean isBareSuperCall(ParseNode.Struct s) {
        if (s.ruleId != R_atom_expr_normal) return false;
        if (!(s.nodes[0] instanceof ParseNode.Id id) || !id.name.equals("super")) return false;
        ParseNode t = s.nodes[1];
        ParseNode first = (t instanceof ParseNode.Struct ts && ts.ruleId == R_atom_expr_trailers)
                ? ts.nodes[0] : t;
        return first instanceof ParseNode.Struct fp && fp.ruleId == R_trailer_paren
                && (fp.nodes.length == 0 || fp.nodes[0] instanceof ParseNode.Null);
    }

    /** The name an "import ..." clause binds: "x as y" -> y, "a.b.c" -> a, "a" -> a. */
    private String importBoundName(ParseNode pn) {
        if (pn instanceof ParseNode.Struct das && das.ruleId == R_dotted_as_name) {
            return idName(das.nodes[1]);
        }
        if (pn instanceof ParseNode.Id id) return id.name;
        if (pn instanceof ParseNode.Struct dn && dn.nodes.length > 0) return idName(dn.nodes[0]);
        return "";
    }

    private void walkWithItemScope(ParseNode n) {
        if (n instanceof ParseNode.Struct s && s.ruleId == R_with_stmt_list) {
            for (ParseNode c : s.nodes) walkWithItemScope(c);
        } else if (n instanceof ParseNode.Struct wi && wi.ruleId == R_with_item) {
            load(wi.nodes[0]);   // ctx expr
            store(wi.nodes[1]);  // as target
        } else {
            load(n);
        }
    }

    private void walkTryClauseScope(ParseNode n) {
        if (!(n instanceof ParseNode.Struct s)) return;
        if (s.ruleId == R_try_stmt_except) {
            ParseNode spec = s.nodes[0];
            if (spec instanceof ParseNode.Struct as && as.ruleId == R_try_stmt_as_name) {
                load(as.nodes[0]);   // exception type
                store(as.nodes[1]);  // as name
            } else if (!(spec instanceof ParseNode.Null)) {
                load(spec);
            }
            walkStmt(s.nodes[1]); // handler body
        } else if (s.ruleId == R_try_stmt_except_and_more || s.ruleId == R_try_stmt_except_list
                || s.ruleId == R_try_stmt_finally || s.ruleId == R_try_stmt_else) {
            for (ParseNode c : s.nodes) walkTryClauseScope(c);
        } else {
            walkStmt(n); // else/finally body
        }
    }

    /** Names declared by a global/nonlocal statement (single id or name_list). */
    private java.util.List<ParseNode> globalNames(ParseNode.Struct s) {
        java.util.List<ParseNode> out = new java.util.ArrayList<>();
        for (ParseNode n : s.nodes) {
            if (n instanceof ParseNode.Id) out.add(n);
            else if (n instanceof ParseNode.Struct st) for (ParseNode c : st.nodes) if (c instanceof ParseNode.Id) out.add(c);
        }
        return out;
    }

    // --- scope_compute_things: local numbering (subset: no cells/free) ---
    /** scope_check_to_close_over: mark id FREE if an enclosing function has it. */
    private void checkToCloseOver(Scope scope, Scope.IdInfo id) {
        if (scope.parent == null) return;
        for (Scope s = scope.parent; s.parent != null; s = s.parent) {
            Scope.IdInfo id2 = s.find(id.qst);
            if (id2 != null) {
                if (id2.kind == Scope.IdKind.LOCAL || id2.kind == Scope.IdKind.CELL || id2.kind == Scope.IdKind.FREE) {
                    id.kind = Scope.IdKind.FREE;
                    closeOverInParents(scope, id.qst);
                }
                break;
            }
        }
    }

    /** scope_close_over_in_parents: mark the binding CELL in its owner, FREE in between. */
    private void closeOverInParents(Scope scope, String name) {
        for (Scope s = scope.parent; ; s = s.parent) {
            Scope.IdInfo id = s.findOrAdd(name, Scope.IdKind.UNDECIDED);
            if (id.kind == Scope.IdKind.UNDECIDED) {
                id.kind = Scope.IdKind.FREE;
            } else {
                if (id.kind == Scope.IdKind.LOCAL) id.kind = Scope.IdKind.CELL;
                return;
            }
        }
    }

    private void computeThings(Scope scope) {
        // MicroPython places the *x parameter after all other parameters (except **y)
        if ((scope.scopeFlags & Scope.FLAG_VARARGS) != 0) {
            int paramIdx = -1;
            for (int i = scope.idInfo.size() - 1; i >= 0; i--) {
                Scope.IdInfo id = scope.idInfo.get(i);
                if ((id.flags & Scope.ID_FLAG_IS_STAR_PARAM) != 0) {
                    if (paramIdx >= 0) {
                        Scope.IdInfo other = scope.idInfo.get(paramIdx);
                        scope.idInfo.set(paramIdx, id);
                        scope.idInfo.set(i, other);
                    }
                    break;
                } else if (paramIdx < 0 && id.flags == Scope.ID_FLAG_IS_PARAM) {
                    paramIdx = i;
                }
            }
        }
        scope.numLocals = 0;
        // 1. number LOCAL variables (and param-cells keep their param slot)
        for (Scope.IdInfo id : scope.idInfo) {
            // __class__ is not counted as a local in a class scope (it becomes a CELL if used)
            if (scope.kind == Scope.Kind.CLASS && "__class__".equals(id.qst)) continue;
            if (scope.kind.isFuncLike() && id.kind == Scope.IdKind.GLOBAL_IMPLICIT) {
                id.kind = Scope.IdKind.GLOBAL_EXPLICIT;
            }
            if (id.kind == Scope.IdKind.GLOBAL_EXPLICIT) {
                scope.scopeFlags |= Scope.FLAG_REFGLOBALS;
            }
            if (id.kind == Scope.IdKind.LOCAL
                    || (id.kind == Scope.IdKind.CELL && (id.flags & Scope.ID_FLAG_IS_PARAM) != 0)) {
                id.localNum = scope.numLocals++;
            }
        }
        // 2. number non-param CELL variables
        for (Scope.IdInfo id : scope.idInfo) {
            if (id.kind == Scope.IdKind.CELL && (id.flags & Scope.ID_FLAG_IS_PARAM) == 0) {
                id.localNum = scope.numLocals++;
            }
        }
        // 3. number FREE variables from the parent's cell/free order; free vars go first
        if (scope.parent != null) {
            int numFree = 0;
            for (Scope.IdInfo pid : scope.parent.idInfo) {
                if (pid.kind == Scope.IdKind.CELL || pid.kind == Scope.IdKind.FREE) {
                    for (Scope.IdInfo id2 : scope.idInfo) {
                        if (id2.kind == Scope.IdKind.FREE && pid.qst.equals(id2.qst)) {
                            id2.localNum = numFree++;
                        }
                    }
                }
            }
            if (numFree > 0) {
                for (Scope.IdInfo id : scope.idInfo) {
                    if (id.kind != Scope.IdKind.FREE || (id.flags & Scope.ID_FLAG_IS_PARAM) != 0) {
                        id.localNum += numFree;
                    }
                }
                scope.numPosArgs += numFree; // free vars counted as params (for passing them in)
                scope.numLocals += numFree;
            }
        }
    }

    /** Dump scopes in the SCOPEDUMP oracle format for differential testing. */
    public static String dump(List<Scope> scopes) {
        StringBuilder sb = new StringBuilder();
        for (Scope s : scopes) {
            sb.append(String.format("SCOPE kind=%d flags=0x%x n_pos=%d n_kwonly=%d nlocal=%d%n",
                    s.kind.ordinal(), s.scopeFlags, s.numPosArgs, s.numKwonlyArgs, s.numLocals));
            for (Scope.IdInfo id : s.idInfo) {
                sb.append(String.format("  ID %s kind=%d flags=0x%x local_num=%d%n",
                        id.qst, id.kind.ordinal(), id.flags, id.localNum));
            }
        }
        return sb.toString();
    }
}
