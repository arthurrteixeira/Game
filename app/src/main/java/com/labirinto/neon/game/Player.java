package com.labirinto.neon.game;

/** "Bit", o robozinho coletor. */
public final class Player extends Actor {
    /** Direção pedida pelo jogador (fica guardada até ser possível virar). */
    public volatile Dir desired = Dir.NONE;
    public boolean moving;
}
