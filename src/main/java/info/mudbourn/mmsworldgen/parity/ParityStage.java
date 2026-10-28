package info.mudbourn.mmsworldgen.parity;

public enum ParityStage {
    NOISE("noise"),
    SURFACE("surface"),
    CARVERS("carvers"),
    FEATURES("features");

    public final String id;

    ParityStage(String id) {
        this.id = id;
    }
}
