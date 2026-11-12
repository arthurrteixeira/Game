# Labirinto Neon

Jogo de labirinto para Android no estilo "come-pontos": você controla **Bit**, um
robozinho coletor, e precisa recolher todos os cristais do labirinto fugindo de
quatro drones caçadores.

## Como jogar

- **Deslize o dedo** para escolher a direção (dá para deslizar sem soltar o dedo; a curva fica guardada até ser possível virar).
- Colete todos os **cristais** para passar de fase.
- Os **núcleos de energia** (nos cantos) deixam os drones azuis e vulneráveis por alguns segundos — capture-os para ganhar 200, 400, 800 e 1600 pontos em sequência.
- Um **diamante bônus** aparece no centro duas vezes por fase.
- Vida extra aos 10.000 pontos. O recorde fica salvo no aparelho.
- Botões no topo: som liga/desliga e pausa. Também funciona com teclado/controle (setas, WASD, Enter, P).

### Os drones

| Drone  | Comportamento                                   |
|--------|--------------------------------------------------|
| Rubi   | persegue você diretamente e acelera no fim da fase |
| Malva  | mira 4 casas à sua frente para cortar o caminho |
| Safira | flanqueia, usando a posição da Rubi             |
| Âmbar  | persegue de longe, mas foge quando chega perto  |

Eles alternam entre ondas de **dispersão** (cada um vai para o seu canto) e de **perseguição**.
A cada fase todos ficam mais rápidos, saem antes da base e o efeito dos núcleos dura menos.

## Baixar o APK

Cada push dispara o workflow **Build APK** no GitHub Actions. Abra a aba *Actions*,
entre na execução mais recente e baixe o artefato `labirinto-neon-apk`
(um `.zip` com `app-debug.apk`). No celular, permita instalar apps de fontes
desconhecidas e abra o APK.

## Compilar localmente

Requer JDK 17 e o Android SDK (API 35):

```sh
./gradlew assembleDebug        # gera app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # instala no aparelho/emulador conectado
```

Ou abra a pasta no Android Studio e clique em *Run*.

## Estrutura

- `app/src/main/java/com/labirinto/neon/game/` — lógica pura do jogo (labirinto, movimento em grade, IA dos drones, pontuação). Não depende do Android.
- `GameView.java` — laço do jogo em `SurfaceView`, desenho neon e controles por toque.
- `Sfx.java` — efeitos sonoros sintetizados em tempo real (sem arquivos de áudio).
- `MainActivity.java` — tela cheia em modo retrato.

Sem dependências externas: apenas o SDK do Android. `minSdk` 21 (Android 5.0).
