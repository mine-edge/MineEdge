package dev.mineedge.client.camera;

import dev.mineedge.client.config.MineEdgeClientConfig;
import dev.mineedge.client.movement.ClientMovement;
import dev.mineedge.client.movement.MovementSounds;
import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementVector;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;

public final class MovementCamera {
    private static final CameraEffects EFFECTS = new CameraEffects();
    private static final CorrectionSmoothing CORRECTION = new CorrectionSmoothing();
    private static final CameraRotationSmoothing ROTATION = new CameraRotationSmoothing();
    private static CameraEffects.Shot shot = CameraEffects.Shot.NONE;
    private static long lastFrame;

    public static void reset() {
        resetEffects();
        ROTATION.reset();
    }

    private static void resetEffects() {
        EFFECTS.reset();
        CORRECTION.reset();
        shot = CameraEffects.Shot.NONE;
        lastFrame = 0;
    }

    public static void correct(MovementVector displacement) {
        if (supported()) CORRECTION.correct(displacement);
        else CORRECTION.reset();
    }

    private static boolean supported() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.getCameraEntity() == mc.player && mc.options.getCameraType().isFirstPerson()
                && !mc.isPaused() && mc.screen == null && mc.isWindowActive() && mc.player.isAlive()
                && ClientMovement.INSTANCE.presentationState() != null;
    }

    public static void event(MovementEvent event, double impact) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.isPaused() || mc.screen != null || !mc.isWindowActive()
                || ClientMovement.INSTANCE.presentationState() == null) return;
        if (supported() && MineEdgeClientConfig.CAMERA_EFFECTS.get()) EFFECTS.event(event, impact);
        MovementSounds.event(event);
    }

    public static void restrictLook() {
        Minecraft mc = Minecraft.getInstance();
        var state = ClientMovement.INSTANCE.presentationState();
        if (mc.player == null || state == null) return;
        mc.player.setYRot(CameraLookLimits.yaw(state, mc.player.getYRot()));
        mc.player.yRotO = CameraLookLimits.yaw(state, mc.player.yRotO);
    }

    public static void angles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        var state = ClientMovement.INSTANCE.presentationState();
        long now = System.nanoTime();
        if (mc.player != null && mc.getCameraEntity() == mc.player && state != null) {
            // This event runs before Camera.setup's third-person reversal and zoom clipping.
            // Smooth forced yaw independently of cosmetic effects and in both camera perspectives.
            boolean controlled = state.advanced().turn() != null || CameraLookLimits.restricted(state);
            event.setYaw(ROTATION.update(event.getYaw(), controlled, now, mc.isPaused()));
        } else {
            ROTATION.reset();
        }
        if (!supported()) { resetEffects(); return; }
        double dt = lastFrame == 0 ? 0 : (now - lastFrame) / 1.0E9;
        lastFrame = now;
        CORRECTION.update(dt);
        if (!MineEdgeClientConfig.CAMERA_EFFECTS.get()) {
            EFFECTS.reset();
            shot = CameraEffects.Shot.NONE;
            return;
        }
        shot = EFFECTS.update(dt, state, event.getYaw(),
                new CameraEffects.Settings(MineEdgeClientConfig.CAMERA_EFFECTS.get(),
                        MineEdgeClientConfig.CAMERA_ROLL.get(), MineEdgeClientConfig.ROLL_STRENGTH.get(),
                        MineEdgeClientConfig.SPEED_FOV.get(), MineEdgeClientConfig.LANDING_STRENGTH.get(),
                        MineEdgeClientConfig.SHAKE_STRENGTH.get(), MineEdgeClientConfig.SWAY_STRENGTH.get(),
                         mc.options.bobView().get() ? MineEdgeClientConfig.BOB_STRENGTH.get() : 0,
                        MineEdgeClientConfig.PARKOUR_STRENGTH.get()));
        event.setRoll(event.getRoll() + (float) shot.roll());
        event.setPitch(event.getPitch() + (float) shot.pitch());
        // Bound only the cosmetic addition. Clamping the smoothed camera itself would
        // snap it straight back to the limit on attachment or when a pipe face changes.
        float yaw = mc.player.getYRot();
        float cosmeticYaw = CameraLookLimits.yaw(state, yaw + (float) shot.yaw()) - yaw;
        event.setYaw(event.getYaw() + cosmeticYaw);
    }

    public static void fov(ViewportEvent.ComputeFov event) {
        if (supported() && MineEdgeClientConfig.CAMERA_EFFECTS.get()
                && event.usedConfiguredFov() && !Minecraft.getInstance().player.isScoping()) {
            event.setFOV(Math.clamp(event.getFOV() + shot.fov() * Minecraft.getInstance().options.fovEffectScale().get(), 1, 179));
        }
    }

    public static Vec3 offset() {
        if (!supported()) return Vec3.ZERO;
        var offset = CORRECTION.offset();
        double height = MineEdgeClientConfig.CAMERA_EFFECTS.get() ? shot.height() : 0;
        return new Vec3(offset.x(), offset.y() + height, offset.z());
    }
    private MovementCamera() {}
}
