import threading
import time

OUT = []


def say(*args):
    OUT.append(' '.join(str(a) for a in args))


main = threading.current_thread()
say('main thread', main.name, main is threading.main_thread(), main.daemon, main.is_alive(),
    threading.active_count(), [t.name for t in threading.enumerate()])
say('ident', isinstance(threading.get_ident(), int), threading.get_ident() == main.ident)


def worker(label, hold):
    # A thread says nothing before it has slept: its first line never races the main thread's.
    time.sleep(hold)
    me = threading.current_thread()
    say(label, 'runs as', me.name, 'daemon', me.daemon, 'ident ok', me.ident == threading.get_ident())
    say(label, 'sees', threading.active_count(), 'threads')


plain = threading.Thread(target=worker, args=('plain', 0.1))
say(repr(plain), plain.is_alive(), plain.ident)
try:
    plain.join()
except RuntimeError as error:
    say('join before start:', error)
plain.start()
say('started', plain.is_alive(), isinstance(plain.ident, int), threading.active_count(),
    [t.name for t in threading.enumerate()])
try:
    plain.start()
except RuntimeError as error:
    say('start twice:', error)
plain.join(0.05)
say('join timed out', plain.is_alive())
plain.join()
say('joined', plain.is_alive(), threading.active_count(), repr(plain).split(', ')[0], 'stopped' in repr(plain))

# kwargs reach the target; a named thread keeps its name and takes no number.
named = threading.Thread(target=worker, name='named', kwargs={'label': 'kw', 'hold': 0.05})
say(repr(named))
named.start()
named.join()


# A thread cannot join itself; the main thread does not end before the program does.
def selfish():
    time.sleep(0.05)
    try:
        threading.current_thread().join()
    except RuntimeError as error:
        say('join self:', error)
    threading.main_thread().join(0.05)
    say('main still alive', threading.main_thread().is_alive())


s = threading.Thread(target=selfish)
s.start()
s.join()


# Daemon status is inherited from the starting thread; a thread's own threads join as any.
def nested():
    time.sleep(0.05)
    child = threading.Thread(target=worker, args=('child', 0.05))
    say('nested makes', child.name, 'daemon', child.daemon)
    child.start()
    child.join()
    say('nested joined child', child.is_alive())


outer = threading.Thread(target=nested, daemon=True)
say('outer', outer.name, outer.daemon, repr(outer))
outer.start()
outer.join()
say('after outer', threading.active_count())


# A thread whose target raises ends (the report goes to stderr); the program goes on.
def failing():
    time.sleep(0.05)
    raise ValueError('boom')


f = threading.Thread(target=failing)
f.start()
f.join()
say('failed thread', f.is_alive(), threading.active_count())


# A subclass overriding run().
class Counter(threading.Thread):
    def __init__(self):
        threading.Thread.__init__(self, name='counter')
        self.total = 0

    def run(self):
        for i in range(5):
            time.sleep(0.05)
            self.total += i


counter = Counter()
counter.start()
say('counting', counter.is_alive(), counter.total)
counter.join()
say('counted', counter.is_alive(), counter.total)
say('end', threading.active_count(), [t.name for t in threading.enumerate()])
