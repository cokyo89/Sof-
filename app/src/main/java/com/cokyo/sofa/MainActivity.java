package com.cokyo.sofa;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#14121A"));
        getWindow().setNavigationBarColor(Color.parseColor("#14121A"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#14121A"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("file:")) return false;
                openUrl(url);
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);
        web.requestFocus();
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // La web decide si cierra una ficha abierta; si no hay nada que cerrar, salimos.
        web.evaluateJavascript("window.onBack ? window.onBack() : false", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    /**
     * pkgs: paquetes separados por comas (versión TV y versión móvil de la misma plataforma).
     * Intenta abrir el enlace (ficha de la peli) dentro de la app instalada; si no puede, abre la app;
     * si no hay ninguna instalada, va a la Play Store.
     */
    private void openApp(String pkgs, String url) {
        String[] list = pkgs.split(",");
        PackageManager pm = getPackageManager();
        for (String pkg : list) {
            if (!isInstalledOne(pkg)) continue;
            if (url != null && !url.isEmpty()) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.setPackage(pkg);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    if (pkg.startsWith("com.netflix")) i.putExtra("source", "30");
                    startActivity(i);
                    return;
                } catch (ActivityNotFoundException ignored) { }
            }
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return;
            }
        }
        String store = list[0];
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + store)));
        } catch (ActivityNotFoundException e) {
            openUrl("https://play.google.com/store/apps/details?id=" + store);
        }
    }

    private boolean isInstalledOne(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg.trim(), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkgs) {
        for (String p : pkgs.split(",")) if (isInstalledOne(p)) return true;
        return false;
    }

    public class Bridge {
        @JavascriptInterface
        public void open(String pkg, String url) {
            runOnUiThread(() -> openApp(pkg, url));
        }

        @JavascriptInterface
        public boolean installed(String pkg) {
            return isInstalled(pkg);
        }

        @JavascriptInterface
        public void openLink(String url) {
            runOnUiThread(() -> openUrl(url));
        }

        /** POST JSON hecho desde Android (sin las restricciones CORS de la web). Responde a window.__nativeCb. */
        @JavascriptInterface
        public void post(String id, String url, String body) {
            new Thread(() -> {
                String result;
                boolean ok;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(10000);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("Accept", "*/*");
                    c.setRequestProperty("App-Version", "3.9.3-web-web");
                    c.setRequestProperty("Referer", "https://www.justwatch.com/");
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                    int code = c.getResponseCode();
                    InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                    StringBuilder sb = new StringBuilder();
                    if (in != null) {
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = r.readLine()) != null) sb.append(line);
                        }
                    }
                    result = sb.toString();
                    ok = code < 400;
                } catch (Exception e) {
                    result = String.valueOf(e.getMessage());
                    ok = false;
                } finally {
                    if (c != null) c.disconnect();
                }
                final String js = "window.__nativeCb(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(result) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }
    }
}
