package info.mudbourn.mmsworldgen.parity;

public enum ParityStage {
    NOISE("noise", true),
    NOISE_HEIGHTMAPS("noise_heightmaps", true),
    SURFACE("surface", true),
    CARVERS("carvers", true),
    FEATURES("features", false),
    FEATURE_TRACE("feature_trace", false);

    public final String id;
    // Whether a mismatch fails the check rather than only being counted.
    public final boolean gated;

    ParityStage(String id, boolean gated) {
        this.id = id;
        this.gated = gated;
    }
}
