import threading
import time

OUT = []


def say(*args):
    OUT.append(' '.join(str(a) for a in args))


# Event
ev = threading.Event()
say('event', ev.is_set(), ev.wait(0.05))


def setter():
    time.sleep(0.1)
    ev.set()


threading.Thread(target=setter).start()
say('wait', ev.wait(), ev.is_set())
say('set again', ev.wait(0.05))
ev.clear()
say('cleared', ev.is_set(), ev.wait(0))

# Semaphore and BoundedSemaphore
sem = threading.Semaphore(2)
say('sem', sem.acquire(), sem.acquire(), sem.acquire(False), sem.acquire(timeout=0.05))
sem.release()
say('released one', sem.acquire(False))
sem.release(2)
say('released two', sem.acquire(False), sem.acquire(False), sem.acquire(False))
try:
    threading.Semaphore(-1)
except ValueError as error:
    say('ValueError:', error)
bsem = threading.BoundedSemaphore(1)
bsem.acquire()
bsem.release()
try:
    bsem.release()
except ValueError as error:
    say('ValueError:', error)
with bsem:
    say('with bsem', bsem.acquire(False))
say('after with bsem', bsem.acquire(False))
bsem.release()
sem = threading.Semaphore(0)


def releaser():
    time.sleep(0.1)
    sem.release()


threading.Thread(target=releaser).start()
say('sem wait', sem.acquire())

# Barrier
results = []
barrier = threading.Barrier(3, action=lambda: results.append('action'))
say('barrier', barrier.parties, barrier.n_waiting, barrier.broken)


def party(delay):
    time.sleep(delay)
    results.append(barrier.wait())


ps = [threading.Thread(target=party, args=(d,)) for d in (0.05, 0.1, 0.2)]
for p in ps:
    p.start()
time.sleep(0.15)
say('waiting', barrier.n_waiting)
for p in ps:
    p.join()
say('released', results[0], sorted(results[1:]), barrier.n_waiting, barrier.broken)

barrier = threading.Barrier(2)


def waiter():
    try:
        barrier.wait()
    except threading.BrokenBarrierError:
        say('waiter: broken')


w = threading.Thread(target=waiter)
w.start()
time.sleep(0.05)
barrier.abort()
w.join()
say('broken', barrier.broken)
barrier.reset()
say('after reset', barrier.broken, barrier.n_waiting)
try:
    barrier.wait(0.05)
except threading.BrokenBarrierError:
    say('wait timed out: broken', barrier.broken)

# Timer
fired = []
timer = threading.Timer(0.1, lambda: fired.append('fired'))
say('timer', timer.name.startswith('Thread-'), timer.daemon)
timer.start()
time.sleep(0.05)
say('before', fired)
time.sleep(0.1)
say('after', fired)
cancelled = threading.Timer(0.05, lambda: fired.append('no'))
cancelled.start()
cancelled.cancel()
cancelled.join()
say('cancelled', fired, cancelled.is_alive())
