# wget <url> [file] -- download a URL to disk (needs an internet card).
if not args:
    tty.print("usage: wget <url> [file]")
    tty.print("       downloads over the internet card; https is fine")
else:
    url = args[0]
    if len(args) > 1:
        dest = sh.resolve(args[1])
    else:
        name = url.rstrip("/").split("/")[-1].split("?")[0]
        dest = sh.resolve(name if name else "index.html")
    try:
        import requests
        r = requests.get(url, stream=True)
        if not r.ok:
            tty.print("wget: HTTP " + str(r.status_code) + " " + str(r.reason))
        else:
            handle = sh.fs.open(dest, "wb")
            total = 0
            try:
                for chunk in r.iter_content(4096):
                    sh.fs._write_chunk(handle, chunk)
                    total += len(chunk)
            finally:
                sh.fs.close(handle)
                r.close()
            tty.print("saved " + str(total) + " bytes -> " + dest)
    except Exception as e:
        tty.print("wget: " + str(e))
