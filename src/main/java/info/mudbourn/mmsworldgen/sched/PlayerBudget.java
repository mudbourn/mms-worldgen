package info.mudbourn.mmsworldgen.sched;

final class PlayerBudget {
    double generateTokens;
    double loadTokens;
    double lastX;
    double lastZ;
    double velocityX;
    double velocityZ;
    boolean positioned;
    int queued;
    int generating;
    String name = "";

    PlayerBudget(double generateTokens, double loadTokens) {
        this.generateTokens = generateTokens;
        this.loadTokens = loadTokens;
    }

    // Updates the smoothed horizontal velocity in blocks per tick.
    void track(double x, double z) {
        if (positioned) {
            velocityX = velocityX * 0.8 + (x - lastX) * 0.2;
            velocityZ = velocityZ * 0.8 + (z - lastZ) * 0.2;
        }
        lastX = x;
        lastZ = z;
        positioned = true;
    }

    // Tells whether the player moves fast enough to have a heading.
    boolean moving() {
        return velocityX * velocityX + velocityZ * velocityZ > 0.01;
    }
}
