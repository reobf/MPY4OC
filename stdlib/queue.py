"""Thread queues using VM-aware Condition waits; all state survives snapshots."""
import threading
import time
import heapq

class Empty(Exception):
    pass

class Full(Exception):
    pass

class Queue:
    def __init__(self, maxsize=0):
        self.maxsize = maxsize
        self._items = []
        self._head = 0
        self.mutex = threading.Lock()
        self.not_empty = threading.Condition(self.mutex)
        self.not_full = threading.Condition(self.mutex)
        self.all_tasks_done = threading.Condition(self.mutex)
        self.unfinished_tasks = 0

    def _qsize(self):
        return len(self._items) - self._head

    def _put(self, item):
        self._items.append(item)

    def _get(self):
        item = self._items[self._head]
        self._items[self._head] = None
        self._head += 1
        if self._head >= 64 and self._head * 2 >= len(self._items):
            self._items = self._items[self._head:]
            self._head = 0
        return item

    def qsize(self):
        with self.mutex:
            return self._qsize()

    def empty(self):
        return self.qsize() == 0

    def full(self):
        with self.mutex:
            return self.maxsize > 0 and self._qsize() >= self.maxsize

    def put(self, item, block=True, timeout=None):
        with self.not_full:
            if self.maxsize > 0:
                if not block:
                    if self._qsize() >= self.maxsize:
                        raise Full
                elif timeout is None:
                    while self._qsize() >= self.maxsize:
                        self.not_full.wait()
                elif timeout < 0:
                    raise ValueError("'timeout' must be a non-negative number")
                else:
                    deadline = time.monotonic() + timeout
                    while self._qsize() >= self.maxsize:
                        remaining = deadline - time.monotonic()
                        if remaining <= 0:
                            raise Full
                        self.not_full.wait(remaining)
            self._put(item)
            self.unfinished_tasks += 1
            self.not_empty.notify()

    def get(self, block=True, timeout=None):
        with self.not_empty:
            if not block:
                if not self._qsize():
                    raise Empty
            elif timeout is None:
                while not self._qsize():
                    self.not_empty.wait()
            elif timeout < 0:
                raise ValueError("'timeout' must be a non-negative number")
            else:
                deadline = time.monotonic() + timeout
                while not self._qsize():
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise Empty
                    self.not_empty.wait(remaining)
            item = self._get()
            self.not_full.notify()
            return item

    def put_nowait(self, item):
        return self.put(item, False)

    def get_nowait(self):
        return self.get(False)

    def task_done(self):
        with self.all_tasks_done:
            remaining = self.unfinished_tasks - 1
            if remaining < 0:
                raise ValueError('task_done() called too many times')
            self.unfinished_tasks = remaining
            if remaining == 0:
                self.all_tasks_done.notify_all()

    def join(self):
        with self.all_tasks_done:
            while self.unfinished_tasks:
                self.all_tasks_done.wait()

class LifoQueue(Queue):
    def _get(self):
        return self._items.pop()

class PriorityQueue(Queue):
    def _put(self, item):
        heapq.heappush(self._items, item)

    def _get(self):
        return heapq.heappop(self._items)

class SimpleQueue:
    def __init__(self):
        self._queue = Queue()

    def put(self, item, block=True, timeout=None):
        with self._queue.not_empty:
            self._queue._put(item)
            self._queue.not_empty.notify()

    def get(self, block=True, timeout=None):
        return self._queue.get(block, timeout)

    def put_nowait(self, item):
        return self.put(item)

    def get_nowait(self):
        return self.get(False)

    def empty(self):
        return self._queue.empty()

    def qsize(self):
        return self._queue.qsize()
