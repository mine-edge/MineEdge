package dev.mineedge.movement;

import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionWorld;
import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.movement.surface.ClimbRoute;
import dev.mineedge.movement.surface.SurfaceContact;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The controller runs on a worker. The calling game thread services only resolver operations,
 * preserving native collision/block callbacks and committing a complete step atomically.
 * No ordinary game tick can observe a partially completed movement step.
 */
public final class OffThreadMovement {
    private static final ExecutorService WORKERS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("MineEdge physics-", 0).factory());
    private static final Runnable DONE = () -> {};

    public record Result(MovementState state, List<MovementEvent> events) {}

    public static Result step(MovementController controller, MovementState state, MovementInput input,
                              MovementTuning tuning, MovementController.MotionResolver resolver) {
        return run(resolver, remote -> {
            var events = new ArrayList<MovementEvent>();
            var next = controller.step(state, input, tuning, remote, events::add);
            return new Result(next, List.copyOf(events));
        });
    }

    public static <T> T run(MovementController.MotionResolver resolver,
                            Function<MovementController.MotionResolver, T> computation) {
        var requests = new LinkedBlockingQueue<Runnable>();
        var closed = new AtomicBoolean();
        var remote = new GameThreadResolver(resolver, requests, closed);
        var result = CompletableFuture.supplyAsync(() -> computation.apply(remote), WORKERS);
        result.whenComplete((value, error) -> requests.add(DONE));
        try {
            Runnable request;
            while ((request = requests.take()) != DONE) request.run();
            return result.join();
        } catch (InterruptedException exception) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Movement bridge interrupted", exception);
        } finally {
            closed.set(true);
            for (var request : requests) {
                if (request instanceof FutureTask<?> task) task.cancel(true);
            }
        }
    }

    private static final class GameThreadResolver implements MovementController.MotionResolver {
        private final MovementController.MotionResolver delegate;
        private final LinkedBlockingQueue<Runnable> requests;
        private final AtomicBoolean closed;

        GameThreadResolver(MovementController.MotionResolver delegate, LinkedBlockingQueue<Runnable> requests, AtomicBoolean closed) {
            this.delegate = delegate;
            this.requests = requests;
            this.closed = closed;
        }

        private <T> T call(Callable<T> operation) {
            if (closed.get()) throw new CancellationException("Movement bridge closed");
            var task = new FutureTask<>(operation);
            requests.add(task);
            if (closed.get()) task.cancel(true);
            try {
                return task.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Physics worker interrupted", exception);
            } catch (ExecutionException exception) {
                throw new CompletionException(exception.getCause());
            }
        }

        private void invoke(Runnable operation) {
            call(() -> {
                operation.run();
                return null;
            });
        }

        @Override
        public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds) {
            return call(() -> delegate.resolve(position, velocity, seconds));
        }

        @Override
        public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds, double stepHeight) {
            return call(() -> delegate.resolve(position, velocity, seconds, stepHeight));
        }

        @Override
        public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds,
                                                 double stepHeight, boolean grounded) {
            return call(() -> delegate.resolve(position, velocity, seconds, stepHeight, grounded));
        }

        @Override
        public double maxStepHeight() {
            return call(delegate::maxStepHeight);
        }

        @Override
        public double jumpFactor(MovementVector position, MovementBody body) {
            return call(() -> delegate.jumpFactor(position, body));
        }

        @Override
        public double groundTraction(MovementVector position, MovementBody body) {
            return call(() -> delegate.groundTraction(position, body));
        }

        @Override
        public void begin(MovementState state) {
            invoke(() -> delegate.begin(state));
        }

        @Override
        public void input(MovementInput input) {
            invoke(() -> delegate.input(input));
        }

        @Override
        public void body(MovementBody body) {
            invoke(() -> delegate.body(body));
        }

        @Override
        public void facing(float yaw) {
            invoke(() -> delegate.facing(yaw));
        }

        @Override
        public void relocate(MovementVector position) {
            invoke(() -> delegate.relocate(position));
        }

        @Override
        public boolean canOccupy(MovementVector position, MovementBody body) {
            return call(() -> delegate.canOccupy(position, body));
        }

        @Override
        public CollisionBox bounds(MovementVector position, MovementBody body) {
            return call(() -> delegate.bounds(position, body));
        }

        @Override
        public CollisionWorld geometry(CollisionBox region) {
            return call(() -> delegate.geometry(region));
        }

        @Override
        public MovementVector position(MovementVector simulated) {
            return call(() -> delegate.position(simulated));
        }

        @Override
        public void synchronize(MovementVector velocity, boolean grounded, MovementBody body) {
            invoke(() -> delegate.synchronize(velocity, grounded, body));
        }

        @Override
        public void landing(LandingState state, MovementTuning tuning) {
            invoke(() -> delegate.landing(state, tuning));
        }

        @Override
        public SurfaceContact surface(MovementVector feet, MovementBody body) {
            return call(() -> delegate.surface(feet, body));
        }

        @Override
        public boolean stepSurface(CollisionBox shape) {
            return call(() -> delegate.stepSurface(shape));
        }

        @Override
        public boolean stepSource(MovementVector source) {
            return call(() -> delegate.stepSource(source));
        }

        @Override
        public ClimbRoute climbRoute(MovementVector feet, MovementVector preferredNormal) {
            return call(() -> delegate.climbRoute(feet, preferredNormal));
        }

        @Override
        public boolean climbValid(ClimbRoute route) {
            return call(() -> delegate.climbValid(route));
        }

        @Override
        public ClimbRoute climbSideways(ClimbRoute route, double height, double distance) {
            return call(() -> delegate.climbSideways(route, height, distance));
        }

        @Override
        public void clearFallDistance() {
            invoke(delegate::clearFallDistance);
        }

        @Override
        public void pushDoors(MovementVector position, MovementVector velocity, MovementBody body, double seconds) {
            invoke(() -> delegate.pushDoors(position, velocity, body, seconds));
        }
    }

    private OffThreadMovement() {}
}
