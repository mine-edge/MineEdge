package dev.mineedge.client.movement;

import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.OffThreadMovement;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Bounded prediction history. An overflow requires a fresh authoritative baseline. */
public final class CommandHistory {
    public static final int CAPACITY = 128;
    private static final class Prediction {
        final MovementInput input;
        final MovementVector impulse;
        MovementState state;

        Prediction(MovementInput input, MovementVector impulse) {
            this.input = input;
            this.impulse = impulse;
        }
    }
    private final Prediction[] predictions = new Prediction[CAPACITY];
    private int head;
    private int size;
    private final Iterable<MovementInput> view = () -> new Iterator<>() {
        private int offset;
        @Override public boolean hasNext() { return offset < size; }
        @Override public MovementInput next() {
            if (!hasNext()) throw new NoSuchElementException();
            return prediction(offset++).input;
        }
    };

    public boolean add(MovementInput input) {
        return add(input, MovementVector.ZERO);
    }

    /** Impulses are pre-command velocity changes in blocks/second, not network inputs. */
    public boolean add(MovementInput input, MovementVector impulse) {
        if (size == CAPACITY) {
            return false;
        }
        if (size > 0 && input.sequence() <= prediction(size - 1).input.sequence()) {
            throw new IllegalArgumentException("Prediction sequences must increase");
        }
        predictions[(head + size++) % CAPACITY] = new Prediction(input, impulse);
        return true;
    }

    public void acknowledge(long sequence) {
        while (size > 0 && prediction(0).input.sequence() <= sequence) {
            predictions[head] = null;
            head = (head + 1) % CAPACITY;
            size--;
        }
    }

    /** Store the complete post-command state, also refreshed after authoritative replay. */
    public void recordPrediction(long sequence, MovementState state) {
        var prediction = find(sequence);
        if (prediction == null) {
            throw new IllegalArgumentException("Prediction is outside pending history");
        }
        prediction.state = state;
    }

    public boolean matches(long sequence, MovementState authoritative) {
        var prediction = find(sequence);
        return prediction != null && authoritative.equals(prediction.state);
    }

    /** Replay only unacknowledged impulses and commands, refreshing the matching fast path. */
    public MovementState replay(MovementState baseline, MovementController controller, MovementTuning tuning,
                                MovementController.MotionResolver resolver) {
        var state = baseline;
        for (int i = 0; i < size; i++) {
            var prediction = prediction(i);
            var impulse = prediction.impulse;
            if (!impulse.equals(MovementVector.ZERO)) state = state.withVelocity(state.velocity().add(impulse));
            state = controller.tick(state, prediction.input, tuning, resolver, event -> {});
            prediction.state = state;
        }
        return state;
    }

    /** Production prediction uses the shared simulation step and runs all replay calculations on a worker. */
    public MovementState replaySimulation(MovementState baseline, MovementController controller, MovementTuning tuning,
                                          MovementController.MotionResolver resolver) {
        if (size == 0) return baseline;
        // Freeze the command order before handing immutable inputs/impulses to the worker.
        // Prediction states are committed only after the entire replay succeeds.
        var pending = new Prediction[size];
        for (int i = 0; i < size; i++) pending[i] = prediction(i);
        MovementState[] states = OffThreadMovement.run(resolver, remote -> {
            var replayed = new MovementState[pending.length];
            var state = baseline;
            for (int i = 0; i < pending.length; i++) {
                var command = pending[i];
                if (!command.impulse.equals(MovementVector.ZERO)) {
                    state = state.withVelocity(state.velocity().add(command.impulse));
                }
                state = controller.step(state, command.input, tuning, remote, event -> {});
                replayed[i] = state;
            }
            return replayed;
        });
        for (int i = 0; i < pending.length; i++) pending[i].state = states[i];
        return states[states.length - 1];
    }

    /** Read-only live iteration on the client thread; reconciliation does not mutate it mid-replay. */
    public Iterable<MovementInput> remaining() {
        return view;
    }

    public int size() {
        return size;
    }

    public void clear() {
        Arrays.fill(predictions, null);
        head = size = 0;
    }

    private Prediction prediction(int offset) {
        return predictions[(head + offset) % CAPACITY];
    }

    private Prediction find(long sequence) {
        if (size == 0) return null;
        // Normal simulation sequences are contiguous: direct indexing without boxed keys.
        long offset = sequence - prediction(0).input.sequence();
        if (offset >= 0 && offset < size) {
            var candidate = prediction((int) offset);
            if (candidate.input.sequence() == sequence) return candidate;
        }
        // Also support monotonically increasing histories with gaps.
        int low = 0, high = size - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            var candidate = prediction(middle);
            if (candidate.input.sequence() < sequence) low = middle + 1;
            else if (candidate.input.sequence() > sequence) high = middle - 1;
            else return candidate;
        }
        return null;
    }
}
