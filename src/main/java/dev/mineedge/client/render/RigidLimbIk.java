package dev.mineedge.client.render;

import net.minecraft.client.model.geom.ModelPart;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Moves a rigid cuboid's pivot to reach its target; never stretches or splits the model. */
final class RigidLimbIk {
    record Pose(Vector3f pivot, Quaternionf rotation) {}

    static Pose solve(Vector3f pivot, Quaternionf vanilla, Vector3f scale, float centerX,
                      float top, float bottom, Vector3f target, float weight) {
        var proximal = vanilla.transform(new Vector3f(centerX * scale.x, top * scale.y, 0)).add(pivot);
        var delta = new Vector3f(target).sub(proximal);
        if (weight < 0.001F) return null;
        var aimed = new Quaternionf(vanilla);
        if (delta.lengthSquared() > 1.0E-8F) {
            var direction = vanilla.transform(new Vector3f(0, 1, 0));
            aimed.rotationTo(direction, delta.normalize()).mul(vanilla);
        }
        var blended = new Quaternionf(vanilla).slerp(aimed, weight);
        var endpointOffset = new Vector3f(centerX * scale.x, bottom * scale.y, 0);
        var endpoint = vanilla.transform(new Vector3f(endpointOffset)).add(pivot).lerp(target, weight);
        return new Pose(endpoint.sub(blended.transform(endpointOffset)), blended);
    }

    static void apply(ModelPart part, Pose pose) {
        var rotation = pose.rotation();
        double qx = rotation.x, qy = rotation.y, qz = rotation.z, qw = rotation.w;
        double xx = qx * qx, yy = qy * qy, zz = qz * qz, ww = qw * qw;
        // JOML 1.10.5's ZYX extraction has the wrong sign for y squared in the
        // X denominator. Extract ModelPart's Z * Y * X angles directly instead.
        double sine = Math.clamp(2 * (qw * qy - qx * qz) / (xx + yy + zz + ww), -1, 1);
        float x, y, z;
        if (Math.abs(sine) > 1 - 1.0E-12) {
            // At +/-90 degrees Y, X/Z share an axis. Choose Z=0 consistently,
            // instead of independently extracting two indeterminate atan2 values.
            x = (float) (2 * Math.atan2(qx, qw));
            y = (float) Math.copySign(Math.PI / 2, sine);
            z = 0;
        } else {
            x = (float) Math.atan2(2 * (qw * qx + qy * qz), ww - xx - yy + zz);
            y = (float) Math.asin(sine);
            z = (float) Math.atan2(2 * (qw * qz + qx * qy), ww + xx - yy - zz);
        }
        part.setPos(pose.pivot().x, pose.pivot().y, pose.pivot().z);
        part.setRotation(x, y, z);
    }

    private RigidLimbIk() {}
}
