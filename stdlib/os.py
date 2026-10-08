# frozen os: only the sandbox-appropriate bits (no filesystem here).
urandom = __os_urandom

def uname():
    return ("mpy-java", "mpy-java", "1.0", "1.0", "java")
