package net.mpy.vm;

import net.mpy.runtime.PyObj;
import net.mpy.runtime.PyExc;

/**
 * A coroutine scheduled on the VM's built-in cooperative event loop (uasyncio).
 * Wraps the coroutine generator plus the bookkeeping the scheduler needs: whether
 * it finished, its result/exception, and whether it went to the host via
 * yieldJava() (blacklisted for the remainder of the current stepAsync round).
 */
public final class AsyncTask {
    final PyGen coro;            // the coroutine driving this task
    boolean done;
    Object result = PyObj.NONE;  // set when the coroutine returns
    PyExc.Instance error;        // set if the coroutine raised
    boolean blacklistedThisRound; // went to host via yieldJava() this round
    Object lastYield = PyObj.NONE; // the value most recently yielded (e.g. a sleep request)
    boolean atYieldPoint;         // true if suspended at a yield (resume via resumeGen);
                                  // false if suspended by budget exhaustion (plain continue)
    /** {@link #parkDeadline} while the task is runnable (not parked). */
    static final long NOT_PARKED = Long.MIN_VALUE;
    // Park state (see Vm's park/unpark section): the host clock value (__time_ms) the
    // task may run again at -- NOT_PARKED while runnable, Long.MAX_VALUE until unparked;
    // a permit left by an unpark that arrived while the task was not parked (its next
    // park returns at once); and whether a queued host signal wakes it (pullSignal).
    long parkDeadline = NOT_PARKED;
    boolean permit;
    boolean wakeOnSignal;
    // When suspended by budget exhaustion mid-call-chain, the deepest active frame
    // (execution's real resume point). Its caller links reach back up to the coro's
    // top frame. Null when suspended at a clean yield point (resume via resumeGen).
    Frame suspendedFrame;

    AsyncTask(PyGen coro) { this.coro = coro; }
}
