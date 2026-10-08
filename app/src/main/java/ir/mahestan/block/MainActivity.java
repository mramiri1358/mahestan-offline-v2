package ir.mahestan.block;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import org.json.JSONObject;

import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int SAVE_FILE_REQUEST_CODE = 1002;
    private byte[] pendingSaveBytes;
    private String pendingShareText;
    private String pendingShareImageData;
    private boolean pageLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.addJavascriptInterface(new FileSaveBridge(), "AndroidFileSaver");
        webView.addJavascriptInterface(new AppBridge(), "AndroidApp");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageLoaded = true;
                deliverPendingShare();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        handleIncomingIntent(getIntent());
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
    }


    private void handleIncomingIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        try {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            CharSequence subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
            String t = text == null ? "" : text.toString().trim();
            if (subject != null && !subject.toString().trim().isEmpty()) {
                String sub = subject.toString().trim();
                if (!t.contains(sub)) t = t.isEmpty() ? sub : sub + "\n" + t;
            }
            pendingShareText = t;

            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                String mime = getContentResolver().getType(stream);
                if (mime == null || !mime.startsWith("image/")) mime = "image/jpeg";
                try (InputStream in = getContentResolver().openInputStream(stream);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    if (in != null) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) != -1 && out.size() <= 8 * 1024 * 1024) out.write(buf, 0, n);
                        pendingShareImageData = "data:" + mime + ";base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                    }
                }
            }
            if (pageLoaded) deliverPendingShare();
        } catch (Exception ignored) { }
    }

    private void deliverPendingShare() {
        if (!pageLoaded || webView == null) return;
        if ((pendingShareText == null || pendingShareText.isEmpty()) && (pendingShareImageData == null || pendingShareImageData.isEmpty())) return;
        try {
            String text = JSONObject.quote(pendingShareText == null ? "" : pendingShareText);
            String image = JSONObject.quote(pendingShareImageData == null ? "" : pendingShareImageData);
            webView.evaluateJavascript("window.handleIncomingSharedData && window.handleIncomingSharedData(" + text + "," + image + ");", null);
            pendingShareText = null;
            pendingShareImageData = null;
        } catch (Exception ignored) { }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }
    public class FileSaveBridge {
        @JavascriptInterface
        public void saveFile(String base64Data, String fileName, String mimeType) {
            try {
                pendingSaveBytes = Base64.decode(base64Data, Base64.DEFAULT);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType((mimeType == null || mimeType.isEmpty()) ? "application/octet-stream" : mimeType);
                intent.putExtra(Intent.EXTRA_TITLE, fileName == null ? "export" : fileName);
                startActivityForResult(intent, SAVE_FILE_REQUEST_CODE);
            } catch (Exception e) {
                pendingSaveBytes = null;
                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("window.toast && window.toast('خطا در باز کردن محل ذخیره',false);", null);
                    }
                });
            }
        }
    }

    public class AppBridge {
        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> {
                try {
                    if (webView != null) webView.evaluateJavascript("window.persistStorage && window.persistStorage();", null);
                } catch (Exception ignored) { }
                finish();
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) results = new Uri[]{uri};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }

        if (requestCode == SAVE_FILE_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingSaveBytes != null) {
                try {
                    Uri uri = data.getData();
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new Exception("OutputStream is null");
                        out.write(pendingSaveBytes);
                        out.flush();
                    }
                    if (webView != null) {
                        webView.evaluateJavascript("window.toast && window.toast('فایل با موفقیت ذخیره شد ✓',true);", null);
                    }
                } catch (Exception e) {
                    if (webView != null) {
                        webView.evaluateJavascript("window.toast && window.toast('ذخیره فایل انجام نشد',false);", null);
                    }
                }
            }
            pendingSaveBytes = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            try { webView.evaluateJavascript("window.persistStorage && window.persistStorage();", null); } catch (Exception ignored) { }
            finish();
        }
    }

    @Override
    protected void onPause() {
        try { if (webView != null) webView.evaluateJavascript("window.persistStorage && window.persistStorage();", null); } catch (Exception ignored) { }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
