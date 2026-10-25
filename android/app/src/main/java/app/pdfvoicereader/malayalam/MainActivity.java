package app.pdfvoicereader.malayalam;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ReaderTtsPlugin.class);
        super.onCreate(savedInstanceState);
        // keep the reader page's engine at normal priority when the app is minimised,
        // so it can keep adding OCR'd pages to the reading queue
        WebView wv = getBridge() != null ? getBridge().getWebView() : null;
        if (wv != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        keepPageRunning();
    }

    @Override
    public void onStop() {
        super.onStop();
        keepPageRunning();
    }

    private void keepPageRunning() {
        WebView wv = getBridge() != null ? getBridge().getWebView() : null;
        if (wv != null) {
            wv.resumeTimers();
        }
    }
}
