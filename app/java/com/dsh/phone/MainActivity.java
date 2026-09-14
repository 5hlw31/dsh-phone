package com.dsh.phone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

/**
 * DSH 手机端 WebView 壳。
 *
 * 三层职责：
 *   1. 承载 —— 把 dsh 的 Web GUI 装进 WebView（Java 侧不做任何业务逻辑）
 *   2. 打通 —— 解决浏览器做不到的三件事：自签证书、basic auth 免输入、系统文件选择器
 *   3. 适配 —— 注入移动端皮肤脚本（MOBILE_SKIN_JS），修复 dsh 桌面布局在窄屏上的问题
 *
 * ⚠️ 本文件为开源脱敏版：所有地址与凭据均为占位符，请按注释替换为你自己的。
 */
public class MainActivity extends Activity {

    // ─────────────────────────────────────────────────────────────
    //  部署前配置（占位符，必须替换）
    // ─────────────────────────────────────────────────────────────

    /** dsh web 的访问地址。服务器版例：https://your-host:18790/ ；手机本机版例：http://127.0.0.1:3080/ */
    private static final String TARGET_URL = "https://<SERVER_HOST>:<PORT>/";

    /** nginx basic auth 用户名。服务端未启用 basic auth 时留空即可。 */
    private static final String AUTH_USER = "";

    /** nginx basic auth 密码。服务端未启用 basic auth 时留空即可。 */
    private static final String AUTH_PASS = "";

    // ─────────────────────────────────────────────────────────────
    //  内部状态
    // ─────────────────────────────────────────────────────────────

    private static final int FILE_CHOOSER_REQUEST = 1001;
    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> fileChooserCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 布局：顶部 3dp 进度条 + 下方撑满的 WebView（不引入任何布局 XML）
        webView = new WebView(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        layout.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(layout);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 触控缩放：双指缩放，但不改字号（字号由注入的 CSS 控制）
        s.setTextZoom(100);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // 注意：不用 setForceDark —— 它会与页面自身的深色主题叠加，产生黑块

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 站内跳转留在 WebView，不交给系统浏览器
                view.loadUrl(request.getUrl().toString());
                return true;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // ⚠️ 安全权衡：无条件接受任意证书，因此无法防御中间人攻击。
                //    这里这样做是因为服务端用自签证书、且为个人自用。
                //    生产做法应改为证书指纹绑定（certificate pinning）：
                //    SslCertificate cert = error.getCertificate();
                //    if (!EXPECTED_SHA256.equals(sha256(cert))) { handler.cancel(); return; }
                handler.proceed();
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                    String host, String realm) {
                // nginx basic auth 自动填凭据 → 手机端无感登录。
                // 凭据留空时退回系统默认行为（弹框），便于本地无认证场景。
                if (TextUtils.isEmpty(AUTH_USER)) {
                    super.onReceivedHttpAuthRequest(view, handler, host, realm);
                    return;
                }
                handler.proceed(AUTH_USER, AUTH_PASS);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 每次页面加载完成后注入移动端皮肤（脚本内部幂等，重复注入安全）
                view.evaluateJavascript(MOBILE_SKIN_JS, null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                // 网页里的"附件"按钮 → 拉起 Android 系统文件选择器
                fileChooserCallback = callback;
                Intent intent = params.createIntent();
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (android.content.ActivityNotFoundException e) {
                    fileChooserCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl(TARGET_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    // 多选
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    // 单选
                    results = new Uri[]{ data.getData() };
                }
            }
            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(results);
                fileChooserCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * 移动端皮肤脚本 —— 本项目的核心部分。
     *
     * 为什么要在 Java 里内联一段 JS：dsh 的 Web GUI 是响应式设计，但它的响应式
     * 断点面向桌面窗口拖拽，不是手机视口。手机上的实际症状是：字号偏大、底部
     * 输入行按钮互相挤压、侧边栏展开后占用固定 280px 把正文压没。
     * 这些都无法通过 WebView 设置解决，只能注入 CSS/JS 去改页面本身的布局。
     *
     * 三个部分：
     *   1. 深色主题驱动 —— dsh 主题脚本读 matchMedia('(prefers-color-scheme: dark)')，
     *      手机若为浅色模式就会渲染浅色界面。这里直接切它自己的开关
     *      （data-ds-dark-theme + colorScheme），比 setForceDark 干净
     *      ——后者会双重暗化出黑块。
     *   2. 基础皮肤 CSS —— 触控热区、字号、防溢出、窄屏换行。
     *      注入用 id 做幂等标记，onPageFinished 可重复调用。
     *   3. 侧边栏抽屉桥接 —— 见下方长注释。
     *
     * ⚠️ 类名是 CSS Modules 哈希（如 VOzbGW_panel、uV2eYG_row），会随 dsh 版本变化。
     *    升级 dsh 后若样式失效，用浏览器开发者工具重新取哈希前缀即可。
     */
    private static final String MOBILE_SKIN_JS =
        "(function(){" +
        // 1) 深色主题驱动：dsh 主题脚本读系统偏好，直接切它自己的开关
        "document.documentElement.style.colorScheme='dark';" +
        "document.body.setAttribute('data-ds-dark-theme','');" +
        // 2) 基础皮肤 CSS（幂等注入）
        "if(!document.getElementById('dsh-mobile-skin')){" +
        "var st=document.createElement('style');" +
        "st.id='dsh-mobile-skin';" +
        "st.textContent=" +
        "'html{-webkit-text-size-adjust:100%}'+" +
        "'body{touch-action:manipulation}'+" +
        "'*{-webkit-tap-highlight-color:transparent}'+" +
        "'button,[role=button],a,select{min-height:38px!important}'+" +
        "'input,textarea,select{font-size:16px!important}'+" +
        "'img,svg,video{max-width:100%!important;height:auto!important}'+" +
        // 设置面板：可滚动 + 内部 flex 可收缩防横向溢出
        "'[class*=\"VOzbGW_panel\"]{overflow-y:auto!important;max-height:100dvh!important;}'+" +
        "'[class*=\"VOzbGW_panel\"] *{min-width:0!important;max-width:100%!important;}'+" +
        "'@media (max-width:560px){'+" +
        // 手机屏小：整体缩小 10%（字体+布局同步小），按钮行紧凑
        "'html{zoom:0.9}'+" +
        "'div:has(> button){flex-wrap:wrap!important;gap:6px!important;}'+" +
        // composer 输入行：按钮组 flex nowrap 会互相挤压 → 强制换行
        "'[class*=\"uV2eYG_row\"]{flex-wrap:wrap!important;gap:6px!important;}'+" +
        "'[class*=\"uV2eYG_row\"] > *{flex:1 1 auto!important;min-width:0!important;}'+" +
        "'button[aria-label^=\"命令\"]{display:none!important;}'+" +
        "'textarea{min-width:0!important;}'+" +
        "'}'" +
        ";" +
        "document.head.appendChild(st);" +
        "}" +
        // 3) 侧边栏抽屉桥接（仅窄屏；单例，onPageFinished 重复注入安全）
        //
        // 【这是什么问题】
        // dsh 的侧边栏展开/收起 = 外层 frame 的 CSS grid 第一列在 280px / 56px 之间切换。
        // 手机上展开后，280px 的侧边栏会挤掉正文；而且它的展开/收起按钮的
        // aria-label 会随状态在"打开侧边栏"/"收起侧边栏"之间切换，
        // 这个 label 就是 React 的真实状态标志。
        //
        // 【天真解法为什么不行】
        // 第一反应是给侧边栏加 CSS `position:fixed!important` 让它浮起来。
        // 但 fixed 会让元素脱离 grid 流，此时 dsh 自己的收起逻辑操作的是 grid 列宽，
        // 元素已经不在流里 → 状态和视觉不同步 → 收起时卡死。
        // 所以：**布局切换必须由 JS 自己接管**，不能只靠 CSS 覆盖。
        //
        // 【本方案】
        // - 用 setInterval 轮询（250ms）读那个 aria-label，得到 React 的真实意图；
        // - 展开时：把 grid 第一列压成 0、正文改到第 2 列占满、侧边栏转为 fixed 抽屉 + 遮罩；
        // - 收起时：还原 React 原本的 grid 值（56px rail）、清掉所有内联样式。
        // - 用 window.__dshBridge 做单例守卫，避免 onPageFinished 多次触发时重复挂载。
        //
        // ⚠️ 为什么用轮询而不是 MutationObserver：
        //    WebView 里 MutationObserver 对这类频繁的样式/属性变更不可靠，
        //    实测会漏回调。250ms 轮询足够跟手，且开销可忽略（只读几个 aria-label）。
        "if(window.matchMedia('(max-width:560px)').matches&&!window.__dshBridge){" +
        "var el=document.querySelector('[class*=\"sidebarCol\"]');" +
        "if(el){(function(){" +
        "var frame=el.parentElement;" +
        "var center=frame.querySelector('[class*=\"centerCol\"]')||frame.children[1];" +
        "var mask=null,desired=null,timer=null;" +
        "function ensureMask(){" +
        "if(mask&&document.body.contains(mask))return;" +
        "mask=document.createElement('div');" +
        "mask.id='dsh-drawer-mask';" +
        "mask.style.cssText='position:fixed;inset:0;z-index:999;background:rgba(0,0,0,.35);';" +
        "mask.addEventListener('click',function(){" +
        "var b=Array.from(document.querySelectorAll('button')).find(function(x){return (x.getAttribute('aria-label')||'').indexOf('收起')>=0;});" +
        "if(b)b.click();" +
        "});" +
        "document.body.appendChild(mask);" +
        "}" +
        "function apply(){" +
        // React 状态判定：按钮 label 含"收起" = 展开态（不受我们的写入污染）
        "var open=!!Array.from(document.querySelectorAll('button')).find(function(b){return (b.getAttribute('aria-label')||'').indexOf('收起')>=0;});" +
        "if(open===desired)return;" +
        "desired=open;" +
        "if(open){" +
        "frame.style.gridTemplateColumns='0px minmax(0px, 1fr) 0px';" +
        "center.style.gridColumn='2';" +
        "el.style.cssText='position:fixed;top:0;left:0;bottom:0;width:min(78vw,320px);z-index:1000;box-shadow:0 0 24px rgba(0,0,0,.45);overflow-y:auto;';" +
        "ensureMask();" +
        "}else{" +
        "frame.style.gridTemplateColumns='56px minmax(0px, 1fr) 0px';" +
        "center.style.gridColumn='';" +
        "el.style.cssText='';" +
        "if(mask){mask.remove();mask=null;}" +
        "}" +
        "}" +
        "timer=setInterval(apply,250);" +
        "window.__dshBridge={teardown:function(){clearInterval(timer);" +
        "if(mask&&mask.parentNode)mask.parentNode.removeChild(mask);" +
        "el.style.cssText='';center.style.gridColumn='';frame.style.gridTemplateColumns='';}};" +
        "apply();" +
        "})()" +
        "}" +         // if(el)
        "}" +         // if(window.matchMedia...)
        "})()";

    @Override
    public void onBackPressed() {
        // 返回键优先走网页历史，到底了才退出 App
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
