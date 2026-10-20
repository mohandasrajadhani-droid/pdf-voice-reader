package app.pdfvoicereader.malayalam;

import android.Manifest;
import android.content.Intent;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bridge between the reader page and Android's speech engine. */
@CapacitorPlugin(name = "ReaderTts")
public class ReaderTtsPlugin extends Plugin {

    private ReaderEngine engine;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void load() {
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
        engine.whenReady(() -> main.post(() -> {
            JSObject r = new JSObject();
            r.put("ok", engine.isReady());
            r.put("engine", engine.engineName());
            JSArray list = new JSArray();
            for (Map<String, Object> v : engine.voices()) {
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
            r.put("current", engine.currentEngine());
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
        engine.whenReady(() -> main.post(() -> {
            JSObject r = new JSObject();
            r.put("ok", engine.isReady());
            r.put("current", engine.currentEngine());
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
                String pkg = engine.currentEngine();
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
                    o.optInt("page", 0)
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
