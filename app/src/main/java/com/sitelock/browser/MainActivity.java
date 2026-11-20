package com.sitelock.browser;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A small WebView browser with a "site lock" switch.
 *
 * When the switch is ON the current site's host is remembered and the app:
 *   - blocks any main-frame navigation to a different host (redirects / leaving the site)
 *   - blocks every new window / pop-up
 *   - blocks known ad / tracker domains at the network level
 *   - suppresses JavaScript alert/confirm/prompt dialogs
 *
 * The loaded page is never reloaded or paused when the switch changes, so a
 * video that is already playing keeps playing.
 */
public class MainActivity extends Activity {

    /** Ad / tracker suffixes. Deliberately excludes video/CDN hosts. */
    private static final Set<String> AD_HOSTS = new HashSet<>(Arrays.asList(
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "adservice.google.com", "google-analytics.com", "googletagmanager.com",
            "scorecardresearch.com", "quantserve.com", "adnxs.com",
            "rubiconproject.com", "openx.net", "pubmatic.com", "casalemedia.com",
            "taboola.com", "outbrain.com", "criteo.com", "amazon-adsystem.com",
            "moatads.com", "adsrvr.org", "adform.net", "bidswitch.net",
            "connect.facebook.net", "bat.bing.com"
    ));

    private WebView webView;
    private EditText urlInput;
    private Button reloadButton;
    private Switch lockSwitch;
    private TextView statusText;
    private TextView blockedText;

    private boolean locked = false;
    private String lockedHost = null;
    private int blockedCount = 0;
    private long lastBlockToast = 0;
    private boolean updatingSwitch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        urlInput = findViewById(R.id.urlInput);
        reloadButton = findViewById(R.id.reloadButton);
        lockSwitch = findViewById(R.id.lockSwitch);
        statusText = findViewById(R.id.statusText);
        blockedText = findViewById(R.id.blockedText);

        configureWebView();

        findViewById(R.id.goButton).setOnClickListener(v -> navigateFromInput());

        reloadButton.setOnClickListener(v -> {
            if (locked) {
                toast("Unlock the site lock before reloading");
                return;
            }
            webView.reload();
        });

        lockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingSwitch) return;
            if (isChecked) lockSite();
            else unlockSite();
        });

        updateStatus();
        webView.loadUrl("about:blank");
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setSupportMultipleWindows(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl(), request.isForMainFrame());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url), true);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                if (locked && isAdHost(request.getUrl().getHost())) {
                    return emptyResponse();
                }
                return null; // normal WebView loading
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!locked) urlInput.setText(url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            /** Consume every request for a new window: no pop-ups, no extra tabs. */
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                      String defaultValue, JsPromptResult result) {
                result.cancel();
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            if (locked) toast("Blocked download: " + url);
        });
    }

    /** @return true to cancel the navigation, false to let WebView handle it. */
    private boolean handleNavigation(Uri uri, boolean mainFrame) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;

        String s = scheme.toLowerCase(Locale.US);
        if (s.equals("about") || s.equals("data") || s.equals("javascript")) return false;

        String host = uri.getHost();
        if (host == null) return false;

        if (locked && isAdHost(host)) {
            recordBlock(uri, "ad domain");
            return true;
        }

        if (locked && mainFrame && !isSameSite(host)) {
            recordBlock(uri, "site locked");
            return true;
        }
        return false;
    }

    private void navigateFromInput() {
        if (locked) {
            toast("Unlock the site lock before opening another address");
            return;
        }
        String input = urlInput.getText().toString().trim();
        if (input.isEmpty()) return;
        String url = (input.startsWith("http://") || input.startsWith("https://"))
                ? input : "https://" + input;
        webView.loadUrl(url);
    }

    private void lockSite() {
        String url = webView.getUrl();
        if (url == null || url.startsWith("about:")) {
            toast("Load a video page first, then switch the lock on");
            setSwitchSilently(false);
            return;
        }
        String host = Uri.parse(url).getHost();
        if (host == null) {
            toast("Could not read the current site");
            setSwitchSilently(false);
            return;
        }
        lockedHost = host.toLowerCase(Locale.US);
        locked = true;
        urlInput.setEnabled(false);
        reloadButton.setEnabled(false);
        updateStatus();
        toast("Locked to " + lockedHost + ". Redirects, pop-ups and ads are blocked.");
    }

    private void unlockSite() {
        locked = false;
        lockedHost = null;
        urlInput.setEnabled(true);
        reloadButton.setEnabled(true);
        updateStatus();
        toast("Lock off - browsing is normal again");
    }

    private void setSwitchSilently(boolean on) {
        updatingSwitch = true;
        lockSwitch.setChecked(on);
        updatingSwitch = false;
    }

    private void updateStatus() {
        if (locked) {
            statusText.setText("LOCKED to " + lockedHost + " - Blocked: " + blockedCount);
        } else {
            statusText.setText("UNLOCKED - Blocked: " + blockedCount);
        }
    }

    private void recordBlock(Uri uri, String reason) {
        final int count = ++blockedCount;
        final String host = uri.getHost() == null ? uri.toString() : uri.getHost();
        runOnUiThread(() -> {
            blockedText.setText("Blocked navigations: " + count);
            statusText.setText("Blocked " + host + " (" + reason + ")");
            long now = System.currentTimeMillis();
            if (now - lastBlockToast > 2000) {
                lastBlockToast = now;
                Toast.makeText(this, "Blocked " + host + " - " + reason, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static boolean isAdHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.US);
        for (String suffix : AD_HOSTS) {
            if (h.equals(suffix) || h.endsWith("." + suffix)) return true;
        }
        return false;
    }

    /** Allow the locked host, its parent, and its subdomains. */
    private boolean isSameSite(String host) {
        if (lockedHost == null || host == null) return false;
        String h = host.toLowerCase(Locale.US);
        return h.equals(lockedHost)
                || h.endsWith("." + lockedHost)
                || lockedHost.endsWith("." + h);
    }

    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(new byte[0]));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (locked) {
            WebBackForwardList list = webView.copyBackForwardList();
            int index = list.getCurrentIndex();
            if (index > 0) {
                WebHistoryItem previous = list.getItemAtIndex(index - 1);
                String url = previous.getUrl();
                if (url != null) {
                    String host = Uri.parse(url).getHost();
                    if (host != null && !isSameSite(host) && !isAdHost(host)) {
                        toast("Blocked: going back would leave " + lockedHost);
                        return;
                    }
                }
            }
        }
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
