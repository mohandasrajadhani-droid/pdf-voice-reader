package app.pdfvoicereader.malayalam;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Owns the phone's text-to-speech engines and the reading queue.
 * Each sentence can use a different engine (for example Google for Malayalam
 * and Samsung or eSpeak for English). The queue lives here, outside the web page,
 * so reading carries on page after page while the screen is off.
 */
public class ReaderEngine {

    public interface Listener {
        void onEvent(String name, String tag, String extra);
    }

    public static class Item {
        final String tag;
        final String text;
        final String lang;
        final String voice;
        final float rate;
        final float pitch;
        final int page;
        final String engine;

        Item(String tag, String text, String lang, String voice, float rate, float pitch, int page, String engine) {
            this.tag = tag;
            this.text = text;
            this.lang = lang;
            this.voice = voice;
            this.rate = rate;
            this.pitch = pitch;
            this.page = page;
            this.engine = engine == null ? "" : engine;
        }
    }

    /** One speech engine installed on the phone ("" = the phone's default engine). */
    private static class Eng {
        final String pkg;
        TextToSpeech tts;
        boolean ready = false;
        boolean initDone = false;
        final List<Runnable> waiting = new ArrayList<>();
        final Map<String, Voice> voices = new HashMap<>();

        Eng(String pkg) {
            this.pkg = pkg;
        }
    }

    /** How many sentences are handed to an engine ahead of the one being spoken. */
    private static final int AHEAD = 40;

    private static ReaderEngine instance;

    public static synchronized ReaderEngine get(Context context) {
        if (instance == null) instance = new ReaderEngine(context.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Map<String, Eng> engs = new HashMap<>();
    private final List<Item> items = new ArrayList<>();
    private final UtteranceProgressListener progress;
    private int current = 0;
    private int queuedUpTo = 0;
    private boolean playing = false;
    private boolean paused = false;
    private int session = 0;
    private int lastPage = -1;
    private String title = "PDF Voice Reader";
    private int totalPages = 0;
    private Listener listener;

    private ReaderEngine(Context context) {
        ctx = context;
        progress = new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                handleStart(utteranceId);
            }

            @Override
            public void onDone(String utteranceId) {
                handleDone(utteranceId, false);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onError(String utteranceId) {
                handleDone(utteranceId, true);
            }

            @Override
            public void onError(String utteranceId, int errorCode) {
                emit("error", tagOf(utteranceId), String.valueOf(errorCode));
                handleDone(utteranceId, true);
            }

            @Override
            public void onStop(String utteranceId, boolean interrupted) {
                // stopped on purpose (pause, stop, or a new queue)
            }
        };
        eng("");
    }

    // ---------------------------------------------------------------- engines

    private synchronized Eng eng(String pkg) {
        String key = pkg == null ? "" : pkg;
        Eng e = engs.get(key);
        if (e == null) {
            e = new Eng(key);
            engs.put(key, e);
            final Eng created = e;
            TextToSpeech.OnInitListener onInit = status -> {
                List<Runnable> run;
                synchronized (ReaderEngine.this) {
                    created.ready = status == TextToSpeech.SUCCESS;
                    created.initDone = true;
                    run = new ArrayList<>(created.waiting);
                    created.waiting.clear();
                }
                for (Runnable r : run) r.run();
            };
            created.tts = key.isEmpty() ? new TextToSpeech(ctx, onInit) : new TextToSpeech(ctx, onInit, key);
            created.tts.setOnUtteranceProgressListener(progress);
        }
        return e;
    }

    /** Runs the task once the engine has started (immediately if it already has). */
    public void whenReady(String pkg, Runnable r) {
        boolean now;
        synchronized (this) {
            Eng e = eng(pkg);
            now = e.initDone;
            if (!now) e.waiting.add(r);
        }
        if (now) r.run();
    }

    public void whenReady(Runnable r) {
        whenReady("", r);
    }

    /** Runs the task once every listed engine has started. */
    private void whenAllReady(Set<String> pkgs, Runnable r) {
        if (pkgs.isEmpty()) {
            r.run();
            return;
        }
        final int[] left = {pkgs.size()};
        for (String p : pkgs) {
            whenReady(p, () -> {
                boolean go;
                synchronized (left) {
                    left[0]--;
                    go = left[0] == 0;
                }
                if (go) r.run();
            });
        }
    }

    public synchronized boolean isReady(String pkg) {
        return eng(pkg).ready;
    }

    public synchronized boolean isReady() {
        return isReady("");
    }

    /** Package name of the phone's default engine. */
    public synchronized String defaultEngine() {
        try {
            String e = eng("").tts.getDefaultEngine();
            return e == null ? "" : e;
        } catch (Exception ex) {
            return "";
        }
    }

    public String engineName() {
        return defaultEngine();
    }

    public String currentEngine() {
        return defaultEngine();
    }

    /** The speech engines installed on the phone. */
    public synchronized List<Map<String, Object>> engines() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (TextToSpeech.EngineInfo e : eng("").tts.getEngines()) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", e.name);
                m.put("label", e.label == null ? e.name : e.label);
                out.add(m);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Starts an engine (kept for the page's engine picker). */
    public void setEngine(String pkg) {
        eng(pkg);
    }

    /** All voices one engine offers: name, language, quality, internet need, downloaded or not. */
    public synchronized List<Map<String, Object>> voices(String pkg) {
        List<Map<String, Object>> out = new ArrayList<>();
        Eng e = eng(pkg);
        if (!e.ready) return out;
        Set<Voice> set = null;
        try {
            set = e.tts.getVoices();
        } catch (Exception ignored) {
        }
        if (set == null) return out;
        e.voices.clear();
        for (Voice v : set) {
            e.voices.put(v.getName(), v);
            Map<String, Object> m = new HashMap<>();
            m.put("name", v.getName());
            m.put("lang", v.getLocale() == null ? "" : v.getLocale().toLanguageTag());
            m.put("quality", v.getQuality());
            m.put("network", v.isNetworkConnectionRequired());
            Set<String> f = v.getFeatures();
            m.put("notInstalled", f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> voices() {
        return voices("");
    }

    // ---------------------------------------------------------------- queue

    public synchronized void setListener(Listener l) {
        listener = l;
    }

    private void emit(String name, String tag, String extra) {
        Listener l;
        synchronized (this) {
            l = listener;
        }
        if (l != null) l.onEvent(name, tag == null ? "" : tag, extra == null ? "" : extra);
    }

    public synchronized boolean isPlaying() {
        return playing;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized String currentTag() {
        return current < items.size() ? items.get(current).tag : "";
    }

    /** Replace the queue and start reading from its first item. */
    public void setQueue(List<Item> list, String docTitle, int pages) {
        synchronized (this) {
            items.clear();
            items.addAll(list);
            title = docTitle == null || docTitle.isEmpty() ? "PDF Voice Reader" : docTitle;
            totalPages = pages;
            lastPage = -1;
        }
        playFrom(0);
    }

    /** Add sentences to the end of the queue (for pages finished by OCR later). */
    public void append(List<Item> list) {
        synchronized (this) {
            items.addAll(list);
            if (playing) topUp();
        }
    }

    public void playFrom(int index) {
        Set<String> needed = new HashSet<>();
        synchronized (this) {
            for (int i = Math.max(0, index); i < items.size(); i++) needed.add(items.get(i).engine);
        }
        whenAllReady(needed, () -> {
            synchronized (ReaderEngine.this) {
                session++;
                stopAll();
                current = Math.max(0, Math.min(index, items.size()));
                queuedUpTo = current;
                playing = current < items.size();
                paused = false;
                lastPage = -1;
                if (playing) {
                    if (!eng(items.get(current).engine).ready) {
                        playing = false;
                        emit("error", items.get(current).tag, "engine");
                        return;
                    }
                    ReaderService.setPlaying(ctx, title, pageText(items.get(current).page), true);
                    topUp();
                } else {
                    emit("end", "", "");
                }
            }
        });
    }

    public synchronized void pause() {
        if (!playing) return;
        playing = false;
        paused = true;
        session++;
        stopAll();
        int p = current < items.size() ? items.get(current).page : 0;
        ReaderService.setPlaying(ctx, title, "Paused · " + pageText(p), false);
        emit("paused", currentTag(), "");
    }

    public void resume() {
        int from;
        synchronized (this) {
            if (playing || items.isEmpty()) return;
            from = current;
        }
        playFrom(from);
        emit("resumed", currentTag(), "");
    }

    public synchronized void stop() {
        playing = false;
        paused = false;
        session++;
        stopAll();
        items.clear();
        current = 0;
        queuedUpTo = 0;
        ReaderService.stop(ctx);
    }

    /** Speaks one short sample (voice test), outside the reading queue. */
    public void sample(Item it) {
        whenReady(it.engine, () -> {
            synchronized (ReaderEngine.this) {
                Eng e = eng(it.engine);
                if (!e.ready) {
                    emit("error", "sample", "engine");
                    return;
                }
                if (playing) pause();
                session++;
                stopAll();
                applyVoice(e, it);
                e.tts.speak(it.text, TextToSpeech.QUEUE_FLUSH, new Bundle(), "sample");
            }
        });
    }

    // ---- internals (called with the lock held) ----

    private void stopAll() {
        for (Eng e : engs.values()) {
            try {
                if (e.tts != null) e.tts.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private String pageText(int page) {
        if (page <= 0) return "Reading";
        return totalPages > 0 ? "Page " + page + " of " + totalPages : "Page " + page;
    }

    private String engOf(int i) {
        return items.get(i).engine;
    }

    private void applyVoice(Eng e, Item it) {
        boolean set = false;
        if (it.voice != null && !it.voice.isEmpty()) {
            Voice v = e.voices.get(it.voice);
            if (v == null) {
                voices(e.pkg);
                v = e.voices.get(it.voice);
            }
            if (v != null) set = e.tts.setVoice(v) == TextToSpeech.SUCCESS;
        }
        if (!set && it.lang != null && !it.lang.isEmpty()) {
            e.tts.setLanguage(Locale.forLanguageTag(it.lang));
        }
        e.tts.setSpeechRate(it.rate);
        e.tts.setPitch(it.pitch);
    }

    /**
     * Hands the next sentences to their engine. Sentences for one engine are queued in a batch;
     * when the next sentence needs a different engine, it waits until this batch has been spoken,
     * so two engines never talk over each other.
     */
    private void topUp() {
        if (current >= items.size()) return;
        int start = Math.max(queuedUpTo, current);
        if (start >= items.size()) return;
        if (start > current && !engOf(start).equals(engOf(start - 1))) return;
        String key = engOf(start);
        Eng e = eng(key);
        int end = Math.min(items.size(), current + AHEAD);
        int i = start;
        for (; i < end && engOf(i).equals(key); i++) {
            Item it = items.get(i);
            applyVoice(e, it);
            e.tts.speak(it.text, TextToSpeech.QUEUE_ADD, new Bundle(), session + ":" + i);
        }
        if (i > queuedUpTo) queuedUpTo = i;
    }

    private int indexOf(String utteranceId, boolean[] sameSession) {
        if (utteranceId == null) return -1;
        int colon = utteranceId.indexOf(':');
        if (colon < 0) return -1;
        try {
            int s = Integer.parseInt(utteranceId.substring(0, colon));
            int i = Integer.parseInt(utteranceId.substring(colon + 1));
            sameSession[0] = s == session;
            return i;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String tagOf(String utteranceId) {
        synchronized (this) {
            boolean[] same = new boolean[1];
            int i = indexOf(utteranceId, same);
            return (same[0] && i >= 0 && i < items.size()) ? items.get(i).tag : "";
        }
    }

    private void handleStart(String utteranceId) {
        String tag;
        synchronized (this) {
            boolean[] same = new boolean[1];
            int i = indexOf(utteranceId, same);
            if (!same[0] || i < 0 || i >= items.size()) return;
            current = i;
            Item it = items.get(i);
            tag = it.tag;
            if (it.page != lastPage) {
                lastPage = it.page;
                ReaderService.setPlaying(ctx, title, pageText(it.page), true);
            }
            topUp();
        }
        emit("start", tag, "");
    }

    private void handleDone(String utteranceId, boolean failed) {
        boolean finished = false;
        synchronized (this) {
            boolean[] same = new boolean[1];
            int i = indexOf(utteranceId, same);
            if (!same[0] || i < 0) return;
            if (i >= items.size() - 1 && playing) {
                playing = false;
                current = items.size();
                finished = true;
            } else if (playing && i == queuedUpTo - 1) {
                // this engine's batch is done: hand the next sentences to the next engine
                current = i + 1;
                topUp();
            }
        }
        if (finished) {
            emit("end", "", failed ? "error" : "");
            ReaderService.stop(ctx);
        }
    }
}
