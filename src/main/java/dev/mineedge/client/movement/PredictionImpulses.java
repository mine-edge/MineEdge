package dev.mineedge.client.movement;

import dev.mineedge.movement.MovementVector;

/** Tracks external velocity changes independently from impulses awaiting their next command. */
final class PredictionImpulses {
    private MovementVector writtenVelocity = MovementVector.ZERO;
    private MovementVector pending = MovementVector.ZERO;

    /** Entity velocity is blocks/native tick; prediction impulses are blocks/second. */
    MovementVector capture(MovementVector entityVelocity) {
        if (entityVelocity.equals(writtenVelocity)) return MovementVector.ZERO;
        var impulse = unimported(entityVelocity).scale(20);
        pending = pending.add(impulse);
        writtenVelocity = entityVelocity;
        return impulse;
    }

    MovementVector pending() { return pending; }

    MovementVector consume() {
        var result = pending;
        pending = MovementVector.ZERO;
        return result;
    }

    MovementVector unimported(MovementVector entityVelocity) {
        // capture() already advances the velocity baseline. Subtracting pending here
        // would turn an imported push into a negative impulse after reconciliation.
        return entityVelocity.subtract(writtenVelocity);
    }

    MovementVector written(MovementVector simulationVelocity) {
        writtenVelocity = simulationVelocity.scale(1.0 / 20);
        return writtenVelocity;
    }

    void clear() {
        writtenVelocity = pending = MovementVector.ZERO;
    }
}
