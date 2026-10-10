package dev.mineedge.client.movement;

import dev.mineedge.movement.MovementVector;
import net.minecraft.world.entity.WalkAnimationState;

/** Native limb animation driven by completed simulation motion, on the model's render clock. */
public final class WalkingPresentation {
    private final SimulationInterpolation walking = new SimulationInterpolation();

    public record Shot(float speed, float position) {}

    public void step(WalkAnimationState animation, MovementVector displacement, long now) {
        // Vanilla travel is bypassed. Restore its animation update using resolved motion,
        // not input/velocity or displacement from an unrelated Minecraft tick boundary.
        float speed = Math.min((float) displacement.horizontalSpeed() * 4.0F, 1.0F);
        animation.update(speed, 0.4F);
        walking.record(sample(animation), now);
    }

    public void reset(WalkAnimationState animation, long now) {
        walking.reset(sample(animation), now);
    }

    public Shot sample(long now) {
        var sample = walking.position(now);
        return sample == null ? null : new Shot((float) sample.x(), (float) sample.y());
    }

    private static MovementVector sample(WalkAnimationState animation) {
        return new MovementVector(animation.speed(), animation.position(), 0);
    }

    public void clear() {
        walking.clear();
    }
}
