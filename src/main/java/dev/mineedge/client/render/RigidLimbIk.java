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
        var angles = pose.rotation().getEulerAnglesZYX(new Vector3f());
        part.setPos(pose.pivot().x, pose.pivot().y, pose.pivot().z);
        part.setRotation(angles.x, angles.y, angles.z);
    }

    private RigidLimbIk() {}
}
