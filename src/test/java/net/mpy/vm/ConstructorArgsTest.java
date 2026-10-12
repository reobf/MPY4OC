package net.mpy.vm;

import org.junit.jupiter.api.Test;

/** The built-in type constructors' arguments: how many each takes, which may be
 *  named (int's base, str's and bytes' encoding/errors, complex's real/imag),
 *  and str(bytes, encoding) / bytes(str, encoding). Extra or named arguments
 *  used to be dropped silently, so int('10', base=16) gave 10. */
class ConstructorArgsTest {
    // {expression, CPython's result as "type repr" or "ExceptionType: message"}
    private static final String[][] CASES = {
            {"int('10', base=16)", "int 16"},
            {"int('10', 16)", "int 16"},
            {"int(x='5')", "TypeError: 'x' is an invalid keyword argument for int()"},
            {"int(base=16)", "TypeError: int() missing string argument"},
            {"int('5', 2, 3)", "TypeError: int() takes at most 2 arguments (3 given)"},
            {"str(b'abc', 'utf-8')", "str 'abc'"},
            {"str(b'abc', encoding='utf-8')", "str 'abc'"},
            {"str(object=5)", "str '5'"},
            {"str(encoding='utf-8')", "str ''"},
            {"str('x', 'utf-8')", "TypeError: decoding str is not supported"},
            {"str(5, 'utf-8')", "TypeError: decoding to str: need a bytes-like object, int found"},
            {"str(bytearray(b'hi'), 'utf-8', 'strict')", "str 'hi'"},
            {"str(1, 2, 3, 4)", "TypeError: str() takes at most 3 arguments (4 given)"},
            {"bytes('abc', 'utf-8')", "bytes b'abc'"},
            {"bytes('abc', encoding='utf-8')", "bytes b'abc'"},
            {"bytes('abc')", "TypeError: string argument without an encoding"},
            {"bytes(3, 'utf-8')", "TypeError: encoding without a string argument"},
            {"bytes(encoding='utf-8')", "TypeError: encoding without a string argument"},
            {"bytes(source=b'x')", "bytes b'x'"},
            {"float(1, 2)", "TypeError: float expected at most 1 argument, got 2"},
            {"float(x=1)", "TypeError: float() takes no keyword arguments"},
            {"list([1], [2])", "TypeError: list expected at most 1 argument, got 2"},
            {"list(iterable=[1])", "TypeError: list() takes no keyword arguments"},
            {"tuple(x=1)", "TypeError: tuple() takes no keyword arguments"},
            {"bool(1, 2)", "TypeError: bool expected at most 1 argument, got 2"},
            {"set([1], [2])", "TypeError: set expected at most 1 argument, got 2"},
            {"range()", "TypeError: range expected at least 1 argument, got 0"},
            {"range(1, 2, 3, 4)", "TypeError: range expected at most 3 arguments, got 4"},
            {"range(stop=5)", "TypeError: range() takes no keyword arguments"},
            {"dict({'a': 1}, {'b': 2})", "TypeError: dict expected at most 1 argument, got 2"},
            {"dict({'a': 1}, b=2)", "dict {'a': 1, 'b': 2}"},
            {"complex(1, 2)", "complex (1+2j)"},
            {"complex(real=1, imag=2)", "complex (1+2j)"},
            {"complex(imag=2)", "complex 2j"},
            {"complex(1, 2, 3)", "TypeError: complex() takes at most 2 arguments (3 given)"},
            {"object(1)", "TypeError: object() takes no arguments"},
            {"type('X', (), {}).__name__", "str 'X'"},
    };

    @Test
    void constructorsTakeCPythonsArguments() {
        PyCases.check(CASES);
    }
}
