package net.mpy.vm;

/** Yielded by a thread's generator in VMs saved before park/unpark (__thread_sleep(ms)):
 *  the scheduler parks the task for {@code ms} milliseconds of the host clock. New code
 *  calls __park() instead; the class stays so such snapshots still load. */
public final class SleepRequest {
    final long ms;
    SleepRequest(long ms) { this.ms = ms; }
    @Override public String toString() { return "<sleep " + ms + "ms>"; }
}
