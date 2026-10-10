package dev.mineedge.config;

import java.util.function.Consumer;

/** Validate the complete request before touching any live or persisted settings. */
public final class ServerSettingsUpdate {
    public enum Result { APPLIED, DENIED, INVALID }

    public static Result apply(boolean authorized, ServerMovementSettings settings,
                               Consumer<ServerMovementSettings> save) {
        if (!authorized) {
            return Result.DENIED;
        }
        if (!settings.isValid()) {
            return Result.INVALID;
        }
        save.accept(settings);
        return Result.APPLIED;
    }

    private ServerSettingsUpdate() {}
}
