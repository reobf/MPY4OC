# cat <file...> -- print file contents.
if not args:
    tty.print("usage: cat <file...>")
else:
    for a in args:
        p = sh.resolve(a)
        data = sh.fs.read_bytes(p)
        if data is None:
            tty.print("cat: no such file: " + p)
        else:
            data = data.decode("utf-8", "replace")   # show a binary file, do not stop at it
            tty.write(data)
            if data and not data.endswith("\n"):
                tty.write("\n")
