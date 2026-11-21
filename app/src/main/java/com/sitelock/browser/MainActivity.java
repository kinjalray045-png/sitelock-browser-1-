package com.sitelock.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final Set<String> AD_HOSTS = new HashSet<>(Arrays.asList(
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "adservice.google.com", "google-analytics.com", "googletagmanager.com",
            "scorecardresearch.com", "quantserve.com", "adnxs.com",
            "rubiconproject.com", "openx.net", "pubmatic.com", "casalemedia.com",
            "taboola.com", "outbrain.com", "criteo.com", "amazon-adsystem.com",
            "moatads.com", "adsrvr.org", "adform.net", "bidswitch.net",
            "connect.facebook.net", "bat.bing.com"
    ));

    private static final String PREFS_NAME = "sitelock_prefs";
    private static final String HISTORY_KEY = "history";

    private static class Tab {
        WebView webView;
        String title = "New tab";
        Tab(WebView w) { webView = w; }
    }

    private final List<Tab> tabs = new ArrayList<>();
    private int currentTab = 0;

    private EditText urlInput;
    private Button reloadButton;
    private Switch lockSwitch;
    private TextView statusText;
    private TextView blockedText;
    private TextView captionView;
    private LinearLayout tabStrip;
    private FrameLayout webContainer;
    private FrameLayout fullscreenContainer;

    private boolean locked = false;
    private String lockedHost = null;
    private int blockedCount = 0;
    private long lastBlockToast = 0;
    private boolean updatingSwitch = false;

    private volatile String detectedVideoUrl = "";
    private boolean captionsOn = false;

    private View videoCustomView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private final Handler captionHandler = new Handler();
    private final Runnable captionRunnable = new Runnable() {
        @Override
        public void run() {
            if (captionsOn && !tabs.isEmpty()) {
                try {
                    tabs.get(currentTab).webView.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');" +
                        "if(!v||!v.textTracks){SLB.caption('');return;}" +
                        "var s='';for(var i=0;i<v.textTracks.length;i++){var t=v.textTracks[i];" +
                        "if(t.mode==='showing'&&t.activeCues&&t.activeCues.length){" +
                        "for(var j=0;j<t.activeCues.length;j++)s+=t.activeCues[j].text+' ';}}" +
                        "SLB.caption(s.trim());})();", null);
                } catch (Exception ignored) { }
            }
            captionHandler.postDelayed(this, 1000);
        }
    };

    private class JsBridge {
        @JavascriptInterface
        public void videoInfo(String url) {
            if (url != null && !url.isEmpty() && !url.startsWith("blob:")) {
                detectedVideoUrl = url;
            }
        }

        @JavascriptInterface
        public void caption(final String text) {
            runOnUiThread(() -> {
                if (captionsOn) {
                    captionView.setText(text == null ? "" : text);
                    captionView.setVisibility(text == null || text.isEmpty()
                            ? View.GONE : View.VISIBLE);
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        reloadButton = findViewById(R.id.reloadButton);
        lockSwitch = findViewById(R.id.lockSwitch);
        statusText = findViewById(R.id.statusText);
        blockedText = findViewById(R.id.blockedText);
        captionView = findViewById(R.id.captionView);
        tabStrip = findViewById(R.id.tabStrip);
        webContainer = findViewById(R.id.webContainer);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);

        findViewById(R.id.goButton).setOnClickListener(v -> navigateFromInput());

        reloadButton.setOnClickListener(v -> {
            if (locked) {
                toast("Unlock the site lock before reloading");
                return;
            }
            currentWebView().reload();
        });

        lockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingSwitch) return;
            if (isChecked) lockSite();
            else unlockSite();
        });

        findViewById(R.id.menuButton).setOnClickListener(this::showMenu);

        if (tabs.isEmpty()) {
            createTab("about:blank");
        }

        captionHandler.post(captionRunnable);
        updateStatus();
    }

    private WebView currentWebView() {
        return tabs.get(currentTab).webView;
    }

    private Tab createTab(String url) {
        WebView w = new WebView(this);
        w.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        w.setVisibility(View.GONE);
        configureWebView(w);
        Tab tab = new Tab(w);
        tabs.add(tab);
        webContainer.addView(w, 0);
        if (url != null && !url.isEmpty()) w.loadUrl(url);
        return tab;
    }

    private void switchToTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        currentTab = index;
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).webView.setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        String url = currentWebView().getUrl();
        if (!locked) {
            urlInput.setText(url == null ? "" : url);
        } else {
            if (url != null) {
                Uri u = Uri.parse(url);
                if (u.getHost() != null) lockedHost = u.getHost().toLowerCase(Locale.US);
            }
        }
        updateTabStrip();
        updateStatus();
    }

    private void closeTab(int index) {
        if (tabs.size() <= 1) {
            currentWebView().loadUrl("about:blank");
            toast("This is the only tab");
            return;
        }
        WebView w = tabs.get(index).webView;
        try {
            w.destroy();
        } catch (Exception ignored) { }
        webContainer.removeView(w);
        tabs.remove(index);
        if (currentTab >= tabs.size()) currentTab = tabs.size() - 1;
        switchToTab(currentTab);
    }

    private void updateTabStrip() {
        tabStrip.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            Button b = new Button(this);
            b.setText("Tab " + (i + 1));
            b.setTextSize(12f);
            b.setAllCaps(false);
            b.setMinimumWidth(70);
            b.setBackgroundColor(i == currentTab ? Color.parseColor("#4CAF50")
                    : Color.parseColor("#424242"));
            b.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 6, 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> switchToTab(index));
            b.setOnLongClickListener(v -> {
                closeTab(index);
                return true;
            });
            tabStrip.addView(b);
        }
        Button plus = new Button(this);
        plus.setText("+ New tab");
        plus.setTextSize(12f);
        plus.setAllCaps(false);
        plus.setBackgroundColor(Color.parseColor("#424242"));
        plus.setTextColor(Color.WHITE);
        plus.setOnClickListener(v -> {
            createTab("about:blank");
            switchToTab(tabs.size() - 1);
            toast("New tab opened");
        });
        tabStrip.addView(plus);
    }

    private void configureWebView(WebView w) {
        WebSettings settings = w.getSettings();
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

        w.addJavascriptInterface(new JsBridge(), "SLB");

        w.setWebViewClient(new WebViewClient() {
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
                if (request.getResourceType() == WebResourceRequest.ResourceType.MEDIA) {
                    detectedVideoUrl = request.getUrl().toString();
                }
                if (locked && isAdHost(request.getUrl().getHost())) {
                    return emptyResponse();
                }
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                saveHistory(url);
                if (view == currentWebView() && !locked) urlInput.setText(url);
                view.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');" +
                    "SLB.videoInfo(v?(v.currentSrc||v.src||''):'');})();", null);
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                if (locked) {
                    toast("Blocked pop-up");
                    return true;
                }
                Tab tab = createTab(null);
                WebView.WebViewTransport transport =
                        (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(tab.webView);
                resultMsg.sendToTarget();
                switchToTab(tabs.size() - 1);
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (videoCustomView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                videoCustomView = view;
                customViewCallback = callback;
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(View.VISIBLE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
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

        w.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            if (locked) toast("Blocked download: " + url);
        });
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void showSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "New tab");
        menu.getMenu().add(0, 2, 1, "Close this tab");
        menu.getMenu().add(0, 3, 2, "History");
        menu.getMenu().add(0, 4, 3, "Download current video");
        menu.getMenu().add(0, 5, 4, captionsOn ? "Captions: ON (tap to turn off)"
                : "Captions: OFF (tap to turn on)");
        menu.getMenu().add(0, 6, 5, "Clear history");
        menu.setOnMenuItemClickListener(item -> {
            onMenuItem(item);
            return true;
        });
        menu.show();
    }

    private void onMenuItem(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                createTab("about:blank");
                switchToTab(tabs.size() - 1);
                toast("New tab opened");
                break;
            case 2:
                closeTab(currentTab);
                break;
            case 3:
                showHistory();
                break;
            case 4:
                showDownloadDialog();
                break;
            case 5:
                captionsOn = !captionsOn;
                if (!captionsOn) captionView.setVisibility(View.GONE);
                toast(captionsOn ? "Captions on" : "Captions off");
                break;
            case 6:
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().remove(HISTORY_KEY).apply();
                toast("History cleared");
                break;
            default:
                break;
        }
    }

    private void saveHistory(String url) {
        if (url == null || url.startsWith("about:") || url.startsWith("data:")) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Set<String> set = new HashSet<>(prefs.getStringSet(HISTORY_KEY, new HashSet<String>()));
        set.add(url);
        prefs.edit().putStringSet(HISTORY_KEY, set).apply();
    }

    private void showHistory() {
        Set<String> set = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getStringSet(HISTORY_KEY, new HashSet<String>());
        final List<String> list = new ArrayList<>(set);
        Collections.sort(list, Collections.reverseOrder());
        if (list.isEmpty()) {
            toast("No history yet");
            return;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, list);
        new AlertDialog.Builder(this)
                .setTitle("History")
                .setAdapter(adapter, (dialog, which) -> {
                    String url = list.get(which);
                    if (locked) {
                        toast("Unlock the site lock first");
                        return;
                    }
                    currentWebView().loadUrl(url);
                })
                .setPositiveButton("Close", null)
                .show();
    }

    private void showDownloadDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Video URL");
        input.setText(detectedVideoUrl);
        new AlertDialog.Builder(this)
                .setTitle("Download video")
                .setMessage("Detected video address below. Edit it if it is wrong, " +
                        "or paste a video address yourself.")
                .setView(input)
                .setPositiveButton("Download", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) {
                        toast("No video address given");
                        return;
                    }
                    startDownload(url);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startDownload(String url) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast("That address cannot be downloaded directly - paste the real media URL");
            return;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("Video download");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "videodownload_" + System.currentTimeMillis() + ".mp4");
            DownloadManager manager =
                    (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            manager.enqueue(request);
            toast("Download started - check your Downloads folder");
        } catch (Exception e) {
            toast("Could not start download: " + e.getMessage());
        }
    }

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
        currentWebView().loadUrl(url);
    }

    private void lockSite() {
        String url = currentWebView().getUrl();
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
        if (videoCustomView != null) {
            exitFullscreen();
            return;
        }
        WebView w = currentWebView();
        if (locked) {
            WebBackForwardList list = w.copyBackForwardList();
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
        if (w.canGoBack()) w.goBack();
        else super.onBackPressed();
    }

    private void exitFullscreen() {
        if (videoCustomView == null) return;
        fullscreenContainer.removeView(videoCustomView);
        fullscreenContainer.setVisibility(View.GONE);
        videoCustomView = null;
        WebChromeClient.CustomViewCallback cb = customViewCallback;
        customViewCallback = null;
        if (cb != null) cb.onCustomViewHidden();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        showSystemUi();
    }

    @Override
    protected void onDestroy() {
        captionHandler.removeCallbacks(captionRunnable);
        super.onDestroy();
    }
}
