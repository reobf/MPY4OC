# frozen heapq module: the standard binary-heap algorithm in pure Python.

def heappush(heap, item):
    heap.append(item)
    _siftdown(heap, 0, len(heap) - 1)

def heappop(heap):
    last = heap.pop()
    if len(heap) > 0:
        top = heap[0]
        heap[0] = last
        _siftup(heap, 0)
        return top
    return last

def heapify(xs):
    i = len(xs) // 2 - 1
    while i >= 0:
        _siftup(xs, i)
        i -= 1

def heapreplace(heap, item):
    top = heap[0]
    heap[0] = item
    _siftup(heap, 0)
    return top

def heappushpop(heap, item):
    if len(heap) > 0 and heap[0] < item:
        item, heap[0] = heap[0], item
        _siftup(heap, 0)
    return item

def _siftdown(heap, start, pos):
    item = heap[pos]
    while pos > start:
        parent = (pos - 1) >> 1
        if item < heap[parent]:
            heap[pos] = heap[parent]
            pos = parent
        else:
            break
    heap[pos] = item

def _siftup(heap, pos):
    end = len(heap)
    start = pos
    item = heap[pos]
    child = 2 * pos + 1
    while child < end:
        right = child + 1
        if right < end and heap[right] < heap[child]:
            child = right
        heap[pos] = heap[child]
        pos = child
        child = 2 * pos + 1
    heap[pos] = item
    _siftdown(heap, start, pos)
