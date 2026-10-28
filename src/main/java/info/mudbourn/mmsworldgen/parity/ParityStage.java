package info.mudbourn.mmsworldgen.parity;

public enum ParityStage {
    NOISE("noise"),
    NOISE_HEIGHTMAPS("noise_heightmaps"),
    SURFACE("surface"),
    CARVERS("carvers"),
    FEATURES("features"),
    FEATURE_TRACE("feature_trace");

    public final String id;

    ParityStage(String id) {
        this.id = id;
    }
}
