package net.mpy.vm;

/** A user-class method bound to an instance (LOAD_ATTR of a function attribute):
 *  calling it prepends the receiver. */
public final class BoundPyMethod {
    public final Object fn;    // PyFunction or Closure
    public final Object self;  // the receiver
    public BoundPyMethod(Object fn, Object self) { this.fn = fn; this.self = self; }
    @Override public String toString() { return "<bound method>"; }
}
