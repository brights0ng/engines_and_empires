package dev.brights0ng.enginesandempires.frontier.deep;

/** What made a thumper shot, which decides what it sets off. */
public enum ShotSource {
    /** The mechanical thumper. */
    MECHANICAL,
    /** The combustive thumper, firing on fuel. */
    COMBUSTIVE,
    /** The combustive thumper dropping its head with nothing to burn. Sets off sculk, but counts for nothing else. */
    COMBUSTIVE_DRY;

    /** Whether the shot counts toward calling up an incursion, brings mobs up and stirs deposits. */
    public boolean isRealShot() {
        return this != COMBUSTIVE_DRY;
    }
}
