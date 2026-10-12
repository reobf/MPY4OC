# which <name> -- show the path of a shell command, or that it's a builtin.
if not args:
    tty.print("usage: which <name>")
else:
    name = args[0]
    import shell
    if name in shell._BUILTINS:
        tty.print(name + ": shell builtin")
    else:
        path = sh.command_path(name)
        if path is not None:
            tty.print(path)
        else:
            tty.print(name + ": not found")
