# frozen binascii over Java primitives (hex + base64).
hexlify = __ba_hexlify
unhexlify = __ba_unhexlify

def a2b_base64(data):
    return __ba_b64decode(data)

def b2a_base64(data):
    return __ba_b64encode(data) + b"\n"
