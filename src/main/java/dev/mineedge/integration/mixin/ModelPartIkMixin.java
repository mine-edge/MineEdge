package dev.mineedge.integration.mixin;

import dev.mineedge.client.render.IkModelPart;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.class)
abstract class ModelPartIkMixin implements IkModelPart {
    @Shadow @Final private List<ModelPart.Cube> cubes;
    @Unique private PartPose mineedge$original;
    @Unique private Vector3f mineedge$originalScale;
    @Override public void mineedge$restorePose() {
        if (mineedge$original != null) {
            var part = (ModelPart) (Object) this;
            var original = mineedge$original;
            var scale = mineedge$originalScale;
            part.loadPose(original);
            part.xScale = scale.x;
            part.yScale = scale.y;
            part.zScale = scale.z;
        }
    }
    @Override public float mineedge$limbCenter() { return cubes.isEmpty() ? 0 : (cubes.getFirst().minX + cubes.getFirst().maxX) / 2; }
    @Override public void mineedge$savePose() {
        // Composed render-only effects share the original pose, not each other's overrides.
        if (mineedge$original != null) return;
        var part = (ModelPart) (Object) this;
        mineedge$original = part.storePose();
        mineedge$originalScale = new Vector3f(part.xScale, part.yScale, part.zScale);
    }

    @Inject(method = "loadPose", at = @At("HEAD"))
    private void mineedge$resetPose(CallbackInfo ci) {
        mineedge$original = null;
        mineedge$originalScale = null;
    }
}
