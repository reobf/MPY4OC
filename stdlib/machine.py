# machine: the minimal subset that is honest on this VM.
#
# disable_irq()/enable_irq(state): in MicroPython these make a critical section
# by masking interrupts. Here "interrupts" ARE the scheduler, and the scheduler
# is the VM's own -- so the semantics can be honoured exactly: while IRQs are
# disabled, no other thread/task runs and the holder proceeds alone. The state
# token nests, as on real ports. (Not preserved across snapshots: a save taken
# mid-critical-section restores with IRQs enabled.)
#
# Anything that cannot be given real semantics here (pins, timers, deepsleep,
# reset) is deliberately absent rather than faked.


def disable_irq():
    return __machine_disable_irq()


def enable_irq(state):
    __machine_enable_irq(state)
