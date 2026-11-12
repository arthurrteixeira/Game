package com.labirinto.neon.game;

/** Drone caçador. Cada um tem uma personalidade de perseguição diferente. */
public final class Enemy extends Actor {
    public enum State { HOUSE, LEAVING, ACTIVE, EATEN, ENTERING }

    public final int id;
    public State state = State.HOUSE;
    public boolean frightened;
    float houseTimer;
    float homeX, homeY;
    /** Canto do labirinto para onde vai no modo dispersão. */
    final int cornerX, cornerY;

    Enemy(int id, int cornerX, int cornerY) {
        this.id = id;
        this.cornerX = cornerX;
        this.cornerY = cornerY;
    }

    /** Visível como "só o olho" voltando para a base. */
    public boolean isEyes() {
        return state == State.EATEN || state == State.ENTERING;
    }
}
