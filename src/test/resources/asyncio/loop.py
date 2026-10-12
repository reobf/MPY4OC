import asyncio

OUT = []


def say(*args):
    OUT.append(' '.join(str(a) for a in args))


def error_text(call):
    try:
        call()
    except BaseException as error:
        return type(error).__name__ + ': ' + str(error)
    return 'no error'


async def nothing():
    return 'nothing'


# As Python 3.12, the main thread gets a loop the first time it asks.
auto = asyncio.get_event_loop()
say('auto loop', isinstance(auto, asyncio.AbstractEventLoop), asyncio.get_event_loop() is auto)
auto.close()

# Outside any loop.
coro = nothing()
say('create_task outside', error_text(lambda: asyncio.create_task(coro)))
coro.close()
say('get_running_loop outside', error_text(asyncio.get_running_loop))
say('current_task outside', error_text(asyncio.current_task))
say('run a number', error_text(lambda: asyncio.run(5)))
say('set_event_loop', error_text(lambda: asyncio.set_event_loop(5)))

loop = asyncio.new_event_loop()
say('new loop', loop.is_running(), loop.is_closed(), isinstance(loop, asyncio.AbstractEventLoop))

# Callbacks run in the order they were scheduled; timers by their time.
loop.call_soon(say, 'soon 1')
handle = loop.call_soon(say, 'soon cancelled')
loop.call_soon(say, 'soon 2', 'with', 'args')
handle.cancel()
loop.call_later(0.1, say, 'later 0.1')
loop.call_later(0.05, say, 'later 0.05')
loop.call_at(loop.time() + 0.15, say, 'at +0.15')
timer = loop.call_later(0.05, say, 'timer cancelled')
timer.cancel()
say('handles', handle.cancelled(), timer.cancelled())
loop.call_later(0.2, loop.stop)
loop.run_forever()
say('stopped', loop.is_running())


async def inside():
    say('running', asyncio.get_running_loop() is loop, loop.is_running())
    say('run_until_complete inside', error_text(lambda: loop.run_until_complete(nothing())))
    c = nothing()
    say('nested run', error_text(lambda: asyncio.run(c)))
    c.close()
    say('close while running', error_text(loop.close))
    return 'inside done'


say('run_until_complete', loop.run_until_complete(inside()))

# Futures.
fut = loop.create_future()
say('pending result', error_text(fut.result))
say('pending exception', error_text(fut.exception))
calls = []
fut.add_done_callback(lambda f: calls.append('first'))
fut.add_done_callback(lambda f: calls.append('second'))
removable = lambda f: calls.append('removed')
fut.add_done_callback(removable)
say('removed callbacks', fut.remove_done_callback(removable))
fut.set_result(7)
say('set twice', error_text(lambda: fut.set_result(8)))
loop.run_until_complete(asyncio.sleep(0))
say('callbacks', calls, fut.result(), fut.done(), fut.cancelled())
late = []
fut.add_done_callback(lambda f: late.append('late'))
loop.run_until_complete(asyncio.sleep(0))
say('callback after done', late)
failed = loop.create_future()
failed.set_exception(KeyError('k'))
say('failed', type(failed.exception()).__name__, error_text(failed.result))
cancelled = loop.create_future()
say('cancel', cancelled.cancel('why'), cancelled.cancel(), error_text(cancelled.result))

# Errors in callbacks go to the exception handler.
seen = []
loop.set_exception_handler(lambda lp, context: seen.append((context['message'][:21], type(context.get('exception')).__name__)))
say('handler', loop.get_exception_handler() is not None)


def broken():
    raise ZeroDivisionError('oops')


loop.call_soon(broken)
loop.run_until_complete(asyncio.sleep(0))
say('handler saw', seen)
loop.set_exception_handler(None)
say('coroutine as callback', error_text(lambda: loop.call_soon(nothing)))
say('call_later None', error_text(lambda: loop.call_later(None, say)))
say('not callable', error_text(lambda: loop.call_soon(5)))

loop.close()
say('closed', loop.is_closed(), error_text(lambda: loop.run_until_complete(asyncio.sleep(0))))
say('call_soon closed', error_text(lambda: loop.call_soon(say)))
loop.close()

# asyncio.run(5) above set (and then cleared) this thread's loop: none now.
say('after run(5)', error_text(asyncio.get_event_loop))
first = asyncio.new_event_loop()
asyncio.set_event_loop(first)
say('set back', asyncio.get_event_loop() is first)
first.close()
say('asyncio.run', asyncio.run(nothing()))
say('after asyncio.run', error_text(asyncio.get_event_loop))

with asyncio.Runner() as runner:
    say('runner', runner.run(nothing()), runner.get_loop().is_closed())
say('runner closed', error_text(runner.get_loop))
