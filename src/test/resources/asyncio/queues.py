import asyncio

OUT = []


def say(*args):
    OUT.append(' '.join(str(a) for a in args))


async def main():
    queue = asyncio.Queue(maxsize=2)
    say('new', queue.qsize(), queue.empty(), queue.full(), queue.maxsize)

    async def producer():
        for i in range(5):
            await queue.put(i)
            say('put', i, queue.qsize())
        await queue.put(None)

    async def consumer():
        while True:
            item = await queue.get()
            queue.task_done()
            if item is None:
                break
            say('got', item)
            await asyncio.sleep(0.05)

    await asyncio.gather(producer(), consumer())
    await queue.join()
    say('joined', queue.empty())

    try:
        queue.get_nowait()
    except asyncio.QueueEmpty:
        say('empty: QueueEmpty')
    queue.put_nowait('x')
    queue.put_nowait('y')
    try:
        queue.put_nowait('z')
    except asyncio.QueueFull:
        say('full: QueueFull', queue.full())
    say('nowait', queue.get_nowait(), queue.get_nowait())
    queue.task_done()
    queue.task_done()
    try:
        queue.task_done()
    except ValueError as error:
        say('task_done:', error)

    # join() waits until every item put was marked done.
    work = asyncio.Queue()
    for i in range(3):
        work.put_nowait(i)

    async def worker():
        while not work.empty():
            item = await work.get()
            await asyncio.sleep(0.05)
            say('worked', item)
            work.task_done()

    runner = asyncio.create_task(worker())
    await work.join()
    say('all work done')
    await runner

    priority = asyncio.PriorityQueue()
    for entry in [(3, 'c'), (1, 'a'), (2, 'b')]:
        await priority.put(entry)
    got = []
    for _ in range(3):     # (await in a comprehension does not compile here, as in MicroPython)
        got.append((await priority.get())[1])
    say('priority', got)
    lifo = asyncio.LifoQueue()
    for item in 'abc':
        lifo.put_nowait(item)
    say('lifo', [lifo.get_nowait() for _ in range(3)])

    # A getter waiting on an empty queue is woken by put().
    waiting = asyncio.Queue()

    async def late_put():
        await asyncio.sleep(0.05)
        await waiting.put('late')

    putter = asyncio.create_task(late_put())
    say('waited for', await waiting.get())
    await putter
    cancelled = asyncio.create_task(waiting.get())
    await asyncio.sleep(0)
    cancelled.cancel()
    await asyncio.sleep(0)
    waiting.put_nowait('kept')
    say('after a cancelled get', waiting.get_nowait(), cancelled.cancelled())


asyncio.run(main())
