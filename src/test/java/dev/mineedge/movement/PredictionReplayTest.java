package dev.mineedge.movement;

import dev.mineedge.client.movement.CommandHistory;
import dev.mineedge.movement.collision.BoxWorld;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionMover;
import java.util.List;
import java.util.ArrayDeque;
import dev.mineedge.network.SnapshotSchedule;
import dev.mineedge.network.CommandInbox;
import dev.mineedge.network.FullInputPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Pose;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionReplayTest {
    @Test
    void localClockSkewAndStallsDoNotProducePredictionCorrections() {
        for (int burst : new int[]{1, 2, 5, 20}) {
            var controller = new MovementController();
            var history = new CommandHistory();
            var inbox = new CommandInbox(0, 0, true);
            var server = MovementControllerTest.grounded();
            var predicted = server;
            long serverStep = 0;
            for (int tick = 1; tick <= 100; tick++) {
                // The server's clock can fire before input arrives or while the client stalls.
                if (inbox.size() == 0) {
                    assertNull(inbox.consumeSimulation(serverStep, 5));
                    assertEquals(tick - 1, inbox.acknowledged());
                }
                var input = new MovementInput(tick, tick % 10 < 5 ? 1 : -1, 0, 0,
                        tick % 12 == 0, tick % 12 == 0, false, false, tick % 8 < 4, tick * 3, 0);
                assertTrue(history.add(input));
                predicted = controller.step(predicted, input, MovementTuning.DEFAULT, MovementControllerTest::floor, e -> {});
                history.recordPrediction(tick, predicted);
                assertTrue(inbox.offer(new FullInputPayload(1, input), serverStep));
                if (tick % burst != 0) continue;
                // A server stall delivers multiple commands at once: retain every physics step.
                MovementInput command;
                while ((command = inbox.consumeSimulation(serverStep, 5)) != null) {
                    serverStep++;
                    server = controller.step(server, command, MovementTuning.DEFAULT, MovementControllerTest::floor, e -> {});
                    assertTrue(history.matches(inbox.acknowledged(), server),
                            "Local authoritative state diverged at command " + command.sequence());
                    history.acknowledge(inbox.acknowledged());
                }
                assertEquals(predicted, server);
                assertEquals(0, history.size());
            }
            assertEquals(100, serverStep, "Each local command must advance physics exactly once");
        }
    }

    @Test
    void matchingDelayedSnapshotsSkipReplayButServerImpulseStillCorrectsAndRefreshesHistory() {
        record Baseline(int sequence, MovementState state) {}
        for (int interval : new int[]{1, 2, 5}) {
            var controller = new MovementController();
            MovementState server = MovementControllerTest.grounded(), predicted = server;
            var history = new CommandHistory();
            var inFlight = new ArrayDeque<Baseline>();
            int skipped = 0, corrections = 0;
            for (int tick = 1; tick <= 90; tick++) {
                var command = MovementControllerTest.forward(tick);
                assertTrue(history.add(command));
                if (tick == 40) server = server.withVelocity(server.velocity().add(new MovementVector(3, 0, 0)));
                server = controller.tick(server, command, MovementTuning.DEFAULT, MovementControllerTest::floor, e -> {});
                predicted = controller.tick(predicted, command, MovementTuning.DEFAULT, MovementControllerTest::floor, e -> {});
                history.recordPrediction(tick, predicted);
                if (tick % interval == 0) inFlight.add(new Baseline(tick, server));
                if (!inFlight.isEmpty() && inFlight.peek().sequence() <= tick - 2) {
                    var baseline = inFlight.remove();
                    boolean matches = history.matches(baseline.sequence(), baseline.state());
                    history.acknowledge(baseline.sequence());
                    assertFalse(history.matches(baseline.sequence(), baseline.state()));
                    if (matches) skipped++;
                    else {
                        corrections++;
                        predicted = history.replay(baseline.state(), controller, MovementTuning.DEFAULT, MovementControllerTest::floor);
                    }
                }
                if (tick < 40 || tick >= 40 + interval + 2) assertEquals(server, predicted);
            }
            assertTrue(skipped > 10, "Matching predictions should avoid collision replay");
            assertEquals(1, corrections, "A server impulse must correct once, then refreshed predictions should match again");
            history.clear();
            assertFalse(history.matches(90, predicted));
        }
    }

    @Test
    void delayedCorrectionsPreservePendingImpulsesWithoutReapplyingAcknowledgedOnes() {
        var controller = new MovementController();
        var history = new CommandHistory();
        var server = MovementControllerTest.grounded();
        var predicted = server;
        var baselines = new java.util.LinkedHashMap<Integer, MovementState>();
        for (int tick = 1; tick <= 15; tick++) {
            var command = MovementControllerTest.forward(tick);
            var impulse = tick == 7 ? new MovementVector(2, 4, 0)
                    : tick == 11 ? new MovementVector(-1, 0, 2) : MovementVector.ZERO;
            // This server-only change forces replay from a snapshot older than both local pushes.
            if (tick == 3) server = server.withVelocity(server.velocity().add(new MovementVector(3, 0, 0)));
            server = server.withVelocity(server.velocity().add(impulse));
            predicted = predicted.withVelocity(predicted.velocity().add(impulse));
            assertTrue(history.add(command, impulse));
            server = controller.tick(server, command, MovementTuning.DEFAULT, MovementControllerTest::floor, e -> {});
            predicted = controller.tick(predicted, command, MovementTuning.DEFAULT, MovementControllerTest::floor, e -> {});
            history.recordPrediction(tick, predicted);
            if (tick == 4 || tick == 9 || tick == 12) baselines.put(tick, server);
        }
        assertNotEquals(server, predicted);
        for (var baseline : baselines.entrySet()) {
            history.acknowledge(baseline.getKey());
            predicted = history.replay(baseline.getValue(), controller, MovementTuning.DEFAULT, MovementControllerTest::floor);
            assertEquals(server, predicted, "Pending pushes must survive correction from tick " + baseline.getKey());
            assertTrue(history.matches(15, server), "Replay must refresh the complete prediction");
            assertEquals(predicted, history.replay(baseline.getValue(), controller, MovementTuning.DEFAULT, MovementControllerTest::floor),
                    "Repeating reconciliation must not accumulate impulses");
        }
        history.clear();
        var resetCommand = MovementControllerTest.forward(1);
        assertTrue(history.add(resetCommand));
        assertEquals(controller.tick(MovementControllerTest.grounded(), resetCommand, MovementTuning.DEFAULT,
                        MovementControllerTest::floor, e -> {}),
                history.replay(MovementControllerTest.grounded(), controller, MovementTuning.DEFAULT, MovementControllerTest::floor));
    }

    @Test
    void equalPositionDoesNotHideDivergentReplayTimers() {
        var history = new CommandHistory();
        var predicted = MovementControllerTest.grounded();
        assertTrue(history.add(MovementControllerTest.forward(1)));
        history.recordPrediction(1, predicted);
        assertTrue(history.matches(1, predicted));
        var authoritative = new MovementState(predicted.position(), predicted.velocity(), predicted.mode(),
                predicted.modeTime() + 0.05, predicted.grounded(), predicted.fallDistance(), predicted.locomotion(),
                predicted.blocks(), predicted.body(), predicted.slideYaw(), predicted.traversal(), predicted.wall(),
                predicted.landing(), predicted.advanced(), predicted.surfaces());
        assertFalse(history.matches(1, authoritative));
    }

    @Test
    void reducedSnapshotCadenceReconcilesDelayedAcknowledgementsWithoutChangingPhysics() {
        record Baseline(int sequence, MovementState state) {}
        for (int interval : new int[]{1, 2, 5}) {
            var controller = new MovementController();
            MovementState server = MovementControllerTest.grounded(), predicted = server;
            var history = new CommandHistory();
            var schedule = new SnapshotSchedule();
            var inFlight = new ArrayDeque<Baseline>();
            for (int tick = 1; tick <= 90; tick++) {
                var input = new MovementInput(tick, 1, 0, 0, tick == 10 || tick == 50, tick == 10 || tick == 50,
                        false, false, true, tick > 70 ? 45 : 0, 0);
                assertTrue(history.add(input));
                server = controller.tick(server, input, MovementTuning.DEFAULT, MovementControllerTest::floor, e -> {});
                predicted = controller.tick(predicted, input, MovementTuning.DEFAULT, MovementControllerTest::floor, e -> {});
                if (schedule.due(tick, 1, true, true, server, Pose.STANDING, MovementTuning.DEFAULT,
                        ResourceLocation.parse("minecraft:overworld"), interval)) inFlight.add(new Baseline(tick, server));
                if (!inFlight.isEmpty() && inFlight.peek().sequence() <= tick - 2) {
                    var baseline = inFlight.remove();
                    history.acknowledge(baseline.sequence());
                    predicted = baseline.state();
                    for (var pending : history.remaining()) predicted = controller.tick(predicted, pending, MovementTuning.DEFAULT, MovementControllerTest::floor, e -> {});
                }
                assertEquals(server, predicted);
                assertTrue(history.size() <= interval + 2);
            }
        }
    }

    @Test
    void acknowledgedBaselineAndPendingCommandsMatchUninterruptedSimulation() {
        MovementController controller = new MovementController();
        MovementState state = MovementControllerTest.grounded();
        CommandHistory history = new CommandHistory();
        MovementState baseline = null;
        for (int tick = 1; tick <= 30; tick++) {
            MovementInput input = new MovementInput(tick, tick < 20 ? 1 : -1, 0, 0,
                    tick >= 10, tick == 10, false, false, false, tick * 3, 0);
            assertTrue(history.add(input));
            state = controller.tick(state, input, MovementTuning.DEFAULT, MovementControllerTest::floor, event -> {});
            if (tick == 12) {
                baseline = state;
            }
        }
        history.acknowledge(12);
        assertEquals(18, history.size());
        MovementState replayed = baseline;
        for (MovementInput input : history.remaining()) {
            replayed = controller.tick(replayed, input, MovementTuning.DEFAULT, MovementControllerTest::floor, event -> {});
        }
        assertEquals(state, replayed);
        history.acknowledge(30);
        assertEquals(0, history.size());
    }

    @Test
    void historyIsBoundedAndCanResetForNewEpochOrTuning() {
        CommandHistory history = new CommandHistory();
        for (int tick = 1; tick <= CommandHistory.CAPACITY; tick++) {
            assertTrue(history.add(MovementControllerTest.forward(tick)));
        }
        assertFalse(history.add(MovementControllerTest.forward(129)));
        assertEquals(CommandHistory.CAPACITY, history.size());
        history.clear();
        assertTrue(history.add(MovementControllerTest.forward(1)));
        assertEquals(1, history.size());
    }

    @Test
    void historyWrapsWithoutAliasingAcknowledgedStatesAndSupportsSequenceGaps() {
        var history = new CommandHistory();
        var state = MovementControllerTest.grounded();
        for (int sequence = 1; sequence <= 400; sequence++) {
            assertTrue(history.add(MovementControllerTest.forward(sequence)));
            history.recordPrediction(sequence, state);
            assertTrue(history.matches(sequence, state));
            if (sequence > 64) {
                history.acknowledge(sequence - 64);
                assertFalse(history.matches(sequence - 64, state));
            }
            var pending = new java.util.ArrayList<Long>();
            for (var input : history.remaining()) pending.add(input.sequence());
            assertEquals(Math.min(sequence, 64), pending.size());
            assertEquals((long) sequence, pending.getLast());
            assertEquals((long) Math.max(1, sequence - 63), pending.getFirst());
        }
        history.acknowledge(400);
        for (long sequence : new long[] {500, 502, 900}) {
            assertTrue(history.add(MovementControllerTest.forward(sequence)));
            history.recordPrediction(sequence, state);
            assertTrue(history.matches(sequence, state));
        }
        assertFalse(history.matches(501, state));
        history.acknowledge(502);
        assertFalse(history.matches(502, state));
        assertTrue(history.matches(900, state));
        assertThrows(IllegalArgumentException.class, () -> history.add(MovementControllerTest.forward(900)));
        assertEquals(1, history.size());
        var iterator = history.remaining().iterator();
        assertEquals(900, iterator.next().sequence());
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertThrows(java.util.NoSuchElementException.class, iterator::next);
    }

    @Test
    void sprintTurnJumpAndCornerCollisionReplayPreserveAllState() {
        var floor = new CollisionBox(new MovementVector(-20, -1, -20), new MovementVector(20, 0, 20));
        var wall = new CollisionBox(new MovementVector(-5, 0, 5), new MovementVector(5, 4, 5.1));
        var corner = new CollisionBox(new MovementVector(2, 0, 3), new MovementVector(3, 4, 8));
        var ceiling = new CollisionBox(new MovementVector(-5, 2.2, 3), new MovementVector(5, 3, 5));
        var world = new BoxWorld(List.of(floor, wall, corner, ceiling));
        MovementController.MotionResolver resolver = (position, velocity, seconds) -> {
            var result = CollisionMover.move(world, CollisionBox.body(position, 0.6, 1.8), velocity.scale(seconds));
            return new MovementController.Motion(position.add(result.recovery()).add(result.displacement()),
                    result.clip(velocity), result.grounded());
        };
        MovementController controller = new MovementController();
        MovementState state = MovementControllerTest.grounded();
        CommandHistory history = new CommandHistory();
        MovementState baseline = null;
        int events = 0;
        for (int tick = 1; tick <= 110; tick++) {
            float yaw = tick <= 65 ? 0 : tick <= 85 ? -45 : -90;
            MovementInput input = new MovementInput(tick, 1, 0, 0, tick >= 30 && tick < 50,
                    tick == 30 || tick == 75, false, false, true, yaw, 0);
            assertTrue(history.add(input));
            var emitted = new java.util.ArrayList<MovementEvent>();
            state = controller.tick(state, input, MovementTuning.DEFAULT, resolver, emitted::add);
            events += emitted.size();
            assertFalse(world.overlaps(CollisionBox.body(state.position(), 0.6, 1.8)));
            if (tick == 20) {
                baseline = state;
                assertTrue(state.locomotion().energy() > 0);
            }
        }
        assertTrue(events >= 4); // Both launches and collision-aware landings happened.
        history.acknowledge(20);
        MovementState replay = baseline;
        for (MovementInput input : history.remaining()) {
            replay = controller.tick(replay, input, MovementTuning.DEFAULT, resolver, event -> {});
        }
        assertEquals(state, replay);
    }

    @Test
    void bufferedLandingPressSurvivesAnAcknowledgedBaseline() {
        MovementController controller = new MovementController();
        MovementState state = new MovementState(new MovementVector(0, 0.3, 0), new MovementVector(0, -3, 0),
                MovementState.Mode.AIR, 1, false, 0);
        state = controller.tick(state, new MovementInput(1, 0, 0, 0, true, true,
                false, false, false, 0, 0), MovementTuning.DEFAULT, MovementControllerTest::floor, event -> {});
        assertFalse(state.grounded());
        assertTrue(state.locomotion().jumpBuffer() > 0);
        MovementState baseline = state;
        MovementInput held = new MovementInput(2, 0, 0, 0, true, false, false, false, false, 0, 0);
        state = controller.tick(state, held, MovementTuning.DEFAULT, MovementControllerTest::floor, event -> {});
        MovementState replay = controller.tick(baseline, held, MovementTuning.DEFAULT,
                MovementControllerTest::floor, event -> {});
        assertEquals(state, replay);
        assertTrue(replay.velocity().y() > 0);
    }
}
