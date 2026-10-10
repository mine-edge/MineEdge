package dev.mineedge.integration;

import dev.mineedge.MineEdge;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Predicate;
import net.minecraft.world.entity.player.Player;

/** Optional common-side gun permission, resolved once without a TaCZ compile/runtime dependency. */
final class TaczSprintCompatibility {
    private static final Predicate<Player> PERMISSION = load();

    static boolean allowed(Player player) {
        return PERMISSION.test(player);
    }

    private static Predicate<Player> load() {
        try {
            var operator = Class.forName("com.tacz.guns.api.entity.IGunOperator", false, TaczSprintCompatibility.class.getClassLoader());
            return bind(operator);
        } catch (ClassNotFoundException absent) {
            return player -> true;
        } catch (ReflectiveOperationException incompatible) {
            MineEdge.LOGGER.debug("TaCZ sprint permission API unavailable; retaining native sprint decisions", incompatible);
            return player -> true;
        }
    }

    static Predicate<Player> bind(Class<?> operator) throws ReflectiveOperationException {
        var permission = MethodHandles.publicLookup().findVirtual(operator, "getProcessedSprintStatus",
                MethodType.methodType(boolean.class, boolean.class))
                .asType(MethodType.methodType(boolean.class, Player.class, boolean.class));
        return player -> {
            if (!operator.isInstance(player)) return true;
            try {
                // Query permission to sprint, not the possibly pose-cleared entity flag.
                return (boolean) permission.invokeExact(player, true);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Could not query TaCZ sprint permission", failure);
            }
        };
    }

    private TaczSprintCompatibility() {}
}
