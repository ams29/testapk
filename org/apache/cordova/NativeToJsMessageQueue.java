package org.apache.cordova;

import android.webkit.ValueCallback;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Objects;
import org.apache.cordova.PluginResult;

public class NativeToJsMessageQueue {
    private static int COMBINED_RESPONSE_CUTOFF = 16777216;
    static final boolean DISABLE_EXEC_CHAINING = false;
    private static final boolean FORCE_ENCODE_USING_EVAL = false;
    private static final String LOG_TAG = "JsMessageQueue";
    private BridgeMode activeBridgeMode;
    private ArrayList<BridgeMode> bridgeModes = new ArrayList<>();
    private boolean paused;
    private final LinkedList<JsMessage> queue = new LinkedList<>();

    public static abstract class BridgeMode {
        public void notifyOfFlush(NativeToJsMessageQueue nativeToJsMessageQueue, boolean z) {
        }

        public abstract void onNativeToJsMessageAvailable(NativeToJsMessageQueue nativeToJsMessageQueue);

        public void reset() {
        }
    }

    public static class NoOpBridgeMode extends BridgeMode {
        public void onNativeToJsMessageAvailable(NativeToJsMessageQueue nativeToJsMessageQueue) {
        }
    }

    public void addBridgeMode(BridgeMode bridgeMode) {
        this.bridgeModes.add(bridgeMode);
    }

    public boolean isBridgeEnabled() {
        return this.activeBridgeMode != null;
    }

    public boolean isEmpty() {
        return this.queue.isEmpty();
    }

    public void setBridgeMode(int i) {
        BridgeMode bridgeMode;
        if (i < -1 || i >= this.bridgeModes.size()) {
            LOG.d(LOG_TAG, "Invalid NativeToJsBridgeMode: " + i);
            return;
        }
        if (i < 0) {
            bridgeMode = null;
        } else {
            bridgeMode = this.bridgeModes.get(i);
        }
        if (bridgeMode != this.activeBridgeMode) {
            LOG.d(LOG_TAG, "Set native->JS mode to " + (bridgeMode == null ? "null" : bridgeMode.getClass().getSimpleName()));
            synchronized (this) {
                this.activeBridgeMode = bridgeMode;
                if (bridgeMode != null) {
                    bridgeMode.reset();
                    if (!this.paused && !this.queue.isEmpty()) {
                        bridgeMode.onNativeToJsMessageAvailable(this);
                    }
                }
            }
        }
    }

    public void reset() {
        synchronized (this) {
            this.queue.clear();
            setBridgeMode(-1);
        }
    }

    private int calculatePackedMessageLength(JsMessage jsMessage) {
        int calculateEncodedLength = jsMessage.calculateEncodedLength();
        return String.valueOf(calculateEncodedLength).length() + calculateEncodedLength + 1;
    }

    private void packMessage(JsMessage jsMessage, StringBuilder sb) {
        sb.append(jsMessage.calculateEncodedLength()).append(' ');
        jsMessage.encodeAsMessage(sb);
    }

    public String popAndEncode(boolean z) {
        int i;
        synchronized (this) {
            BridgeMode bridgeMode = this.activeBridgeMode;
            if (bridgeMode == null) {
                return null;
            }
            bridgeMode.notifyOfFlush(this, z);
            if (this.queue.isEmpty()) {
                return null;
            }
            Iterator it = this.queue.iterator();
            int i2 = 0;
            int i3 = 0;
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                int calculatePackedMessageLength = calculatePackedMessageLength((JsMessage) it.next());
                if (i2 > 0 && (i = COMBINED_RESPONSE_CUTOFF) > 0 && i3 + calculatePackedMessageLength > i) {
                    break;
                }
                i3 += calculatePackedMessageLength;
                i2++;
            }
            StringBuilder sb = new StringBuilder(i3);
            for (int i4 = 0; i4 < i2; i4++) {
                packMessage(this.queue.removeFirst(), sb);
            }
            if (!this.queue.isEmpty()) {
                sb.append('*');
            }
            String sb2 = sb.toString();
            return sb2;
        }
    }

    public String popAndEncodeAsJs() {
        int i;
        synchronized (this) {
            if (this.queue.size() == 0) {
                return null;
            }
            Iterator it = this.queue.iterator();
            int i2 = 0;
            int i3 = 0;
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                int calculateEncodedLength = ((JsMessage) it.next()).calculateEncodedLength() + 50;
                if (i2 > 0 && (i = COMBINED_RESPONSE_CUTOFF) > 0 && i3 + calculateEncodedLength > i) {
                    break;
                }
                i3 += calculateEncodedLength;
                i2++;
            }
            int i4 = i2 == this.queue.size() ? 1 : 0;
            StringBuilder sb = new StringBuilder(i3 + (i4 != 0 ? 0 : 100));
            for (int i5 = 0; i5 < i2; i5++) {
                JsMessage removeFirst = this.queue.removeFirst();
                if (i4 == 0 || i5 + 1 != i2) {
                    sb.append("try{");
                    removeFirst.encodeAsJsMessage(sb);
                    sb.append("}finally{");
                } else {
                    removeFirst.encodeAsJsMessage(sb);
                }
            }
            if (i4 == 0) {
                sb.append("window.setTimeout(function(){cordova.require('cordova/plugin/android/polling').pollOnce();},0);");
            }
            while (i4 < i2) {
                sb.append('}');
                i4++;
            }
            String sb2 = sb.toString();
            return sb2;
        }
    }

    public void addJavaScript(String str) {
        enqueueMessage(new JsMessage(str));
    }

    public void addPluginResult(PluginResult pluginResult, String str) {
        if (str == null) {
            LOG.e(LOG_TAG, "Got plugin result with no callbackId", new Throwable());
            return;
        }
        boolean z = pluginResult.getStatus() == PluginResult.Status.NO_RESULT.ordinal();
        boolean keepCallback = pluginResult.getKeepCallback();
        if (!z || !keepCallback) {
            enqueueMessage(new JsMessage(pluginResult, str));
        }
    }

    /* JADX WARNING: Code restructure failed: missing block: B:11:0x001d, code lost:
        return;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private void enqueueMessage(org.apache.cordova.NativeToJsMessageQueue.JsMessage r2) {
        /*
            r1 = this;
            monitor-enter(r1)
            org.apache.cordova.NativeToJsMessageQueue$BridgeMode r0 = r1.activeBridgeMode     // Catch:{ all -> 0x001e }
            if (r0 != 0) goto L_0x000e
            java.lang.String r2 = "JsMessageQueue"
            java.lang.String r0 = "Dropping Native->JS message due to disabled bridge"
            org.apache.cordova.LOG.d(r2, r0)     // Catch:{ all -> 0x001e }
            monitor-exit(r1)     // Catch:{ all -> 0x001e }
            return
        L_0x000e:
            java.util.LinkedList<org.apache.cordova.NativeToJsMessageQueue$JsMessage> r0 = r1.queue     // Catch:{ all -> 0x001e }
            r0.add(r2)     // Catch:{ all -> 0x001e }
            boolean r2 = r1.paused     // Catch:{ all -> 0x001e }
            if (r2 != 0) goto L_0x001c
            org.apache.cordova.NativeToJsMessageQueue$BridgeMode r2 = r1.activeBridgeMode     // Catch:{ all -> 0x001e }
            r2.onNativeToJsMessageAvailable(r1)     // Catch:{ all -> 0x001e }
        L_0x001c:
            monitor-exit(r1)     // Catch:{ all -> 0x001e }
            return
        L_0x001e:
            r2 = move-exception
            monitor-exit(r1)     // Catch:{ all -> 0x001e }
            throw r2
        */
        throw new UnsupportedOperationException("Method not decompiled: org.apache.cordova.NativeToJsMessageQueue.enqueueMessage(org.apache.cordova.NativeToJsMessageQueue$JsMessage):void");
    }

    public void setPaused(boolean z) {
        BridgeMode bridgeMode;
        if (this.paused && z) {
            LOG.e(LOG_TAG, "nested call to setPaused detected.", new Throwable());
        }
        this.paused = z;
        if (!z) {
            synchronized (this) {
                if (!this.queue.isEmpty() && (bridgeMode = this.activeBridgeMode) != null) {
                    bridgeMode.onNativeToJsMessageAvailable(this);
                }
            }
        }
    }

    public static class LoadUrlBridgeMode extends BridgeMode {
        private final CordovaInterface cordova;
        /* access modifiers changed from: private */
        public final CordovaWebViewEngine engine;

        public LoadUrlBridgeMode(CordovaWebViewEngine cordovaWebViewEngine, CordovaInterface cordovaInterface) {
            this.engine = cordovaWebViewEngine;
            this.cordova = cordovaInterface;
        }

        public void onNativeToJsMessageAvailable(final NativeToJsMessageQueue nativeToJsMessageQueue) {
            this.cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    String popAndEncodeAsJs = nativeToJsMessageQueue.popAndEncodeAsJs();
                    if (popAndEncodeAsJs != null) {
                        LoadUrlBridgeMode.this.engine.loadUrl("javascript:" + popAndEncodeAsJs, false);
                    }
                }
            });
        }
    }

    public static class OnlineEventsBridgeMode extends BridgeMode {
        /* access modifiers changed from: private */
        public final OnlineEventsBridgeModeDelegate delegate;
        /* access modifiers changed from: private */
        public boolean ignoreNextFlush;
        /* access modifiers changed from: private */
        public boolean online;

        public interface OnlineEventsBridgeModeDelegate {
            void runOnUiThread(Runnable runnable);

            void setNetworkAvailable(boolean z);
        }

        public OnlineEventsBridgeMode(OnlineEventsBridgeModeDelegate onlineEventsBridgeModeDelegate) {
            this.delegate = onlineEventsBridgeModeDelegate;
        }

        public void reset() {
            this.delegate.runOnUiThread(new Runnable() {
                public void run() {
                    boolean unused = OnlineEventsBridgeMode.this.online = false;
                    boolean unused2 = OnlineEventsBridgeMode.this.ignoreNextFlush = true;
                    OnlineEventsBridgeMode.this.delegate.setNetworkAvailable(true);
                }
            });
        }

        public void onNativeToJsMessageAvailable(final NativeToJsMessageQueue nativeToJsMessageQueue) {
            this.delegate.runOnUiThread(new Runnable() {
                public void run() {
                    if (!nativeToJsMessageQueue.isEmpty()) {
                        boolean unused = OnlineEventsBridgeMode.this.ignoreNextFlush = false;
                        OnlineEventsBridgeMode.this.delegate.setNetworkAvailable(OnlineEventsBridgeMode.this.online);
                    }
                }
            });
        }

        public void notifyOfFlush(NativeToJsMessageQueue nativeToJsMessageQueue, boolean z) {
            if (z && !this.ignoreNextFlush) {
                this.online = !this.online;
            }
        }
    }

    public static class EvalBridgeMode extends BridgeMode {
        private final CordovaInterface cordova;
        /* access modifiers changed from: private */
        public final CordovaWebViewEngine engine;

        public EvalBridgeMode(CordovaWebViewEngine cordovaWebViewEngine, CordovaInterface cordovaInterface) {
            this.engine = cordovaWebViewEngine;
            this.cordova = cordovaInterface;
        }

        public void onNativeToJsMessageAvailable(final NativeToJsMessageQueue nativeToJsMessageQueue) {
            this.cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    String popAndEncodeAsJs = nativeToJsMessageQueue.popAndEncodeAsJs();
                    if (popAndEncodeAsJs != null) {
                        EvalBridgeMode.this.engine.evaluateJavascript(popAndEncodeAsJs, (ValueCallback<String>) null);
                    }
                }
            });
        }
    }

    private static class JsMessage {
        final String jsPayloadOrCallbackId;
        final PluginResult pluginResult;

        JsMessage(String str) {
            Objects.requireNonNull(str);
            this.jsPayloadOrCallbackId = str;
            this.pluginResult = null;
        }

        JsMessage(PluginResult pluginResult2, String str) {
            if (str == null || pluginResult2 == null) {
                throw null;
            }
            this.jsPayloadOrCallbackId = str;
            this.pluginResult = pluginResult2;
        }

        static int calculateEncodedLengthHelper(PluginResult pluginResult2) {
            switch (pluginResult2.getMessageType()) {
                case 1:
                    return pluginResult2.getStrMessage().length() + 1;
                case 3:
                    return pluginResult2.getMessage().length() + 1;
                case 4:
                case 5:
                    return 1;
                case 6:
                    return pluginResult2.getMessage().length() + 1;
                case 7:
                    return pluginResult2.getMessage().length() + 1;
                case 8:
                    int i = 1;
                    for (int i2 = 0; i2 < pluginResult2.getMultipartMessagesSize(); i2++) {
                        int calculateEncodedLengthHelper = calculateEncodedLengthHelper(pluginResult2.getMultipartMessage(i2));
                        i += String.valueOf(calculateEncodedLengthHelper).length() + 1 + calculateEncodedLengthHelper;
                    }
                    return i;
                default:
                    return pluginResult2.getMessage().length();
            }
        }

        /* access modifiers changed from: package-private */
        public int calculateEncodedLength() {
            PluginResult pluginResult2 = this.pluginResult;
            if (pluginResult2 == null) {
                return this.jsPayloadOrCallbackId.length() + 1;
            }
            return String.valueOf(pluginResult2.getStatus()).length() + 2 + 1 + this.jsPayloadOrCallbackId.length() + 1 + calculateEncodedLengthHelper(this.pluginResult);
        }

        static void encodeAsMessageHelper(StringBuilder sb, PluginResult pluginResult2) {
            switch (pluginResult2.getMessageType()) {
                case 1:
                    sb.append('s');
                    sb.append(pluginResult2.getStrMessage());
                    return;
                case 3:
                    sb.append('n').append(pluginResult2.getMessage());
                    return;
                case 4:
                    sb.append(pluginResult2.getMessage().charAt(0));
                    return;
                case 5:
                    sb.append('N');
                    return;
                case 6:
                    sb.append('A');
                    sb.append(pluginResult2.getMessage());
                    return;
                case 7:
                    sb.append('S');
                    sb.append(pluginResult2.getMessage());
                    return;
                case 8:
                    sb.append('M');
                    for (int i = 0; i < pluginResult2.getMultipartMessagesSize(); i++) {
                        PluginResult multipartMessage = pluginResult2.getMultipartMessage(i);
                        sb.append(String.valueOf(calculateEncodedLengthHelper(multipartMessage)));
                        sb.append(' ');
                        encodeAsMessageHelper(sb, multipartMessage);
                    }
                    return;
                default:
                    sb.append(pluginResult2.getMessage());
                    return;
            }
        }

        /* access modifiers changed from: package-private */
        public void encodeAsMessage(StringBuilder sb) {
            PluginResult pluginResult2 = this.pluginResult;
            if (pluginResult2 == null) {
                sb.append('J').append(this.jsPayloadOrCallbackId);
                return;
            }
            int status = pluginResult2.getStatus();
            boolean z = true;
            boolean z2 = status == PluginResult.Status.NO_RESULT.ordinal();
            if (status != PluginResult.Status.OK.ordinal()) {
                z = false;
            }
            sb.append((z2 || z) ? 'S' : 'F').append(this.pluginResult.getKeepCallback() ? '1' : '0').append(status).append(' ').append(this.jsPayloadOrCallbackId).append(' ');
            encodeAsMessageHelper(sb, this.pluginResult);
        }

        /* access modifiers changed from: package-private */
        public void buildJsMessage(StringBuilder sb) {
            int messageType = this.pluginResult.getMessageType();
            if (messageType == 5) {
                sb.append("null");
            } else if (messageType == 6) {
                sb.append("cordova.require('cordova/base64').toArrayBuffer('").append(this.pluginResult.getMessage()).append("')");
            } else if (messageType == 7) {
                sb.append("atob('").append(this.pluginResult.getMessage()).append("')");
            } else if (messageType != 8) {
                sb.append(this.pluginResult.getMessage());
            } else {
                int multipartMessagesSize = this.pluginResult.getMultipartMessagesSize();
                for (int i = 0; i < multipartMessagesSize; i++) {
                    new JsMessage(this.pluginResult.getMultipartMessage(i), this.jsPayloadOrCallbackId).buildJsMessage(sb);
                    if (i < multipartMessagesSize - 1) {
                        sb.append(",");
                    }
                }
            }
        }

        /* access modifiers changed from: package-private */
        public void encodeAsJsMessage(StringBuilder sb) {
            PluginResult pluginResult2 = this.pluginResult;
            if (pluginResult2 == null) {
                sb.append(this.jsPayloadOrCallbackId);
                return;
            }
            int status = pluginResult2.getStatus();
            sb.append("cordova.callbackFromNative('").append(this.jsPayloadOrCallbackId).append("',").append(status == PluginResult.Status.OK.ordinal() || status == PluginResult.Status.NO_RESULT.ordinal()).append(",").append(status).append(",[");
            buildJsMessage(sb);
            sb.append("],").append(this.pluginResult.getKeepCallback()).append(");");
        }
    }
}
