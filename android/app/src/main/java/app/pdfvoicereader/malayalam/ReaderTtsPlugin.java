package app.pdfvoicereader.malayalam;

import android.Manifest;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.content.pm.PackageManager;
import android.speech.tts.TextToSpeech;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bridge between the reader page and Android's speech engine. */
@CapacitorPlugin(name = "ReaderTts")
public class ReaderTtsPlugin extends Plugin {

    private ReaderEngine engine;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** A PDF handed to the app by WhatsApp, Files, Gmail…, copied into the app's cache. */
    private static JSObject incoming;

    @Override
    public void load() {
        if (getActivity() != null) handleIncoming(getActivity().getIntent());
        engine = ReaderEngine.get(getContext());
        engine.setListener((name, tag, extra) -> main.post(() -> {
            JSObject o = new JSObject();
            o.put("tag", tag);
            o.put("extra", extra);
            notifyListeners(name, o);
        }));
    }

    @PluginMethod
    public void getVoices(PluginCall call) {
        String pkg = call.getString("engine", "");
        engine.whenReady(pkg, () -> main.post(() -> {
            JSObject r = new JSObject();
            r.put("ok", engine.isReady(pkg));
            r.put("engine", pkg.isEmpty() ? engine.defaultEngine() : pkg);
            JSArray list = new JSArray();
            for (Map<String, Object> v : engine.voices(pkg)) {
                JSObject o = new JSObject();
                for (Map.Entry<String, Object> e : v.entrySet()) o.put(e.getKey(), e.getValue());
                list.put(o);
            }
            r.put("voices", list);
            call.resolve(r);
        }));
    }

    @PluginMethod
    public void getEngines(PluginCall call) {
        engine.whenReady(() -> main.post(() -> {
            JSObject r = new JSObject();
            r.put("current", engine.defaultEngine());
            r.put("default", engine.defaultEngine());
            JSArray list = new JSArray();
            for (Map<String, Object> e : engine.engines()) {
                JSObject o = new JSObject();
                for (Map.Entry<String, Object> x : e.entrySet()) o.put(x.getKey(), x.getValue());
                list.put(o);
            }
            r.put("engines", list);
            call.resolve(r);
        }));
    }

    @PluginMethod
    public void setEngine(PluginCall call) {
        String pkg = call.getString("engine", "");
        engine.setEngine(pkg);
        engine.whenReady(pkg, () -> main.post(() -> {
            JSObject r = new JSObject();
            r.put("ok", engine.isReady(pkg));
            r.put("current", pkg);
            call.resolve(r);
        }));
    }

    /** Opens Android's screen for downloading voice data (install=true) or the text-to-speech settings. */
    @PluginMethod
    public void openTtsSettings(PluginCall call) {
        boolean install = Boolean.TRUE.equals(call.getBoolean("install", true));
        if (getActivity() == null) {
            call.reject("no screen");
            return;
        }
        String[] actions = install
            ? new String[]{TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA, "com.android.settings.TTS_SETTINGS"}
            : new String[]{"com.android.settings.TTS_SETTINGS"};
        for (String action : actions) {
            try {
                Intent i = new Intent(action);
                String pkg = call.getString("engine", "");
                if (pkg == null || pkg.isEmpty()) pkg = engine.defaultEngine();
                if (TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA.equals(action) && pkg != null && !pkg.isEmpty()) i.setPackage(pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getActivity().startActivity(i);
                call.resolve();
                return;
            } catch (Exception ignored) {
            }
        }
        call.reject("Android's speech settings could not be opened");
    }

    @PluginMethod
    public void speak(PluginCall call) {
        List<ReaderEngine.Item> list = parseItems(call);
        if (list == null) return;
        boolean append = Boolean.TRUE.equals(call.getBoolean("append", false));
        if (append) {
            engine.append(list);
        } else {
            askNotificationPermission();
            String title = call.getString("title", "PDF Voice Reader");
            Integer pages = call.getInt("pages", 0);
            int firstPage = list.isEmpty() ? 0 : list.get(0).page;
            ReaderService.begin(getContext(), title, firstPage > 0 ? "Page " + firstPage : "Reading");
            engine.setQueue(list, title, pages == null ? 0 : pages);
        }
        call.resolve();
    }

    @PluginMethod
    public void sample(PluginCall call) {
        List<ReaderEngine.Item> list = parseItems(call);
        if (list == null) return;
        if (!list.isEmpty()) engine.sample(list.get(0));
        call.resolve();
    }

    @PluginMethod
    public void pause(PluginCall call) {
        engine.pause();
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        engine.stop();
        call.resolve();
    }

    @PluginMethod
    public void state(PluginCall call) {
        JSObject r = new JSObject();
        r.put("playing", engine.isPlaying());
        r.put("paused", engine.isPaused());
        r.put("tag", engine.currentTag());
        call.resolve(r);
    }

    @PluginMethod
    public void keepScreenOn(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", true));
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }
        call.resolve();
    }

    // ------------------------------------------------------------ sleep timer

    @PluginMethod
    public void setSleep(PluginCall call) {
        Double ms = call.getDouble("ms", 0.0);
        Integer page = call.getInt("page", 0);
        engine.setSleep(ms == null ? 0 : ms.longValue(), page == null ? 0 : page);
        call.resolve();
    }

    // ------------------------------------------------------------ save as audio

    @PluginMethod
    public void exportAudio(PluginCall call) {
        List<ReaderEngine.Item> list = parseItems(call);
        if (list == null) return;
        String name = call.getString("name", "PDF Voice Reader.m4a");
        boolean started = AudioExporter.start(getContext(), list, name, new AudioExporter.Callback() {
            private long last = 0;

            @Override
            public void progress(int done, int total) {
                long now = System.currentTimeMillis();
                if (done < total && now - last < 400) return;
                last = now;
                JSObject o = new JSObject();
                o.put("done", done);
                o.put("total", total);
                main.post(() -> notifyListeners("exportProgress", o));
            }

            @Override
            public void done(String uri, String shown, String where, long bytes, double seconds, int skipped) {
                JSObject o = new JSObject();
                o.put("uri", uri);
                o.put("name", shown);
                o.put("where", where);
                o.put("bytes", bytes);
                o.put("seconds", seconds);
                o.put("skipped", skipped);
                main.post(() -> notifyListeners("exportDone", o));
            }

            @Override
            public void failed(String message, boolean cancelled) {
                JSObject o = new JSObject();
                o.put("message", message);
                o.put("cancelled", cancelled);
                main.post(() -> notifyListeners("exportError", o));
            }
        });
        if (!started) {
            call.reject("An audio file is already being made.");
            return;
        }
        call.resolve();
    }

    @PluginMethod
    public void cancelExport(PluginCall call) {
        AudioExporter.cancel();
        call.resolve();
    }

    // ------------------------------------------------------------ files

    /** Saves a file made by the page (Word, text) into Downloads › PDF Voice Reader. */
    @PluginMethod
    public void saveFile(PluginCall call) {
        String name = call.getString("name", "document");
        String mime = call.getString("mime", "application/octet-stream");
        String data = call.getString("data", "");
        new Thread(() -> {
            File tmp = new File(getContext().getCacheDir(), "save.tmp");
            try {
                byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                try (OutputStream os = new FileOutputStream(tmp)) {
                    os.write(bytes);
                }
                String clean = name.replaceAll("[\\\\/:*?\"<>|]+", " ").trim();
                String[] r = AudioExporter.FileSaver.save(getContext(), tmp, clean.isEmpty() ? "document" : clean, mime, false);
                JSObject o = new JSObject();
                o.put("uri", r[0]);
                o.put("name", r[1]);
                o.put("where", r[2]);
                call.resolve(o);
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }, "save-file").start();
    }

    @PluginMethod
    public void openFile(PluginCall call) {
        fileIntent(call, Intent.ACTION_VIEW);
    }

    @PluginMethod
    public void shareFile(PluginCall call) {
        fileIntent(call, Intent.ACTION_SEND);
    }

    private void fileIntent(PluginCall call, String action) {
        String u = call.getString("uri", "");
        String mime = call.getString("mime", "*/*");
        if (u == null || u.isEmpty() || getActivity() == null) {
            call.reject("no file");
            return;
        }
        try {
            Uri uri = Uri.parse(u);
            Intent i = new Intent(action);
            if (Intent.ACTION_SEND.equals(action)) {
                i.setType(mime);
                i.putExtra(Intent.EXTRA_STREAM, uri);
            } else {
                i.setDataAndType(uri, mime);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.ACTION_SEND.equals(action) ? Intent.createChooser(i, "Share") : i;
            getActivity().startActivity(chooser);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? "No app can open this file" : e.getMessage());
        }
    }

    // ------------------------------------------------------------ "Open with" / "Share" a PDF into the app

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        handleIncoming(intent);
    }

    @PluginMethod
    public void takeIncoming(PluginCall call) {
        JSObject r;
        synchronized (ReaderTtsPlugin.class) {
            r = incoming;
            incoming = null;
        }
        call.resolve(r == null ? new JSObject() : r);
    }

    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        final List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_VIEW.equals(action)) {
            if (intent.getData() != null) uris.add(intent.getData());
        } else if (Intent.ACTION_SEND.equals(action)) {
            Object o = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (o instanceof Uri) uris.add((Uri) o);
            else {
                // text shared as words (not a file): read it like a text file
                CharSequence txt = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (txt != null && txt.length() > 0) {
                    shareText(txt.toString(), intent.getStringExtra(Intent.EXTRA_SUBJECT));
                    return;
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty()) return;
        if (getActivity() != null && intent == getActivity().getIntent()) getActivity().setIntent(new Intent(Intent.ACTION_MAIN));
        new Thread(() -> {
            JSObject r = new JSObject();
            try {
                File dir = freshIncomingDir();
                JSArray files = new JSArray();
                int k = 0;
                for (Uri src : uris) {
                    String name = displayName(src, "Shared file " + (k + 1));
                    File dst = new File(dir, "f" + (k++) + ".bin");
                    try (InputStream is = getContext().getContentResolver().openInputStream(src); OutputStream os = new FileOutputStream(dst)) {
                        if (is == null) throw new IllegalStateException("empty");
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    JSObject f = new JSObject();
                    f.put("path", dst.getAbsolutePath());
                    f.put("name", name);
                    files.put(f);
                }
                JSONObject first = files.getJSONObject(0);
                r.put("path", first.getString("path"));
                r.put("name", first.getString("name"));
                r.put("files", files);
            } catch (Exception e) {
                r.put("path", "x");
                r.put("error", "The file could not be opened from the other app. Save it to your phone first, then open it with Open file.");
            }
            deliver(r);
        }, "incoming-file").start();
    }

    private void shareText(String text, String subject) {
        new Thread(() -> {
            JSObject r = new JSObject();
            try {
                File dst = new File(freshIncomingDir(), "shared.txt");
                try (OutputStream os = new FileOutputStream(dst)) {
                    os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                String name = (subject == null || subject.trim().isEmpty() ? "Shared text" : subject.trim()) + ".txt";
                r.put("path", dst.getAbsolutePath());
                r.put("name", name);
            } catch (Exception e) {
                r.put("path", "x");
                r.put("error", "The shared text could not be opened.");
            }
            deliver(r);
        }, "incoming-text").start();
    }

    private File freshIncomingDir() {
        File dir = new File(getContext().getCacheDir(), "incoming");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private String displayName(Uri src, String fallback) {
        try (Cursor c = getContext().getContentResolver().query(src, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = src.getLastPathSegment();
        return last != null && !last.isEmpty() ? last : fallback;
    }

    private void deliver(JSObject r) {
        synchronized (ReaderTtsPlugin.class) {
            incoming = r;
        }
        main.post(() -> notifyListeners("incomingFile", new JSObject()));
    }

    private List<ReaderEngine.Item> parseItems(PluginCall call) {
        JSArray arr = call.getArray("items");
        if (arr == null) {
            call.reject("items missing");
            return null;
        }
        List<ReaderEngine.Item> out = new ArrayList<>();
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new ReaderEngine.Item(
                    o.optString("tag", String.valueOf(i)),
                    o.optString("text", ""),
                    o.optString("lang", ""),
                    o.optString("voice", ""),
                    (float) o.optDouble("rate", 1.0),
                    (float) o.optDouble("pitch", 1.0),
                    o.optInt("page", 0),
                    o.optString("engine", "")
                ));
            }
        } catch (Exception e) {
            call.reject("bad items: " + e.getMessage());
            return null;
        }
        return out;
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || getActivity() == null) return;
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7302);
        }
    }
}
