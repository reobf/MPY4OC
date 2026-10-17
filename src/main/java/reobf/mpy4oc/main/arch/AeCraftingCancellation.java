package reobf.mpy4oc.main.arch;

import java.lang.reflect.Field;
import li.cil.oc.api.machine.Value;

/** Adds exact-job cancellation to OC 1.12.64's read-only CraftingStatus API. */
final class AeCraftingCancellation {
    private static final String STATUS = "li.cil.oc.integration.appeng.NetworkControl$CraftingStatus";

    private AeCraftingCancellation() {}

    static boolean supports(Value value) {
        if (value == null || !STATUS.equals(value.getClass().getName())) return false;
        // Prefer a future OC implementation if it exposes its own callback.
        try {
            value.getClass().getMethod("cancel", li.cil.oc.api.machine.Context.class, li.cil.oc.api.machine.Arguments.class);
            return false;
        } catch (NoSuchMethodException absent) {
            return true;
        }
    }

    /** Must run on the server thread, like every non-direct OC callback. */
    static Object[] cancel(Value value) throws ReflectiveOperationException {
        if (!supports(value)) throw new IllegalArgumentException("unsupported AE crafting status");
        Class<?> type = value.getClass();
        Field linkField = type.getDeclaredField("link");
        linkField.setAccessible(true);
        Object raw = linkField.get(value);
        if (!(raw instanceof scala.Option<?>)) throw new IllegalStateException("unsupported AE crafting link");
        scala.Option<?> option = (scala.Option<?>) raw;
        if (option.isEmpty()) {
            Object[] computing = (Object[]) type.getMethod("isComputing", li.cil.oc.api.machine.Context.class, li.cil.oc.api.machine.Arguments.class).invoke(value, null, null);
            return new Object[]{false, computing.length > 0 && Boolean.TRUE.equals(computing[0]) ? "computing" : "no link"};
        }
        // Resolve the optional AE API only here. Never search CPUs by name,
        // index or output: a CPU could already be working on a different job.
        Class<?> api = Class.forName("appeng.api.networking.crafting.ICraftingLink", false, type.getClassLoader());
        Object link = option.get();
        if (!api.isInstance(link)) throw new IllegalStateException("unsupported AE crafting link");
        if (Boolean.TRUE.equals(api.getMethod("isCanceled").invoke(link))) return new Object[]{true};
        if (Boolean.TRUE.equals(api.getMethod("isDone").invoke(link))) return new Object[]{false, "done"};
        api.getMethod("cancel").invoke(link);
        return Boolean.TRUE.equals(api.getMethod("isCanceled").invoke(link)) ? new Object[]{true} : new Object[]{false, "pending"};
    }
}
