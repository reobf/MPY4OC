package net.mpy.vm;

/**
 * The three built-in method decorators. @staticmethod / @classmethod / @property
 * are plain callables applied at class-body time (MAKE_FUNCTION then
 * CALL_FUNCTION), producing these wrapper objects in the class namespace; the
 * magic is purely in how attribute lookup unwraps them:
 *   - StaticMethod: returns the raw function (no self, no cls)
 *   - ClassMethod: binds the class (or the instance's class) as the first arg
 *   - Property: on instance access, calls the getter immediately
 */
public final class Descriptors {
    private Descriptors() {}

    public static final class StaticMethod {
        public final Object fn;
        public StaticMethod(Object fn) { this.fn = fn; }
    }

    public static final class ClassMethod {
        public final Object fn;
        public ClassMethod(Object fn) { this.fn = fn; }
    }

    public static final class Property {
        public final Object getter;
        public Property(Object getter) { this.getter = getter; }
    }
}
