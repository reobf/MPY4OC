"""jasyncio: the old name of this VM's asyncio. It is asyncio itself (the same functions,
classes and loops), kept so programs that import it keep working; use asyncio."""

from asyncio import *
from asyncio import _get_running_loop
