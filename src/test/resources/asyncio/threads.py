import asyncio
import threading

OUT = []


def say(*args):
    OUT.append(' '.join(str(a) for a in args))


def double(x):
    return x * 2


def missing():
    raise KeyError('missing')


async def main():
    say('to_thread', await asyncio.to_thread(double, 21))
    try:
        await asyncio.to_thread(missing)
    except KeyError as error:
        say('to_thread raised', type(error).__name__, error)
    loop = asyncio.get_running_loop()
    say('run_in_executor', await loop.run_in_executor(None, double, 5))

    # Another thread hands the loop work while it waits.
    got = loop.create_future()

    def other():
        loop.call_soon_threadsafe(got.set_result, 'from a thread')
    sender = threading.Thread(target=other)
    sender.start()
    say('call_soon_threadsafe', await got)

    # A thread runs a coroutine on this loop and waits for its result.
    results = []

    async def doubled(value):
        await asyncio.sleep(0.05)
        return value * 2

    def submitter():
        future = asyncio.run_coroutine_threadsafe(doubled(4), loop)
        results.append(future.result(5))
    worker = threading.Thread(target=submitter)
    worker.start()
    while not results:
        await asyncio.sleep(0.05)
    say('run_coroutine_threadsafe', results[0])
    while sender.is_alive() or worker.is_alive():
        await asyncio.sleep(0.05)


asyncio.run(main())

# A thread has no event loop until it sets one (Python 3.12).
errors = []


def no_loop():
    try:
        asyncio.get_event_loop()
    except RuntimeError as error:
        errors.append(str(error))


thread = threading.Thread(target=no_loop)
thread.start()
thread.join()
say('thread get_event_loop', errors)

# Each thread runs its own loop.
seen = []


async def where():
    await asyncio.sleep(0.05)
    return threading.current_thread().name


def runs_its_own():
    seen.append(asyncio.run(where()))


thread = threading.Thread(target=runs_its_own, name='worker')
thread.start()
thread.join()
say('own loop', seen, asyncio.run(where()))
