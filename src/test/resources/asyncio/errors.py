import asyncio
import gc

OUT = []


def say(*args):
    OUT.append(' '.join(str(a) for a in args))


cancelled = []
messages = []


async def leftover(name):
    try:
        await asyncio.sleep(10)
    except asyncio.CancelledError:
        cancelled.append(name)
        raise


async def fails_when_cancelled():
    try:
        await asyncio.sleep(10)
    except asyncio.CancelledError:
        raise RuntimeError('during cancel')


def handler(loop, context):
    messages.append(context['message'] + ': ' + type(context.get('exception')).__name__)


async def main():
    asyncio.get_running_loop().set_exception_handler(handler)
    asyncio.create_task(leftover('a'))
    asyncio.create_task(leftover('b'))
    asyncio.create_task(fails_when_cancelled())
    await asyncio.sleep(0)
    say('main returns')
    return 'main'


# asyncio.run() cancels what main left running, and reports what failed doing so.
say('result', asyncio.run(main()))
say('cancelled', sorted(cancelled))
say('messages', messages)


async def boom():
    raise ValueError('lost')


async def forgets():
    asyncio.get_running_loop().set_exception_handler(handler)
    asyncio.create_task(boom())
    await asyncio.sleep(0.05)


del messages[:]
asyncio.run(forgets())
gc.collect()
say('never retrieved', messages)


async def retrieves():
    asyncio.get_running_loop().set_exception_handler(handler)
    task = asyncio.create_task(boom())
    await asyncio.sleep(0.05)
    say('retrieved', type(task.exception()).__name__)


del messages[:]
asyncio.run(retrieves())
gc.collect()
say('nothing reported', messages)


async def main_fails():
    asyncio.create_task(leftover('c'))
    await asyncio.sleep(0)
    raise KeyError('main')


del cancelled[:]
try:
    asyncio.run(main_fails())
except KeyError as error:
    say('main raised', type(error).__name__, error, cancelled)
