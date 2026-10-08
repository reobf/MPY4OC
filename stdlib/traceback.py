# frozen traceback module (CPython-style API over the VM's traceback machinery).
# format_exc() reflects the most recently entered except block.

def format_exc():
    e = __tb_current()
    if e is None:
        return "NoneType: None\n"
    return __tb_format(e) + "\n"

def format_exception(e):
    return __tb_format(e) + "\n"

def print_exc():
    e = __tb_current()
    if e is None:
        print("NoneType: None")
    else:
        print(__tb_format(e))
