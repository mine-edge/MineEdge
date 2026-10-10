package dev.mineedge.client.movement;

import dev.mineedge.movement.MovementRate;
import dev.mineedge.movement.MovementVector;
import net.minecraft.world.entity.WalkAnimationState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WalkingPresentationTest {
    private static final long INTERVAL = MovementRate.INTERVAL_NANOS;

    @Test
    void resolvedWalkingAdvancesLimbsOnTheSameClockAsModelPosition() {
        var animation = new WalkAnimationState();
        var walking = new WalkingPresentation();
        var model = new SimulationInterpolation();
        walking.reset(animation, 0);
        model.reset(MovementVector.ZERO, 0);
        var displacement = new MovementVector(0.2, 0, 0);
        walking.step(animation, displacement, INTERVAL);
        model.record(displacement, INTERVAL);

        assertEquals(0.32F, animation.speed(), 1.0E-6F);
        assertTrue(animation.position() > 0, "Bypassing native travel must not freeze the walking cycle");
        for (int frame = 0; frame <= 10; frame++) {
            long now = INTERVAL + INTERVAL * frame / 10;
            var shot = walking.sample(now);
            double progress = model.position(now).x() / displacement.x();
            assertEquals(animation.speed() * progress, shot.speed(), 1.0E-6);
            assertEquals(animation.position() * progress, shot.position(), 1.0E-6);
        }
    }

    @Test
    void blockedMovementAndVerticalFallsDoNotCreateWalking() {
        var animation = new WalkAnimationState();
        var walking = new WalkingPresentation();
        walking.reset(animation, 0);
        walking.step(animation, MovementVector.ZERO, INTERVAL);
        walking.step(animation, new MovementVector(0, -5, 0), 2 * INTERVAL);
        assertEquals(0, animation.speed());
        assertEquals(0, animation.position());

        walking.step(animation, new MovementVector(0, 0, -0.2), 3 * INTERVAL);
        assertTrue(animation.isMoving());
        float speed = animation.speed();
        for (int step = 4; step <= 24; step++) {
            walking.step(animation, MovementVector.ZERO, step * INTERVAL);
        }
        assertTrue(animation.speed() < speed * 0.001, "Limbs must settle when collision stops movement");
    }

    @Test
    void sprintingCapsLimbAmplitude() {
        var animation = new WalkAnimationState();
        var walking = new WalkingPresentation();
        walking.reset(animation, 0);
        for (int step = 1; step <= 30; step++) {
            walking.step(animation, new MovementVector(3, 0, 4), step * INTERVAL);
        }
        assertEquals(1, animation.speed(), 1.0E-5);
        assertTrue(animation.position() > 20);
    }

    @Test
    void correctionsResetOnlyTheRenderTimelineAndReleaseDropsTheOverride() {
        var animation = new WalkAnimationState();
        var walking = new WalkingPresentation();
        walking.reset(animation, 0);
        walking.step(animation, new MovementVector(0.2, 0, 0), INTERVAL);
        float speed = animation.speed();
        float position = animation.position();

        walking.reset(animation, 10 * INTERVAL);
        var shot = walking.sample(10 * INTERVAL);
        assertEquals(speed, shot.speed());
        assertEquals(position, shot.position());
        walking.clear();
        assertNull(walking.sample(11 * INTERVAL));
        assertEquals(speed, animation.speed());
        assertEquals(position, animation.position());
    }
}
