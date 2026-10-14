package dev.mineedge.client.camera;

import dev.mineedge.movement.*;
import dev.mineedge.movement.wall.WallState;
import dev.mineedge.client.movement.RollPresentation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraEffectsTest {
    private static final CameraEffects.Settings FULL = new CameraEffects.Settings(true, true, 1, 1, 1, 0, 0, 0, 0);
    private static final CameraEffects.Settings DYNAMIC = new CameraEffects.Settings(true, true, 1, 0, 0, 0, 1, 1, 1);
    private static final CameraEffects.Settings OFF = new CameraEffects.Settings(true, true, 1, 0, 0, 0, 0, 0, 0);
    private static MovementState state(MovementState.Mode mode) {
        return new MovementState(MovementVector.ZERO, new MovementVector(0, 0, 7.2), mode, 0, true, 0);
    }

    @Test void speedFovConvergesEquallyAtDifferentFrameRatesWithoutChangingGameplay() {
        MovementState state = state(MovementState.Mode.GROUND);
        double expected = 10 * (1 - Math.exp(-4));
        for (int fps : new int[]{30, 60, 144}) {
            var fx = new CameraEffects();
            CameraEffects.Shot shot = CameraEffects.Shot.NONE;
            for (int i = 0; i < fps; i++) shot = fx.update(1.0 / fps, state, 0, FULL);
            assertEquals(expected, shot.fov(), 1.0E-9);
        }
        assertEquals(new MovementVector(0, 0, 7.2), state.velocity());
    }

    @Test void rollCanBeDisabledImmediatelyAndMoveExitRecovers() {
        var fx = new CameraEffects();
        var slide = state(MovementState.Mode.SLIDE);
        for (int i = 0; i < 60; i++) fx.update(1.0 / 60, slide, 0, FULL);
        assertEquals(0, fx.update(0, slide, 0, new CameraEffects.Settings(true, false, 1, 1, 1, 1, 0, 0, 0)).roll());
        for (int i = 0; i < 60; i++) fx.update(1.0 / 60, slide, 0, FULL);
        CameraEffects.Shot shot = CameraEffects.Shot.NONE;
        for (int i = 0; i < 120; i++) shot = fx.update(1.0 / 60, state(MovementState.Mode.GROUND), 0, FULL);
        assertEquals(0, shot.roll(), 0.001);
    }

    @Test void hardLandingNarrowsFovWithoutSpeedFovAndSmoothlyRecovers() {
        var landing = new CameraEffects.Settings(true, true, 1, 0, 1, 0, 0, 0, 0);
        var recovering = state(MovementState.Mode.HARD_LANDING).withVelocity(new MovementVector(0, 0, 2));
        var standing = state(MovementState.Mode.GROUND).withVelocity(MovementVector.ZERO);
        for (int fps : new int[]{30, 60, 144}) {
            var fx = new CameraEffects();
            CameraEffects.Shot shot = CameraEffects.Shot.NONE;
            for (int i = 0; i < fps; i++) shot = fx.update(1.0 / fps, recovering, 0, landing);
            assertEquals(-10 * (1 - Math.exp(-8)), shot.fov(), 1.0E-9);
            double narrowed = shot.fov();
            shot = fx.update(1.0 / fps, standing, 0, landing);
            assertTrue(shot.fov() > narrowed && shot.fov() < 0);
            for (int i = 0; i < fps * 3; i++) shot = fx.update(1.0 / fps, standing, 0, landing);
            assertEquals(0, shot.fov(), 1.0E-8);
            fx.update(0.1, recovering, 0, landing);
            assertEquals(0, fx.update(0, recovering, 0, OFF).fov());
            fx.update(0.1, recovering, 0, landing);
            fx.reset();
            assertEquals(CameraEffects.Shot.NONE, fx.update(0, standing, 0, landing));
        }
    }

    @Test void wallRollUsesMinecraftRightDirection() {
        var base = state(MovementState.Mode.WALLRUN);
        var wall = WallState.EMPTY.contact(new MovementVector(1, 0, 0), null, MovementVector.ZERO, MovementVector.ZERO, false, 0);
        var running = new MovementState(base.position(), base.velocity(), base.mode(), 0, false, 0,
                base.locomotion(), base.blocks(), base.body(), 0, base.traversal(), wall);
        assertTrue(new CameraEffects().update(0.1, running, 0, FULL).roll() < 0);
        assertTrue(new CameraEffects().update(0.1, running, 180, FULL).roll() > 0);
    }

    @Test void landingRecoveryAndResetAreBoundedEvenAfterHitches() {
        var fx = new CameraEffects();
        fx.event(MovementEvent.LAND, 100);
        fx.event(MovementEvent.HARD_LAND, 100);
        var shot = fx.update(10, state(MovementState.Mode.GROUND), 0, FULL);
        assertTrue(shot.height() >= -0.15 && shot.height() < 0);
        for (int i = 0; i < 300; i++) shot = fx.update(0.02, state(MovementState.Mode.GROUND), 0, FULL);
        assertEquals(0, shot.height(), 1.0E-8);
        fx.reset();
        assertEquals(CameraEffects.Shot.NONE, fx.update(0, state(MovementState.Mode.GROUND), 0, FULL));
        fx.event(MovementEvent.LAND, 20);
        assertEquals(0, fx.update(0.05, state(MovementState.Mode.GROUND), 0,
                new CameraEffects.Settings(true, false, 0, 0, 0, 0, 0, 0, 0)).height());
    }

    @Test void movementLeanTracksCameraRelativeVelocityAndAcceleration() {
        var lean = new CameraEffects.Settings(true, true, 1, 0, 0, 0, 1, 0, 0);
        var sideways = state(MovementState.Mode.GROUND).withVelocity(new MovementVector(4, 0, 0));
        assertTrue(new CameraEffects().update(0.1, sideways, 0, lean).roll() > 0);
        assertTrue(new CameraEffects().update(0.1, sideways, 180, lean).roll() < 0);
        var fx = new CameraEffects();
        var standing = sideways.withVelocity(MovementVector.ZERO);
        assertEquals(CameraEffects.Shot.NONE, fx.update(0.1, standing, 0, lean));
        assertTrue(fx.update(0.1, state(MovementState.Mode.GROUND), 0, lean).pitch() > 0);
        for (int i = 0; i < 60; i++) fx.update(1.0 / 60, state(MovementState.Mode.GROUND), 0, lean);
        assertTrue(fx.update(0.1, standing, 0, lean).pitch() < 0);
        for (int i = 0; i < 300; i++) fx.update(1.0 / 60, standing, 0, lean);
        assertEquals(0, fx.update(0, standing, 0, lean).pitch(), 1.0E-8);
    }

    @Test void headBobUsesDistanceAndFadesOnStoppingOrLeavingGround() {
        var bob = new CameraEffects.Settings(true, true, 1, 0, 0, 0, 0, 1, 0);
        CameraEffects.Shot reference = null;
        for (int fps : new int[]{30, 60, 144}) {
            var fx = new CameraEffects();
            CameraEffects.Shot shot = CameraEffects.Shot.NONE;
            for (int i = 0; i < fps; i++) shot = fx.update(1.0 / fps, state(MovementState.Mode.GROUND), 0, bob);
            assertTrue(Math.abs(shot.height()) > 0.01);
            if (reference != null) {
                assertEquals(reference.height(), shot.height(), 1.0E-9);
                assertEquals(reference.roll(), shot.roll(), 1.0E-9);
                assertEquals(reference.pitch(), shot.pitch(), 1.0E-9);
            }
            reference = shot;
            var air = new MovementState(MovementVector.ZERO, new MovementVector(0, 0, 7.2),
                    MovementState.Mode.AIR, 0, false, 0);
            for (int i = 0; i < fps * 3; i++) shot = fx.update(1.0 / fps, air, 0, bob);
            assertEquals(0, shot.height(), 1.0E-9);
            assertEquals(0, shot.roll(), 1.0E-9);
            for (int i = 0; i < fps; i++) fx.update(1.0 / fps, state(MovementState.Mode.GROUND), 0, bob);
            var standing = state(MovementState.Mode.GROUND).withVelocity(MovementVector.ZERO);
            for (int i = 0; i < fps * 3; i++) shot = fx.update(1.0 / fps, standing, 0, bob);
            assertEquals(0, shot.height(), 1.0E-9);
        }
    }

    @Test void parkourFeedbackWorksWithoutShakeAndRecoversAfterRepeatedEvents() {
        var actions = new CameraEffects.Settings(true, true, 1, 0, 0, 0, 0, 0, 1);
        var standing = state(MovementState.Mode.GROUND).withVelocity(MovementVector.ZERO);
        var fx = new CameraEffects();
        fx.event(MovementEvent.JUMP, 0);
        assertTrue(fx.update(0.05, standing, 0, actions).pitch() < 0);
        fx.reset();
        fx.event(MovementEvent.VAULT_START, 0);
        var vault = fx.update(0.05, standing, 0, actions);
        assertTrue(vault.pitch() > 0);
        assertTrue(vault.height() < 0);
        for (int i = 0; i < 100; i++) {
            for (MovementEvent event : MovementEvent.values()) fx.event(event, 100);
            var shot = fx.update(10, standing, 0, DYNAMIC);
            assertTrue(Double.isFinite(shot.pitch()) && Math.abs(shot.pitch()) <= 10);
            assertTrue(Double.isFinite(shot.roll()) && Math.abs(shot.roll()) <= 5);
            assertTrue(shot.height() >= -0.15 && shot.height() <= 0.1);
        }
        CameraEffects.Shot shot = CameraEffects.Shot.NONE;
        for (int i = 0; i < 360; i++) shot = fx.update(1.0 / 60, standing, 0, DYNAMIC);
        assertEquals(0, shot.pitch(), 1.0E-8);
        assertEquals(0, shot.roll(), 1.0E-8);
        assertEquals(0, shot.height(), 1.0E-8);
    }

    @Test void zeroStrengthsAndMasterToggleClearDynamicFeedbackImmediately() {
        var fx = new CameraEffects();
        fx.event(MovementEvent.ROLL_START, 0);
        var running = state(MovementState.Mode.GROUND);
        assertNotEquals(CameraEffects.Shot.NONE, fx.update(0.1, running, 0, DYNAMIC));
        assertEquals(CameraEffects.Shot.NONE, fx.update(0, running, 0, OFF));
        assertEquals(CameraEffects.Shot.NONE, fx.update(0, running, 0, DYNAMIC));
        fx.event(MovementEvent.VAULT_START, 0);
        fx.update(0.1, running, 0, DYNAMIC);
        var disabled = new CameraEffects.Settings(false, true, 1, 1, 1, 1, 1, 1, 1);
        assertEquals(CameraEffects.Shot.NONE, fx.update(0, running, 0, disabled));
        assertEquals(CameraEffects.Shot.NONE, fx.update(0, running, 0, DYNAMIC));
        fx.event(MovementEvent.TURN, 0);
        var noRoll = new CameraEffects.Settings(true, false, 1, 0, 0, 0, 0, 0, 1);
        assertEquals(0, fx.update(0.1, running, 0, noRoll).roll());
        assertEquals(0, fx.update(0, running, 0, DYNAMIC).roll());
    }

    @Test void constantSpeedReversalHasDirectionalBrakingAndMouseLookDoesNotCreateAcceleration() {
        var lean = new CameraEffects.Settings(true, true, 1, 0, 0, 0, 1, 0, 0);
        var forward = state(MovementState.Mode.GROUND);
        var backward = forward.withVelocity(new MovementVector(0, 0, -7.2));
        var fx = new CameraEffects();
        fx.update(0, forward, 0, lean);
        assertEquals(0, fx.update(0.05, forward, 180, lean).pitch(), 1.0E-9,
                "Looking behind must not generate a braking kick");
        assertTrue(fx.update(0.05, backward, 0, lean).pitch() < -1,
                "Reversing at equal speed must still transfer body momentum");
        fx.reset();
        fx.update(0, forward, 180, lean);
        assertTrue(fx.update(0.05, backward, 180, lean).pitch() > 1,
                "Acceleration feedback must be relative to the actual view direction");
    }

    @Test void directionalInertiaAndEventSpringsAgreeAcrossFrameRates() {
        var effects = new CameraEffects.Settings(true, true, 1, 0, 1, 0, 1, 0, 1);
        var standing = state(MovementState.Mode.GROUND).withVelocity(MovementVector.ZERO);
        CameraEffects.Shot reference = null;
        for (int fps : new int[]{30, 60, 144}) {
            var fx = new CameraEffects();
            fx.update(0, standing, 0, effects);
            fx.event(MovementEvent.JUMP, 0);
            CameraEffects.Shot shot = CameraEffects.Shot.NONE;
            for (int i = 0; i < fps / 2; i++) shot = fx.update(1.0 / fps, state(MovementState.Mode.GROUND), 0, effects);
            fx.event(MovementEvent.LAND, 8);
            var reversed = standing.withVelocity(new MovementVector(3, 0, -7.2));
            for (int i = 0; i < fps / 2; i++) shot = fx.update(1.0 / fps, reversed, 0, effects);
            if (reference != null) {
                assertEquals(reference.pitch(), shot.pitch(), 1.0E-9);
                assertEquals(reference.roll(), shot.roll(), 1.0E-9);
                assertEquals(reference.height(), shot.height(), 1.0E-9);
            }
            reference = shot;
        }
    }

    @Test void attachingToALedgeReleasesInertiaSmoothly() {
        var lean = new CameraEffects.Settings(true, true, 1, 0, 0, 0, 1, 0, 0);
        var fx = new CameraEffects();
        var standing = state(MovementState.Mode.GROUND).withVelocity(MovementVector.ZERO);
        fx.update(0, standing, 0, lean);
        var moving = standing.withVelocity(new MovementVector(3, 0, 7.2));
        var before = fx.update(0.05, moving, 0, lean);
        var hanging = state(MovementState.Mode.LEDGE_HANG).withVelocity(MovementVector.ZERO);
        var attached = fx.update(1.0 / 60, hanging, 0, lean);
        assertTrue(attached.pitch() > 0 && attached.pitch() < before.pitch());
        assertTrue(attached.roll() > 0 && attached.roll() < before.roll());
        for (int i = 0; i < 180; i++) attached = fx.update(1.0 / 60, hanging, 0, lean);
        assertEquals(0, attached.pitch(), 1.0E-9);
        assertEquals(0, attached.roll(), 1.0E-9);
    }

    @Test void airborneSpeedDoesNotAdvanceFootfalls() {
        var bob = new CameraEffects.Settings(true, true, 1, 0, 0, 0, 0, 1, 0);
        var slow = new CameraEffects();
        var fast = new CameraEffects();
        for (int i = 0; i < 20; i++) {
            slow.update(0.02, state(MovementState.Mode.GROUND), 0, bob);
            fast.update(0.02, state(MovementState.Mode.GROUND), 0, bob);
        }
        var air = new MovementState(MovementVector.ZERO, MovementVector.ZERO, MovementState.Mode.AIR, 0, false, 0);
        for (int i = 0; i < 20; i++) {
            var a = slow.update(0.02, air, 0, bob);
            var b = fast.update(0.02, air.withVelocity(new MovementVector(0, 0, 12)), 0, bob);
            assertEquals(a, b, "Air travel must only fade the last grounded footfall");
        }
        assertEquals(slow.update(0.02, state(MovementState.Mode.GROUND), 0, bob),
                fast.update(0.02, state(MovementState.Mode.GROUND), 0, bob));
    }

    @Test void parkourPostureEasesInAndOutAndDisablingDoesNotStoreHiddenMotion() {
        var parkour = new CameraEffects.Settings(true, true, 1, 0, 0, 0, 0, 0, 1);
        var slide = state(MovementState.Mode.SLIDE);
        var standing = state(MovementState.Mode.GROUND).withVelocity(MovementVector.ZERO);
        var fx = new CameraEffects();
        assertEquals(0, fx.update(0, slide, 0, parkour).pitch());
        double entry = fx.update(1.0 / 60, slide, 0, parkour).pitch();
        assertTrue(entry > 0 && entry < 0.1, "Entering a slide must not snap to its posture");
        for (int i = 0; i < 120; i++) fx.update(1.0 / 60, slide, 0, parkour);
        assertEquals(2.5, fx.update(0, slide, 0, parkour).pitch(), 1.0E-6);
        double exit = fx.update(1.0 / 60, standing, 0, parkour).pitch();
        assertTrue(exit > 2.4 && exit < 2.5, "Exiting a slide must retain a short natural recovery");
        for (int i = 0; i < 180; i++) fx.update(1.0 / 60, standing, 0, parkour);
        assertEquals(0, fx.update(0, standing, 0, parkour).pitch(), 1.0E-9);
        fx.update(0.1, slide, 0, parkour);
        assertEquals(0, fx.update(0.1, slide, 0, OFF).pitch());
        assertEquals(0, fx.update(0, slide, 0, parkour).pitch());
    }

    @Test void fallRollCompletesOneForwardRevolutionAtDifferentRenderRatesAndPartialStrength() {
        var parkour = new CameraEffects.Settings(true, true, 0.4, 0, 0, 0, 0, 0, 0.7);
        var noRotation = new CameraEffects.Settings(true, false, 0.4, 0, 0, 0, 0, 0, 0.7);
        for (int fps : new int[]{30, 60, 144}) {
            var fx = new CameraEffects();
            var baseline = new CameraEffects();
            var rolling = state(MovementState.Mode.ROLL);
            assertEquals(0, fx.update(0, rolling, 0, parkour).pitch());
            baseline.update(0, rolling, 0, noRotation);
            double previous = 0;
            for (int frame = 1; frame <= Math.ceil(fps * RollPresentation.DURATION); frame++) {
                double rotation = fx.update(1.0 / fps, rolling, 0, parkour).pitch()
                        - baseline.update(1.0 / fps, rolling, 0, noRotation).pitch();
                assertTrue(rotation > previous, "The camera must keep rotating forward");
                if (frame * 5 == fps * 2) assertEquals(180, rotation, 1.0E-8);
                previous = rotation;
            }
            assertEquals(360, previous, 1.0E-8);
            // A stale presentation state must not start a second revolution.
            assertEquals(baseline.update(1.0 / fps, rolling, 0, noRotation).pitch(),
                    fx.update(1.0 / fps, rolling, 0, parkour).pitch(), 1.0E-8);
        }
    }

    @Test void interruptedRollFinishesRotationAndTheNextRollStartsFresh() {
        var parkour = new CameraEffects.Settings(true, true, 1, 0, 0, 0, 0, 0, 1);
        var noRotation = new CameraEffects.Settings(true, false, 1, 0, 0, 0, 0, 0, 1);
        var fx = new CameraEffects();
        var baseline = new CameraEffects();
        var rolling = new MovementState(MovementVector.ZERO, MovementVector.ZERO,
                MovementState.Mode.ROLL, RollPresentation.DURATION / 2, true, 0);
        assertEquals(180, fx.update(0, rolling, 0, parkour).pitch(), 1.0E-8);
        baseline.update(0, rolling, 0, noRotation);
        var air = state(MovementState.Mode.AIR);
        double rotation = 0;
        for (int i = 0; i < 24; i++) {
            rotation = fx.update(1.0 / 60, air, 0, parkour).pitch()
                    - baseline.update(1.0 / 60, air, 0, noRotation).pitch();
            assertTrue(rotation > 180);
        }
        assertEquals(360, rotation, 1.0E-8);
        assertEquals(baseline.update(0, air, 0, noRotation).pitch(), fx.update(0, air, 0, parkour).pitch());
        assertEquals(baseline.update(0, state(MovementState.Mode.ROLL), 0, noRotation).pitch(),
                fx.update(0, state(MovementState.Mode.ROLL), 0, parkour).pitch());
    }

    @Test void disablingOrResettingClearsFallRollRotationImmediately() {
        var parkour = new CameraEffects.Settings(true, true, 1, 0, 0, 0, 0, 0, 1);
        var rolling = state(MovementState.Mode.ROLL);
        var standing = state(MovementState.Mode.GROUND);
        for (var disabled : new CameraEffects.Settings[]{OFF,
                new CameraEffects.Settings(true, false, 1, 0, 0, 0, 0, 0, 1),
                new CameraEffects.Settings(true, true, 0, 0, 0, 0, 0, 0, 1),
                new CameraEffects.Settings(false, true, 1, 0, 0, 0, 0, 0, 1)}) {
            var fx = new CameraEffects();
            var baseline = new CameraEffects();
            var noRotation = new CameraEffects.Settings(true, false, 1, 0, 0, 0, 0, 0, 1);
            fx.update(0, rolling, 0, parkour);
            baseline.update(0, rolling, 0, noRotation);
            assertTrue(fx.update(0.1, rolling, 0, parkour).pitch() > 10);
            baseline.update(0.1, rolling, 0, noRotation);
            assertEquals(baseline.update(0, rolling, 0, disabled).pitch(),
                    fx.update(0, rolling, 0, disabled).pitch());
            assertEquals(baseline.update(0, standing, 0, parkour).pitch(),
                    fx.update(0, standing, 0, parkour).pitch());
            fx.update(0, rolling, 0, parkour);
            fx.update(0.1, rolling, 0, parkour);
            fx.reset();
            assertEquals(CameraEffects.Shot.NONE, fx.update(0, standing, 0, parkour));
        }
    }
}
