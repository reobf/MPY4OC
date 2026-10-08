# frozen gc over the host runtime (best-effort; the VM's own memory limit is
# separate and enforced regardless).
collect = __gc_collect
mem_alloc = __gc_mem_alloc
mem_free = __gc_mem_free

def enable():
    pass

def disable():
    pass

def isenabled():
    return True
