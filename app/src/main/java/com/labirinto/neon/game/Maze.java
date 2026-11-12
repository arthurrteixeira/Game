package com.labirinto.neon.game;

/**
 * Labirinto original do jogo.
 * Legenda: # parede, . cristal, o núcleo de energia, - porta da base,
 * H interior da base, P início do jogador, espaço = corredor vazio.
 * Linhas com espaço nas bordas são túneis que dão a volta na tela.
 */
public final class Maze {
    public static final int WALL = 0, EMPTY = 1, DOT = 2, POWER = 3, DOOR = 4, HOUSE = 5;

    static final String[] LAYOUT = {
        "###################",
        "#o...#.......#...o#",
        "#.##.#.#####.#.##.#",
        "#.................#",
        "#.##.##.###.##.##.#",
        "#....#...#...#....#",
        "####.#.#.#.#.#.####",
        "####...#...#...####",
        "####.###.#.###.####",
        "####.#       #.####",
        "####.# ##-## #.####",
        "    .  #HHH#  .    ",
        "####.# ##### #.####",
        "####.#   P   #.####",
        "####.# ##### #.####",
        "#........#........#",
        "#.##.###.#.###.##.#",
        "#o.#.....#.....#.o#",
        "##.#.#.#####.#.#.##",
        "#....#...#...#....#",
        "#.######.#.######.#",
        "#.................#",
        "###################",
    };

    public final int w = LAYOUT[0].length();
    public final int h = LAYOUT.length;
    private final int[] tiles = new int[w * h];
    public int dotsLeft, totalDots;
    public int playerStartX, playerStartY;
    /** Saída da base (logo acima da porta) e centro da base. */
    public int exitX, exitY, homeX, homeY;
    /** Linha do túnel (-1 se não houver). */
    public int tunnelRow = -1;

    public Maze() {
        reset();
    }

    public void reset() {
        dotsLeft = 0;
        for (int y = 0; y < h; y++) {
            String row = LAYOUT[y];
            if (row.charAt(0) != '#') tunnelRow = y;
            for (int x = 0; x < w; x++) {
                char c = row.charAt(x);
                int t;
                switch (c) {
                    case '#': t = WALL; break;
                    case '.': t = DOT; dotsLeft++; break;
                    case 'o': t = POWER; dotsLeft++; break;
                    case '-': t = DOOR; exitX = x; exitY = y - 1; homeX = x; homeY = y + 1; break;
                    case 'H': t = HOUSE; break;
                    case 'P': t = EMPTY; playerStartX = x; playerStartY = y; break;
                    default: t = EMPTY;
                }
                tiles[y * w + x] = t;
            }
        }
        totalDots = dotsLeft;
    }

    public int wrapX(int x) {
        return ((x % w) + w) % w;
    }

    public int get(int x, int y) {
        if (y < 0 || y >= h) return WALL;
        return tiles[y * w + wrapX(x)];
    }

    /** Corredor livre para o jogador e para inimigos fora da base. */
    public boolean isOpen(int x, int y) {
        int t = get(x, y);
        return t == EMPTY || t == DOT || t == POWER;
    }

    public boolean isWall(int x, int y) {
        return get(x, y) == WALL;
    }

    /** Coleta o item do tile e devolve o que havia nele. */
    public int eat(int x, int y) {
        if (y < 0 || y >= h) return EMPTY;
        int i = y * w + wrapX(x);
        int t = tiles[i];
        if (t == DOT || t == POWER) {
            tiles[i] = EMPTY;
            dotsLeft--;
        }
        return t;
    }
}
