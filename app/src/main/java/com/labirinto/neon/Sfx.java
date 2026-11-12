package com.labirinto.neon;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.labirinto.neon.game.Game;

/** Efeitos sonoros sintetizados na hora (sem arquivos de áudio). */
final class Sfx {
    private static final int RATE = 22050;
    private final AudioTrack[] tracks = new AudioTrack[9];
    private boolean dotToggle;
    volatile boolean muted;

    Sfx() {
        try {
            tracks[Game.SND_DOT] = make(blip(520, 0.045f));
            tracks[8] = make(blip(390, 0.045f));
            tracks[Game.SND_POWER] = make(sweep(220, 880, 0.32f, 0.5f));
            tracks[Game.SND_EAT_ENEMY] = make(notes(new float[]{660, 880, 1100, 1320}, 0.06f, 0.5f));
            tracks[Game.SND_DEATH] = make(sweep(700, 90, 1.1f, 0.55f));
            tracks[Game.SND_CLEAR] = make(notes(new float[]{523, 659, 784, 1046, 784, 1046}, 0.1f, 0.45f));
            tracks[Game.SND_LIFE] = make(notes(new float[]{1046, 0, 1046, 0, 1318}, 0.08f, 0.45f));
            tracks[Game.SND_BONUS] = make(notes(new float[]{880, 1318}, 0.09f, 0.45f));
            tracks[Game.SND_START] = make(notes(new float[]{392, 523, 659, 523, 587, 784, 698, 880, 784, 0, 1046},
                    0.13f, 0.4f));
        } catch (Throwable ignored) {
            // Sem áudio disponível: o jogo segue mudo.
        }
    }

    void play(int id) {
        if (muted) return;
        if (id == Game.SND_DOT) {
            dotToggle = !dotToggle;
            if (dotToggle) id = 8;
        }
        AudioTrack t = tracks[id];
        if (t == null) return;
        try {
            if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) t.stop();
            t.reloadStaticData();
            t.play();
        } catch (Throwable ignored) {
        }
    }

    void release() {
        for (AudioTrack t : tracks) {
            if (t != null) {
                try {
                    t.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static AudioTrack make(short[] pcm) {
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.length * 2, AudioTrack.MODE_STATIC);
        t.write(pcm, 0, pcm.length);
        return t;
    }

    private static float env(int i, int n) {
        float a = Math.min(1f, i / (RATE * 0.004f));
        float r = Math.min(1f, (n - i) / (RATE * 0.02f));
        return Math.min(a, r);
    }

    private static short[] blip(float freq, float dur) {
        int n = (int) (RATE * dur);
        short[] out = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            float f = freq * (1f + 0.6f * i / n);
            ph += f / RATE;
            double tri = 4 * Math.abs(ph - Math.floor(ph + 0.5)) - 1;
            out[i] = (short) (tri * 0.35 * env(i, n) * Short.MAX_VALUE);
        }
        return out;
    }

    private static short[] sweep(float f0, float f1, float dur, float vol) {
        int n = (int) (RATE * dur);
        short[] out = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / n;
            float f = f0 + (f1 - f0) * t;
            f *= 1f + 0.03f * (float) Math.sin(i * 0.004);
            ph += f / RATE;
            double sq = (ph - Math.floor(ph)) < 0.5 ? 1 : -1;
            out[i] = (short) (sq * vol * 0.5 * env(i, n) * Short.MAX_VALUE);
        }
        return out;
    }

    private static short[] notes(float[] freqs, float each, float vol) {
        int per = (int) (RATE * each);
        short[] out = new short[per * freqs.length];
        double ph = 0;
        for (int k = 0; k < freqs.length; k++) {
            for (int i = 0; i < per; i++) {
                int j = k * per + i;
                if (freqs[k] <= 0) continue;
                ph += freqs[k] / RATE;
                double sq = (ph - Math.floor(ph)) < 0.5 ? 1 : -1;
                out[j] = (short) (sq * vol * 0.5 * env(i, per) * Short.MAX_VALUE);
            }
        }
        return out;
    }
}
