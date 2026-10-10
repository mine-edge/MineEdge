package dev.mineedge.client.render;

/** Client-only bookkeeping for temporary rigid limb and clothing transforms. */
public interface IkModelPart {
    float mineedge$limbCenter();
    void mineedge$savePose();
    void mineedge$restorePose();
}
