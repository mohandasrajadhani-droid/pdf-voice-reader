package app.pdfvoicereader.malayalam;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Owns Android's text-to-speech engine and the reading queue.
 * The queue lives here, outside the web page, so reading carries on
 * page after page while the screen is off and the page is asleep.
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

        Item(String tag, String text, String lang, String voice, float rate, float pitch, int page) {
            this.tag = tag;
            this.text = text;
            this.lang = lang;
            this.voice = voice;
            this.rate = rate;
            this.pitch = pitch;
            this.page = page;
        }
    }

    /** How many sentences are handed to the engine ahead of the one being spoken. */
    private static final int AHEAD = 40;

    private static ReaderEngine instance;

    public static synchronized ReaderEngine get(Context context) {
        if (instance == null) instance = new ReaderEngine(context.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private TextToSpeech tts;
    private boolean ready = false;
    private boolean initDone = false;
    private final List<Runnable> whenReady = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private final Map<String, Voice> voiceCache = new HashMap<>();
    private int current = 0;
    private int queuedUpTo = 0;
    private boolean playing = false;
    private boolean paused = false;
    private int session = 0;
    private int lastPage = -1;
    private String title = "PDF Voice Reader";
    private int totalPages = 0;
    private Listener listener;
    private String enginePkg = "";
    private int ttsGeneration = 0;

    private ReaderEngine(Context context) {
        ctx = context;
        createTts("");
    }

    /** Starts the speech engine: the phone's default one, or the engine with the given package name. */
    private void createTts(String pkg) {
        final int myGen;
        synchronized (this) {
            if (tts != null) {
                try {
                    tts.stop();
                    tts.shutdown();
                } catch (Exception ignored) {
                }
            }
            ready = false;
            initDone = false;
            voiceCache.clear();
            enginePkg = pkg == null ? "" : pkg;
            myGen = ++ttsGeneration;
        }
        TextToSpeech.OnInitListener onInit = status -> {
            List<Runnable> run;
            synchronized (ReaderEngine.this) {
                if (myGen != ttsGeneration) return;
                ready = status == TextToSpeech.SUCCESS;
                initDone = true;
                run = new ArrayList<>(whenReady);
                whenReady.clear();
            }
            for (Runnable r : run) r.run();
        };
        TextToSpeech created = enginePkg.isEmpty() ? new TextToSpeech(ctx, onInit) : new TextToSpeech(ctx, onInit, enginePkg);
        synchronized (this) {
            tts = created;
        }
        created.setOnUtteranceProgressListener(new UtteranceProgressListener() {
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
                // stopped on purpose (pause, stop, or a new queue); nothing to do
            }
        });
    }

    /** Switches to another speech engine installed on the phone (empty = the phone's default). */
    public void setEngine(String pkg) {
        synchronized (this) {
            if (playing) {
                playing = false;
                paused = true;
                session++;
            }
        }
        createTts(pkg);
    }

    /** The speech engines installed on the phone, and which one is in use. */
    public synchronized List<Map<String, Object>> engines() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (TextToSpeech.EngineInfo e : tts.getEngines()) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", e.name);
                m.put("label", e.label == null ? e.name : e.label);
                out.add(m);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized String currentEngine() {
        if (!enginePkg.isEmpty()) return enginePkg;
        return engineName();
    }

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

    /** Runs the task once the engine has started (immediately if it already has). */
    public void whenReady(Runnable r) {
        boolean now;
        synchronized (this) {
            now = initDone;
            if (!now) whenReady.add(r);
        }
        if (now) r.run();
    }

    public synchronized boolean isReady() {
        return ready;
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

    public synchronized String engineName() {
        try {
            String e = tts.getDefaultEngine();
            return e == null ? "" : e;
        } catch (Exception ex) {
            return "";
        }
    }

    /** All voices the phone offers: name, language, quality, and whether it needs internet. */
    public synchronized List<Map<String, Object>> voices() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!ready) return out;
        Set<Voice> set = null;
        try {
            set = tts.getVoices();
        } catch (Exception ignored) {
        }
        if (set == null) return out;
        voiceCache.clear();
        for (Voice v : set) {
            voiceCache.put(v.getName(), v);
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
        whenReady(() -> {
            synchronized (ReaderEngine.this) {
                if (!ready) {
                    emit("error", "", "engine");
                    return;
                }
                session++;
                tts.stop();
                current = Math.max(0, Math.min(index, items.size()));
                queuedUpTo = current;
                playing = current < items.size();
                paused = false;
                lastPage = -1;
                if (playing) {
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
        tts.stop();
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
        tts.stop();
        items.clear();
        current = 0;
        queuedUpTo = 0;
        ReaderService.stop(ctx);
    }

    /** Speaks one short sample (voice test), outside the reading queue. */
    public void sample(Item it) {
        whenReady(() -> {
            synchronized (ReaderEngine.this) {
                if (!ready) return;
                if (playing) pause();
                session++;
                tts.stop();
                applyVoice(it);
                tts.speak(it.text, TextToSpeech.QUEUE_FLUSH, new Bundle(), "sample");
            }
        });
    }

    // ---- internals (called with the lock held) ----

    private String pageText(int page) {
        if (page <= 0) return "Reading";
        return totalPages > 0 ? "Page " + page + " of " + totalPages : "Page " + page;
    }

    private void applyVoice(Item it) {
        boolean set = false;
        if (it.voice != null && !it.voice.isEmpty()) {
            Voice v = voiceCache.get(it.voice);
            if (v == null) {
                voices();
                v = voiceCache.get(it.voice);
            }
            if (v != null) set = tts.setVoice(v) == TextToSpeech.SUCCESS;
        }
        if (!set && it.lang != null && !it.lang.isEmpty()) {
            tts.setLanguage(Locale.forLanguageTag(it.lang));
        }
        tts.setSpeechRate(it.rate);
        tts.setPitch(it.pitch);
    }

    private void topUp() {
        int end = Math.min(items.size(), current + AHEAD);
        for (int i = Math.max(queuedUpTo, current); i < end; i++) {
            Item it = items.get(i);
            applyVoice(it);
            tts.speak(it.text, TextToSpeech.QUEUE_ADD, new Bundle(), session + ":" + i);
        }
        if (end > queuedUpTo) queuedUpTo = end;
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
            }
        }
        if (finished) {
            emit("end", "", failed ? "error" : "");
            ReaderService.stop(ctx);
        }
    }
}
