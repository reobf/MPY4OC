import asyncio

OUT = []


def say(*args):
    OUT.append(' '.join(str(a) for a in args))


async def worker(name, delay):
    say('start', name, asyncio.current_task().get_name())
    await asyncio.sleep(delay)
    say('end', name)
    return name


async def ping(n):
    for i in range(n):
        say('ping', i)
        await asyncio.sleep(0)


async def pong(n):
    for i in range(n):
        say('pong', i)
        await asyncio.sleep(0)


async def main():
    say('main', asyncio.current_task().get_name())
    t1 = asyncio.create_task(worker('a', 0.1))
    t2 = asyncio.create_task(worker('b', 0.05), name='named')
    say('created', t1.get_name(), t2.get_name(), t1.done())
    results = await asyncio.gather(t1, t2, worker('c', 0))
    say('gather', results)
    await asyncio.gather(ping(3), pong(3))
    say('sleep result', await asyncio.sleep(0.05, 'value'))
    say('tasks', len(asyncio.all_tasks()), asyncio.current_task() in asyncio.all_tasks())
    loop = asyncio.get_running_loop()
    say('loop', loop.is_running(), loop.is_closed(), isinstance(loop, asyncio.AbstractEventLoop))
    fut = loop.create_future()
    loop.call_later(0.05, fut.set_result, 42)
    say('future', await fut, fut.done(), fut.result())
    t3 = asyncio.ensure_future(worker('d', 0))
    say('ensure_future', await t3, isinstance(t3, asyncio.Task), asyncio.isfuture(t3))
    say('iscoroutine', asyncio.iscoroutine(t3), asyncio.iscoroutinefunction(worker))
    return 'done'


say('run', asyncio.run(main()))
say('run again', asyncio.run(ping(1)))


async def fails():
    await asyncio.sleep(0)
    raise ValueError('boom')


try:
    asyncio.run(fails())
except ValueError as error:
    say('raised', type(error).__name__, error)
