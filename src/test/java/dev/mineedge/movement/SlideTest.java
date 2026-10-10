package dev.mineedge.movement;

import dev.mineedge.movement.collision.BoxWorld;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionMover;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlideTest {
    private final MovementController controller = new MovementController();
    private final List<MovementEvent> events = new ArrayList<>();

    private static final CollisionBox FLOOR = new CollisionBox(new MovementVector(-100, -1, -100), new MovementVector(100, 0, 100));

    private static final class World implements MovementController.MotionResolver {
        BoxWorld world = new BoxWorld(List.of(FLOOR));
        MovementBody body = MovementBody.STANDING;

        @Override
        public void body(MovementBody body) { this.body = body; }

        @Override
        public boolean canOccupy(MovementVector position, MovementBody body) {
            return !world.overlaps(CollisionBox.body(position, 0.6, body.height()));
        }

        @Override
        public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds) {
            var result = CollisionMover.move(world, CollisionBox.body(position, 0.6, body.height()), velocity.scale(seconds));
            return new MovementController.Motion(position.add(result.recovery()).add(result.displacement()), result.clip(velocity), result.grounded());
        }
    }

    private MovementState tick(MovementState state, MovementInput input, World world) {
        return controller.tick(state, input, MovementTuning.DEFAULT, world, events::add);
    }

    private static MovementInput input(int tick, boolean held, boolean pressed, boolean jump, float yaw, float forward, float strafe) {
        return new MovementInput(tick, forward, strafe, strafe, jump, jump, held, pressed, true, yaw, 0);
    }

    private static MovementState moving() {
        return new MovementState(MovementVector.ZERO, new MovementVector(0, 0, 6), MovementState.Mode.GROUND, 1, true, 0);
    }

    @Test
    void slideRequiresFreshGroundedPressAndForwardSpeedRatherThanTotalSpeed() {
        World world = new World();
        var sideways = tick(moving(), input(1, true, true, false, 90, 1, 0), world);
        assertEquals(MovementState.Mode.GROUND, sideways.mode());
        assertEquals(MovementBody.CROUCHING, sideways.body());
        assertFalse(events.contains(MovementEvent.SLIDE_START));
        var held = tick(moving(), input(1, true, false, false, 0, 1, 0), world);
        assertNotEquals(MovementState.Mode.SLIDE, held.mode());
        var slow = tick(moving().withVelocity(new MovementVector(0, 0, 3.4)), input(1, true, true, false, 0, 1, 0), world);
        assertNotEquals(MovementState.Mode.SLIDE, slow.mode());
        var falling = new MovementState(new MovementVector(0, 2, 0), new MovementVector(0, -1, 6), MovementState.Mode.AIR, 1, false, 0);
        assertNotEquals(MovementState.Mode.SLIDE, tick(falling, input(1, true, true, false, 0, 1, 0), world).mode());
        assertEquals(MovementState.Mode.SLIDE, tick(moving(), input(1, true, true, false, 0, 1, 0), world).mode());
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.SLIDE_START).count());
    }

    @Test
    void disabledSlidingRejectsEntryAndLandingSlidesAndExitsAnActiveSlide() {
        var tuning = MovementTuning.DEFAULT.withFeatures(new MovementFeatures(false, true, true, true, true));
        var world = new World();
        var crouch = input(1, true, true, false, 0, 1, 0);
        var blocked = controller.tick(moving(), crouch, tuning, world, events::add);
        assertEquals(MovementState.Mode.GROUND, blocked.mode());
        assertEquals(MovementBody.CROUCHING, blocked.body());
        assertFalse(events.contains(MovementEvent.SLIDE_START));

        var sliding = tick(moving(), crouch, world);
        assertEquals(MovementState.Mode.SLIDE, sliding.mode());
        events.clear();
        var released = controller.tick(sliding, input(2, false, false, false, 0, 0, 0), tuning, world, events::add);
        assertEquals(MovementState.Mode.GROUND, released.mode());
        assertEquals(MovementBody.STANDING, released.body());
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.SLIDE_END).count());
        assertFalse(world.world.overlaps(CollisionBox.body(released.position(), 0.6, released.body().height())));

        events.clear();
        var jumping = controller.tick(moving(), input(1, false, false, true, 0, 1, 0), tuning, world, events::add);
        for (int n = 2; n <= 25; n++) {
            jumping = controller.tick(jumping, input(n, true, false, false, 0, 1, 0), tuning, world, events::add);
            assertNotEquals(MovementState.Mode.SLIDE, jumping.mode());
        }
        assertTrue(jumping.grounded());
        assertFalse(events.contains(MovementEvent.SLIDE_START));
    }

    @Test
    void tapSlidesForHalfASecondAndHalvesMomentumExactlyOnceOnRelease() {
        // faith_move/tests.rs::slide_lasts_half_a_second_and_halves_speed_on_exit.
        World world = new World();
        MovementState state = moving();
        for (int tick = 1; tick <= 9; tick++) {
            state = tick(state, input(tick, false, tick == 1, false, 0, 0, 0), world);
            assertEquals(MovementState.Mode.SLIDE, state.mode());
            assertEquals(MovementBody.LOW, state.body());
        }
        assertEquals(0.45, state.modeTime(), 1.0E-12);
        state = tick(state, input(10, false, false, false, 0, 0, 0), world);
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertEquals(MovementBody.STANDING, state.body());
        assertEquals(6 * Math.pow(1 - 0.8 / MovementRate.SUBSTEP_HZ, MovementRate.SUBSTEP_HZ / 2) * 0.5,
                state.velocity().horizontalSpeed(), 1.0E-10);
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.SLIDE_END).count());
    }

    @Test
    void slideEntryRequiresSprintEvenWithEnoughForwardMomentum() {
        World world = new World();
        var crouch = input(1, true, true, false, 0, 1, 0);
        for (double speed : new double[]{4, 6}) {
            events.clear();
            var start = moving().withVelocity(new MovementVector(0, 0, speed));
            var walking = tick(start, crouch.withSprint(false), world);
            assertEquals(MovementState.Mode.GROUND, walking.mode());
            assertEquals(MovementBody.CROUCHING, walking.body());
            assertFalse(events.contains(MovementEvent.SLIDE_START));
            var sprinting = tick(start, crouch, world);
            assertEquals(MovementState.Mode.SLIDE, sprinting.mode());
            assertTrue(events.contains(MovementEvent.SLIDE_START));
            var continuing = tick(sprinting, input(2, true, false, false, 0, 1, 0).withSprint(false), world);
            assertEquals(MovementState.Mode.SLIDE, continuing.mode());
        }
    }

    @Test
    void sprintJumpCanLandInASlideFromEarlyOrLateAirborneCrouchAndReplayExactly() {
        for (boolean early : new boolean[]{false, true}) {
            events.clear(); World world = new World();
            var state = MovementControllerTest.grounded();
            for (int n = 1; n <= 20; n++) state = tick(state, input(n, false, false, false, 0, 1, 0), world);
            state = tick(state, input(21, false, false, true, 0, 1, 0), world);
            assertFalse(state.grounded());
            var replay = state; boolean crouch = false;
            for (int n = 22; n <= 50 && !state.grounded(); n++) {
                boolean pressed = !crouch && (early || state.velocity().y() <= 0);
                crouch |= pressed;
                var command = input(n, crouch, pressed, false, 0, 1, 0);
                state = tick(state, command, world);
                replay = controller.tick(replay, command, MovementTuning.DEFAULT, world, event -> {});
                assertEquals(state, replay);
                if (!state.grounded()) assertFalse(events.contains(MovementEvent.SLIDE_START));
            }
            assertEquals(MovementState.Mode.SLIDE, state.mode()); assertEquals(MovementBody.LOW, state.body());
            assertTrue(state.modeTime() < 0.05, "Slide timer included time spent airborne");
            assertFalse(state.advanced().coiled()); assertTrue(events.contains(MovementEvent.COIL_END));
            assertEquals(1, events.stream().filter(e -> e == MovementEvent.SLIDE_START).count());
            assertEquals(1, events.stream().filter(e -> e == MovementEvent.JUMP).count());
            state = tick(state, input(51, false, false, true, 0, 1, 0), world);
            assertEquals(MovementState.Mode.SLIDE, state.mode(), "Landing slide did not get its minimum duration");
            assertEquals(0, state.locomotion().jumpBuffer());
        }
    }

    @Test
    void jumpLandingSlideRequiresSprintHeldCrouchAndRunUpMomentum() {
        for (int scenario = 0; scenario < 3; scenario++) {
            events.clear(); World world = new World();
            var state = MovementControllerTest.grounded();
            int sequence = 1;
            if (scenario != 2) for (int n = 0; n < 20; n++)
                state = tick(state, input(sequence++, false, false, false, 0, 1, 0), world);
            state = tick(state, input(sequence++, false, false, true, 0, 1, 0), world);
            for (int n = 0; n < 30 && !state.grounded(); n++) {
                boolean crouch = scenario != 1 || n < 3;
                var command = input(sequence++, crouch, n == 0, false, 0, 1, 0).withSprint(scenario != 0);
                state = tick(state, command, world);
            }
            assertTrue(state.grounded()); assertNotEquals(MovementState.Mode.SLIDE, state.mode());
            assertFalse(events.contains(MovementEvent.SLIDE_START), "Unexpected landing slide for scenario=" + scenario);
        }
    }

    @Test
    void jumpCannotLaunchOrBufferThroughSlideExitAndSlideWinsSimultaneousPresses() {
        World world = new World();
        MovementState state = moving();
        for (int tick = 1; tick <= 12; tick++) {
            state = tick(state, input(tick, false, tick == 1, tick == 1 || tick == 8 || tick == 10, 0, 0, 0), world);
            assertEquals(0, state.position().y(), 1.0E-12);
            assertEquals(0, state.locomotion().jumpBuffer());
        }
        assertFalse(events.contains(MovementEvent.JUMP));
    }

    @Test
    void slideSteersButDoesNotAccelerateAndAbortUsesReferenceHintPriority() {
        World world = new World();
        MovementState state = tick(moving(), input(1, true, true, false, 0, 1, 0), world);
        double speed = state.velocity().horizontalSpeed();
        for (int tick = 2; tick <= 12; tick++) {
            state = tick(state, input(tick, true, false, false, 90, 1, 1), world);
        }
        assertEquals(MovementState.Mode.SLIDE, state.mode());
        assertTrue(state.velocity().x() < -0.5);
        assertTrue(state.position().x() < -0.2);
        assertTrue(state.velocity().horizontalSpeed() < speed);
        var sidewaysBack = tick(state, input(13, true, false, false, 90, -1, 0.5F), world);
        assertEquals(MovementState.Mode.SLIDE, sidewaysBack.mode());
        state = tick(state, input(13, true, false, false, 90, -1, 0), world);
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertEquals(MovementBody.CROUCHING, state.body());
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.SLIDE_END).count());
    }

    @Test
    void speedAbortOccursAtReferenceExponentialBrakingTime() {
        World world = new World();
        MovementState state = moving();
        int ticks = 0;
        do {
            ticks++;
            state = tick(state, input(ticks, true, ticks == 1, false, 0, 0, 0), world);
        } while (state.mode() == MovementState.Mode.SLIDE && ticks < 100);
        assertEquals(Math.log(6 / 2.5) / 0.8, ticks * 0.05, 0.06);
        assertEquals(MovementBody.CROUCHING, state.body());
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.SLIDE_END).count());
    }

    @Test
    void lowCeilingPreventsSlideExitAndJumpFromGrowingBodyUntilClear() {
        World world = new World();
        var ceiling = new CollisionBox(new MovementVector(-100, 1, -100), new MovementVector(100, 2, 100));
        world.world = new BoxWorld(List.of(FLOOR, ceiling));
        var state = new MovementState(MovementVector.ZERO, new MovementVector(0, 0, 4), MovementState.Mode.SLIDE,
                0.45, true, 0, LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.LOW, 0);
        state = tick(state, input(1, false, false, true, 0, 1, 0), world);
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertEquals(MovementBody.LOW, state.body());
        for (int tick = 2; tick <= 8; tick++) {
            state = tick(state, input(tick, false, false, tick == 2, 0, 1, 0), world);
            assertEquals(MovementBody.LOW, state.body());
            assertEquals(0, state.position().y());
            assertFalse(world.world.overlaps(CollisionBox.body(state.position(), 0.6, state.body().height())));
        }
        assertFalse(events.contains(MovementEvent.JUMP));
        world.world = new BoxWorld(List.of(FLOOR));
        state = tick(state, input(9, false, false, false, 0, 1, 0), world);
        assertEquals(MovementBody.STANDING, state.body());
    }

    @Test
    void slideLosingSupportReturnsToAirAndHalvesMomentumOnce() {
        World world = new World();
        world.world = new BoxWorld(List.of(new CollisionBox(new MovementVector(-10, -1, -10), new MovementVector(10, 0, 0.2))));
        MovementState state = moving();
        for (int tick = 1; tick <= 5; tick++) {
            state = tick(state, input(tick, true, tick == 1, false, 0, 0, 0), world);
        }
        assertEquals(MovementState.Mode.AIR, state.mode());
        assertTrue(state.position().y() < 0);
        assertTrue(state.velocity().horizontalSpeed() > 2.7 && state.velocity().horizontalSpeed() < 3.1);
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.SLIDE_END).count());
    }

    @Test
    void crouchSpeedIsCappedAndReleasingAutomaticallyUsesClearance() {
        World world = new World();
        MovementState state = MovementControllerTest.grounded();
        for (int tick = 1; tick <= 40; tick++) {
            state = tick(state, input(tick, true, tick == 1, false, 0, 1, 0), world);
        }
        assertEquals(MovementBody.CROUCHING, state.body());
        assertEquals(MovementTuning.DEFAULT.sprintSpeed() * 0.2, state.velocity().horizontalSpeed(), 1.0E-12);
        state = tick(state, input(41, false, false, false, 0, 1, 0), world);
        assertEquals(MovementBody.STANDING, state.body());
    }

    @Test
    void acknowledgedSlideBodyYawAndTimerReplayExactlyThroughLowGap() {
        World world = new World();
        var ceiling = new CollisionBox(new MovementVector(-10, 1, 1), new MovementVector(10, 2, 4));
        world.world = new BoxWorld(List.of(FLOOR, ceiling));
        var commands = new ArrayList<MovementInput>();
        MovementState state = moving();
        MovementState baseline = null;
        for (int tick = 1; tick <= 45; tick++) {
            var command = input(tick, tick <= 4, tick == 1, tick == 6, tick <= 10 ? 5 : 0, 1, 0);
            commands.add(command);
            state = tick(state, command, world);
            if (tick == 4) {
                baseline = state;
                assertEquals(MovementState.Mode.SLIDE, state.mode());
                assertEquals(MovementBody.LOW, state.body());
            }
        }
        MovementState replay = baseline;
        for (int i = 4; i < commands.size(); i++) {
            replay = controller.tick(replay, commands.get(i), MovementTuning.DEFAULT, newResolver(world.world), event -> {});
        }
        assertEquals(state, replay);
        assertEquals(MovementBody.STANDING, state.body());
        assertTrue(state.position().z() > 4.3);
    }

    private static World newResolver(BoxWorld shapes) {
        World resolver = new World();
        resolver.world = shapes;
        return resolver;
    }
}
