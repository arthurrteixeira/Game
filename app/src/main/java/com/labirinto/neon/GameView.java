package com.labirinto.neon;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.labirinto.neon.game.Dir;
import com.labirinto.neon.game.Enemy;
import com.labirinto.neon.game.Game;
import com.labirinto.neon.game.Maze;
import com.labirinto.neon.game.Player;

/** Superfície do jogo: laço principal, desenho e controles por toque. */
public final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable, Game.Listener {

    private static final int BG = 0xFF06050E;
    private static final int PLAYER_COLOR = 0xFF9BFF5C;
    private static final int[] ENEMY_COLORS = {0xFFFF3B5C, 0xFFD45CFF, 0xFF3DB8FF, 0xFFFFA53D};
    private static final String[] ENEMY_NAMES = {"RUBI", "MALVA", "SAFIRA", "ÂMBAR"};
    private static final String[] ENEMY_DESC = {"persegue você", "corta seu caminho", "ataca pelos lados", "imprevisível"};
    private static final int[] WALL_COLORS = {0xFF5B6CFF, 0xFFFF4FD8, 0xFF2EE6B8, 0xFFFFB13D, 0xFF8F5BFF};

    private final Game game = new Game(System.nanoTime());
    private final Sfx sfx = new Sfx();
    private final SharedPreferences prefs;

    private Thread thread;
    private volatile boolean running;
    private volatile boolean paused;

    // Layout
    private int viewW, viewH;
    private float ts, offX, mazeTop, hudTop;
    private final RectF pauseBtn = new RectF(), soundBtn = new RectF();
    private Bitmap mazeBitmap;
    private int mazeBitmapLevel = -1;

    // Toque
    private float anchorX, anchorY;
    private boolean swiped;
    private final float density;

    // Tintas reutilizadas
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint flashPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint enemyBody = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public GameView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        prefs = ctx.getSharedPreferences("labirinto_neon", Context.MODE_PRIVATE);
        game.highScore = prefs.getInt("high", 0);
        sfx.muted = prefs.getBoolean("muted", false);
        game.setListener(this);
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        flashPaint.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        running = true;
        thread = new Thread(this, "game-loop");
        thread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        synchronized (game) {
            layout(width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
    }

    /** Chamado quando o app vai para o fundo. */
    public void onHostPause() {
        if (game.state == Game.State.PLAYING || game.state == Game.State.READY) paused = true;
        saveHigh();
    }

    public void release() {
        sfx.release();
    }

    private void saveHigh() {
        if (game.highScore > prefs.getInt("high", 0)) prefs.edit().putInt("high", game.highScore).apply();
    }

    @Override
    public void onSound(int sound) {
        sfx.play(sound);
    }

    @Override
    public void onGameOver(int finalScore) {
        saveHigh();
    }

    @Override
    public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = (now - last) / 1e9f;
            last = now;
            synchronized (game) {
                if (!paused) game.update(dt);
            }
            Canvas c = null;
            SurfaceHolder h = getHolder();
            try {
                c = lockCanvas(h);
                if (c != null) {
                    synchronized (game) {
                        render(c);
                    }
                }
            } finally {
                if (c != null) {
                    try {
                        h.unlockCanvasAndPost(c);
                    } catch (IllegalStateException ignored) {
                    }
                }
            }
            long spent = (System.nanoTime() - now) / 1_000_000L;
            if (spent < 16) {
                try {
                    Thread.sleep(16 - spent);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private boolean hwCanvasFailed;

    private Canvas lockCanvas(SurfaceHolder h) {
        if (Build.VERSION.SDK_INT >= 26 && !hwCanvasFailed) {
            try {
                return h.lockHardwareCanvas();
            } catch (Throwable t) {
                hwCanvasFailed = true;
            }
        }
        return h.lockCanvas();
    }

    // ------------------------------------------------------------------ layout

    private void layout(int w, int h) {
        viewW = w;
        viewH = h;
        Maze m = game.maze;
        float rows = m.h + 5.5f;
        ts = Math.min(w / (float) m.w, h / rows);
        offX = (w - ts * m.w) / 2f;
        float total = ts * rows;
        hudTop = (h - total) / 2f;
        mazeTop = hudTop + ts * 3.2f;
        float b = ts * 1.9f;
        float by = hudTop + ts * 0.6f;
        float right = offX + ts * m.w;
        pauseBtn.set(right - b, by, right, by + b);
        soundBtn.set(right - b * 2.2f, by, right - b * 1.2f, by + b);
        mazeBitmap = null;
    }

    // ------------------------------------------------------------------ entrada

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                anchorX = x;
                anchorY = y;
                swiped = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = x - anchorX, dy = y - anchorY;
                float th = 22 * density;
                if (Math.abs(dx) > th || Math.abs(dy) > th) {
                    Dir d = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? Dir.RIGHT : Dir.LEFT)
                            : (dy > 0 ? Dir.DOWN : Dir.UP);
                    if (!paused) game.setDesired(d);
                    anchorX = x;
                    anchorY = y;
                    swiped = true;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!swiped) onTap(x, y);
                return true;
            default:
                return true;
        }
    }

    private void onTap(float x, float y) {
        float slop = ts * 0.4f;
        if (x > soundBtn.left - slop && x < soundBtn.right + slop && y > soundBtn.top - slop && y < soundBtn.bottom + slop) {
            sfx.muted = !sfx.muted;
            prefs.edit().putBoolean("muted", sfx.muted).apply();
            return;
        }
        boolean inGame = game.state != Game.State.TITLE && game.state != Game.State.GAME_OVER;
        if (inGame && x > pauseBtn.left - slop && x < pauseBtn.right + slop && y > pauseBtn.top - slop && y < pauseBtn.bottom + slop) {
            paused = !paused;
            return;
        }
        if (paused) {
            paused = false;
            return;
        }
        synchronized (game) {
            game.action();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Dir d = null;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_W: d = Dir.UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_S: d = Dir.DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_A: d = Dir.LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_D: d = Dir.RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_SPACE:
                if (paused) paused = false;
                else synchronized (game) { game.action(); }
                return true;
            case KeyEvent.KEYCODE_P:
                paused = !paused;
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
        if (!paused) game.setDesired(d);
        return true;
    }

    // ------------------------------------------------------------------ desenho

    private float sx(float tileX) { return offX + (tileX + 0.5f) * ts; }
    private float sy(float tileY) { return mazeTop + (tileY + 0.5f) * ts; }

    private void render(Canvas c) {
        if (ts <= 0) layout(c.getWidth(), c.getHeight());
        c.drawColor(BG);
        int wallColor = WALL_COLORS[(game.level - 1) % WALL_COLORS.length];
        if (mazeBitmap == null || mazeBitmapLevel != game.level) {
            mazeBitmap = buildMazeBitmap(wallColor);
            mazeBitmapLevel = game.level;
        }

        boolean flash = game.state == Game.State.LEVEL_CLEAR && ((int) (game.stateTime * 6) & 1) == 1;
        c.drawBitmap(mazeBitmap, offX, mazeTop, flash ? flashPaint : bmpPaint);

        drawDots(c);
        if (game.bonusActive && game.state == Game.State.PLAYING) drawBonus(c, sx(game.bonusX), sy(game.bonusY), ts * 0.42f);

        c.save();
        c.clipRect(offX, mazeTop, offX + ts * game.maze.w, mazeTop + ts * game.maze.h);
        boolean showEnemies = game.state == Game.State.READY || game.state == Game.State.PLAYING
                || (game.state == Game.State.DYING && game.stateTime < 0.5f);
        if (showEnemies) {
            for (Enemy e : game.enemies) drawEnemy(c, e, sx(e.x), sy(e.y), ts * 0.46f, game.time);
        }
        if (game.state != Game.State.TITLE && game.state != Game.State.GAME_OVER) {
            Player p = game.player;
            float dying = game.state == Game.State.DYING ? Math.min(1f, game.stateTime / (Game.DYING_TIME * 0.8f)) : -1f;
            drawPlayer(c, sx(p.x), sy(p.y), ts * 0.42f, p.facing, p.moving && game.state == Game.State.PLAYING, dying, game.time);
        }
        drawPopups(c);
        c.restore();

        drawHud(c);

        switch (game.state) {
            case TITLE: drawTitle(c); break;
            case READY: banner(c, game.level == 1 && game.score == 0 ? "PRONTO!" : "FASE " + game.level, 0xFFFFE45C, null); break;
            case LEVEL_CLEAR: banner(c, "FASE CONCLUÍDA!", 0xFF9BFF5C, null); break;
            case GAME_OVER: drawGameOver(c); break;
            default: break;
        }
        if (paused && game.state != Game.State.TITLE && game.state != Game.State.GAME_OVER) {
            fill.setShader(null);
            fill.setColor(0xAA000000);
            c.drawRect(0, 0, viewW, viewH, fill);
            banner(c, "PAUSADO", Color.WHITE, "toque para continuar");
        }
    }

    // --- labirinto

    private Bitmap buildMazeBitmap(int color) {
        Maze m = game.maze;
        int bw = Math.max(1, (int) Math.ceil(ts * m.w)), bh = Math.max(1, (int) Math.ceil(ts * m.h));
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Path p = new Path();
        float in = 0.32f;
        for (int y = 0; y < m.h; y++) {
            for (int x = 0; x < m.w; x++) {
                if (!m.isWall(x, y)) continue;
                addEdge(p, m, x, y, 0, -1, in);
                addEdge(p, m, x, y, 0, 1, in);
                addEdge(p, m, x, y, -1, 0, in);
                addEdge(p, m, x, y, 1, 0, in);
            }
        }
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeCap(Paint.Cap.ROUND);
        pt.setStrokeJoin(Paint.Join.ROUND);
        // brilho
        pt.setColor(color);
        pt.setAlpha(150);
        pt.setStrokeWidth(ts * 0.34f);
        pt.setMaskFilter(new BlurMaskFilter(Math.max(1f, ts * 0.32f), BlurMaskFilter.Blur.NORMAL));
        c.drawPath(p, pt);
        pt.setMaskFilter(null);
        // tubo
        pt.setAlpha(255);
        pt.setStrokeWidth(ts * 0.16f);
        c.drawPath(p, pt);
        // núcleo claro
        pt.setColor(mix(color, Color.WHITE, 0.6f));
        pt.setStrokeWidth(ts * 0.05f);
        c.drawPath(p, pt);

        // porta da base
        for (int y = 0; y < m.h; y++) {
            for (int x = 0; x < m.w; x++) {
                if (m.get(x, y) != Maze.DOOR) continue;
                pt.setColor(0xFFFF8AD8);
                pt.setStrokeWidth(ts * 0.14f);
                float cy = (y + 0.5f) * ts;
                c.drawLine(x * ts, cy, (x + 1) * ts, cy, pt);
            }
        }
        return bmp;
    }

    /**
     * Adiciona a borda de um tile de parede voltada para (nx,ny) se o vizinho for corredor.
     * As pontas se estendem ou recuam para formar cantos internos e externos fechados.
     */
    private void addEdge(Path p, Maze m, int x, int y, int nx, int ny, float in) {
        if (m.isWall(x + nx, y + ny) || outside(m, x + nx, y + ny)) return;
        int tx = -ny, ty = nx; // tangente
        float b = 0.5f - in;
        float a1 = endOffset(m, x, y, tx, ty, nx, ny, in);
        float a0 = -endOffset(m, x, y, -tx, -ty, nx, ny, in);
        float cx = (x + 0.5f), cy = (y + 0.5f);
        p.moveTo((cx + a0 * tx + b * nx) * ts, (cy + a0 * ty + b * ny) * ts);
        p.lineTo((cx + a1 * tx + b * nx) * ts, (cy + a1 * ty + b * ny) * ts);
    }

    private static boolean outside(Maze m, int x, int y) {
        return y < 0 || y >= m.h || x < 0 || x >= m.w;
    }

    private static boolean solid(Maze m, int x, int y) {
        return outside(m, x, y) ? true : m.isWall(x, y);
    }

    private static float endOffset(Maze m, int x, int y, int tx, int ty, int nx, int ny, float in) {
        if (!solid(m, x + tx, y + ty)) return 0.5f - in;          // canto externo
        if (solid(m, x + tx + nx, y + ty + ny)) return 0.5f + in; // canto interno
        return 0.5f;                                               // reta
    }

    private void drawDots(Canvas c) {
        Maze m = game.maze;
        fill.setShader(null);
        float pulse = 0.25f + 0.06f * (float) Math.sin(game.time * 7);
        for (int y = 0; y < m.h; y++) {
            for (int x = 0; x < m.w; x++) {
                int t = m.get(x, y);
                if (t == Maze.DOT) {
                    fill.setColor(0xFFFFE9C4);
                    c.drawCircle(sx(x), sy(y), ts * 0.1f, fill);
                } else if (t == Maze.POWER) {
                    float cx = sx(x), cy = sy(y);
                    glow(c, cx, cy, ts * 0.75f, 0xFF7CFFF0, 110);
                    fill.setShader(null);
                    fill.setColor(0xFFB8FFF8);
                    c.drawCircle(cx, cy, ts * pulse, fill);
                    fill.setColor(Color.WHITE);
                    c.drawCircle(cx, cy, ts * pulse * 0.45f, fill);
                }
            }
        }
    }

    private void glow(Canvas c, float cx, float cy, float radius, int color, int alpha) {
        int col = (color & 0x00FFFFFF) | (alpha << 24);
        fill.setShader(new RadialGradient(cx, cy, radius, col, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, radius, fill);
        fill.setShader(null);
    }

    // --- personagens

    /** Bit: robozinho redondo com visor e antena. */
    private void drawPlayer(Canvas c, float cx, float cy, float r, Dir facing, boolean moving, float dying, float time) {
        if (dying >= 0) {
            fill.setShader(null);
            for (int k = 0; k < 12; k++) {
                double ang = k * Math.PI / 6 + dying * 2;
                float dist = r * (0.3f + 3.2f * dying);
                fill.setColor(k % 2 == 0 ? PLAYER_COLOR : 0xFF7CFFF0);
                fill.setAlpha((int) (255 * (1 - dying)));
                c.drawCircle(cx + (float) Math.cos(ang) * dist, cy + (float) Math.sin(ang) * dist, r * 0.16f * (1.2f - dying), fill);
            }
            fill.setAlpha(255);
            r *= (1 - dying);
            if (r < 1) return;
        }
        float squash = moving ? 0.06f * (float) Math.sin(time * 24) : 0;
        glow(c, cx, cy, r * 2f, PLAYER_COLOR, 70);

        // antena
        float tipX = cx + facing.dx * r * 0.28f, tipY = cy - r * 1.42f;
        stroke.setColor(0xFF5FC23A);
        stroke.setStrokeWidth(r * 0.14f);
        c.drawLine(cx, cy - r * 0.8f, tipX, tipY, stroke);
        fill.setColor(((int) (time * 3) & 1) == 0 ? 0xFFFFEE55 : 0xFFFF7A3D);
        c.drawCircle(tipX, tipY, r * 0.17f, fill);

        // corpo
        rect.set(cx - r * (1 + squash), cy - r * (1 - squash), cx + r * (1 + squash), cy + r * (1 - squash));
        fill.setColor(PLAYER_COLOR);
        c.drawOval(rect, fill);
        stroke.setColor(0xFF3E9E2A);
        stroke.setStrokeWidth(r * 0.12f);
        c.drawOval(rect, stroke);

        // visor olhando para a direção do movimento
        float vx = cx + facing.dx * r * 0.32f, vy = cy + facing.dy * r * 0.3f - r * 0.08f;
        float vw = facing.dy != 0 ? r * 1.25f : r * 1.05f;
        rect.set(vx - vw / 2, vy - r * 0.3f, vx + vw / 2, vy + r * 0.3f);
        fill.setColor(0xFF0E2A1A);
        c.drawRoundRect(rect, r * 0.3f, r * 0.3f, fill);
        boolean blink = ((int) (time * 10) % 37) == 0;
        fill.setColor(0xFF7CFFF0);
        float ex = facing.dy != 0 ? r * 0.3f : r * 0.24f;
        float shift = facing.dx * r * 0.08f;
        if (blink) {
            stroke.setColor(0xFF7CFFF0);
            stroke.setStrokeWidth(r * 0.08f);
            c.drawLine(vx - ex - r * 0.1f + shift, vy, vx - ex + r * 0.1f + shift, vy, stroke);
            c.drawLine(vx + ex - r * 0.1f + shift, vy, vx + ex + r * 0.1f + shift, vy, stroke);
        } else {
            c.drawCircle(vx - ex + shift, vy, r * 0.13f, fill);
            c.drawCircle(vx + ex + shift, vy, r * 0.13f, fill);
        }
    }

    /** Drone: corpo hexagonal, olho único e dois rotores. */
    private void drawEnemy(Canvas c, Enemy e, float cx, float cy, float r, float time) {
        cy += (float) Math.sin(time * 6 + e.id * 1.7) * r * 0.08f;
        int col = ENEMY_COLORS[e.id];
        Dir f = e.facing;
        if (e.isEyes()) {
            drawEye(c, cx, cy, r, f, 0xFF0A0A1A);
            return;
        }
        boolean scared = e.frightened;
        boolean flashing = scared && game.frightTimer < 2f && ((int) (time * 8) & 1) == 1;
        int body = scared ? (flashing ? 0xFFEDEDFF : 0xFF2B3BFF) : col;
        glow(c, cx, cy, r * 2f, body, 70);

        // rotores
        stroke.setColor(mix(body, Color.WHITE, 0.4f));
        stroke.setStrokeWidth(r * 0.1f);
        float spin = Math.abs((float) Math.sin(time * 38 + e.id));
        for (int s = -1; s <= 1; s += 2) {
            float rx = cx + s * r * 0.55f, ry = cy - r * 0.98f;
            c.drawLine(cx + s * r * 0.4f, cy - r * 0.6f, rx, ry, stroke);
            fill.setColor(mix(body, Color.WHITE, 0.55f));
            fill.setAlpha(200);
            rect.set(rx - r * (0.1f + 0.38f * spin), ry - r * 0.08f, rx + r * (0.1f + 0.38f * spin), ry + r * 0.08f);
            c.drawOval(rect, fill);
            fill.setAlpha(255);
        }

        // corpo hexagonal arredondado
        path.reset();
        for (int k = 0; k < 6; k++) {
            double ang = Math.PI / 3 * k;
            float px = cx + (float) Math.cos(ang) * r;
            float py = cy + (float) Math.sin(ang) * r * 0.88f;
            if (k == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        enemyBody.setStyle(Paint.Style.FILL);
        enemyBody.setPathEffect(new CornerPathEffect(r * 0.35f));
        enemyBody.setColor(body);
        c.drawPath(path, enemyBody);
        // faixa inferior
        enemyBody.setColor(mix(body, Color.BLACK, 0.35f));
        rect.set(cx - r * 0.55f, cy + r * 0.45f, cx + r * 0.55f, cy + r * 0.62f);
        c.drawRoundRect(rect, r * 0.1f, r * 0.1f, enemyBody);

        if (scared) {
            int fc = flashing ? 0xFFFF3B5C : 0xFFFFD0E0;
            fill.setColor(fc);
            c.drawCircle(cx - r * 0.3f, cy - r * 0.15f, r * 0.12f, fill);
            c.drawCircle(cx + r * 0.3f, cy - r * 0.15f, r * 0.12f, fill);
            stroke.setColor(fc);
            stroke.setStrokeWidth(r * 0.08f);
            path.reset();
            path.moveTo(cx - r * 0.5f, cy + r * 0.25f);
            for (int k = 1; k <= 6; k++) path.lineTo(cx - r * 0.5f + k * r / 6f, cy + r * ((k & 1) == 1 ? 0.1f : 0.25f));
            c.drawPath(path, stroke);
        } else {
            drawEye(c, cx, cy - r * 0.05f, r, f, 0xFF140A1E);
        }
    }

    private void drawEye(Canvas c, float cx, float cy, float r, Dir f, int pupil) {
        fill.setShader(null);
        fill.setColor(Color.WHITE);
        c.drawCircle(cx, cy, r * 0.42f, fill);
        fill.setColor(pupil);
        c.drawCircle(cx + f.dx * r * 0.18f, cy + f.dy * r * 0.18f, r * 0.21f, fill);
        fill.setColor(Color.WHITE);
        c.drawCircle(cx + f.dx * r * 0.18f - r * 0.07f, cy + f.dy * r * 0.18f - r * 0.07f, r * 0.06f, fill);
    }

    private void drawBonus(Canvas c, float cx, float cy, float r) {
        float spin = (float) Math.cos(game.time * 3);
        float w = r * 0.75f * Math.max(0.15f, Math.abs(spin));
        glow(c, cx, cy, r * 2f, 0xFFFF6BF0, 90);
        path.reset();
        path.moveTo(cx, cy - r);
        path.lineTo(cx + w, cy - r * 0.2f);
        path.lineTo(cx, cy + r);
        path.lineTo(cx - w, cy - r * 0.2f);
        path.close();
        fill.setShader(new LinearGradient(cx, cy - r, cx, cy + r, 0xFFFFC2F7, 0xFF8A3DFF, Shader.TileMode.CLAMP));
        c.drawPath(path, fill);
        fill.setShader(null);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(r * 0.08f);
        c.drawLine(cx - w, cy - r * 0.2f, cx + w, cy - r * 0.2f, stroke);
    }

    private void drawPopups(Canvas c) {
        text.setTextSize(ts * 0.7f);
        for (int i = 0; i < game.popT.length; i++) {
            float t = game.popT[i];
            if (t <= 0) continue;
            text.setColor(0xFF7CFFF0);
            text.setAlpha((int) (255 * Math.min(1f, t * 2)));
            c.drawText(String.valueOf(game.popV[i]), sx(game.popX[i]), sy(game.popY[i]) - (1.2f - t) * ts * 0.8f, text);
        }
        text.setAlpha(255);
    }

    // --- HUD

    private void drawHud(Canvas c) {
        float left = offX + ts * 0.3f;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(ts * 0.62f);
        text.setColor(0xFF8C86B8);
        c.drawText("PONTOS", left, hudTop + ts * 1.2f, text);
        text.setTextSize(ts * 1.05f);
        text.setColor(Color.WHITE);
        c.drawText(String.valueOf(game.score), left, hudTop + ts * 2.45f, text);

        float mid = offX + ts * game.maze.w * 0.42f;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(ts * 0.62f);
        text.setColor(0xFF8C86B8);
        c.drawText("RECORDE", mid, hudTop + ts * 1.2f, text);
        text.setTextSize(ts * 1.05f);
        text.setColor(0xFFFFE45C);
        c.drawText(String.valueOf(game.highScore), mid, hudTop + ts * 2.45f, text);

        // botões
        drawButton(c, soundBtn);
        float scx = soundBtn.centerX(), scy = soundBtn.centerY(), u = soundBtn.width() * 0.12f;
        fill.setColor(Color.WHITE);
        path.reset();
        path.moveTo(scx - u * 2.6f, scy - u);
        path.lineTo(scx - u * 1.4f, scy - u);
        path.lineTo(scx + u * 0.2f, scy - u * 2.4f);
        path.lineTo(scx + u * 0.2f, scy + u * 2.4f);
        path.lineTo(scx - u * 1.4f, scy + u);
        path.lineTo(scx - u * 2.6f, scy + u);
        path.close();
        c.drawPath(path, fill);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(u * 0.6f);
        if (sfx.muted) {
            c.drawLine(scx + u * 1.1f, scy - u, scx + u * 2.9f, scy + u, stroke);
            c.drawLine(scx + u * 1.1f, scy + u, scx + u * 2.9f, scy - u, stroke);
        } else {
            rect.set(scx - u * 1.2f, scy - u * 2f, scx + u * 2.2f, scy + u * 2f);
            c.drawArc(rect, -45, 90, false, stroke);
            rect.set(scx - u * 0.4f, scy - u * 1.1f, scx + u * 1.3f, scy + u * 1.1f);
            c.drawArc(rect, -45, 90, false, stroke);
        }

        boolean inGame = game.state != Game.State.TITLE && game.state != Game.State.GAME_OVER;
        if (inGame) {
            drawButton(c, pauseBtn);
            float pcx = pauseBtn.centerX(), pcy = pauseBtn.centerY(), q = pauseBtn.width() * 0.12f;
            fill.setColor(Color.WHITE);
            if (paused) {
                path.reset();
                path.moveTo(pcx - q * 1.3f, pcy - q * 2f);
                path.lineTo(pcx + q * 2f, pcy);
                path.lineTo(pcx - q * 1.3f, pcy + q * 2f);
                path.close();
                c.drawPath(path, fill);
            } else {
                rect.set(pcx - q * 1.6f, pcy - q * 2f, pcx - q * 0.4f, pcy + q * 2f);
                c.drawRoundRect(rect, q * 0.3f, q * 0.3f, fill);
                rect.set(pcx + q * 0.4f, pcy - q * 2f, pcx + q * 1.6f, pcy + q * 2f);
                c.drawRoundRect(rect, q * 0.3f, q * 0.3f, fill);
            }
        }

        // rodapé: vidas e fase
        float footY = mazeTop + ts * game.maze.h + ts * 1.15f;
        if (inGame) {
            int shown = Math.min(game.lives - 1, 6); // vidas reserva
            for (int i = 0; i < shown; i++) {
                drawPlayer(c, offX + ts * (1.1f + i * 1.5f), footY, ts * 0.5f, Dir.RIGHT, false, -1f, 0);
            }
        }
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(ts * 0.75f);
        text.setColor(0xFF8C86B8);
        c.drawText("FASE " + game.level, offX + ts * game.maze.w - ts * 0.3f, footY + ts * 0.3f, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    private void drawButton(Canvas c, RectF r) {
        fill.setShader(null);
        fill.setColor(0x33FFFFFF);
        c.drawRoundRect(r, r.width() * 0.25f, r.width() * 0.25f, fill);
    }

    // --- telas

    private void banner(Canvas c, String title, int color, String sub) {
        float cy = mazeTop + ts * (game.maze.homeY + 4.6f);
        fill.setShader(null);
        fill.setColor(0xCC06050E);
        rect.set(offX + ts, cy - ts * 1.3f, offX + ts * (game.maze.w - 1), cy + (sub != null ? ts * 1.6f : ts * 0.6f));
        c.drawRoundRect(rect, ts * 0.4f, ts * 0.4f, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(ts * 1.15f);
        text.setColor(color);
        c.drawText(title, viewW / 2f, cy, text);
        if (sub != null) {
            text.setTextSize(ts * 0.6f);
            text.setColor(0xFFCFCBEA);
            c.drawText(sub, viewW / 2f, cy + ts * 1.1f, text);
        }
    }

    private void drawTitle(Canvas c) {
        fill.setShader(null);
        fill.setColor(0xE606050E);
        c.drawRect(0, 0, viewW, viewH, fill);
        float cx = viewW / 2f;
        float y = mazeTop + ts * 2.2f;

        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(ts * 2.0f);
        text.setShader(new LinearGradient(cx - ts * 6, 0, cx + ts * 6, 0, 0xFF7CFFF0, 0xFFFF4FD8, Shader.TileMode.CLAMP));
        c.drawText("LABIRINTO", cx, y, text);
        c.drawText("NEON", cx, y + ts * 2.1f, text);
        text.setShader(null);

        y += ts * 4.6f;
        drawPlayer(c, offX + ts * 3.2f, y, ts * 0.55f, Dir.RIGHT, true, -1f, game.time);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(ts * 0.8f);
        text.setColor(PLAYER_COLOR);
        c.drawText("BIT", offX + ts * 4.8f, y - ts * 0.05f, text);
        text.setTextSize(ts * 0.55f);
        text.setColor(0xFFCFCBEA);
        c.drawText("você, o robô coletor", offX + ts * 4.8f, y + ts * 0.7f, text);

        for (int i = 0; i < 4; i++) {
            y += ts * 2.0f;
            Enemy fake = game.enemies[i];
            boolean wasScared = fake.frightened;
            Dir wasFacing = fake.facing;
            Enemy.State wasState = fake.state;
            fake.frightened = false;
            fake.facing = Dir.LEFT;
            fake.state = Enemy.State.ACTIVE;
            drawEnemy(c, fake, offX + ts * 3.2f, y, ts * 0.55f, game.time);
            fake.frightened = wasScared;
            fake.facing = wasFacing;
            fake.state = wasState;
            text.setTextSize(ts * 0.8f);
            text.setColor(ENEMY_COLORS[i]);
            c.drawText(ENEMY_NAMES[i], offX + ts * 4.8f, y - ts * 0.05f, text);
            text.setTextSize(ts * 0.55f);
            text.setColor(0xFFCFCBEA);
            c.drawText(ENEMY_DESC[i], offX + ts * 4.8f, y + ts * 0.7f, text);
        }

        text.setTextAlign(Paint.Align.CENTER);
        y += ts * 2.2f;
        text.setTextSize(ts * 0.58f);
        text.setColor(0xFFCFCBEA);
        c.drawText("Deslize o dedo para mover", cx, y, text);
        c.drawText("Colete todos os cristais", cx, y + ts * 0.9f, text);
        c.drawText("Núcleos deixam os drones vulneráveis", cx, y + ts * 1.8f, text);

        if (((int) (game.time * 2.5f) & 1) == 0) {
            text.setTextSize(ts * 0.95f);
            text.setColor(0xFFFFE45C);
            c.drawText("TOQUE PARA COMEÇAR", cx, y + ts * 3.6f, text);
        }
    }

    private void drawGameOver(Canvas c) {
        fill.setShader(null);
        fill.setColor(0x99000000);
        c.drawRect(0, 0, viewW, viewH, fill);
        boolean record = game.score > 0 && game.score >= game.highScore;
        banner(c, "FIM DE JOGO", 0xFFFF3B5C,
                game.stateTime > 1f ? (record ? "NOVO RECORDE! toque para jogar" : "toque para jogar de novo") : null);
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
                | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }
}
