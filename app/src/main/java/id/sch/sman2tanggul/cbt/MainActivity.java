package id.sch.sman2tanggul.cbt;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * CBT SMAN 2 Tanggul — Exam Browser berbasis WebView.
 * - Blokir screenshot & rekam layar (FLAG_SECURE), layar tetap menyala, layar penuh (immersive)
 * - Mode ujian (dipicu dari web via window.CBTNative.setExamMode): tombol kembali mati,
 *   kunci layar (screen pinning), tautan luar diblokir, keluar aplikasi / panel notifikasi /
 *   layar terbagi dilaporkan sebagai pelanggaran ke web.
 * - Kamera untuk Scan QR, pemilih file (galeri / Excel), unduhan http(s).
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final int REQ_CAMERA = 1002;

    private FrameLayout root;
    private WebView web;
    private LinearLayout errorView;
    private TextView errorText;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;

    private String startUrl;
    private String baseHost;
    private volatile boolean examMode = false;
    private boolean lockTaskActive = false;
    private boolean loadFailed = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        startUrl = getString(R.string.cbt_url).trim();
        baseHost = normHost(Uri.parse(startUrl).getHost());

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#EFF6FF"));
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        hideSystemBars();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " CBTExamApp/" + BuildConfig.VERSION_NAME);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        WebView.setWebContentsDebuggingEnabled(false);

        web.addJavascriptInterface(new Bridge(), "CBTNative");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> handleDownload(url, userAgent, contentDisposition, mimeType));
        web.setOnLongClickListener(v -> true); // matikan menu salin/tempel tekan-lama
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);

        if (startUrl.contains("NAMA-BLOG-ANDA") || !URLUtil.isHttpsUrl(startUrl)) {
            showError("Alamat web CBT belum diatur.\nAdmin: ubah cbt_url di file app/src/main/res/values/config.xml lalu build ulang aplikasi.", false);
            return;
        }
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(startUrl);
    }

    /* ======================= Jembatan JavaScript ======================= */
    private class Bridge {
        @JavascriptInterface
        public void setExamMode(final boolean on) { runOnUiThread(() -> applyExamMode(on)); }

        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public String version() { return BuildConfig.VERSION_NAME; }

        @JavascriptInterface
        public void exitApp() { runOnUiThread(() -> { if (!examMode) confirmExit(); }); }
    }

    private void applyExamMode(boolean on) {
        examMode = on;
        if (on && getResources().getBoolean(R.bool.kunci_layar_saat_ujian) && !lockTaskActive) {
            try { startLockTask(); lockTaskActive = true; } catch (Exception ignored) { }
        }
        if (!on && lockTaskActive) {
            try { stopLockTask(); } catch (Exception ignored) { }
            lockTaskActive = false;
        }
        hideSystemBars();
    }

    private void reportViolation(String reason) {
        if (!examMode || web == null) return;
        String js = "window.ujianApp&&window.ujianApp.registerViolation(" + JSONObject.quote(reason) + ")";
        web.evaluateJavascript(js, null);
    }

    /* ======================= Navigasi & Error ======================= */
    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            if (scheme.equals("http") || scheme.equals("https")) {
                if (baseHost.equals(normHost(u.getHost()))) return false; // tetap di aplikasi
                if (examMode) { toast("Membuka tautan luar dinonaktifkan selama ujian."); return true; }
                openExternal(u);
                return true;
            }
            if (!examMode) openExternal(u);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            loadFailed = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!loadFailed) errorView.setVisibility(View.GONE);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                loadFailed = true;
                showError("Tidak dapat terhubung ke server CBT.\nPeriksa koneksi internet Anda, lalu tekan Coba Lagi.", true);
            }
        }
    }

    /* ======================= Kamera, File, Fullscreen Video ======================= */
    private class Chrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                boolean wantsCamera = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsCamera = true;
                }
                if (!wantsCamera) { request.deny(); return; }
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    pendingPermission = request;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                }
            });
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                toast("Tidak ada aplikasi untuk memilih file.");
                return false;
            }
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) { callback.onCustomViewHidden(); return; }
            customView = view;
            customCallback = callback;
            root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            hideSystemBars();
        }

        @Override
        public void onHideCustomView() { hideCustomView(); }
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        hideSystemBars();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && pendingPermission != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingPermission.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                pendingPermission.deny();
                toast("Izin kamera ditolak. Login tetap bisa dengan NISN & password.");
            }
            pendingPermission = null;
        }
    }

    private void handleDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast("Ekspor/unduh file Excel & cetak PDF tersedia di browser Chrome (laptop/HP), bukan di aplikasi.");
            return;
        }
        try {
            DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
            rq.setMimeType(mimeType);
            rq.addRequestHeader("User-Agent", userAgent);
            rq.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
            rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType));
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) { dm.enqueue(rq); toast("Mengunduh file..."); }
        } catch (Exception e) {
            if (!examMode) openExternal(Uri.parse(url));
        }
    }

    /* ======================= Siklus Hidup & Deteksi Pelanggaran ======================= */
    @Override
    protected void onPause() {
        reportViolation("Meninggalkan aplikasi ujian");
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
        else reportViolation("Membuka panel notifikasi atau jendela lain");
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        if (isInMultiWindowMode) reportViolation("Menggunakan layar terbagi / jendela mengambang");
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { hideCustomView(); return; }
        if (examMode) { toast("Tombol kembali dinonaktifkan selama ujian."); return; }
        confirmExit();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /* ======================= Utilitas ======================= */
    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage("Keluar dari aplikasi CBT?")
                .setPositiveButton("Keluar", (d, w) -> finish())
                .setNegativeButton("Batal", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            toast("Tidak ada aplikasi untuk membuka tautan ini.");
        }
    }

    private static String normHost(String h) {
        if (h == null) return "";
        h = h.toLowerCase();
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(32), dp(32), dp(32), dp(32));
        box.setBackgroundColor(Color.parseColor("#EFF6FF"));
        box.setClickable(true);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1D4ED8"));
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        errorText = new TextView(this);
        errorText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        errorText.setTextColor(Color.parseColor("#334155"));
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(0, dp(16), 0, dp(24));
        box.addView(errorText);

        Button retry = new Button(this);
        retry.setText("Coba Lagi");
        retry.setAllCaps(false);
        retry.setTextColor(Color.WHITE);
        retry.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1D4ED8"));
        bg.setCornerRadius(dp(14));
        retry.setBackground(bg);
        retry.setPadding(dp(28), dp(12), dp(28), dp(12));
        retry.setTag("retry");
        retry.setOnClickListener(v -> {
            errorView.setVisibility(View.GONE);
            if (web == null) return;
            String cur = web.getUrl();
            if (cur == null || cur.startsWith("data:") || cur.startsWith("about:")) web.loadUrl(startUrl);
            else web.reload();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(retry, lp);
        return box;
    }

    private void showError(String msg, boolean canRetry) {
        errorText.setText(msg);
        View retry = errorView.findViewWithTag("retry");
        if (retry != null) retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
        errorView.setVisibility(View.VISIBLE);
    }
}
