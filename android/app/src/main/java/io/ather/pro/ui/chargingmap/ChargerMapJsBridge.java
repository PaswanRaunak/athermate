package io.ather.pro.ui.chargingmap;

import android.os.Handler;
import android.webkit.JavascriptInterface;

/** WebView ↔ Compose bridge for public charger map selection / tile failures. */
public final class ChargerMapJsBridge {
    public interface Callbacks {
        void onMapReady();

        void onChargerSelected(String id);

        void onTilesFailed(String reason);
    }

    private final Handler mainHandler;
    private final Callbacks callbacks;

    public ChargerMapJsBridge(Handler mainHandler, Callbacks callbacks) {
        this.mainHandler = mainHandler;
        this.callbacks = callbacks;
    }

    @JavascriptInterface
    public void onMapReady() {
        mainHandler.post(callbacks::onMapReady);
    }

    @JavascriptInterface
    public void onChargerSelected(String id) {
        mainHandler.post(() -> callbacks.onChargerSelected(id));
    }

    @JavascriptInterface
    public void onTilesFailed(String reason) {
        mainHandler.post(() -> callbacks.onTilesFailed(reason != null ? reason : "tiles failed"));
    }
}
