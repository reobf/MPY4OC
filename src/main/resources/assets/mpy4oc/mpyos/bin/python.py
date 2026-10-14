# MPYOS interpreter: python
# python -- run a Python file, or start an interactive session.
#
#   python <file> [args...]   run the file; its arguments are in sys.argv[1:]
#   python                    interactive REPL: eval prints the value, else exec
#
# Both run in a fresh sandbox namespace: reads fall through to the system globals
# (component / computer / fs / the mpyos libs are all directly usable), but writes
# stay in the sandbox and never touch the system namespace. Functions defined here
# keep the sandbox as their scope. The sandbox is discarded on exit.
#
# REPL extras: up/down history, Tab completion (names from the sandbox, the system
# globals and builtins; after a dot, attributes of the resolved object via dir()).
# Compound statements end with a blank line. Unclosed brackets, triple-quoted
# strings and explicit backslash continuations also show the continuation prompt.

import sys
import _process

if sys.argv[1:]:
    _process.python(sys.argv[1:])
else:
    # --- interactive REPL ---
    tty.print("Python (mpyos). 'exit' to leave. Blank line ends a block.")
    sandbox = {}
    history = []

    def _ident_start(text, end):
        # walk back over [A-Za-z0-9_.] to find the start of the dotted token
        i = end
        while i > 0:
            c = text[i - 1]
            if c.isalpha() or c.isdigit() or c == "_" or c == ".":
                i -= 1
            else:
                break
        return i

    def _resolve(name):
        # Resolve exactly the way execution would: evaluate the dotted path in the
        # sandbox (falls through to the system globals). Membership tests against
        # globals() don't work for injected globals like component/computer -- they live
        # in the VM globals, reachable only via name-lookup fall-through.
        try:
            return eval_sandbox(name, sandbox)
        except Exception:
            return None

    def _complete(text, pos):
        start = _ident_start(text, pos)
        token = text[start:pos]
        dot = token.rfind(".")
        names = []
        if dot < 0:
            # bare name: sandbox + system globals (builtins live there too)
            partial = token
            seen = []
            for k in list(sandbox.keys()):
                if k.startswith(partial) and k not in seen:
                    seen.append(k)
            for k in list(globals().keys()):
                if k.startswith(partial) and k not in seen:
                    seen.append(k)
            # injected globals and common builtins are not literal keys of globals()
            # (they live in the VM globals, reached via fall-through), so offer
            # them explicitly
            for k in ("component", "computer", "print", "len", "dir", "range",
                      "repr", "type", "str", "int", "float", "list", "dict",
                      "set", "tuple", "getattr", "setattr", "hasattr", "sorted",
                      "sum", "min", "max", "abs", "enumerate", "isinstance"):
                if k.startswith(partial) and k not in seen:
                    seen.append(k)
            names = seen
            base_len = 0
        else:
            base = token[:dot]
            partial = token[dot + 1:]
            obj = _resolve(base)
            if obj is None:
                return []
            try:
                attrs = [str(x) for x in dir(obj)]
            except Exception:
                return []
            # the component module also completes to attached component types
            # (component.gpu / component.filesystem ... resolve as primaries)
            if base == "component":
                try:
                    for _t in component.list().values():
                        if _t not in attrs:
                            attrs.append(_t)
                except Exception:
                    pass
            seen = []
            for k in attrs:
                k = str(k)
                if k.startswith(partial) and not k.startswith("__") and k not in seen:
                    seen.append(k)
            names = seen
            base_len = dot + 1
        if not names:
            return []
        out = []
        for n in names:
            new_text = text[:start] + token[:base_len] + n + text[pos:]
            new_pos = start + base_len + len(n)
            out.append((new_text, new_pos, n))
        return out

    def _input_open(src):
        # Scan only lexical continuations; the compiler still validates syntax.
        # Ignore brackets and backslashes inside comments and quoted strings.
        brackets = []
        quote = ""
        triple = False
        i = 0
        while i < len(src):
            c = src[i]
            if quote:
                if c == "\\":
                    if i + 1 == len(src):
                        return True
                    i += 2
                    continue
                if triple:
                    if src[i:i + 3] == quote * 3:
                        quote = ""
                        i += 3
                        continue
                elif c == quote:
                    quote = ""
                elif c == "\n":
                    return False  # an ordinary unterminated string is an error
            elif c == "#":
                while i < len(src) and src[i] != "\n":
                    i += 1
                continue
            elif c == "'" or c == '"':
                quote = c
                triple = src[i:i + 3] == c * 3
                if triple:
                    i += 3
                    continue
            elif c in "([{":
                brackets.append(c)
            elif c in ")]}":
                if not brackets or "([{".find(brackets[-1]) != ")]}".find(c):
                    return False
                brackets.pop()
            elif c == "\\":
                if i + 1 == len(src):
                    return True
                if src[i + 1] != "\n":
                    return False
                i += 2
                continue
            i += 1
        if quote and not triple:
            return False
        return bool(brackets) or bool(quote)

    def _is_block(src):
        src = src.lstrip()
        if src.startswith("@"):
            return True
        end = 0
        while end < len(src) and (src[end].isalpha() or src[end].isdigit() or src[end] == "_"):
            end += 1
        return src[:end] in ("def", "class", "if", "for", "while", "try", "with", "async")

    pending = []
    while True:
        try:
            line = tty.read("... " if pending else ">>> ", history, _complete)
            if line is None:
                if pending:
                    pending = []
                    tty.print("KeyboardInterrupt")
                    continue
                break
            stripped = line.strip()
            if not pending and not stripped:
                continue
            if not pending and (stripped == "exit" or stripped == "exit()"):
                break
            if line:
                history.append(line)
            # Keep indentation and whitespace inside string literals intact.
            pending.append(line)
            source = "\n".join(pending)
            if _input_open(source) or (stripped and _is_block(source)):
                continue
            pending = []
            try:
                _r = eval_sandbox(source, sandbox)
                if _r is not None:
                    tty.print(repr(_r))
            except SyntaxError:
                exec_sandbox(source, sandbox)
        except KeyboardInterrupt:
            pending = []
            tty.print("KeyboardInterrupt")
        except Exception as _e:
            tty.print("error: " + str(_e))
