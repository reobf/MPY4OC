import asyncio

OUT = []


def say(*args):
    OUT.append(' '.join(str(a) for a in args))


async def main():
    lock = asyncio.Lock()

    async def use(name):
        async with lock:
            say('has lock', name, lock.locked())
            await asyncio.sleep(0.05)
        say('released', name)

    await asyncio.gather(use('a'), use('b'), use('c'))
    say('locked after', lock.locked())
    try:
        lock.release()
    except RuntimeError as error:
        say('release unlocked:', error)

    # A cancelled waiter does not get the lock; the next one does.
    await lock.acquire()

    async def take(name):
        await lock.acquire()
        say('took', name)
        lock.release()
    t1 = asyncio.create_task(take('t1'))
    t2 = asyncio.create_task(take('t2'))
    await asyncio.sleep(0)
    t1.cancel()
    await asyncio.sleep(0)
    lock.release()
    await t2
    say('t1', t1.cancelled())

    event = asyncio.Event()

    async def wait_event(name):
        say('waiting', name, event.is_set())
        await event.wait()
        say('woke', name)

    waiters = [asyncio.create_task(wait_event(n)) for n in ('e1', 'e2')]
    await asyncio.sleep(0.05)
    event.set()
    await asyncio.gather(*waiters)
    say('set', event.is_set(), await event.wait())
    event.clear()
    say('cleared', event.is_set())

    cond = asyncio.Condition()
    ready = []

    async def consumer(name):
        async with cond:
            await cond.wait_for(lambda: ready)
            say('consumer', name, ready.pop(0))

    consumers = [asyncio.create_task(consumer(n)) for n in ('c1', 'c2')]
    await asyncio.sleep(0.05)
    async with cond:
        ready.append('item1')
        cond.notify()
    await asyncio.sleep(0.05)
    async with cond:
        ready.append('item2')
        cond.notify_all()
    await asyncio.gather(*consumers)
    try:
        await cond.wait()
    except RuntimeError as error:
        say('wait without the lock:', error)
    try:
        cond.notify()
    except RuntimeError as error:
        say('notify without the lock:', error)

    sem = asyncio.Semaphore(2)
    inside = []

    async def limited(name):
        async with sem:
            inside.append(name)
            say('inside', sorted(inside), sem.locked())
            await asyncio.sleep(0.05)
            inside.remove(name)

    await asyncio.gather(*(limited(n) for n in ('s1', 's2', 's3', 's4')))
    say('semaphore free', sem.locked())
    bounded = asyncio.BoundedSemaphore(1)
    await bounded.acquire()
    bounded.release()
    try:
        bounded.release()
    except ValueError as error:
        say('bounded:', error)
    try:
        asyncio.Semaphore(-1)
    except ValueError as error:
        say('negative:', error)


asyncio.run(main())
