package net.mpy.compiler.parse;

import net.mpy.compiler.lex.Tok;

/**
 * The MicroPython grammar table (GENERATED from py/grammar.h).
 *
 * Rule numbers use the exact 2-pass enum scheme from parse.c: all rules
 * WITH a compile function first (in grammar.h order), then const_object,
 * then all rules WITHOUT one. Verified: expr_stmt = 5 (matches parse oracle).
 *
 * Each rule has an action (OR/AND/LIST), an arg count, ident/blank flags,
 * an optional compile-function name (informational; the compiler dispatches
 * on rule number), and its argument list (tokens / rule refs / optional rule
 * refs). The engine (next sub-step) consumes this table directly.
 */
public final class Grammar {

    private Grammar() {}

    public enum Act { OR, AND, LIST, SPECIAL }
    public enum ArgKind { TOK, RULE, OPT_RULE }

    public static final class Arg {
        public final ArgKind kind; public final int tok; public final int rule;
        Arg(ArgKind k, int tok, int rule) { this.kind=k; this.tok=tok; this.rule=rule; }
    }
    public static Arg tok(Tok t)   { return new Arg(ArgKind.TOK, t.ordinal(), -1); }
    public static Arg rule(int r)  { return new Arg(ArgKind.RULE, -1, r); }
    public static Arg optRule(int r){ return new Arg(ArgKind.OPT_RULE, -1, r); }

    public static final class Rule {
        public final int number; public final String name; public final String compileFn;
        public final Act act; public final int argCount;
        public final boolean allowIdent, addBlank; public final Arg[] args;
        Rule(int number,String name,String compileFn,Act act,int argCount,
             boolean allowIdent,boolean addBlank,Arg[] args){
            this.number=number;this.name=name;this.compileFn=compileFn;this.act=act;
            this.argCount=argCount;this.allowIdent=allowIdent;this.addBlank=addBlank;this.args=args;
        }
        public boolean hasCompileFn(){ return compileFn != null; }
    }

    public static final int RULE_CONST_OBJECT = 58;
    public static final int NUM_RULES = 176;

    public static final Rule[] RULES = new Rule[NUM_RULES];
    static {
        RULES[0] = new Rule(0, "file_input", "generic_all_nodes", Act.AND, 1, true, false, new Arg[]{Grammar.optRule(1)});
        RULES[1] = new Rule(1, "file_input_2", "generic_all_nodes", Act.LIST, 2, false, false, new Arg[]{Grammar.rule(62)});
        RULES[2] = new Rule(2, "decorated", "decorated", Act.AND, 2, true, false, new Arg[]{Grammar.rule(66), Grammar.rule(67)});
        RULES[3] = new Rule(3, "funcdef", "funcdef", Act.AND, 8, false, true, new Arg[]{Grammar.tok(Tok.KW_DEF), Grammar.tok(Tok.NAME), Grammar.tok(Tok.DEL_PAREN_OPEN), Grammar.optRule(70), Grammar.tok(Tok.DEL_PAREN_CLOSE), Grammar.optRule(69), Grammar.tok(Tok.DEL_COLON), Grammar.rule(125)});
        RULES[4] = new Rule(4, "simple_stmt_2", "generic_all_nodes", Act.LIST, 3, false, false, new Arg[]{Grammar.rule(84), Grammar.tok(Tok.DEL_SEMICOLON)});
        RULES[5] = new Rule(5, "expr_stmt", "expr_stmt", Act.AND, 2, false, false, new Arg[]{Grammar.rule(6), Grammar.optRule(85)});
        RULES[6] = new Rule(6, "testlist_star_expr", "generic_tuple", Act.LIST, 3, false, false, new Arg[]{Grammar.rule(90), Grammar.tok(Tok.DEL_COMMA)});
        RULES[7] = new Rule(7, "del_stmt", "del_stmt", Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.KW_DEL), Grammar.rule(157)});
        RULES[8] = new Rule(8, "pass_stmt", "generic_all_nodes", Act.AND, 1, false, false, new Arg[]{Grammar.tok(Tok.KW_PASS)});
        RULES[9] = new Rule(9, "break_stmt", "break_cont_stmt", Act.AND, 1, false, false, new Arg[]{Grammar.tok(Tok.KW_BREAK)});
        RULES[10] = new Rule(10, "continue_stmt", "break_cont_stmt", Act.AND, 1, false, false, new Arg[]{Grammar.tok(Tok.KW_CONTINUE)});
        RULES[11] = new Rule(11, "return_stmt", "return_stmt", Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.KW_RETURN), Grammar.optRule(54)});
        RULES[12] = new Rule(12, "yield_stmt", "yield_stmt", Act.AND, 1, false, false, new Arg[]{Grammar.rule(57)});
        RULES[13] = new Rule(13, "raise_stmt", "raise_stmt", Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.KW_RAISE), Grammar.optRule(94)});
        RULES[14] = new Rule(14, "import_name", "import_name", Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.KW_IMPORT), Grammar.rule(107)});
        RULES[15] = new Rule(15, "import_from", "import_from", Act.AND, 4, false, false, new Arg[]{Grammar.tok(Tok.KW_FROM), Grammar.rule(97), Grammar.tok(Tok.KW_IMPORT), Grammar.rule(99)});
        RULES[16] = new Rule(16, "global_stmt", "global_nonlocal_stmt", Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.KW_GLOBAL), Grammar.rule(109)});
        RULES[17] = new Rule(17, "nonlocal_stmt", "global_nonlocal_stmt", Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.KW_NONLOCAL), Grammar.rule(109)});
        RULES[18] = new Rule(18, "assert_stmt", "assert_stmt", Act.AND, 3, false, false, new Arg[]{Grammar.tok(Tok.KW_ASSERT), Grammar.rule(128), Grammar.optRule(110)});
        RULES[19] = new Rule(19, "async_stmt", "async_stmt", Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.KW_ASYNC), Grammar.rule(112)});
        RULES[20] = new Rule(20, "if_stmt", "if_stmt", Act.AND, 6, false, false, new Arg[]{Grammar.tok(Tok.KW_IF), Grammar.rule(26), Grammar.tok(Tok.DEL_COLON), Grammar.rule(125), Grammar.optRule(113), Grammar.optRule(121)});
        RULES[21] = new Rule(21, "while_stmt", "while_stmt", Act.AND, 5, false, false, new Arg[]{Grammar.tok(Tok.KW_WHILE), Grammar.rule(26), Grammar.tok(Tok.DEL_COLON), Grammar.rule(125), Grammar.optRule(121)});
        RULES[22] = new Rule(22, "for_stmt", "for_stmt", Act.AND, 7, false, false, new Arg[]{Grammar.tok(Tok.KW_FOR), Grammar.rule(157), Grammar.tok(Tok.KW_IN), Grammar.rule(54), Grammar.tok(Tok.DEL_COLON), Grammar.rule(125), Grammar.optRule(121)});
        RULES[23] = new Rule(23, "try_stmt", "try_stmt", Act.AND, 4, false, false, new Arg[]{Grammar.tok(Tok.KW_TRY), Grammar.tok(Tok.DEL_COLON), Grammar.rule(125), Grammar.rule(115)});
        RULES[24] = new Rule(24, "with_stmt", "with_stmt", Act.AND, 4, false, false, new Arg[]{Grammar.tok(Tok.KW_WITH), Grammar.rule(122), Grammar.tok(Tok.DEL_COLON), Grammar.rule(125)});
        RULES[25] = new Rule(25, "suite_block_stmts", "generic_all_nodes", Act.LIST, 2, false, false, new Arg[]{Grammar.rule(82)});
        RULES[26] = new Rule(26, "namedexpr_test", "namedexpr", Act.AND, 2, true, false, new Arg[]{Grammar.rule(128), Grammar.optRule(127)});
        RULES[27] = new Rule(27, "test_if_expr", "test_if_expr", Act.AND, 2, true, false, new Arg[]{Grammar.rule(30), Grammar.optRule(129)});
        RULES[28] = new Rule(28, "lambdef", "lambdef", Act.AND, 4, false, true, new Arg[]{Grammar.tok(Tok.KW_LAMBDA), Grammar.optRule(76), Grammar.tok(Tok.DEL_COLON), Grammar.rule(128)});
        RULES[29] = new Rule(29, "lambdef_nocond", "lambdef", Act.AND, 4, false, true, new Arg[]{Grammar.tok(Tok.KW_LAMBDA), Grammar.optRule(76), Grammar.tok(Tok.DEL_COLON), Grammar.rule(130)});
        RULES[30] = new Rule(30, "or_test", "or_and_test", Act.LIST, 1, false, false, new Arg[]{Grammar.rule(31), Grammar.tok(Tok.KW_OR)});
        RULES[31] = new Rule(31, "and_test", "or_and_test", Act.LIST, 1, false, false, new Arg[]{Grammar.rule(131), Grammar.tok(Tok.KW_AND)});
        RULES[32] = new Rule(32, "not_test_2", "not_test_2", Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.KW_NOT), Grammar.rule(131)});
        RULES[33] = new Rule(33, "comparison", "comparison", Act.LIST, 1, false, false, new Arg[]{Grammar.rule(35), Grammar.rule(132)});
        RULES[34] = new Rule(34, "star_expr", "star_expr", Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.OP_STAR), Grammar.rule(35)});
        RULES[35] = new Rule(35, "expr", "binary_op", Act.LIST, 1, false, false, new Arg[]{Grammar.rule(36), Grammar.tok(Tok.OP_PIPE)});
        RULES[36] = new Rule(36, "xor_expr", "binary_op", Act.LIST, 1, false, false, new Arg[]{Grammar.rule(37), Grammar.tok(Tok.OP_CARET)});
        RULES[37] = new Rule(37, "and_expr", "binary_op", Act.LIST, 1, false, false, new Arg[]{Grammar.rule(38), Grammar.tok(Tok.OP_AMPERSAND)});
        RULES[38] = new Rule(38, "shift_expr", "term", Act.LIST, 1, false, false, new Arg[]{Grammar.rule(39), Grammar.rule(136)});
        RULES[39] = new Rule(39, "arith_expr", "term", Act.LIST, 1, false, false, new Arg[]{Grammar.rule(40), Grammar.rule(137)});
        RULES[40] = new Rule(40, "term", "term", Act.LIST, 1, false, false, new Arg[]{Grammar.rule(139), Grammar.rule(138)});
        RULES[41] = new Rule(41, "factor_2", "factor_2", Act.AND, 2, true, false, new Arg[]{Grammar.rule(140), Grammar.rule(139)});
        RULES[42] = new Rule(42, "power", "power", Act.AND, 2, true, false, new Arg[]{Grammar.rule(141), Grammar.optRule(143)});
        RULES[43] = new Rule(43, "atom_expr_await", "atom_expr_await", Act.AND, 3, false, false, new Arg[]{Grammar.tok(Tok.KW_AWAIT), Grammar.rule(144), Grammar.optRule(142)});
        RULES[44] = new Rule(44, "atom_expr_normal", "atom_expr_normal", Act.AND, 2, true, false, new Arg[]{Grammar.rule(144), Grammar.optRule(142)});
        RULES[45] = new Rule(45, "atom_paren", "atom_paren", Act.AND, 3, false, false, new Arg[]{Grammar.tok(Tok.DEL_PAREN_OPEN), Grammar.optRule(145), Grammar.tok(Tok.DEL_PAREN_CLOSE)});
        RULES[46] = new Rule(46, "atom_bracket", "atom_bracket", Act.AND, 3, false, false, new Arg[]{Grammar.tok(Tok.DEL_BRACKET_OPEN), Grammar.optRule(146), Grammar.tok(Tok.DEL_BRACKET_CLOSE)});
        RULES[47] = new Rule(47, "atom_brace", "atom_brace", Act.AND, 3, false, false, new Arg[]{Grammar.tok(Tok.DEL_BRACE_OPEN), Grammar.optRule(159), Grammar.tok(Tok.DEL_BRACE_CLOSE)});
        RULES[48] = new Rule(48, "trailer_paren", "trailer_paren", Act.AND, 3, false, false, new Arg[]{Grammar.tok(Tok.DEL_PAREN_OPEN), Grammar.optRule(164), Grammar.tok(Tok.DEL_PAREN_CLOSE)});
        RULES[49] = new Rule(49, "trailer_bracket", "trailer_bracket", Act.AND, 3, false, false, new Arg[]{Grammar.tok(Tok.DEL_BRACKET_OPEN), Grammar.rule(51), Grammar.tok(Tok.DEL_BRACKET_CLOSE)});
        RULES[50] = new Rule(50, "trailer_period", "trailer_period", Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.DEL_PERIOD), Grammar.tok(Tok.NAME)});
        RULES[51] = new Rule(51, "subscriptlist", "generic_tuple", Act.LIST, 3, false, false, new Arg[]{Grammar.rule(152), Grammar.tok(Tok.DEL_COMMA)});
        RULES[52] = new Rule(52, "subscript_2", "subscript", Act.AND, 2, true, false, new Arg[]{Grammar.rule(128), Grammar.optRule(53)});
        RULES[53] = new Rule(53, "subscript_3", "subscript", Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.DEL_COLON), Grammar.optRule(153)});
        RULES[54] = new Rule(54, "testlist", "generic_tuple", Act.LIST, 3, false, false, new Arg[]{Grammar.rule(128), Grammar.tok(Tok.DEL_COMMA)});
        RULES[55] = new Rule(55, "dictorsetmaker_item", "dictorsetmaker_item", Act.AND, 2, true, false, new Arg[]{Grammar.rule(128), Grammar.optRule(59)});
        RULES[56] = new Rule(56, "classdef", "classdef", Act.AND, 5, false, true, new Arg[]{Grammar.tok(Tok.KW_CLASS), Grammar.tok(Tok.NAME), Grammar.optRule(163), Grammar.tok(Tok.DEL_COLON), Grammar.rule(125)});
        RULES[57] = new Rule(57, "yield_expr", "yield_expr", Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.KW_YIELD), Grammar.optRule(174)});
        RULES[58] = new Rule(58, "const_object", null, Act.SPECIAL, 0, false, false, new Arg[0]);
        RULES[59] = new Rule(59, "generic_colon_test", null, Act.AND, 2, true, false, new Arg[]{Grammar.tok(Tok.DEL_COLON), Grammar.rule(128)});
        RULES[60] = new Rule(60, "generic_equal_test", null, Act.AND, 2, true, false, new Arg[]{Grammar.tok(Tok.DEL_EQUAL), Grammar.rule(128)});
        RULES[61] = new Rule(61, "single_input", null, Act.OR, 3, false, false, new Arg[]{Grammar.tok(Tok.NEWLINE), Grammar.rule(83), Grammar.rule(111)});
        RULES[62] = new Rule(62, "file_input_3", null, Act.OR, 2, false, false, new Arg[]{Grammar.tok(Tok.NEWLINE), Grammar.rule(82)});
        RULES[63] = new Rule(63, "eval_input", null, Act.AND, 2, true, false, new Arg[]{Grammar.rule(54), Grammar.optRule(64)});
        RULES[64] = new Rule(64, "eval_input_2", null, Act.AND, 1, false, false, new Arg[]{Grammar.tok(Tok.NEWLINE)});
        RULES[65] = new Rule(65, "decorator", null, Act.AND, 4, false, false, new Arg[]{Grammar.tok(Tok.OP_AT), Grammar.rule(108), Grammar.optRule(48), Grammar.tok(Tok.NEWLINE)});
        RULES[66] = new Rule(66, "decorators", null, Act.LIST, 2, false, false, new Arg[]{Grammar.rule(65)});
        RULES[67] = new Rule(67, "decorated_body", null, Act.OR, 3, false, false, new Arg[]{Grammar.rule(56), Grammar.rule(3), Grammar.rule(68)});
        RULES[68] = new Rule(68, "async_funcdef", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.KW_ASYNC), Grammar.rule(3)});
        RULES[69] = new Rule(69, "funcdefrettype", null, Act.AND, 2, true, false, new Arg[]{Grammar.tok(Tok.DEL_MINUS_MORE), Grammar.rule(128)});
        RULES[70] = new Rule(70, "typedargslist", null, Act.LIST, 3, false, false, new Arg[]{Grammar.rule(71), Grammar.tok(Tok.DEL_COMMA)});
        RULES[71] = new Rule(71, "typedargslist_item", null, Act.OR, 3, false, false, new Arg[]{Grammar.rule(72), Grammar.rule(73), Grammar.rule(74)});
        RULES[72] = new Rule(72, "typedargslist_name", null, Act.AND, 3, true, false, new Arg[]{Grammar.tok(Tok.NAME), Grammar.optRule(59), Grammar.optRule(60)});
        RULES[73] = new Rule(73, "typedargslist_star", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.OP_STAR), Grammar.optRule(75)});
        RULES[74] = new Rule(74, "typedargslist_dbl_star", null, Act.AND, 3, false, false, new Arg[]{Grammar.tok(Tok.OP_DBL_STAR), Grammar.tok(Tok.NAME), Grammar.optRule(59)});
        RULES[75] = new Rule(75, "tfpdef", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.NAME), Grammar.optRule(59)});
        RULES[76] = new Rule(76, "varargslist", null, Act.LIST, 3, false, false, new Arg[]{Grammar.rule(77), Grammar.tok(Tok.DEL_COMMA)});
        RULES[77] = new Rule(77, "varargslist_item", null, Act.OR, 3, false, false, new Arg[]{Grammar.rule(78), Grammar.rule(79), Grammar.rule(80)});
        RULES[78] = new Rule(78, "varargslist_name", null, Act.AND, 2, true, false, new Arg[]{Grammar.tok(Tok.NAME), Grammar.optRule(60)});
        RULES[79] = new Rule(79, "varargslist_star", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.OP_STAR), Grammar.optRule(81)});
        RULES[80] = new Rule(80, "varargslist_dbl_star", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.OP_DBL_STAR), Grammar.tok(Tok.NAME)});
        RULES[81] = new Rule(81, "vfpdef", null, Act.AND, 1, true, false, new Arg[]{Grammar.tok(Tok.NAME)});
        RULES[82] = new Rule(82, "stmt", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(111), Grammar.rule(83)});
        RULES[83] = new Rule(83, "simple_stmt", null, Act.AND, 2, true, false, new Arg[]{Grammar.rule(4), Grammar.tok(Tok.NEWLINE)});
        RULES[84] = new Rule(84, "small_stmt", null, Act.OR, 8, false, false, new Arg[]{Grammar.rule(7), Grammar.rule(8), Grammar.rule(93), Grammar.rule(96), Grammar.rule(16), Grammar.rule(17), Grammar.rule(18), Grammar.rule(5)});
        RULES[85] = new Rule(85, "expr_stmt_2", null, Act.OR, 3, false, false, new Arg[]{Grammar.rule(91), Grammar.rule(86), Grammar.rule(87)});
        RULES[86] = new Rule(86, "expr_stmt_augassign", null, Act.AND, 2, true, false, new Arg[]{Grammar.rule(92), Grammar.rule(89)});
        RULES[87] = new Rule(87, "expr_stmt_assign_list", null, Act.LIST, 2, false, false, new Arg[]{Grammar.rule(88)});
        RULES[88] = new Rule(88, "expr_stmt_assign", null, Act.AND, 2, true, false, new Arg[]{Grammar.tok(Tok.DEL_EQUAL), Grammar.rule(89)});
        RULES[89] = new Rule(89, "expr_stmt_6", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(57), Grammar.rule(6)});
        RULES[90] = new Rule(90, "testlist_star_expr_2", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(34), Grammar.rule(128)});
        RULES[91] = new Rule(91, "annassign", null, Act.AND, 3, false, false, new Arg[]{Grammar.tok(Tok.DEL_COLON), Grammar.rule(128), Grammar.optRule(88)});
        RULES[92] = new Rule(92, "augassign", null, Act.OR, 13, false, false, new Arg[]{Grammar.tok(Tok.DEL_PLUS_EQUAL), Grammar.tok(Tok.DEL_MINUS_EQUAL), Grammar.tok(Tok.DEL_STAR_EQUAL), Grammar.tok(Tok.DEL_AT_EQUAL), Grammar.tok(Tok.DEL_SLASH_EQUAL), Grammar.tok(Tok.DEL_PERCENT_EQUAL), Grammar.tok(Tok.DEL_AMPERSAND_EQUAL), Grammar.tok(Tok.DEL_PIPE_EQUAL), Grammar.tok(Tok.DEL_CARET_EQUAL), Grammar.tok(Tok.DEL_DBL_LESS_EQUAL), Grammar.tok(Tok.DEL_DBL_MORE_EQUAL), Grammar.tok(Tok.DEL_DBL_STAR_EQUAL), Grammar.tok(Tok.DEL_DBL_SLASH_EQUAL)});
        RULES[93] = new Rule(93, "flow_stmt", null, Act.OR, 5, false, false, new Arg[]{Grammar.rule(9), Grammar.rule(10), Grammar.rule(11), Grammar.rule(13), Grammar.rule(12)});
        RULES[94] = new Rule(94, "raise_stmt_arg", null, Act.AND, 2, true, false, new Arg[]{Grammar.rule(128), Grammar.optRule(95)});
        RULES[95] = new Rule(95, "raise_stmt_from", null, Act.AND, 2, true, false, new Arg[]{Grammar.tok(Tok.KW_FROM), Grammar.rule(128)});
        RULES[96] = new Rule(96, "import_stmt", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(14), Grammar.rule(15)});
        RULES[97] = new Rule(97, "import_from_2", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(108), Grammar.rule(98)});
        RULES[98] = new Rule(98, "import_from_2b", null, Act.AND, 2, true, false, new Arg[]{Grammar.rule(101), Grammar.optRule(108)});
        RULES[99] = new Rule(99, "import_from_3", null, Act.OR, 3, false, false, new Arg[]{Grammar.tok(Tok.OP_STAR), Grammar.rule(100), Grammar.rule(106)});
        RULES[100] = new Rule(100, "import_as_names_paren", null, Act.AND, 3, true, false, new Arg[]{Grammar.tok(Tok.DEL_PAREN_OPEN), Grammar.rule(106), Grammar.tok(Tok.DEL_PAREN_CLOSE)});
        RULES[101] = new Rule(101, "one_or_more_period_or_ellipsis", null, Act.LIST, 2, false, false, new Arg[]{Grammar.rule(102)});
        RULES[102] = new Rule(102, "period_or_ellipsis", null, Act.OR, 2, false, false, new Arg[]{Grammar.tok(Tok.DEL_PERIOD), Grammar.tok(Tok.ELLIPSIS)});
        RULES[103] = new Rule(103, "import_as_name", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.NAME), Grammar.optRule(105)});
        RULES[104] = new Rule(104, "dotted_as_name", null, Act.AND, 2, true, false, new Arg[]{Grammar.rule(108), Grammar.optRule(105)});
        RULES[105] = new Rule(105, "as_name", null, Act.AND, 2, true, false, new Arg[]{Grammar.tok(Tok.KW_AS), Grammar.tok(Tok.NAME)});
        RULES[106] = new Rule(106, "import_as_names", null, Act.LIST, 3, false, false, new Arg[]{Grammar.rule(103), Grammar.tok(Tok.DEL_COMMA)});
        RULES[107] = new Rule(107, "dotted_as_names", null, Act.LIST, 1, false, false, new Arg[]{Grammar.rule(104), Grammar.tok(Tok.DEL_COMMA)});
        RULES[108] = new Rule(108, "dotted_name", null, Act.LIST, 1, false, false, new Arg[]{Grammar.tok(Tok.NAME), Grammar.tok(Tok.DEL_PERIOD)});
        RULES[109] = new Rule(109, "name_list", null, Act.LIST, 1, false, false, new Arg[]{Grammar.tok(Tok.NAME), Grammar.tok(Tok.DEL_COMMA)});
        RULES[110] = new Rule(110, "assert_stmt_extra", null, Act.AND, 2, true, false, new Arg[]{Grammar.tok(Tok.DEL_COMMA), Grammar.rule(128)});
        RULES[111] = new Rule(111, "compound_stmt", null, Act.OR, 9, false, false, new Arg[]{Grammar.rule(20), Grammar.rule(21), Grammar.rule(22), Grammar.rule(23), Grammar.rule(24), Grammar.rule(3), Grammar.rule(56), Grammar.rule(2), Grammar.rule(19)});
        RULES[112] = new Rule(112, "async_stmt_2", null, Act.OR, 3, false, false, new Arg[]{Grammar.rule(3), Grammar.rule(24), Grammar.rule(22)});
        RULES[113] = new Rule(113, "if_stmt_elif_list", null, Act.LIST, 2, false, false, new Arg[]{Grammar.rule(114)});
        RULES[114] = new Rule(114, "if_stmt_elif", null, Act.AND, 4, false, false, new Arg[]{Grammar.tok(Tok.KW_ELIF), Grammar.rule(26), Grammar.tok(Tok.DEL_COLON), Grammar.rule(125)});
        RULES[115] = new Rule(115, "try_stmt_2", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(116), Grammar.rule(120)});
        RULES[116] = new Rule(116, "try_stmt_except_and_more", null, Act.AND, 3, true, false, new Arg[]{Grammar.rule(119), Grammar.optRule(121), Grammar.optRule(120)});
        RULES[117] = new Rule(117, "try_stmt_except", null, Act.AND, 4, false, false, new Arg[]{Grammar.tok(Tok.KW_EXCEPT), Grammar.optRule(118), Grammar.tok(Tok.DEL_COLON), Grammar.rule(125)});
        RULES[118] = new Rule(118, "try_stmt_as_name", null, Act.AND, 2, true, false, new Arg[]{Grammar.rule(128), Grammar.optRule(105)});
        RULES[119] = new Rule(119, "try_stmt_except_list", null, Act.LIST, 2, false, false, new Arg[]{Grammar.rule(117)});
        RULES[120] = new Rule(120, "try_stmt_finally", null, Act.AND, 3, false, false, new Arg[]{Grammar.tok(Tok.KW_FINALLY), Grammar.tok(Tok.DEL_COLON), Grammar.rule(125)});
        RULES[121] = new Rule(121, "else_stmt", null, Act.AND, 3, true, false, new Arg[]{Grammar.tok(Tok.KW_ELSE), Grammar.tok(Tok.DEL_COLON), Grammar.rule(125)});
        RULES[122] = new Rule(122, "with_stmt_list", null, Act.LIST, 1, false, false, new Arg[]{Grammar.rule(123), Grammar.tok(Tok.DEL_COMMA)});
        RULES[123] = new Rule(123, "with_item", null, Act.AND, 2, true, false, new Arg[]{Grammar.rule(128), Grammar.optRule(124)});
        RULES[124] = new Rule(124, "with_item_as", null, Act.AND, 2, true, false, new Arg[]{Grammar.tok(Tok.KW_AS), Grammar.rule(35)});
        RULES[125] = new Rule(125, "suite", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(126), Grammar.rule(83)});
        RULES[126] = new Rule(126, "suite_block", null, Act.AND, 4, true, false, new Arg[]{Grammar.tok(Tok.NEWLINE), Grammar.tok(Tok.INDENT), Grammar.rule(25), Grammar.tok(Tok.DEDENT)});
        RULES[127] = new Rule(127, "namedexpr_test_2", null, Act.AND, 2, true, false, new Arg[]{Grammar.tok(Tok.OP_ASSIGN), Grammar.rule(128)});
        RULES[128] = new Rule(128, "test", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(28), Grammar.rule(27)});
        RULES[129] = new Rule(129, "test_if_else", null, Act.AND, 4, false, false, new Arg[]{Grammar.tok(Tok.KW_IF), Grammar.rule(30), Grammar.tok(Tok.KW_ELSE), Grammar.rule(128)});
        RULES[130] = new Rule(130, "test_nocond", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(29), Grammar.rule(30)});
        RULES[131] = new Rule(131, "not_test", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(32), Grammar.rule(33)});
        RULES[132] = new Rule(132, "comp_op", null, Act.OR, 9, false, false, new Arg[]{Grammar.tok(Tok.OP_LESS), Grammar.tok(Tok.OP_MORE), Grammar.tok(Tok.OP_DBL_EQUAL), Grammar.tok(Tok.OP_LESS_EQUAL), Grammar.tok(Tok.OP_MORE_EQUAL), Grammar.tok(Tok.OP_NOT_EQUAL), Grammar.tok(Tok.KW_IN), Grammar.rule(133), Grammar.rule(134)});
        RULES[133] = new Rule(133, "comp_op_not_in", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.KW_NOT), Grammar.tok(Tok.KW_IN)});
        RULES[134] = new Rule(134, "comp_op_is", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.KW_IS), Grammar.optRule(135)});
        RULES[135] = new Rule(135, "comp_op_is_not", null, Act.AND, 1, false, false, new Arg[]{Grammar.tok(Tok.KW_NOT)});
        RULES[136] = new Rule(136, "shift_op", null, Act.OR, 2, false, false, new Arg[]{Grammar.tok(Tok.OP_DBL_LESS), Grammar.tok(Tok.OP_DBL_MORE)});
        RULES[137] = new Rule(137, "arith_op", null, Act.OR, 2, false, false, new Arg[]{Grammar.tok(Tok.OP_PLUS), Grammar.tok(Tok.OP_MINUS)});
        RULES[138] = new Rule(138, "term_op", null, Act.OR, 5, false, false, new Arg[]{Grammar.tok(Tok.OP_STAR), Grammar.tok(Tok.OP_AT), Grammar.tok(Tok.OP_SLASH), Grammar.tok(Tok.OP_PERCENT), Grammar.tok(Tok.OP_DBL_SLASH)});
        RULES[139] = new Rule(139, "factor", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(41), Grammar.rule(42)});
        RULES[140] = new Rule(140, "factor_op", null, Act.OR, 3, false, false, new Arg[]{Grammar.tok(Tok.OP_PLUS), Grammar.tok(Tok.OP_MINUS), Grammar.tok(Tok.OP_TILDE)});
        RULES[141] = new Rule(141, "atom_expr", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(43), Grammar.rule(44)});
        RULES[142] = new Rule(142, "atom_expr_trailers", null, Act.LIST, 2, false, false, new Arg[]{Grammar.rule(151)});
        RULES[143] = new Rule(143, "power_dbl_star", null, Act.AND, 2, true, false, new Arg[]{Grammar.tok(Tok.OP_DBL_STAR), Grammar.rule(139)});
        RULES[144] = new Rule(144, "atom", null, Act.OR, 12, false, false, new Arg[]{Grammar.tok(Tok.NAME), Grammar.tok(Tok.INTEGER), Grammar.tok(Tok.FLOAT_OR_IMAG), Grammar.tok(Tok.STRING), Grammar.tok(Tok.BYTES), Grammar.tok(Tok.ELLIPSIS), Grammar.tok(Tok.KW_NONE), Grammar.tok(Tok.KW_TRUE), Grammar.tok(Tok.KW_FALSE), Grammar.rule(45), Grammar.rule(46), Grammar.rule(47)});
        RULES[145] = new Rule(145, "atom_2b", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(57), Grammar.rule(146)});
        RULES[146] = new Rule(146, "testlist_comp", null, Act.AND, 2, true, false, new Arg[]{Grammar.rule(147), Grammar.optRule(148)});
        RULES[147] = new Rule(147, "testlist_comp_2", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(34), Grammar.rule(26)});
        RULES[148] = new Rule(148, "testlist_comp_3", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(172), Grammar.rule(149)});
        RULES[149] = new Rule(149, "testlist_comp_3b", null, Act.AND, 2, true, false, new Arg[]{Grammar.tok(Tok.DEL_COMMA), Grammar.optRule(150)});
        RULES[150] = new Rule(150, "testlist_comp_3c", null, Act.LIST, 3, false, false, new Arg[]{Grammar.rule(147), Grammar.tok(Tok.DEL_COMMA)});
        RULES[151] = new Rule(151, "trailer", null, Act.OR, 3, false, false, new Arg[]{Grammar.rule(48), Grammar.rule(49), Grammar.rule(50)});
        RULES[152] = new Rule(152, "subscript", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(53), Grammar.rule(52)});
        RULES[153] = new Rule(153, "subscript_3b", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(154), Grammar.rule(155)});
        RULES[154] = new Rule(154, "subscript_3c", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.DEL_COLON), Grammar.optRule(128)});
        RULES[155] = new Rule(155, "subscript_3d", null, Act.AND, 2, true, false, new Arg[]{Grammar.rule(128), Grammar.optRule(156)});
        RULES[156] = new Rule(156, "sliceop", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.DEL_COLON), Grammar.optRule(128)});
        RULES[157] = new Rule(157, "exprlist", null, Act.LIST, 3, false, false, new Arg[]{Grammar.rule(158), Grammar.tok(Tok.DEL_COMMA)});
        RULES[158] = new Rule(158, "exprlist_2", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(34), Grammar.rule(35)});
        RULES[159] = new Rule(159, "dictorsetmaker", null, Act.AND, 2, true, false, new Arg[]{Grammar.rule(55), Grammar.optRule(160)});
        RULES[160] = new Rule(160, "dictorsetmaker_tail", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(172), Grammar.rule(161)});
        RULES[161] = new Rule(161, "dictorsetmaker_list", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.DEL_COMMA), Grammar.optRule(162)});
        RULES[162] = new Rule(162, "dictorsetmaker_list2", null, Act.LIST, 3, false, false, new Arg[]{Grammar.rule(55), Grammar.tok(Tok.DEL_COMMA)});
        RULES[163] = new Rule(163, "classdef_2", null, Act.AND, 3, true, false, new Arg[]{Grammar.tok(Tok.DEL_PAREN_OPEN), Grammar.optRule(164), Grammar.tok(Tok.DEL_PAREN_CLOSE)});
        RULES[164] = new Rule(164, "arglist", null, Act.LIST, 3, false, false, new Arg[]{Grammar.rule(165), Grammar.tok(Tok.DEL_COMMA)});
        RULES[165] = new Rule(165, "arglist_2", null, Act.OR, 3, false, false, new Arg[]{Grammar.rule(166), Grammar.rule(167), Grammar.rule(168)});
        RULES[166] = new Rule(166, "arglist_star", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.OP_STAR), Grammar.rule(128)});
        RULES[167] = new Rule(167, "arglist_dbl_star", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.OP_DBL_STAR), Grammar.rule(128)});
        RULES[168] = new Rule(168, "argument", null, Act.AND, 2, true, false, new Arg[]{Grammar.rule(128), Grammar.optRule(169)});
        RULES[169] = new Rule(169, "argument_2", null, Act.OR, 3, false, false, new Arg[]{Grammar.rule(172), Grammar.rule(60), Grammar.rule(170)});
        RULES[170] = new Rule(170, "argument_3", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.OP_ASSIGN), Grammar.rule(128)});
        RULES[171] = new Rule(171, "comp_iter", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(172), Grammar.rule(173)});
        RULES[172] = new Rule(172, "comp_for", null, Act.AND, 5, false, true, new Arg[]{Grammar.tok(Tok.KW_FOR), Grammar.rule(157), Grammar.tok(Tok.KW_IN), Grammar.rule(30), Grammar.optRule(171)});
        RULES[173] = new Rule(173, "comp_if", null, Act.AND, 3, false, false, new Arg[]{Grammar.tok(Tok.KW_IF), Grammar.rule(130), Grammar.optRule(171)});
        RULES[174] = new Rule(174, "yield_arg", null, Act.OR, 2, false, false, new Arg[]{Grammar.rule(175), Grammar.rule(54)});
        RULES[175] = new Rule(175, "yield_arg_from", null, Act.AND, 2, false, false, new Arg[]{Grammar.tok(Tok.KW_FROM), Grammar.rule(128)});
    }

    public static int number(String ruleName) {
        for (Rule r : RULES) if (r != null && r.name.equals(ruleName)) return r.number;
        return -1;
    }
}
