# motd -- the tip MPYOS shows at boot, as OpenOS does: a random line of
# /usr/misc/greetings.txt. init.py prints it under the banner.

PATH = "/usr/misc/greetings.txt"


def tips(data):
    """The tips in the file's bytes: its lines, without blank lines and # comments."""
    if not data:
        return []
    lines = [line.strip() for line in data.decode("utf-8").split("\n")]
    return [line for line in lines if line and not line.startswith("#")]


def pick(tips, t):
    """The tip for time t (computer.worldTime(), in ticks), or None. Fibonacci hashing, so
    boots a few seconds apart land far apart in the list."""
    if not tips:
        return None
    return tips[(((t * 0x9E3779B97F4A7C15) & 0xFFFFFFFFFFFFFFFF) >> 32) % len(tips)]
