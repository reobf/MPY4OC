import threading
import time

OUT = []


def say(*args):
    OUT.append(' '.join(str(a) for a in args))


lock = threading.Lock()
say('new', lock.locked(), repr(lock).split(' ')[0][1:])
say('acquire', lock.acquire(), lock.locked(), repr(lock).split(' ')[0][1:])
say('again, non-blocking', lock.acquire(False))
say('again, timeout', lock.acquire(timeout=0.05))
for args in ((False, 1), (True, -2)):
    try:
        lock.acquire(*args)
        say('no error for', args)
    except ValueError as error:
        say('ValueError:', error)
try:
    lock.acquire(timeout=threading.TIMEOUT_MAX + 1)
except OverflowError:
    say('OverflowError')    # the wording is the platform's
lock.release()
try:
    lock.release()
except RuntimeError as error:
    say('RuntimeError:', error)
with lock:
    say('with', lock.locked())
say('after with', lock.locked())

# A blocked acquire waits without spending anything; release hands the lock over.
lock.acquire()
log = []


def taker():
    log.append('taker waits')
    lock.acquire()
    log.append('taker has it')
    time.sleep(0.1)
    lock.release()
    log.append('taker released')


t = threading.Thread(target=taker)
t.start()
time.sleep(0.05)
say('while held:', log)
lock.release()
time.sleep(0.05)
say('after release:', log, lock.locked())
say('blocking acquire', lock.acquire(timeout=0.3))
time.sleep(0.05)
say('then:', log, lock.locked())
lock.release()
t.join()

# A timed acquire gives up while another thread holds the lock, and succeeds once it is released.
lock.acquire()


def holder():
    time.sleep(0.2)
    lock.release()


h = threading.Thread(target=holder)
h.start()
say('timeout while held', lock.acquire(timeout=0.05))
say('then', lock.acquire(timeout=0.3))
lock.release()
h.join()

# RLock: reentrant for its owner, and only its owner releases it.
rlock = threading.RLock()
say('rlock', rlock.acquire(), rlock.acquire(), rlock._is_owned())
rlock.release()
rlock.release()
say('rlock released twice', rlock._is_owned())
try:
    rlock.release()
except RuntimeError as error:
    say('RuntimeError:', error)


def thief():
    time.sleep(0.05)
    try:
        rlock.release()
    except RuntimeError as error:
        say('thief:', error)
    say('thief acquire', rlock.acquire(False), rlock.acquire(timeout=0.05), rlock._is_owned())


rlock.acquire()
th = threading.Thread(target=thief)
th.start()
th.join()
rlock.release()
say('rlock free', rlock.acquire(False), rlock._is_owned())
rlock.release()
with rlock:
    with rlock:
        say('nested with', rlock._is_owned())
say('after nested with', rlock._is_owned(), rlock.acquire(False))
rlock.release()
