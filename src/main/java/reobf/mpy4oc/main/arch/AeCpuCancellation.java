package reobf.mpy4oc.main.arch;

import java.lang.reflect.Field;
import li.cil.oc.api.machine.Value;

/** Guarded cancellation of a live OC CPU, including jobs submitted outside Python. */
final class AeCpuCancellation {
    private static final String CPU = "li.cil.oc.integration.appeng.NetworkControl$Cpu";

    private AeCpuCancellation() {}

    static boolean supports(Value value) {
        return value != null && CPU.equals(value.getClass().getName());
    }

    private static Object field(Value value, String name) throws ReflectiveOperationException {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    private static Class<?> api(Value value, String name) throws ClassNotFoundException {
        return Class.forName(name, false, value.getClass().getClassLoader());
    }

    /** Resolve only a live handle, never OC's saved enumeration-index fallback. */
    private static Object cluster(Value value) throws ReflectiveOperationException {
        if (!supports(value)) throw new IllegalArgumentException("unsupported AE CPU");
        Object cpu = field(value, "cpu");
        if (cpu == null) throw new IllegalStateException("Refresh the CPU list after restoring the service");
        Class<?> type = api(value, "appeng.me.cluster.implementations.CraftingCPUCluster");
        if (Boolean.TRUE.equals(type.getMethod("isDestroyed").invoke(cpu))
            || !Boolean.TRUE.equals(type.getMethod("isActive").invoke(cpu)))
            throw new IllegalStateException("CPU is no longer active");
        Object controller = field(value, "controller");
        if (controller == null) throw new IllegalStateException("CPU controller unavailable");
        Object proxy = api(value, "appeng.me.helpers.IGridProxyable").getMethod("getProxy").invoke(controller);
        Object grid = api(value, "appeng.me.helpers.AENetworkProxy").getMethod("getCrafting").invoke(proxy);
        Object cpus = api(value, "appeng.api.networking.crafting.ICraftingGrid").getMethod("getCpus").invoke(grid);
        if (!(cpus instanceof java.util.Collection<?>) || !((java.util.Collection<?>) cpus).contains(cpu))
            throw new IllegalStateException("CPU is no longer on this network");
        return cpu;
    }

    /** Both operations run entirely in the synchronized server phase. */
    static Object[] invoke(Value value, String method, Object[] args) throws ReflectiveOperationException {
        boolean cancel = "cancelJob".equals(method);
        if (!(cancel || "getJobId".equals(method))) throw new IllegalArgumentException("Unknown CPU operation");
        if (args.length != (cancel ? 1 : 0) || (cancel && (!(args[0] instanceof String) || ((String) args[0]).isEmpty())))
            throw new IllegalArgumentException(cancel ? "cancelJob() requires a job ID" : "getJobId() takes no arguments");
        Object cpu = cluster(value);
        Class<?> type = api(value, "appeng.me.cluster.implementations.CraftingCPUCluster");
        Class<?> links = api(value, "appeng.api.networking.crafting.ICraftingLink");
        Object link = type.getMethod("getLastCraftingLink").invoke(cpu);
        boolean busy = Boolean.TRUE.equals(type.getMethod("isBusy").invoke(cpu));
        if (link == null || !busy || Boolean.TRUE.equals(links.getMethod("isDone").invoke(link))
            || Boolean.TRUE.equals(links.getMethod("isCanceled").invoke(link)))
            return cancel ? new Object[]{false, "Task has finished or changed. Refresh the CPU details."} : new Object[]{null};
        Object id = links.getMethod("getCraftingID").invoke(link);
        if (!(id instanceof String) || ((String) id).isEmpty()) throw new IllegalStateException("CPU task ID unavailable");
        if (!cancel) return new Object[]{id};
        if (!id.equals(args[0])) return new Object[]{false, "Task has changed. Refresh the CPU details before canceling."};
        // No VM yield, scheduling or network lookup between the identity check and cancellation.
        // Use the CPU's normal cancellation path, which also clears tasks and returns ingredients.
        type.getMethod("cancel").invoke(cpu);
        return new Object[]{true};
    }
}
