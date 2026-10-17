"""Command-line parsing for MPYOS programs (common argparse subset)."""
import sys

SUPPRESS = '==SUPPRESS=='
OPTIONAL, ZERO_OR_MORE, ONE_OR_MORE, REMAINDER = '?', '*', '+', '...'

class ArgumentError(Exception):
    def __init__(self, argument, message):
        self.argument, self.message = argument, message
        Exception.__init__(self, ('argument ' + '/'.join(argument.option_strings or [argument.dest]) + ': ' if argument else '') + message)

class ArgumentTypeError(Exception):
    pass

class Namespace:
    def __init__(self, **kwargs):
        for key, value in kwargs.items():
            setattr(self, key, value)
    def __repr__(self):
        return 'Namespace(' + ', '.join(k + '=' + repr(v) for k, v in self.__dict__.items()) + ')'
    def __eq__(self, other):
        return isinstance(other, Namespace) and self.__dict__ == other.__dict__
    def __contains__(self, key):
        return key in self.__dict__

class Action:
    def __init__(self, option_strings, dest, nargs=None, const=None, default=None, type=None, choices=None, required=False, help=None, metavar=None):
        self.option_strings, self.dest, self.nargs, self.const = option_strings, dest, nargs, const
        self.default, self.type, self.choices, self.required = default, type, choices, required
        self.help, self.metavar = help, metavar

class BooleanOptionalAction(Action):
    pass

class FileType:
    def __init__(self, mode='r', bufsize=-1, encoding=None, errors=None):
        self.mode, self.encoding, self.errors = mode, encoding, errors
    def __call__(self, string):
        if string == '-':
            raise ArgumentTypeError('standard streams are not exposed as files; use input/print')
        try:
            return open(string, self.mode, encoding=self.encoding, errors=self.errors)
        except OSError as e:
            raise ArgumentTypeError(str(e))

class _Group:
    def __init__(self, parser, title=None, description=None, exclusive=False, required=False):
        self.parser, self.title, self.description = parser, title, description
        self.exclusive, self.required, self.actions = exclusive, required, []
    def add_argument(self, *args, **kwargs):
        action = self.parser.add_argument(*args, **kwargs)
        self.actions.append(action)
        return action
    def add_mutually_exclusive_group(self, **kwargs):
        return self.parser.add_mutually_exclusive_group(**kwargs)
    def set_defaults(self, **kwargs):
        self.parser.set_defaults(**kwargs)

class _Subparsers:
    def __init__(self, parser, dest, required, help):
        self.parser, self.dest, self.required, self.help = parser, dest, required, help
        self.parsers = {}
    def add_parser(self, name, **kwargs):
        aliases = kwargs.pop('aliases', ())
        kwargs.pop('help', None)
        child = ArgumentParser(prog=kwargs.pop('prog', self.parser.prog + ' ' + name), **kwargs)
        for value in [name] + list(aliases):
            if value in self.parsers:
                raise ValueError('conflicting subparser: ' + value)
            self.parsers[value] = child
        return child

class ArgumentParser:
    def __init__(self, prog=None, usage=None, description=None, epilog=None, parents=(), formatter_class=None, prefix_chars='-', fromfile_prefix_chars=None, argument_default=None, conflict_handler='error', add_help=True, allow_abbrev=True, exit_on_error=True):
        if prefix_chars != '-' or fromfile_prefix_chars is not None or formatter_class is not None or parents or conflict_handler != 'error':
            raise NotImplementedError('custom prefixes, fromfile, formatters, parents and conflict resolution are not supported')
        self.prog = prog if prog is not None else (sys.argv[0] if sys.argv else 'program')
        self.usage, self.description, self.epilog = usage, description, epilog
        self.argument_default, self.allow_abbrev, self.exit_on_error = argument_default, allow_abbrev, exit_on_error
        self._actions, self._options, self._defaults, self._groups = [], {}, {}, []
        self._subparsers = None
        if add_help:
            self.add_argument('-h', '--help', action='help', help='show this help message and exit')
    def add_argument(self, *names, **kwargs):
        if not names or any(not isinstance(n, str) or not n for n in names):
            raise ValueError('argument name is required')
        optional = names[0].startswith('-')
        if optional and any(not n.startswith('-') or n == '-' for n in names):
            raise ValueError('invalid option string')
        if not optional and (len(names) != 1 or 'dest' in kwargs):
            raise ValueError('invalid positional argument')
        dest = kwargs.pop('dest', (next((n for n in names if n.startswith('--')), names[0]).lstrip('-').replace('-', '_') if optional else names[0]))
        kind = kwargs.pop('action', 'store')
        version = kwargs.pop('version', None)
        if kind is BooleanOptionalAction:
            kind = 'boolean'
            names = tuple(n for name in names for n in ([name, '--no-' + name[2:]] if name.startswith('--') else [name]))
        if kind not in ('store', 'store_const', 'store_true', 'store_false', 'append', 'append_const', 'extend', 'count', 'help', 'version', 'boolean'):
            raise NotImplementedError('custom argparse actions are not supported')
        zero = kind in ('store_const', 'store_true', 'store_false', 'append_const', 'count', 'help', 'version', 'boolean')
        if zero:
            if 'nargs' in kwargs:
                raise TypeError('nargs is not valid for this action')
            kwargs['nargs'] = 0
        if kind in ('store_true', 'store_false'):
            kwargs['const'] = kind == 'store_true'
        if 'default' not in kwargs:
            kwargs['default'] = self._defaults.get(dest, SUPPRESS if kind in ('help', 'version') else kind == 'store_false' if kind in ('store_true', 'store_false') else self.argument_default)
        action = Action(list(names) if optional else [], dest, **kwargs)
        action.kind, action.version = kind, version
        if action.type is not None and not callable(action.type):
            raise ValueError('type must be callable')
        if action.nargs not in (None, '?', '*', '+', REMAINDER) and (not isinstance(action.nargs, int) or action.nargs < 0):
            raise ValueError('invalid nargs')
        if not optional and action.nargs == 0:
            raise ValueError('positional arguments must consume values')
        for name in action.option_strings:
            if name in self._options:
                raise ArgumentError(action, 'conflicting option string: ' + name)
        self._actions.append(action)
        for name in action.option_strings:
            self._options[name] = action
        return action
    def add_argument_group(self, title=None, description=None):
        group = _Group(self, title, description)
        self._groups.append(group)
        return group
    def add_mutually_exclusive_group(self, required=False):
        group = _Group(self, exclusive=True, required=required)
        self._groups.append(group)
        return group
    def add_subparsers(self, dest=SUPPRESS, required=False, help=None, **kwargs):
        if self._subparsers is not None:
            raise ValueError('cannot have multiple subparser arguments')
        if kwargs:
            raise NotImplementedError('subparser presentation options are not supported')
        self._subparsers = _Subparsers(self, dest, required, help)
        return self._subparsers
    def set_defaults(self, **kwargs):
        self._defaults.update(kwargs)
        for action in self._actions:
            if action.dest in kwargs:
                action.default = kwargs[action.dest]
    def get_default(self, dest):
        for action in self._actions:
            if action.dest == dest:
                return action.default
        return self._defaults.get(dest)
    def _option(self, token):
        name = token.split('=', 1)[0]
        if name in self._options:
            return name
        if self.allow_abbrev and name.startswith('--'):
            choices = [n for n in self._options if n.startswith(name)]
            if len(choices) > 1:
                raise ArgumentError(None, 'ambiguous option: ' + name + ' could match ' + ', '.join(choices))
            if choices:
                return choices[0]
        return None
    def _looks_option(self, token):
        if token == '-' or not token.startswith('-'):
            return False
        if token == '--' or self._option(token) is not None:
            return True
        try:
            float(token)
            return any(n[1:].isdigit() for n in self._options if n.startswith('-') and not n.startswith('--'))
        except ValueError:
            return True
    def _convert(self, action, value):
        try:
            result = action.type(value) if action.type is not None else value
        except (ValueError, TypeError, ArgumentTypeError) as e:
            raise ArgumentError(action, 'invalid value: ' + repr(value) + ' (' + str(e) + ')')
        if action.choices is not None and result not in action.choices:
            raise ArgumentError(action, 'invalid choice: ' + repr(result))
        return result
    def _store(self, namespace, action, values, option=None):
        kind = action.kind
        if kind == 'help':
            self.print_help()
            self.exit()
        if kind == 'version':
            print((action.version or '') % {'prog': self.prog})
            self.exit()
        if kind in ('store_const', 'store_true', 'store_false', 'append_const'):
            value = action.const
        elif kind == 'boolean':
            value = not option.startswith('--no-')
        elif kind == 'count':
            value = (getattr(namespace, action.dest, 0) or 0) + 1
        elif action.nargs == '?' and not values:
            value = action.const if action.option_strings else action.default
            if isinstance(value, str) and value != SUPPRESS:
                value = self._convert(action, value)
        else:
            values = [self._convert(action, v) for v in values]
            value = values[0] if action.nargs in (None, '?') else values
        if action.dest == SUPPRESS or value == SUPPRESS:
            return
        if kind in ('append', 'append_const', 'extend'):
            previous = list(getattr(namespace, action.dest, None) or [])
            if kind == 'extend':
                previous.extend(value)
            else:
                previous.append(value)
            value = previous
        setattr(namespace, action.dest, value)
    def parse_args(self, args=None, namespace=None):
        result, extras = self.parse_known_args(args, namespace)
        if extras:
            self.error('unrecognized arguments: ' + ' '.join(extras))
        return result
    def parse_known_args(self, args=None, namespace=None):
        try:
            return self._parse(list(sys.argv[1:] if args is None else args), Namespace() if namespace is None else namespace)
        except ArgumentError as e:
            if not self.exit_on_error:
                raise
            self.error(str(e))
    def _parse(self, args, namespace):
        for action in self._actions:
            if action.dest != SUPPRESS and action.default != SUPPRESS and not hasattr(namespace, action.dest):
                setattr(namespace, action.dest, action.default)
        for key, value in self._defaults.items():
            if not hasattr(namespace, key):
                setattr(namespace, key, value)
        seen, extras, positionals, i, stopped = [], [], [], 0, False
        positions = []
        positional_actions = [a for a in self._actions if not a.option_strings]
        # Split options while retaining positional order; a subcommand owns its tail.
        while i < len(args):
            token = args[i]
            if token == '--' and not stopped:
                stopped = True
                i += 1
                continue
            name = None if stopped else self._option(token)
            if name is None and not stopped and token.startswith('-') and not token.startswith('--') and len(token) > 2 and token[:2] in self._options:
                short = self._options[token[:2]]
                if short.nargs == 0:
                    args[i:i+1] = [token[:2], '-' + token[2:]]
                else:
                    args[i:i+1] = [token[:2] + '=' + token[2:]]
                continue
            if name is None:
                if not stopped and self._looks_option(token):
                    extras.append(token)
                else:
                    positionals.append(token)
                    positions.append(i)
                    # Subcommands follow fixed/optional parent positionals.
                    minimum = sum(a.nargs if isinstance(a.nargs, int) else 1 for a in positional_actions)
                    if self._subparsers is not None and len(positionals) > minimum:
                        positionals.extend(args[i+1:])
                        positions.extend(range(i+1, len(args)))
                        break
                i += 1
                continue
            action = self._options[name]
            seen.append(action)
            i += 1
            explicit = token.split('=', 1)[1] if '=' in token else None
            if action.nargs == 0:
                if explicit is not None:
                    raise ArgumentError(action, 'ignored explicit argument')
                self._store(namespace, action, [], name)
                continue
            values = [] if explicit is None else [explicit]
            limit = 1 if action.nargs in (None, '?') else action.nargs
            minimum = 0 if action.nargs in ('?', '*', REMAINDER) else 1 if action.nargs in (None, '+') else action.nargs
            if explicit is None:
                while i < len(args) and (not isinstance(limit, int) or len(values) < limit) and (action.nargs == REMAINDER or not self._looks_option(args[i])):
                    values.append(args[i])
                    i += 1
            if len(values) < minimum:
                raise ArgumentError(action, 'expected argument(s)')
            self._store(namespace, action, values, name)
        cursor = 0
        for index, action in enumerate(positional_actions):
            nargs = action.nargs
            reserve = sum(a.nargs if isinstance(a.nargs, int) else 0 if a.nargs in ('?', '*') else 1 for a in positional_actions[index+1:])
            available = max(0, len(positionals)-cursor-reserve-(1 if self._subparsers is not None else 0))
            if nargs in ('*', '+') and cursor < len(positions):
                contiguous = 1
                while cursor+contiguous < len(positions) and positions[cursor+contiguous] == positions[cursor+contiguous-1]+1:
                    contiguous += 1
                available = min(available, max(0, contiguous-reserve))
            count = min(available, 1) if nargs == '?' else available if nargs in ('*', '+', REMAINDER) else 1 if nargs is None else nargs
            minimum = 0 if nargs in ('?', '*', REMAINDER) else 1 if nargs in (None, '+') else nargs
            if count < minimum or cursor+count > len(positionals):
                raise ArgumentError(action, 'the following argument is required: ' + action.dest)
            values = positionals[cursor:cursor+count]
            cursor += count
            if count:
                seen.append(action)
            self._store(namespace, action, values)
        rest = positionals[cursor:]
        if self._subparsers is not None:
            sub = self._subparsers
            if rest:
                name = rest[0]
                if name not in sub.parsers:
                    raise ArgumentError(None, 'invalid subcommand: ' + name)
                if sub.dest != SUPPRESS:
                    setattr(namespace, sub.dest, name)
                child, unknown = sub.parsers[name].parse_known_args(rest[1:])
                for key, value in child.__dict__.items():
                    setattr(namespace, key, value)
                extras.extend(unknown)
            elif sub.required:
                raise ArgumentError(None, 'a subcommand is required')
            elif sub.dest != SUPPRESS and not hasattr(namespace, sub.dest):
                setattr(namespace, sub.dest, None)
        else:
            extras.extend(rest)
        for action in self._actions:
            if action.required and action not in seen:
                raise ArgumentError(action, 'required')
            if action not in seen and isinstance(action.default, str) and action.default != SUPPRESS and getattr(namespace, action.dest, None) == action.default:
                setattr(namespace, action.dest, self._convert(action, action.default))
        for group in self._groups:
            if group.exclusive:
                present = [a for a in group.actions if a in seen]
                if len(present) > 1 or group.required and not present:
                    raise ArgumentError(None, 'exactly one of ' + ', '.join(a.dest for a in group.actions) + ' is required' if group.required else 'mutually exclusive arguments')
        return namespace, extras
    def format_usage(self):
        if self.usage is not None:
            return 'usage: ' + self.usage % {'prog': self.prog} + '\n'
        parts = [self.prog]
        for a in self._actions:
            text = a.option_strings[0] if a.option_strings else a.dest
            if a.option_strings and a.nargs != 0:
                text += ' ' + (a.metavar or a.dest.upper())
            if a.option_strings and not a.required or a.nargs in ('?', '*'):
                text = '[' + text + ']'
            parts.append(text)
        if self._subparsers is not None:
            parts.append('{' + ','.join(self._subparsers.parsers) + '} ...')
        return 'usage: ' + ' '.join(parts) + '\n'
    def format_help(self):
        lines = [self.format_usage()]
        if self.description:
            lines.extend([self.description, ''])
        for a in self._actions:
            if a.help == SUPPRESS:
                continue
            names = ', '.join(a.option_strings) if a.option_strings else a.dest
            if a.option_strings and a.nargs != 0:
                names += ' ' + str(a.metavar or a.dest.upper())
            text = a.help or ''
            if text:
                text = text % {'prog': self.prog, 'default': a.default, 'dest': a.dest}
            lines.append('  ' + names + '  ' + text)
        if self._subparsers is not None:
            lines.append('  {' + ','.join(self._subparsers.parsers) + '}')
        if self.epilog:
            lines.extend(['', self.epilog])
        return '\n'.join(lines) + '\n'
    def print_usage(self, file=None):
        self._print_message(self.format_usage(), file)
    def print_help(self, file=None):
        self._print_message(self.format_help(), file)
    def _print_message(self, message, file=None):
        if file is None:
            import _process
            try:
                runtime = _process.runtime()
            except OSError:
                print(message, end='')
                return
            job = runtime.active.get(runtime.pid())
            tty = _process._JobTty(runtime, job) if job is not None and job['background'] else runtime.tty
            tty.write(message)
        else:
            file.write(message)
    def exit(self, status=0, message=None):
        if message:
            self._print_message(message)
        raise SystemExit(status)
    def error(self, message):
        self.print_usage()
        self.exit(2, self.prog + ': error: ' + message + '\n')
