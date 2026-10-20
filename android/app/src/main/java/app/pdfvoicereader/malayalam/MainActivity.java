package app.pdfvoicereader.malayalam;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ReaderTtsPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
