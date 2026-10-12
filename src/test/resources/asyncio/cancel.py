import asyncio

OUT = []


def say(*args):
    OUT.append(' '.join(str(a) for a in args))


async def inner():
    try:
        await asyncio.sleep(1)
    except asyncio.CancelledError as error:
        say('inner cancelled', error.args)
        raise
    finally:
        say('inner finally')


async def outer():
    try:
        await inner()
    except asyncio.CancelledError:
        say('outer cancelled')
        raise


async def waiter(task):
    try:
        await task
    except asyncio.CancelledError:
        say('waiter saw the cancellation')
        raise


async def swallow():
    try:
        await asyncio.sleep(1)
    except asyncio.CancelledError:
        say('swallowed')
    await asyncio.sleep(0)
    return 'kept going'


async def general():
    try:
        await asyncio.sleep(1)
    except Exception:
        say('caught by Exception')
    except BaseException as error:
        say('caught by BaseException', type(error).__name__)
        raise


async def never_runs():
    say('body ran')


async def child(name, delay):
    try:
        await asyncio.sleep(delay)
        say('child done', name)
        return name
    except asyncio.CancelledError:
        say('child cancelled', name)
        raise


async def main():
    # A cancellation reaches the innermost await first, and unwinds outward.
    task = asyncio.create_task(outer())
    watcher = asyncio.create_task(waiter(task))
    await asyncio.sleep(0)
    say('cancel', task.cancel('stop'), task.cancelling())
    try:
        await task
    except asyncio.CancelledError as error:
        say('main saw', type(error).__name__, error.args)
    say('states', task.cancelled(), task.done(), task.cancel())
    try:
        task.result()
    except asyncio.CancelledError as error:
        say('result raises', type(error).__name__)
    try:
        task.exception()
    except asyncio.CancelledError:
        say('exception() raises too')
    await asyncio.sleep(0)
    say('watcher', watcher.cancelled())

    # Cancelled before it ever ran: the body never starts.
    early = asyncio.create_task(never_runs())
    say('early cancel', early.cancel())
    await asyncio.sleep(0)
    say('early', early.cancelled())

    # A task that catches the CancelledError carries on and is not cancelled.
    kept = asyncio.create_task(swallow())
    await asyncio.sleep(0)
    kept.cancel()
    say('kept', await kept, kept.cancelled(), kept.cancelling(), kept.uncancel(), kept.cancelling())

    # except Exception does not catch it: CancelledError is a BaseException.
    plain = asyncio.create_task(general())
    await asyncio.sleep(0)
    plain.cancel()
    try:
        await plain
    except asyncio.CancelledError:
        say('plain cancelled', plain.cancelled())

    # shield: the outer await is cancelled, the shielded work goes on.
    work = asyncio.create_task(child('shielded', 0.1))

    async def protect():
        return await asyncio.shield(work)
    guard = asyncio.create_task(protect())
    await asyncio.sleep(0)
    guard.cancel()
    try:
        await guard
    except asyncio.CancelledError:
        say('guard cancelled', work.cancelled(), work.done())
    say('shielded result', await work)

    # gather: cancelling it cancels the children still running.
    group = asyncio.gather(child('g1', 0.05), child('g2', 0.2))
    await asyncio.sleep(0.1)
    say('gather cancel', group.cancel())
    try:
        await group
    except asyncio.CancelledError:
        say('gather cancelled', group.cancelled())

    # A task cancelled while it waits for another task: the other one is cancelled first.
    first = asyncio.create_task(child('first', 1))

    async def chained():
        try:
            return await first
        except asyncio.CancelledError:
            say('chained cancelled')
            raise
    second = asyncio.create_task(chained())
    await asyncio.sleep(0)
    second.cancel()
    await asyncio.gather(second, return_exceptions=True)
    say('chain', first.cancelled(), second.cancelled())


asyncio.run(main())
