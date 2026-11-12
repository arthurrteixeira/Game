package com.labirinto.neon.game;

/** Algo que anda pelo labirinto. Posição em unidades de tile; centros dos tiles são inteiros. */
public class Actor {
    public float x, y;
    public Dir dir = Dir.NONE;
    /** Última direção não-nula (para desenhar para onde olha). */
    public Dir facing = Dir.LEFT;

    public int tileX() { return Math.round(x); }
    public int tileY() { return Math.round(y); }

    void place(float px, float py, Dir d) {
        x = px;
        y = py;
        dir = d;
        if (d != Dir.NONE) facing = d;
    }
}
