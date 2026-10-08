"""原型: 每个 run() 自带一个协程调度池, 该调度器本身是线程池里的一个 task。

和现在的区别:
  现在 —— 协程和线程平铺在同一个池子里, 调度器必须【运行时检查】谁卡在半途,
          才能阻止别的协程看到半成品 (0.0.61/0.0.63 就是这些检查)。
  这里 —— 一组协程 = 池子里的【一个】task。它被 ops 耗尽或宿主调用切走时,
          整组一起停, 别的协程【根本没机会跑】。原子性是结构决定的, 不用检查。
          线程之间照旧自由交错 —— 那本来就是线程该有的语义。
"""

import jasyncio as _j


class _Group:
    """一次 run() 的私有协程池。"""

    def __init__(self, main_coro):
        self.queue = [main_coro]      # 待推进的协程
        self.main = main_coro
        self.result = None
        self.exc = None
        self.done = False

    def spawn(self, coro):
        """在这一组里再起一个协程(相当于 create_task)。"""
        self.queue.append(coro)
        return coro

    def _drive(self):
        """调度器本体。作为一个 task 注册进线程池, 所以 `yield` = 让给别的线程。"""
        while self.queue:
            for c in list(self.queue):
                if c not in self.queue:
                    continue
                try:
                    # 推进这个协程一步。若 ops 在这句中间耗尽, 整个 _drive 被挂起,
                    # 队列里其它协程自然停在原地 —— 这就是结构性的原子性。
                    c.send(None)
                except StopIteration as si:
                    self.queue.remove(c)
                    if c is self.main:
                        self.result = si.args[0] if si.args else None
                except BaseException as e:
                    self.queue.remove(c)
                    if c is self.main:
                        self.exc = e
                        self.queue = []      # 主协程炸了, 整组结束
                        break
            # 一轮结束才让给池子: 让出点永远落在协程的 send 之间, 不会落在中间
            yield
        self.done = True


def prun(main_coro, extra=()):
    """原型版 run: 建一个私有协程池, 交给线程池驱动, 等它结算。

    可重入 —— 每次调用都是独立的一组, 所以线程里可以 run, 多个 run 也能并存。
    """
    g = _Group(main_coro)
    for c in extra:
        g.spawn(c)
    _j._spawn(g._drive())                 # 调度器作为一个 task 进入线程池
    return g                              # 调用方轮询 g.done / g.result
