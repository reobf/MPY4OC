import threading
import time

OUT = []


def say(*args):
    OUT.append(' '.join(str(a) for a in args))


cond = threading.Condition()
items = []


def consumer():
    with cond:
        while not items:
            say('consumer waits')
            cond.wait()
        say('consumer got', items.pop(0))


c = threading.Thread(target=consumer)
c.start()
time.sleep(0.05)
with cond:
    items.append('apple')
    cond.notify()
    say('notified')
c.join()

# Timeouts return False; wait_for() returns the predicate's value.
with cond:
    say('wait timeout', cond.wait(0.05))
    say('wait_for timeout', cond.wait_for(lambda: False, 0.05))
    say('wait_for at once', cond.wait_for(lambda: 'ready'))

# A wait or a notify needs the lock.
for name, call in (('wait', lambda: cond.wait()), ('notify', lambda: cond.notify()), ('notify_all', lambda: cond.notify_all())):
    try:
        call()
    except RuntimeError as error:
        say(name + ':', error)

# notify_all wakes every waiter; notify(n) only n of them.
cond = threading.Condition(threading.Lock())
woken = []


def waiter(name):
    with cond:
        cond.wait()
        woken.append(name)


ws = [threading.Thread(target=waiter, args=(name,)) for name in ('a', 'b', 'c')]
for w in ws:
    w.start()
time.sleep(0.05)
with cond:
    cond.notify(1)
time.sleep(0.05)
say('after notify(1):', len(woken))
with cond:
    cond.notify_all()
for w in ws:
    w.join()
say('after notify_all:', sorted(woken))

# wait_for with a predicate that another thread makes true.
state = {'ready': False}


def maker():
    time.sleep(0.1)
    with cond:
        state['ready'] = True
        cond.notify()


m = threading.Thread(target=maker)
m.start()
with cond:
    say('wait_for', cond.wait_for(lambda: state['ready'], 1))
m.join()

# A Condition over an RLock keeps the recursion level across the wait.
rcond = threading.Condition(threading.RLock())
rlog = []


def rwaiter():
    with rcond:
        with rcond:
            rcond.wait()
            rlog.append(rcond._is_owned())


r = threading.Thread(target=rwaiter)
r.start()
time.sleep(0.05)
with rcond:
    rcond.notify()
r.join()
say('owned after wait:', rlog)
