package com.labirinto.neon.game;

import java.util.Random;

/** Toda a lógica do jogo, sem dependências do Android (testável na JVM). */
public final class Game {

    public enum State { TITLE, READY, PLAYING, DYING, LEVEL_CLEAR, GAME_OVER }

    public interface Listener {
        void onSound(int sound);
        void onGameOver(int finalScore);
    }

    public static final int SND_DOT = 0, SND_POWER = 1, SND_EAT_ENEMY = 2, SND_DEATH = 3,
            SND_CLEAR = 4, SND_LIFE = 5, SND_BONUS = 6, SND_START = 7;

    public static final int START_LIVES = 3;
    public static final int EXTRA_LIFE_SCORE = 10000;
    public static final float READY_TIME = 2.2f, DYING_TIME = 1.8f, CLEAR_TIME = 2.6f;

    /** Alternância dispersão/perseguição (segundos). Índices pares = dispersão. */
    private static final float[] MODE_SCHEDULE = {7, 20, 7, 20, 5, 20, 5};

    public final Maze maze = new Maze();
    public final Player player = new Player();
    public final Enemy[] enemies = new Enemy[4];

    public State state = State.TITLE;
    public float stateTime;
    public float time;
    public int score, lives, level, highScore;
    public int dotsEaten;
    private boolean extraLifeGiven;

    private int modeIndex;
    private float modeTimer;
    public float frightTimer;
    private int eatChain;
    /** Pequena pausa dramática quando um drone é capturado. */
    public float freezeTimer;

    public boolean bonusActive;
    public float bonusTimer;
    public int bonusValue;
    public final int bonusX, bonusY;

    /** Pontuações flutuantes. */
    public final float[] popX = new float[6], popY = new float[6], popT = new float[6];
    public final int[] popV = new int[6];

    private final Random rng;
    private Listener listener;

    public Game(long seed) {
        rng = new Random(seed);
        enemies[0] = new Enemy(0, maze.w - 2, -3);
        enemies[1] = new Enemy(1, 1, -3);
        enemies[2] = new Enemy(2, maze.w - 1, maze.h + 1);
        enemies[3] = new Enemy(3, 0, maze.h + 1);
        bonusX = maze.playerStartX;
        bonusY = maze.playerStartY;
        level = 1;
        lives = START_LIVES;
        resetPositions();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private void sound(int s) {
        if (listener != null) listener.onSound(s);
    }

    // ------------------------------------------------------------------ ciclo de vida

    public void newGame() {
        score = 0;
        lives = START_LIVES;
        level = 1;
        extraLifeGiven = false;
        startLevel();
        sound(SND_START);
    }

    private void startLevel() {
        maze.reset();
        dotsEaten = 0;
        bonusActive = false;
        resetPositions();
    }

    private void resetPositions() {
        player.place(maze.playerStartX, maze.playerStartY, Dir.NONE);
        player.facing = Dir.LEFT;
        player.desired = Dir.NONE;
        float releaseScale = Math.max(0.35f, 1f - (level - 1) * 0.15f);
        float[][] homes = {
            {maze.exitX, maze.exitY},
            {maze.homeX, maze.homeY},
            {maze.homeX - 1, maze.homeY},
            {maze.homeX + 1, maze.homeY},
        };
        float[] release = {0f, 1f, 4.5f, 8f};
        for (int i = 0; i < 4; i++) {
            Enemy e = enemies[i];
            e.homeX = homes[i][0];
            e.homeY = homes[i][1];
            e.place(e.homeX, e.homeY, i == 0 ? Dir.LEFT : Dir.NONE);
            e.state = i == 0 ? Enemy.State.ACTIVE : Enemy.State.HOUSE;
            e.houseTimer = release[i] * releaseScale;
            e.frightened = false;
        }
        modeIndex = 0;
        modeTimer = 0;
        frightTimer = 0;
        freezeTimer = 0;
        setState(State.READY);
    }

    private void setState(State s) {
        state = s;
        stateTime = 0;
    }

    /** Toque / tecla de ação nas telas de título e fim de jogo. */
    public void action() {
        if (state == State.TITLE || (state == State.GAME_OVER && stateTime > 1f)) newGame();
    }

    public void setDesired(Dir d) {
        player.desired = d;
    }

    // ------------------------------------------------------------------ atualização

    public void update(float dt) {
        if (dt > 0.05f) dt = 0.05f;
        time += dt;
        stateTime += dt;
        for (int i = 0; i < popT.length; i++) if (popT[i] > 0) popT[i] -= dt;

        switch (state) {
            case READY:
                if (stateTime >= READY_TIME) setState(State.PLAYING);
                break;
            case PLAYING:
                if (freezeTimer > 0) {
                    freezeTimer -= dt;
                } else {
                    step(dt);
                }
                break;
            case DYING:
                if (stateTime >= DYING_TIME) {
                    lives--;
                    if (lives <= 0) {
                        setState(State.GAME_OVER);
                        if (listener != null) listener.onGameOver(score);
                    } else {
                        resetPositions();
                    }
                }
                break;
            case LEVEL_CLEAR:
                if (stateTime >= CLEAR_TIME) {
                    level++;
                    startLevel();
                }
                break;
            default:
                break;
        }
    }

    private float levelFactor() {
        return Math.min(1f + 0.06f * (level - 1), 1.36f);
    }

    public float playerSpeed() {
        return 6.8f * levelFactor();
    }

    private float enemySpeed(Enemy e) {
        if (e.isEyes()) return 15f;
        float s = 6.3f * levelFactor();
        if (e.frightened) s *= 0.55f;
        else if (e.id == 0 && maze.dotsLeft < 20) s *= 1.1f; // o líder acelera no fim da fase
        if (e.tileY() == maze.tunnelRow && (e.x < 5 || e.x > maze.w - 6)) s *= 0.5f;
        return s;
    }

    private float frightDuration() {
        return Math.max(1.5f, 7f - (level - 1) * 0.6f);
    }

    public boolean isChaseMode() {
        return (modeIndex & 1) == 1;
    }

    private void step(float dt) {
        // Modos dos drones (o relógio para enquanto estão assustados)
        if (frightTimer > 0) {
            frightTimer -= dt;
            if (frightTimer <= 0) {
                frightTimer = 0;
                for (Enemy e : enemies) e.frightened = false;
            }
        } else if (modeIndex < MODE_SCHEDULE.length) {
            modeTimer += dt;
            if (modeTimer >= MODE_SCHEDULE[modeIndex]) {
                modeTimer = 0;
                modeIndex++;
                for (Enemy e : enemies) if (e.state == Enemy.State.ACTIVE) reverse(e);
            }
        }

        updatePlayer(dt);
        eatAtPlayer();
        if (state != State.PLAYING) return;

        for (Enemy e : enemies) updateEnemy(e, dt);
        checkCollisions();
        if (state != State.PLAYING) return;

        if (bonusActive) {
            bonusTimer -= dt;
            if (bonusTimer <= 0) bonusActive = false;
            else if (player.tileX() == bonusX && player.tileY() == bonusY) {
                bonusActive = false;
                addScore(bonusValue);
                popup(bonusX, bonusY, bonusValue);
                sound(SND_BONUS);
            }
        }
    }

    // ------------------------------------------------------------------ jogador

    private void updatePlayer(float dt) {
        final Player p = player;
        final Dir want = p.desired;
        if (want != Dir.NONE && p.dir != Dir.NONE && want == p.dir.opposite()) {
            reverse(p); // inverter é permitido a qualquer momento
        }
        float bx = p.x, by = p.y;
        move(p, playerSpeed() * dt, (tx, ty, cur) -> {
            if (want != Dir.NONE && maze.isOpen(tx + want.dx, ty + want.dy)) return want;
            if (cur != Dir.NONE && maze.isOpen(tx + cur.dx, ty + cur.dy)) return cur;
            return Dir.NONE;
        });
        p.moving = bx != p.x || by != p.y;
    }

    private void eatAtPlayer() {
        int t = maze.eat(player.tileX(), player.tileY());
        if (t == Maze.DOT) {
            addScore(10);
            dotsEaten++;
            sound(SND_DOT);
        } else if (t == Maze.POWER) {
            addScore(50);
            dotsEaten++;
            sound(SND_POWER);
            frightTimer = frightDuration();
            eatChain = 0;
            for (Enemy e : enemies) {
                if (e.isEyes()) continue;
                e.frightened = true;
                if (e.state == Enemy.State.ACTIVE) reverse(e);
            }
        } else {
            return;
        }
        if (dotsEaten == 60 || dotsEaten == 130) {
            bonusActive = true;
            bonusTimer = 9f;
            bonusValue = Math.min(100 * (1 << Math.min(level - 1, 5)), 5000);
        }
        if (maze.dotsLeft == 0) {
            setState(State.LEVEL_CLEAR);
            sound(SND_CLEAR);
        }
    }

    private void addScore(int pts) {
        score += pts;
        if (score > highScore) highScore = score;
        if (!extraLifeGiven && score >= EXTRA_LIFE_SCORE) {
            extraLifeGiven = true;
            lives++;
            sound(SND_LIFE);
        }
    }

    private void popup(float x, float y, int v) {
        int best = 0;
        for (int i = 1; i < popT.length; i++) if (popT[i] < popT[best]) best = i;
        popX[best] = x;
        popY[best] = y;
        popV[best] = v;
        popT[best] = 1.2f;
    }

    // ------------------------------------------------------------------ drones

    private void updateEnemy(final Enemy e, float dt) {
        switch (e.state) {
            case HOUSE:
                e.houseTimer -= dt;
                if (e.houseTimer <= 0) e.state = Enemy.State.LEAVING;
                break;
            case LEAVING: {
                float d = 3.5f * dt;
                if (Math.abs(e.x - maze.exitX) > 1e-3f) {
                    e.x = approach(e.x, maze.exitX, d);
                    e.facing = e.x < maze.exitX ? Dir.RIGHT : Dir.LEFT;
                } else {
                    e.x = maze.exitX;
                    e.y = approach(e.y, maze.exitY, d);
                    e.facing = Dir.UP;
                    if (e.y <= maze.exitY) {
                        e.y = maze.exitY;
                        e.state = Enemy.State.ACTIVE;
                        e.dir = rng.nextBoolean() ? Dir.LEFT : Dir.RIGHT;
                        e.facing = e.dir;
                    }
                }
                break;
            }
            case ENTERING:
                e.y = approach(e.y, maze.homeY, 8f * dt);
                e.facing = Dir.DOWN;
                if (e.y >= maze.homeY) {
                    e.y = maze.homeY;
                    e.state = Enemy.State.LEAVING;
                    e.frightened = false;
                }
                break;
            case ACTIVE:
            case EATEN:
                move(e, enemySpeed(e) * dt, (tx, ty, cur) -> chooseEnemyDir(e, tx, ty, cur));
                break;
        }
    }

    private static float approach(float v, float target, float d) {
        if (v < target) return Math.min(v + d, target);
        return Math.max(v - d, target);
    }

    private static final Dir[] PRIORITY = {Dir.UP, Dir.LEFT, Dir.DOWN, Dir.RIGHT};

    private Dir chooseEnemyDir(Enemy e, int tx, int ty, Dir cur) {
        if (e.state == Enemy.State.EATEN && tx == maze.exitX && ty == maze.exitY) {
            e.state = Enemy.State.ENTERING;
            return Dir.NONE;
        }
        Dir back = cur.opposite();
        if (e.frightened && e.state == Enemy.State.ACTIVE) {
            Dir[] opts = new Dir[4];
            int n = 0;
            for (Dir d : PRIORITY) if (d != back && maze.isOpen(tx + d.dx, ty + d.dy)) opts[n++] = d;
            if (n == 0) return maze.isOpen(tx + back.dx, ty + back.dy) ? back : Dir.NONE;
            return opts[rng.nextInt(n)];
        }
        int[] target = targetFor(e);
        Dir best = Dir.NONE;
        long bestD = Long.MAX_VALUE;
        for (Dir d : PRIORITY) {
            if (d == back) continue;
            int nx = tx + d.dx, ny = ty + d.dy;
            if (!maze.isOpen(nx, ny)) continue;
            long ddx = nx - target[0], ddy = ny - target[1];
            long dist = ddx * ddx + ddy * ddy;
            if (dist < bestD) {
                bestD = dist;
                best = d;
            }
        }
        if (best == Dir.NONE && back != Dir.NONE && maze.isOpen(tx + back.dx, ty + back.dy)) best = back;
        return best;
    }

    private final int[] tmpTarget = new int[2];

    /** Personalidades: Rubi persegue direto, Malva mira à frente, Safira flanqueia, Âmbar é tímida. */
    int[] targetFor(Enemy e) {
        int[] t = tmpTarget;
        if (e.state == Enemy.State.EATEN) {
            t[0] = maze.exitX;
            t[1] = maze.exitY;
            return t;
        }
        int px = player.tileX(), py = player.tileY();
        Dir f = player.facing;
        if (!isChaseMode()) {
            t[0] = e.cornerX;
            t[1] = e.cornerY;
            return t;
        }
        switch (e.id) {
            case 0:
                t[0] = px;
                t[1] = py;
                break;
            case 1:
                t[0] = px + f.dx * 4;
                t[1] = py + f.dy * 4;
                break;
            case 2: {
                int ax = px + f.dx * 2, ay = py + f.dy * 2;
                Enemy lead = enemies[0];
                t[0] = 2 * ax - lead.tileX();
                t[1] = 2 * ay - lead.tileY();
                break;
            }
            default: {
                int dx = e.tileX() - px, dy = e.tileY() - py;
                if (dx * dx + dy * dy > 64) {
                    t[0] = px;
                    t[1] = py;
                } else {
                    t[0] = e.cornerX;
                    t[1] = e.cornerY;
                }
            }
        }
        return t;
    }

    // ------------------------------------------------------------------ colisões

    private void checkCollisions() {
        for (Enemy e : enemies) {
            if (e.state != Enemy.State.ACTIVE && e.state != Enemy.State.LEAVING) continue;
            float dx = Math.abs(e.x - player.x);
            if (dx > maze.w / 2f) dx = maze.w - dx;
            float dy = e.y - player.y;
            if (dx * dx + dy * dy > 0.36f) continue;
            if (e.frightened) {
                e.frightened = false;
                // Capturado ainda saindo da base: só volta para dentro.
                e.state = e.state == Enemy.State.LEAVING ? Enemy.State.ENTERING : Enemy.State.EATEN;
                eatChain++;
                int pts = 200 << Math.min(eatChain - 1, 3);
                addScore(pts);
                popup(e.x, e.y, pts);
                freezeTimer = 0.45f;
                sound(SND_EAT_ENEMY);
            } else {
                setState(State.DYING);
                sound(SND_DEATH);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ movimento em grid

    /** Inverte a direção; no centro exato de um tile só se o caminho de volta estiver livre. */
    void reverse(Actor a) {
        Dir back = a.dir.opposite();
        if (back == Dir.NONE) return;
        boolean atCenter = a.x == Math.round(a.x) && a.y == Math.round(a.y);
        if (atCenter && !maze.isOpen(Math.round(a.x) + back.dx, Math.round(a.y) + back.dy)) return;
        a.dir = back;
        a.facing = back;
    }

    interface Chooser {
        /** Chamado quando o ator está exatamente no centro de um tile. */
        Dir choose(int tx, int ty, Dir current);
    }

    /** Anda {@code dist} tiles, decidindo a direção a cada centro de tile. */
    void move(Actor a, float dist, Chooser c) {
        int guard = 0;
        while (dist > 1e-6f && guard++ < 16) {
            if (a.dir == Dir.NONE) {
                int tx = Math.round(a.x), ty = Math.round(a.y);
                a.x = tx;
                a.y = ty;
                Dir d = c.choose(tx, ty, Dir.NONE);
                if (d == Dir.NONE) return;
                a.dir = d;
                a.facing = d;
            }
            boolean horiz = a.dir.dx != 0;
            float pos = horiz ? a.x : a.y;
            int s = a.dir.dx + a.dir.dy;
            float next = s > 0 ? (float) Math.floor(pos) + 1 : (float) Math.ceil(pos) - 1;
            float rem = Math.abs(next - pos);
            if (dist < rem - 1e-4f) {
                if (horiz) a.x += s * dist; else a.y += s * dist;
                return;
            }
            dist = Math.max(0f, dist - rem);
            if (horiz) a.x = next; else a.y = next;
            if (a.x < 0) a.x += maze.w;
            else if (a.x >= maze.w) a.x -= maze.w;
            Dir d = c.choose(Math.round(a.x), Math.round(a.y), a.dir);
            a.dir = d;
            if (d == Dir.NONE) return;
            a.facing = d;
        }
    }
}
