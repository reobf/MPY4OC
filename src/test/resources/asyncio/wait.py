import asyncio

OUT = []


def say(*args):
    OUT.append(' '.join(str(a) for a in args))


async def job(name, delay, fail=False):
    await asyncio.sleep(delay)
    if fail:
        raise ValueError(name)
    return name


def names(tasks):
    return sorted(t.get_name() for t in tasks)


async def error_text(coro):
    try:
        await coro
    except Exception as error:
        return type(error).__name__ + ': ' + str(error)
    return 'no error'


async def main():
    a = asyncio.create_task(job('a', 0.05), name='a')
    b = asyncio.create_task(job('b', 0.15), name='b')
    done, pending = await asyncio.wait({a, b}, return_when=asyncio.FIRST_COMPLETED)
    say('first completed', names(done), names(pending))
    done, pending = await asyncio.wait({a, b})
    say('all completed', names(done), names(pending))

    c = asyncio.create_task(job('c', 0.05, fail=True), name='c')
    d = asyncio.create_task(job('d', 0.2), name='d')
    done, pending = await asyncio.wait([c, d], return_when=asyncio.FIRST_EXCEPTION)
    say('first exception', names(done), names(pending), type(c.exception()).__name__)
    done, pending = await asyncio.wait([d], timeout=0.05)
    say('timed out', names(done), names(pending))
    await d

    say('empty', await error_text(asyncio.wait(set())))
    say('bad return_when', await error_text(asyncio.wait({d}, return_when='SOMETIMES')))
    coro = job('x', 0)
    say('coroutines', await error_text(asyncio.wait([coro])))
    await coro

    # wait_for: the result, or TimeoutError with the awaited task cancelled.
    say('wait_for', await asyncio.wait_for(job('quick', 0.05), 1))
    slow = asyncio.create_task(job('slow', 1))
    try:
        await asyncio.wait_for(slow, 0.1)
    except TimeoutError as error:
        say('wait_for timeout', type(error).__name__, slow.cancelled())
    say('wait_for 0', await asyncio.wait_for(asyncio.sleep(0, 'ready'), None))
    done_future = asyncio.get_running_loop().create_future()
    done_future.set_result('at once')
    say('wait_for done', await asyncio.wait_for(done_future, 0))
    try:
        await asyncio.wait_for(job('late', 1), 0)
    except TimeoutError:
        say('wait_for 0 times out')

    # asyncio.timeout(): the block is cancelled, and TimeoutError comes out of it.
    try:
        async with asyncio.timeout(0.1) as limit:
            await asyncio.sleep(1)
    except TimeoutError:
        say('timeout expired', limit.expired())
    try:
        async with asyncio.timeout(None) as limit:
            await asyncio.sleep(0.05)
            limit.reschedule(asyncio.get_running_loop().time() + 0.05)
            try:
                await asyncio.sleep(0.5)
            except asyncio.CancelledError:
                say('cancelled inside the block')
                raise
    except TimeoutError:
        say('rescheduled timeout expired', limit.expired())
    async with asyncio.timeout(1):
        await asyncio.sleep(0.05)
    say('in time')

    # as_completed: in the order they finish.
    order = []
    for next_done in asyncio.as_completed([job('slowest', 0.2), job('fastest', 0.05), job('middle', 0.1)]):
        order.append(await next_done)
    say('as_completed', order)

    # gather: results in order; return_exceptions keeps failures as values.
    say('gather', await asyncio.gather(job('one', 0.1), job('two', 0.05)))
    results = await asyncio.gather(job('ok', 0), job('bad', 0, fail=True), return_exceptions=True)
    say('gather exceptions', [type(r).__name__ if isinstance(r, Exception) else r for r in results])
    try:
        await asyncio.gather(job('fine', 0.1), job('broken', 0.05, fail=True))
    except ValueError as error:
        say('gather raises', error)
    same = job('same', 0)
    say('same coroutine twice', await asyncio.gather(same, same))
    say('nothing', await asyncio.gather())


asyncio.run(main())
