package net.mpy.vm;

/** Yielded by jasyncio.sleep_ms(ms): asks the scheduler to not resume this task
 *  for at least {@code ms} real milliseconds (measured only while the VM is live
 *  and being driven; time at rest across a snapshot does not count). */
public final class SleepRequest {
    final long ms;
    SleepRequest(long ms) { this.ms = ms; }
    @Override public String toString() { return "<sleep " + ms + "ms>"; }
}
