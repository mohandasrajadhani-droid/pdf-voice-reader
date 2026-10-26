package app.pdfvoicereader.malayalam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Turns sentences into one audio file (AAC in .m4a) using the phone's speech engines:
 * each sentence is synthesised to a WAV file, converted to 24 kHz mono, and fed to the
 * phone's AAC encoder. The result is saved in Music/PDF Voice Reader.
 */
public class AudioExporter {

    public interface Callback {
        void progress(int done, int total);

        void done(String uri, String name, String where, long bytes, double seconds, int skipped);

        void failed(String message, boolean cancelled);
    }

    private static final int RATE = 24000;
    private static final int GAP_MS = 220;
    private static AudioExporter running;

    private final Context ctx;
    private volatile boolean cancelled = false;
    private final Map<String, TextToSpeech> engines = new HashMap<>();
    private final Map<String, Map<String, Voice>> voiceMaps = new HashMap<>();
    private final Map<String, CountDownLatch> waits = new HashMap<>();
    private final Map<String, Boolean> results = new HashMap<>();

    private MediaCodec codec;
    private MediaMuxer muxer;
    private int track = -1;
    private boolean muxing = false;
    private long samplesIn = 0;

    private AudioExporter(Context c) {
        ctx = c.getApplicationContext();
    }

    public static synchronized boolean start(Context c, List<ReaderEngine.Item> items, String name, Callback cb) {
        if (running != null) return false;
        AudioExporter ex = new AudioExporter(c);
        running = ex;
        new Thread(() -> {
            try {
                ex.run(items, name, cb);
            } finally {
                synchronized (AudioExporter.class) {
                    running = null;
                }
            }
        }, "audio-export").start();
        return true;
    }

    public static synchronized void cancel() {
        if (running != null) running.cancelled = true;
    }

    // ------------------------------------------------------------------ main loop

    private void run(List<ReaderEngine.Item> items, String name, Callback cb) {
        File work = new File(ctx.getCacheDir(), "export");
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();
        File seg = new File(work, "seg.wav");
        File out = new File(work, "out.m4a");
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        int skipped = 0;
        try {
            startEncoder(out);
            short[] gap = new short[RATE * GAP_MS / 1000];
            for (int k = 0; k < items.size(); k++) {
                if (cancelled) throw new InterruptedException("cancelled");
                ReaderEngine.Item it = items.get(k);
                short[] pcm = null;
                if (it.text != null && !it.text.trim().isEmpty()) {
                    pcm = synth(it, seg);
                    if (pcm == null) pcm = synth(it, seg); // one retry (network voices can hiccup)
                }
                if (pcm == null) {
                    skipped++;
                } else {
                    feed(pcm);
                    // the item's own pause ("Natural reading"), else a short default gap
                    feed(it.pause > 0 ? new short[RATE * it.pause / 1000] : gap);
                }
                cb.progress(k + 1, items.size());
            }
            if (samplesIn == 0) throw new IllegalStateException("No sentence could be turned into audio. Try another voice in Voice settings.");
            finishEncoder();
            if (cancelled) throw new InterruptedException("cancelled");
            String[] saved = publish(out, safeName(name));
            cb.done(saved[0], saved[1], saved[2], out.length(), samplesIn / (double) RATE, skipped);
        } catch (InterruptedException e) {
            releaseEncoder();
            cb.failed("cancelled", true);
        } catch (Exception e) {
            releaseEncoder();
            cb.failed(e.getMessage() == null ? e.toString() : e.getMessage(), false);
        } finally {
            for (TextToSpeech t : engines.values()) {
                try {
                    t.shutdown();
                } catch (Exception ignored) {
                }
            }
            //noinspection ResultOfMethodCallIgnored
            seg.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    private static String safeName(String n) {
        String s = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|]+", " ").trim();
        if (s.isEmpty()) s = "PDF Voice Reader";
        if (!s.toLowerCase(Locale.ROOT).endsWith(".m4a")) s += ".m4a";
        return s;
    }

    // ------------------------------------------------------------------ speech to WAV

    private TextToSpeech engine(String pkg) throws InterruptedException {
        TextToSpeech t = engines.get(pkg);
        if (t != null) return t;
        CountDownLatch ready = new CountDownLatch(1);
        final int[] status = {TextToSpeech.ERROR};
        TextToSpeech.OnInitListener on = s -> {
            status[0] = s;
            ready.countDown();
        };
        t = pkg.isEmpty() ? new TextToSpeech(ctx, on) : new TextToSpeech(ctx, on, pkg);
        if (!ready.await(20, TimeUnit.SECONDS) || status[0] != TextToSpeech.SUCCESS) {
            throw new IllegalStateException("The speech engine " + (pkg.isEmpty() ? "" : pkg + " ") + "did not start.");
        }
        t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                finish(id, true);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onError(String id) {
                finish(id, false);
            }

            @Override
            public void onError(String id, int code) {
                finish(id, false);
            }
        });
        Map<String, Voice> vm = new HashMap<>();
        try {
            Set<Voice> vs = t.getVoices();
            if (vs != null) for (Voice v : vs) vm.put(v.getName(), v);
        } catch (Exception ignored) {
        }
        voiceMaps.put(pkg, vm);
        engines.put(pkg, t);
        return t;
    }

    private void finish(String id, boolean ok) {
        CountDownLatch l;
        synchronized (waits) {
            results.put(id, ok);
            l = waits.get(id);
        }
        if (l != null) l.countDown();
    }

    private int seq = 0;

    private short[] synth(ReaderEngine.Item it, File seg) throws InterruptedException {
        TextToSpeech t = engine(it.engine);
        boolean set = false;
        Voice v = voiceMaps.get(it.engine).get(it.voice);
        if (v != null) set = t.setVoice(v) == TextToSpeech.SUCCESS;
        if (!set && it.lang != null && !it.lang.isEmpty()) t.setLanguage(Locale.forLanguageTag(it.lang));
        t.setSpeechRate(it.rate);
        t.setPitch(it.pitch);
        //noinspection ResultOfMethodCallIgnored
        seg.delete();
        String id = "x" + (seq++);
        CountDownLatch l = new CountDownLatch(1);
        synchronized (waits) {
            waits.put(id, l);
        }
        int r = t.synthesizeToFile(it.text, new Bundle(), seg, id);
        if (r != TextToSpeech.SUCCESS) return null;
        long limit = 30000 + it.text.length() * 400L;
        while (!l.await(500, TimeUnit.MILLISECONDS)) {
            if (cancelled) {
                t.stop();
                throw new InterruptedException("cancelled");
            }
            limit -= 500;
            if (limit <= 0) {
                t.stop();
                return null;
            }
        }
        Boolean ok;
        synchronized (waits) {
            waits.remove(id);
            ok = results.remove(id);
        }
        if (ok == null || !ok || !seg.exists() || seg.length() < 64) return null;
        try {
            return readWav(seg);
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads a 16-bit PCM WAV file, mixes it to mono and resamples it to 24 kHz. */
    static short[] readWav(File f) throws Exception {
        byte[] all = new byte[(int) f.length()];
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            r.readFully(all);
        }
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        if (all.length < 44 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) throw new Exception("not wav");
        int pos = 12, channels = 1, rate = RATE, bits = 16, dataAt = -1, dataLen = 0, fmt = 1;
        while (pos + 8 <= all.length) {
            int id = b.getInt(pos), len = b.getInt(pos + 4);
            if (id == 0x20746d66) { // "fmt "
                fmt = b.getShort(pos + 8) & 0xffff;
                channels = b.getShort(pos + 10);
                rate = b.getInt(pos + 12);
                bits = b.getShort(pos + 22);
            } else if (id == 0x61746164) { // "data"
                dataAt = pos + 8;
                dataLen = len;
                if (dataLen <= 0 || dataAt + dataLen > all.length) dataLen = all.length - dataAt; // engines that never fill in the size
                break;
            }
            if (len < 0) break;
            pos += 8 + len + (len & 1);
        }
        if (dataAt < 0 || bits != 16 || (fmt != 1 && fmt != 0xFFFE) || channels < 1 || rate < 4000) throw new Exception("unsupported wav");
        int frames = dataLen / (2 * channels);
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int s = 0;
            for (int c = 0; c < channels; c++) s += b.getShort(dataAt + (i * channels + c) * 2);
            mono[i] = (short) (s / channels);
        }
        return resample(mono, rate, RATE);
    }

    static short[] resample(short[] in, int from, int to) {
        if (from == to || in.length == 0) return in;
        int n = (int) ((long) in.length * to / from);
        short[] out = new short[n];
        double step = (double) from / to;
        for (int i = 0; i < n; i++) {
            double x = i * step;
            int a = (int) x;
            int bIdx = Math.min(a + 1, in.length - 1);
            double f = x - a;
            out[i] = (short) Math.round(in[Math.min(a, in.length - 1)] * (1 - f) + in[bIdx] * f);
        }
        return out;
    }

    // ------------------------------------------------------------------ AAC encoder

    private void startEncoder(File out) throws Exception {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        samplesIn = 0;
    }

    private void feed(short[] pcm) {
        int off = 0;
        while (off < pcm.length) {
            int inIdx = codec.dequeueInputBuffer(10000);
            if (inIdx >= 0) {
                ByteBuffer in = codec.getInputBuffer(inIdx);
                if (in == null) continue;
                in.clear();
                int n = Math.min(pcm.length - off, in.remaining() / 2);
                n = Math.min(n, 4096);
                in.order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < n; i++) in.putShort(pcm[off + i]);
                long pts = samplesIn * 1000000L / RATE;
                codec.queueInputBuffer(inIdx, 0, n * 2, pts, 0);
                samplesIn += n;
                off += n;
            }
            drain(false);
        }
    }

    private void drain(boolean endOfStream) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idle = 0;
        while (true) {
            int outIdx = codec.dequeueOutputBuffer(info, endOfStream ? 10000 : 0);
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++idle > 300) return;
                continue;
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxing = true;
            } else if (outIdx >= 0) {
                ByteBuffer ob = codec.getOutputBuffer(outIdx);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (ob != null && info.size > 0 && muxing) {
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    muxer.writeSampleData(track, ob, info);
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    private void finishEncoder() {
        int inIdx;
        do {
            inIdx = codec.dequeueInputBuffer(10000);
            if (inIdx < 0) drain(false);
        } while (inIdx < 0);
        codec.queueInputBuffer(inIdx, 0, 0, samplesIn * 1000000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        drain(true);
        releaseEncoder();
    }

    private void releaseEncoder() {
        try {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        } catch (Exception ignored) {
        }
        codec = null;
        try {
            if (muxer != null) {
                if (muxing) muxer.stop();
                muxer.release();
            }
        } catch (Exception ignored) {
        }
        muxer = null;
        muxing = false;
    }

    // ------------------------------------------------------------------ saving

    /** Copies the finished file into Music/PDF Voice Reader. Returns {uri, name, where}. */
    private String[] publish(File f, String name) throws Exception {
        return FileSaver.save(ctx, f, name, "audio/mp4", true);
    }

    /** Small helper shared with the plugin: saves into Downloads or Music and returns a shareable uri. */
    public static class FileSaver {
        public static String[] save(Context ctx, File src, String name, String mime, boolean music) throws Exception {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver cr = ctx.getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                String dir = (music ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_DOWNLOADS) + "/PDF Voice Reader";
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, dir);
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri col = music ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri uri = cr.insert(col, cv);
                if (uri == null) throw new IllegalStateException("Android did not allow saving the file.");
                try (OutputStream os = cr.openOutputStream(uri); InputStream is = new FileInputStream(src)) {
                    if (os == null) throw new IllegalStateException("Android did not allow saving the file.");
                    copy(is, os);
                }
                cv.clear();
                cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, cv, null, null);
                String shown = name;
                try (android.database.Cursor c = cr.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) shown = c.getString(0);
                } catch (Exception ignored) {
                }
                return new String[]{uri.toString(), shown, (music ? "Music" : "Downloads") + " › PDF Voice Reader"};
            }
            File dir = new File(ctx.getExternalFilesDir(music ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_DOWNLOADS), "PDF Voice Reader");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File dst = new File(dir, name);
            try (InputStream is = new FileInputStream(src); OutputStream os = new FileOutputStream(dst)) {
                copy(is, os);
            }
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", dst);
            return new String[]{uri.toString(), name, "the app's " + (music ? "Music" : "Download") + " folder"};
        }

        static void copy(InputStream is, OutputStream os) throws java.io.IOException {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }
}
