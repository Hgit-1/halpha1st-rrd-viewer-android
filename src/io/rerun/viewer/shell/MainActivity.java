package io.rerun.viewer.shell;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.EditText;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String VIEWER_PAGE = "http://127.0.0.1:%d/index.html?renderer=webgl&memory_limit_bytes=100663296&asset_version=58";
    private static final String LOCAL_FILE_PREFIX = "/local/";
    private static final int REQUEST_STORAGE = 4101;
    private static final int REQUEST_PICK_FILE = 4102;
    private static final String TAG = "RerunViewer";
    // Fail closed: never silently send an unbounded recording to WASM.
    private volatile boolean enableStaticSegmentation = true;
    private boolean importPreparing;
    private volatile int importGeneration;
    private final Map<String, Integer> initialParts = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
    private String lastConsoleWarning;
    private long lastConsoleWarningAt;
    private int suppressedWarnings;

    private WebView viewer;
    private TextView error;
    private ValueCallback<Uri[]> pendingFileCallback;
    private final Map<String, File> incomingFiles = new java.util.concurrent.ConcurrentHashMap<String, File>();
    private final Map<String, RrdFrames.Index> incomingIndexes = new java.util.concurrent.ConcurrentHashMap<String, RrdFrames.Index>();
    private final Map<String, File> incomingSegmentFiles = new java.util.concurrent.ConcurrentHashMap<String, File>();
    private final Map<String, IncomingSource> incomingSources = new java.util.concurrent.ConcurrentHashMap<String, IncomingSource>();
    private boolean viewerStarted;
    private String currentSegmentId;
    private int currentSegmentPart;
    private String currentSegmentUrl;
    
    // Dynamic windows are retained as an experimental endpoint, but are not
    // selected for normal imports until the bundled Viewer exposes a reliable
    // playback-position API. Normal imports use explicit complete-frame segments.
    private boolean useDynamicWindows = false;
    private int windowSize = 300;        // Fixed window size: 300 frames
    private int lookBehind = 200;        // Load windows covering 200 frames behind
    private int lookAhead = 100;         // Load windows covering 100 frames ahead
    private int maxWindows = 6;          // Maximum concurrent windows
    
    private String currentWindowId;
    private String pendingDynamicWindowId;
    private int totalFrames = 0;
    private int currentTimelinePosition = 0;
    private long lastPositionUpdate = 0;
    
    // Track loaded windows by ID
    private static class WindowRecord {
        final int windowId;
        final String url;
        final int startFrame;
        final int endFrame;
        long lastAccessTime;
        
        WindowRecord(int id, String url, int start, int end) {
            this.windowId = id;
            this.url = url;
            this.startFrame = start;
            this.endFrame = end;
            this.lastAccessTime = System.currentTimeMillis();
        }
    }
    
    private final java.util.HashMap<Integer, WindowRecord> loadedWindows = new java.util.HashMap<Integer, WindowRecord>();
    private final java.util.HashSet<String> activeWindows = new java.util.HashSet<String>();
    
    private final Map<String, Long> segmentEofAt = new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private boolean segmentSwitchScheduled;
    private Runnable automaticSegmentAdvance;
    private final Map<String, String> liveSources = new java.util.concurrent.ConcurrentHashMap<String, String>();
    private final Map<String, RrdLiveSession> liveSessions = new java.util.concurrent.ConcurrentHashMap<String, RrdLiveSession>();
    private String liveStreamUrl;
    private Intent pendingIntent;
    private LocalViewerServer localServer;
    private String viewerPage;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileWorkers = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private TextView importStatus;
    private TextView debugLog;
    private final java.util.LinkedList<String> logBuffer = new java.util.LinkedList<String>();
    private static final int MAX_LOG_LINES = 100;
    private FrameLayout viewerRoot;
    private Bitmap transitionBitmap;
    private ImageView transitionCover;
    private final ArrayList<String> pendingReceivers = new ArrayList<String>();
    private final ArrayList<String> activeReceivers = new ArrayList<String>();
    private boolean viewerReady;
    private boolean rendererRecoveryAttempted;
    private boolean forceSoftwareLayer;
    private String lastStage = "native-start";
    private File debugLogFile;
    private final Object debugLogLock = new Object();
    private final Runnable startupWatchdog = new Runnable() {
        @Override public void run() {
            if (!viewerReady && viewer != null)
                showError("Rerun 初始化超时。请检查系统 WebView 是否支持 WebAssembly 和 WebGL。\n\n点按重试");
        }
    };
    private long lastJsHeartbeat;
    private long importActiveSince;
    private final Runnable heartbeatCheck = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (viewer != null && viewerReady && importActiveSince > 0 && importStatus != null) {
                final long probeStarted = System.currentTimeMillis();
                try {
                    viewer.evaluateJavascript("'alive'", new ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            lastJsHeartbeat = System.currentTimeMillis();
                            if (importStatus != null) {
                                String text = importStatus.getText().toString();
                                if (text.startsWith("Viewer JS 暂停响应") || text.startsWith("未收到 Viewer 心跳"))
                                    importStatus.setVisibility(View.GONE);
                            }
                        }
                    });
                } catch (Throwable probeFailure) {
                    Log.w(TAG, "Viewer heartbeat probe failed", probeFailure);
                }
                long now = System.currentTimeMillis();
                long age = lastJsHeartbeat == 0 ? now - importActiveSince : now - lastJsHeartbeat;
                if (age > 15000) {
                    String prefix = lastJsHeartbeat == 0 ? "未收到 Viewer 响应" : "Viewer JS 暂停响应";
                    importStatus.setText(prefix + "：" + (age / 1000) + " 秒；原生界面仍运行");
                    importStatus.setVisibility(View.VISIBLE);
                }
            }
            mainHandler.postDelayed(this, 5000);
        }
    };
    private final Runnable importWatchdog = new Runnable() {
        @Override public void run() {
            if (!destroyed && importStatus != null
                    && importActiveSince > 0) {
                importStatus.setText("记录仍在载入；可能超出 WebView 可用内存");
                importStatus.setVisibility(View.VISIBLE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initDebugLog();
        debugLog("native-start version=58 mode=bounded-preview viewer-gc=96MiB");
        pendingIntent = getIntent();
        try {
            buildView();
            localServer = new LocalViewerServer();
            // ServerSocket/Socket operations must not run on Android's main
            // thread. Huawei's runtime throws NetworkOnMainThreadException.
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        localServer.start();
                        localServer.verifyTcpConnection();
                        final String page = String.format(java.util.Locale.US, VIEWER_PAGE,
                                localServer.getPort());
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                viewerPage = page;
                                requestStorageThenStart();
                            }
                        });
                    } catch (final Throwable failure) {
                        Log.e(TAG, "Failed to initialize local viewer server", failure);
                        mainHandler.post(new Runnable() {
                            @Override public void run() { showFatalError(failure); }
                        });
                    }
                }
            }, "rerun-local-server-init").start();
        } catch (Throwable failure) {
            Log.e(TAG, "Failed to initialize viewer", failure);
            showFatalError(failure);
        }
    }

    private void buildView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(13, 16, 17));

        // Enable WebView debugging for chrome://inspect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        viewer = new WebView(this);
        viewer.setBackgroundColor(Color.rgb(13, 16, 17));
        viewer.setOverScrollMode(View.OVER_SCROLL_NEVER);
        viewer.setVerticalScrollBarEnabled(false);
        viewer.setHorizontalScrollBarEnabled(false);
        viewer.setFocusable(true);
        viewer.setFocusableInTouchMode(true);
        viewer.setHapticFeedbackEnabled(true);
        viewer.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                if ((event.getActionMasked() == MotionEvent.ACTION_DOWN
                        || event.getActionMasked() == MotionEvent.ACTION_MOVE)
                        && view.getParent() != null) {
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                }
                if ((event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                        && view.getParent() != null) {
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            }
        });
        viewer.setLongClickable(false);
        WebSettings settings = viewer.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Keep the static Viewer bundle reusable across segment WebView rebuilds.
        // Recording responses remain no-store below; only index/JS/WASM are cacheable.
        settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        // The system picker returns content:// URIs. The selected file is also
        // copied into the app cache below, so this is only needed by Chromium
        // while the chooser result is being handed back to the page.
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true); // Honor bundled mobile viewport.
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setTextZoom(100);
        if (forceSoftwareLayer) {
            // Some Huawei WebView/GPU combinations terminate the Chromium
            // renderer while creating Rerun's WebGL context. Retry once with
            // software compositing instead of leaving a dead WebView on screen.
            viewer.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            Log.w(TAG, "Using software WebView layer for renderer recovery");
        }
        final WebView bridgeViewer = viewer;
        viewer.addJavascriptInterface(new Object() {
            @JavascriptInterface public void stage(final String value) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed || viewer != bridgeViewer) return;
                        lastStage = value;
                        debugLog("stage=" + value);
                        if (value.startsWith("metrics:")) {
                            Runtime runtime = Runtime.getRuntime();
                            debugLog("native-heap-used=" + (runtime.totalMemory() - runtime.freeMemory())
                                    + " native-pss-KiB=" + android.os.Debug.getPss());
                        }
                        if (value.startsWith("fatal:")) {
                            pendingReceivers.clear();
                            activeReceivers.clear();
                            importStatusActiveReset();
                            viewerReady = false;
                            mainHandler.removeCallbacks(startupWatchdog);
                            WebView failed = viewer;
                            viewer = null;
                            if (failed.getParent() instanceof FrameLayout)
                                ((FrameLayout) failed.getParent()).removeView(failed);
                            failed.stopLoading();
                            failed.destroy();
                            showError("Viewer 执行异常，已停止自动重试。请点按清空后重新选择分段。\n" + value);
                        }
                        Log.i(TAG, "Viewer stage: " + value);
                    }
                });
            }

            @JavascriptInterface public void heartbeat() {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed || viewer != bridgeViewer) return;
                        lastJsHeartbeat = System.currentTimeMillis();
                        if (importStatus != null) {
                            String text = importStatus.getText().toString();
                            if (text.startsWith("Viewer JS 暂停响应") || text.startsWith("未收到 Viewer 响应"))
                                importStatus.setVisibility(View.GONE);
                        }
                    }
                });
            }

            @JavascriptInterface public void ready() {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed || viewer == null || viewer != bridgeViewer) return;
                        viewerReady = true;
                        removeTransitionCover();
                        mainHandler.removeCallbacks(startupWatchdog);
                        if (error != null) error.setVisibility(View.GONE);
                        if (viewer != null) {
                            for (String url : activeReceivers) addReceiver(url);
                            for (String url : pendingReceivers) {
                                addReceiver(url);
                                activeReceivers.add(url);
                            }
                            pendingReceivers.clear();
                            if (pendingDynamicWindowId != null) {
                                String fileId = pendingDynamicWindowId;
                                pendingDynamicWindowId = null;
                                loadInitialWindow(fileId);
                            }
                        }
                    }
                });
            }
        }, "RerunAndroid");
        if (Build.VERSION.SDK_INT >= 26) {
            viewer.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        // Rerun's viewer has its own responsive layout. A desktop-compatible UA
        // skips the warning banner while keeping the original Rerun visual design.
        settings.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                + "Chrome/120.0.0.0 Safari/537.36");
        viewer.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                String msg = message.message();
                
                // Capture timeline position
                if (msg != null && msg.startsWith("TIMELINE_POS:")) {
                    try {
                        int frame = Integer.parseInt(msg.substring(13));
                        currentTimelinePosition = frame;
                        lastPositionUpdate = System.currentTimeMillis();
                    } catch (Exception e) {
                        // Ignore
                    }
                }
                
                if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR
                        || message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING
                            && msg != null && msg.equals(lastConsoleWarning) && now - lastConsoleWarningAt < 10000) {
                        suppressedWarnings++;
                        return true;
                    }
                    if (suppressedWarnings > 0) debugLog("JS repeated warnings suppressed=" + suppressedWarnings);
                    suppressedWarnings = 0;
                    lastConsoleWarning = msg;
                    lastConsoleWarningAt = now;
                    debugLog("JS " + message.messageLevel() + ": "
                            + (msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg));
                }
                return false;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             WebChromeClient.FileChooserParams params) {
                if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
                pendingFileCallback = callback;
                openFilePicker();
                return true;
            }
        });
        viewer.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                WebResourceResponse response = openIncomingWebResource(url);
                return response != null ? response : null;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view != viewer) return true;
                boolean crashed = detail != null && detail.didCrash();
                Log.e(TAG, "WebView renderer exited; didCrash=" + crashed
                        + ", stage=" + lastStage);
                debugLog("renderer-gone didCrash=" + crashed + " stage=" + lastStage);
                pendingReceivers.clear();
                activeReceivers.clear();
                importStatusActiveReset();
                if (!rendererRecoveryAttempted) {
                    rendererRecoveryAttempted = true;
                    forceSoftwareLayer = false; // Do not add software-compositing memory after a crash.
                    mainHandler.post(new Runnable() {
                        @Override public void run() { recoverFromRendererGone(); }
                    });
                } else {
                    mainHandler.removeCallbacks(startupWatchdog);
                    if (viewer != null) {
                        FrameLayout parent = (FrameLayout) viewer.getParent();
                        if (parent != null) parent.removeView(viewer);
                        viewer.destroy();
                        viewer = null;
                    }
                    showError("Rerun WebView 渲染进程退出，阶段：" + lastStage
                            + "。软件合成重试也失败。\n\n点按此处重建 WebView；请截图此阶段文字以便排查。");
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.startsWith("http://127.0.0.1:")) {
                    showError("Rerun 本地资源加载失败。\n\n请点击重试，或重新安装 APK。");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // A loaded HTML page is not proof that WebAssembly initialized.
            }
        });
        root.addView(viewer, new FrameLayout.LayoutParams(-1, -1));

        ImageButton openButton = new ImageButton(this);
        openButton.setContentDescription("打开 RRD 文件");
        openButton.setTooltipText("打开文件；长按加载选项");
        openButton.setImageResource(R.drawable.ic_folder_open);
        openButton.setColorFilter(Color.rgb(226, 234, 237));
        openButton.setPadding(dp(15), dp(15), dp(15), dp(15));
        openButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setShape(GradientDrawable.RECTANGLE);
        buttonBackground.setColor(Color.argb(220, 35, 43, 46));
        buttonBackground.setCornerRadius(dp(18));
        buttonBackground.setStroke(dp(1), Color.argb(150, 119, 137, 143));
        openButton.setBackground(buttonBackground);
        openButton.setElevation(dp(4));
        openButton.setAlpha(0.94f);
        openButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openNativeFilePicker(); }
        });
        openButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                showViewerActions();
                return true;
            }
        });
        FrameLayout.LayoutParams openParams = new FrameLayout.LayoutParams(dp(54), dp(48));
        openParams.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        // Keep the upstream top-panel toggles unobstructed.
        openParams.setMargins(0, dp(40), dp(26), 0);
        root.addView(openButton, openParams);

        error = new TextView(this);
        error.setTextColor(Color.WHITE);
        error.setTextSize(16);
        error.setGravity(android.view.Gravity.CENTER);
        error.setPadding(32, 32, 32, 32);
        error.setBackgroundColor(Color.rgb(13, 16, 17));
        error.setVisibility(View.GONE);
        error.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                error.setVisibility(View.GONE);
                stopImportAndReload();
            }
        });
        root.addView(error, new FrameLayout.LayoutParams(-1, -1));
        // Keep legacy status bookkeeping detached: never overlay the Rerun
        // timeline or render stale import watchdog text on the canvas.
        importStatus = new TextView(this);
        importStatus.setVisibility(View.GONE);
        importStatus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        viewerRoot = root;
        if (transitionBitmap != null) {
            transitionCover = new ImageView(this);
            transitionCover.setImageBitmap(transitionBitmap);
            transitionCover.setScaleType(ImageView.ScaleType.FIT_XY);
            transitionCover.setBackgroundColor(Color.rgb(13, 16, 17));
            root.addView(transitionCover, new FrameLayout.LayoutParams(-1, -1));
        }
        setContentView(root);
        mainHandler.removeCallbacks(heartbeatCheck);
        mainHandler.postDelayed(heartbeatCheck, 5000);
    }

    private void requestStorageThenStart() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 32
                && (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
        } else {
            startViewerFromIntent();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_STORAGE) {
            initDebugLog();
            debugLog("storage-permission-callback");
            startViewerFromIntent();
        }
    }

    private void startViewerFromIntent() {
        if (viewerStarted) return;
        viewerStarted = true;
        Intent initialIntent = pendingIntent;
        pendingIntent = null;
        if (initialIntent != null && handleIncomingIntent(initialIntent)) {
            // Render the shell while the selected document is being copied.
            loadViewerPage(viewerPage);
            return;
        }
        loadViewerPage(viewerPage);
    }

    private void loadViewerPage(String page) {
        viewerReady = false;
        importActiveSince = 0;
        lastJsHeartbeat = 0;
        mainHandler.removeCallbacks(startupWatchdog);
        mainHandler.postDelayed(startupWatchdog, 110000);
        viewer.loadUrl(page);
    }

    private boolean handleIncomingIntent(Intent intent) {
        String action = intent.getAction();
        Uri direct = intent.getData();
        if (Intent.ACTION_VIEW.equals(action) && direct != null) {
            if ("http".equalsIgnoreCase(direct.getScheme())
                    || "https".equalsIgnoreCase(direct.getScheme())) {
                return connectLiveStream(direct.toString());
            }
            return loadIncomingUris(singleUri(direct));
        }
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> streams = new ArrayList<Uri>();
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) streams.add(stream);
            if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> multiple = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (multiple != null) streams.addAll(multiple);
            }
            if (!streams.isEmpty()) return loadIncomingUris(streams);
        }
        return false;
    }

    private void showViewerActions() {
        final String[] actions = {
            "小文件完整加载（上限 8MiB；仍有解码风险）",
            "安全分段加载（默认；优先含图像标识的段）",
            "选择当前记录分段",
            "停止并清空 Viewer",
            "连接 HTTP 直播",
            "定位检测框标识所在分段"
        };
        new AlertDialog.Builder(this).setTitle("Rerun v58 · 加载选项")
                .setItems(actions, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        if (importPreparing && which != 3) {
                            new AlertDialog.Builder(MainActivity.this).setMessage("正在索引，请稍后；可选择停止取消当前加载。")
                                    .setPositiveButton("确定", null).show();
                            return;
                        }
                        if (which == 0 || which == 1) {
                            enableStaticSegmentation = which == 1;
                            stopImportAndReload();
                            openNativeFilePicker();
                        } else if (which == 2) {
                            if (currentSegmentId != null) showSegmentPicker();
                            else new AlertDialog.Builder(MainActivity.this)
                                    .setMessage("当前不是分段模式。完整加载不需要选择分段。")
                                    .setPositiveButton("确定", null).show();
                        } else if (which == 3) stopImportAndReload();
                        else if (which == 4) {
                            stopImportAndReload();
                            showLiveUrlDialog();
                        } else if (which == 5) locatePreviewSegment(true);
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void showLiveUrlDialog() {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("http://192.168.1.100:9876/recording");
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        field.setPadding(dp(18), dp(12), dp(18), dp(12));
        new AlertDialog.Builder(this).setTitle("连接 Rerun HTTP 直播流")
                .setMessage("长按右上角文件夹打开此入口；只接受可信的 RRD HTTP(S) 流")
                .setView(field)
                .setNegativeButton("取消", null)
                .setPositiveButton("连接", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        connectLiveStream(field.getText().toString().trim());
                    }
                }).show();
    }

    private boolean connectLiveStream(String address) {
        Uri uri;
        try { uri = Uri.parse(address); }
        catch (RuntimeException invalid) { showError("直播 URL 无效"); return false; }
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isEmpty()
                || uri.getUserInfo() != null) {
            showError("只支持不含账号密码的 http(s) RRD 流 URL");
            return false;
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        liveSources.put(id, uri.toString());
        final RrdLiveSession session = new RrdLiveSession(uri.toString(),
                new File(new File(getCacheDir(), "live-dvr"), "stream-" + id + ".rrd"),
                new RrdLiveSession.Listener() {
                    @Override public void onState(final String text) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                if (!destroyed && importStatus != null) {
                                    importStatus.setText(text);
                                    importStatus.setVisibility(View.VISIBLE);
                                }
                            }
                        });
                    }
                });
        liveSessions.put(id, session);
        session.start();
        liveStreamUrl = "http://127.0.0.1:" + localServer.getPort() + "/live/" + id + "/stream.rrd";
        currentSegmentId = null;
        pendingReceivers.clear();
        activeReceivers.clear();
        if (importStatus != null) {
            importStatus.setText("正在连接 HTTP 直播流…（点此停止）");
            importStatus.setVisibility(View.VISIBLE);
        }
        if (viewerReady && viewer != null) {
            addReceiver(liveStreamUrl);
            activeReceivers.add(liveStreamUrl);
        } else pendingReceivers.add(liveStreamUrl);
        return true;
    }

    private ArrayList<Uri> singleUri(Uri uri) {
        ArrayList<Uri> uris = new ArrayList<Uri>();
        uris.add(uri);
        return uris;
    }

    private boolean loadIncomingUris(final ArrayList<Uri> uris) {
        if (uris.isEmpty()) return false;
        if (importPreparing || uris.size() != 1) {
            new AlertDialog.Builder(this).setMessage("请等待当前索引完成，每次只打开一个记录。")
                    .setPositiveButton("确定", null).show();
            return false;
        }
        if (!activeReceivers.isEmpty() || !pendingReceivers.isEmpty()) stopImportAndReload();
        importPreparing = true;
        final int generation = importGeneration;
        if (importStatus != null) {
            importStatus.setText("正在后台准备文件…");
            importStatus.setVisibility(View.VISIBLE);
        }
        fileWorkers.execute(new Runnable() {
            @Override public void run() {
                // Remove orphaned copies from prior runs; do not delete any
                // file currently registered with the HTTP viewer.
                File[] cached = getCacheDir().listFiles();
                if (cached != null) for (File file : cached) {
                    if (file.getName().matches("[0-9a-f]{32}-.*\\.(rrd|rbl)")
                            && !incomingFiles.containsValue(file)) file.delete();
                }
                final ArrayList<String> urls = new ArrayList<String>();
                for (Uri uri : uris) {
                    String url = cacheIncomingFile(uri);
                    if (url != null) urls.add(url);
                }
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        importPreparing = false;
                        if (destroyed || generation != importGeneration) {
                            incomingIndexes.clear(); incomingSources.clear(); initialParts.clear();
                            for (File file : incomingFiles.values()) file.delete();
                            incomingFiles.clear();
                            return;
                        }
                        if (urls.isEmpty()) {
                            showError("安全加载失败：文件可能不支持分段、长度未知、单帧过大，或完整模式超过 8MiB。未回退到整文件加载。请查看调试日志。");
                            return;
                        }
                        if (importStatus != null) {
                            importStatus.setText(viewerReady ? "文件已准备，正在打开记录…"
                                    : "文件已准备，等待 Viewer 初始化…");
                            importStatus.setVisibility(View.VISIBLE);
                        }
                        currentSegmentId = null;
                        currentSegmentPart = 0;
                        if (urls.size() > 1 && urls.get(0).contains("/part-")) {
                            showError("分段模式一次只能打开一个文件");
                            return;
                        }
                        for (String url : urls) {
                            if (url.startsWith("dynamic:")) {
                                // Dynamic window mode
                                String fileId = url.substring(8);
                                if (viewerReady && viewer != null) {
                                    loadInitialWindow(fileId);
                                } else {
                                    pendingDynamicWindowId = fileId;
                                    debugLog("DYNAMIC_WINDOW: queued until Viewer ready: " + fileId);
                                }
                                continue;  // Don't add dynamic: URL to receivers
                            } else if (currentSegmentId == null && url.contains("/part-")) {
                                currentSegmentId = localIdFromUrl(url);
                                Integer selectedPart = initialParts.get(currentSegmentId);
                                currentSegmentPart = selectedPart == null ? 0 : selectedPart;
                                currentSegmentUrl = url;
                            }
                            if (viewerReady && viewer != null) {
                                addReceiver(url);
                                activeReceivers.add(url);
                            } else pendingReceivers.add(url);
                        }
                    }
                });
            }
        });
        return true;
    }

    private String localIdFromUrl(String url) {
        try {
            String path = Uri.parse(url).getPath();
            if (path == null || !path.startsWith(LOCAL_FILE_PREFIX)) return null;
            String rest = path.substring(LOCAL_FILE_PREFIX.length());
            int slash = rest.indexOf('/');
            return slash > 0 ? rest.substring(0, slash) : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private WebResourceResponse openIncomingWebResource(String url) {
        try {
            Uri parsed = Uri.parse(url);
            if (localServer == null || !"http".equals(parsed.getScheme())
                    || !"127.0.0.1".equals(parsed.getHost())
                    || parsed.getPort() != localServer.getPort()) return null;
            String path = parsed.getPath();
            if (path == null || !path.startsWith(LOCAL_FILE_PREFIX)) return null;
            String rest = path.substring(LOCAL_FILE_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash < 1) return null;
            // Window responses must come from the validated HTTP window endpoint,
            // not the whole-file fallback below.
            if ("frames".equals(rest.substring(slash + 1))) return null;
            String id = rest.substring(0, slash);
            IncomingSource source = incomingSources.get(id);
            File file = incomingFiles.get(id);
            InputStream input;
            String name;
            long length;
            int segmentNumber = -1;
            if (source != null) {
                InputStream sourceInput = getContentResolver().openInputStream(source.uri);
                if (sourceInput == null) return null;
                RrdFrames.Index index = incomingIndexes.get(id);
                String filename = rest.substring(slash + 1);
                if (index != null && filename.startsWith("part-") && filename.endsWith(".rrd")) {
                    int part;
                    try { part = Integer.parseInt(filename.substring(5, filename.length() - 4)); }
                    catch (NumberFormatException badPart) { sourceInput.close(); return null; }
                    if (part < 0 || part >= index.segments.size()) { sourceInput.close(); return null; }
                    RrdFrames.Segment segment = index.segments.get(part);
                    if (index.selectedParts != null) {
                        input = new RrdFrames.SparseStream(sourceInput, index, index.selectedParts.get(part));
                    } else {
                        input = RrdFrames.open(sourceInput, index, part);
                    }
                    segmentNumber = part;
                    name = "part-" + part + ".rrd";
                    if (index.selectedParts != null) {
                        long sparseLength = index.preambleEnd + 8;
                        for (Integer n : index.selectedParts.get(part)) sparseLength += index.frames.get(n).encodedSize;
                        length = sparseLength;
                    } else length = index.preambleEnd + segment.sampledLength + 8;
                } else {
                    input = sourceInput;
                    name = source.name;
                    length = source.length;
                }
            } else if (file != null && file.isFile()) {
                input = new FileInputStream(file);
                name = file.getName();
                length = file.length();
            } else return null;
            if (input == null) return null;
            if (segmentNumber >= 0) {
                final String eofKey = id + ":" + segmentNumber;
                input = new SegmentEofInputStream(input, length, new Runnable() {
                    @Override public void run() {
                        long timestamp = System.currentTimeMillis();
                        segmentEofAt.put(eofKey, timestamp);
                        Log.i(TAG, "[DIAG] Segment EOF detected: " + eofKey + " at " + timestamp);
                        debugLog("EOF: " + eofKey);
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                Log.i(TAG, "[DIAG] Calling scheduleAutomaticSegmentAdvance for: " + eofKey);
                                scheduleAutomaticSegmentAdvance();
                            }
                        });
                    }
                });
            }
            String mime = URLConnection.guessContentTypeFromName(name);
            if (mime == null) mime = "application/octet-stream";
            WebResourceResponse response = new WebResourceResponse(mime, null, input);
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "no-store");
            if (length >= 0) headers.put("Content-Length", String.valueOf(length));
            response.setResponseHeaders(headers);
            Log.i(TAG, "WebView direct stream: " + name + " bytes=" + length);
            return response;
        } catch (Exception failure) {
            Log.e(TAG, "Unable to intercept local recording", failure);
            return null;
        }
    }

    private void addReceiver(final String url) {
        if (viewer == null) return;
        // The WebHandle is assigned only after its start() Promise resolves.
        String script = "(function(){if(!window._handle)return 'not-ready';"
                + "window._handle.add_receiver(" + org.json.JSONObject.quote(url)
                + "," + (liveStreamUrl != null && liveStreamUrl.equals(url) ? "true" : "false")
                + ");return 'submitted';})()";
        viewer.evaluateJavascript(script, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                Log.i(TAG, "RRD receiver result=" + value);
                if ("\"submitted\"".equals(value)) {
                    if (importStatus != null) {
                        importActiveSince = System.currentTimeMillis();
                        lastJsHeartbeat = importActiveSince;
                        if (currentSegmentId != null) {
                            RrdFrames.Index index = incomingIndexes.get(currentSegmentId);
                            int count = index == null ? 0 : index.segments.size();
                            importStatus.setText("正在播放分段 " + (currentSegmentPart + 1)
                                    + "/" + count + "，完整帧模式");
                            if (segmentEofAt.containsKey(currentSegmentId + ":" + currentSegmentPart))
                                scheduleAutomaticSegmentAdvance();
                        } else if (liveStreamUrl != null && liveStreamUrl.equals(url)) {
                            importStatus.setText("直播已连接；点此停止直播");
                        } else importStatus.setText("正在解析记录…（点此停止）");
                        importStatus.setVisibility(View.VISIBLE);
                    }
                    mainHandler.removeCallbacks(importWatchdog);
                    mainHandler.postDelayed(importWatchdog, 180000);
                    if (currentSegmentId == null) importStatus.setVisibility(View.GONE);
                } else if ("\"not-ready\"".equals(value)) {
                    if (!pendingReceivers.contains(url)) pendingReceivers.add(url);
                    if (importStatus != null) importStatus.setText("等待 Viewer 初始化后打开记录…");
                } else {
                    showError("文件已准备，但 Viewer 拒绝打开记录：" + value);
                }
            }
        });
    }

    private long recordingSegmentTargetBytes() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager != null && manager.isLowRamDevice()) return 2L * 1024 * 1024;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(info);
        if (info.totalMem > 0 && info.totalMem < 3L * 1024 * 1024 * 1024)
            return 3L * 1024 * 1024;
        return 4L * 1024 * 1024;
    }

    private String cacheIncomingFile(Uri uri) {
        File output = null;
        try {
            ContentResolver resolver = getContentResolver();
            String name = displayName(resolver, uri);
            if (name == null) name = "recording.rrd";
            String id = UUID.randomUUID().toString().replace("-", "");
            // A large RRD must not be duplicated on nearly full internal storage.
            // The TCP worker opens the granted URI afresh for each HTTP request.
            long length = -1;
            try (android.os.ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "r")) {
                if (fd != null) length = fd.getStatSize();
            }
            if (enableStaticSegmentation && name.toLowerCase(java.util.Locale.US).endsWith(".rrd")) {
                try (InputStream scan = resolver.openInputStream(uri)) {
                    if (scan != null) {
                        RrdFrames.Index index = RrdFrames.scan(scan, length, recordingSegmentTargetBytes());
                        for (RrdFrames.Segment segment : index.segments) {
                            if (segment.uncompressedLength > 16L * 1024 * 1024
                                    || index.preambleEnd + segment.sampledLength + 8 > 8L * 1024 * 1024)
                                throw new IOException("Single frame/prefix exceeds preview budget");
                        }
                        // A byte-sized part can contain seconds of unrelated timelines while
                        // the actual image stream has already ended. Build image-centered parts
                        // from marker-bearing Arrow messages before exposing the recording.
                        try (InputStream markerScan = resolver.openInputStream(uri)) {
                            if (markerScan != null) {
                                java.util.List<Integer> imageFrames = RrdPreview.findMarkerFrames(markerScan, index, false);
                                // Do not add the raw detection chunks here: one Boxes2D Arrow
                                // message spans tens of seconds, and would move the timeline far
                                // past the last image in this bounded receiver. Row filtering is
                                // required before those chunks can be safely merged with video.
                                java.util.List<Integer> markerFrames = imageFrames;
                                if (imageFrames.size() >= 2) {
                                    java.util.List<java.util.List<Integer>> sparse = new java.util.ArrayList<java.util.List<Integer>>();
                                    java.util.List<Integer> current = new java.util.ArrayList<Integer>();
                                    long sparseBytes = index.preambleEnd + index.frames.get(0).encodedSize;
                                    final long sparseTarget = recordingSegmentTargetBytes();
                                    for (Integer frameNo : markerFrames) {
                                        long size = index.frames.get(frameNo).encodedSize;
                                        if (!current.isEmpty() && sparseBytes + size > sparseTarget) {
                                            sparse.add(current); current = new java.util.ArrayList<Integer>();
                                            sparseBytes = index.preambleEnd + index.frames.get(0).encodedSize;
                                        }
                                        current.add(frameNo); sparseBytes += size;
                                    }
                                    if (!current.isEmpty()) sparse.add(current);
                                    if (sparse.size() > 1) {
                                        index = index.withSelectedParts(sparse);
                                        debugLog("TIME_ALIGNED image-only-frames=" + imageFrames.size()
                                                + " parts=" + sparse.size() + " boxes=deferred-row-filter");
                                    }
                                }
                            }
                        }
                        incomingIndexes.put(id, index);
                        incomingSources.put(id, new IncomingSource(uri, name, length));
                        Log.i(TAG, "[DIAG] ===== RRD SEGMENTATION =====");
                        Log.i(TAG, "[DIAG] File: " + name + ", size: " + length + " bytes");
                        Log.i(TAG, "[DIAG] Target segment size: " + recordingSegmentTargetBytes() + " bytes");
                        Log.i(TAG, "Indexed RRD into " + index.segments.size() + " parts");
                        debugLog("Seg: " + index.segments.size() + " parts, target=" + recordingSegmentTargetBytes()/1024 + "KB");
                        debugLog("Total frames: " + (index.frames != null ? index.frames.size() : "unknown"));
                        for (int i = 0; i < Math.min(5, index.segments.size()); i++) {
                            RrdFrames.Segment seg = index.segments.get(i);
                            Log.i(TAG, "[DIAG] Segment " + i + ": bytes " + seg.start + "-" + seg.end 
                                    + " (size: " + (seg.end - seg.start) + ", uncompressed: " 
                                    + seg.uncompressedLength + ")");
                        }
                        if (index.segments.size() > 5) {
                            Log.i(TAG, "[DIAG] ... and " + (index.segments.size() - 5) + " more segments");
                        }
                        
                        // Use dynamic windows if enabled and frame index available
                        if (useDynamicWindows && index.frames != null && !index.frames.isEmpty()) {
                            debugLog("DYNAMIC_WINDOW: Enabled, window_size=" + windowSize + ", total_frames=" + index.frames.size());
                            return "dynamic:" + id;
                        }
                        
                        int first = 0;
                        try (InputStream probe = resolver.openInputStream(uri)) {
                            int found = probe == null ? -1 : RrdPreview.findSegment(probe, index, false);
                            if (found >= 0) first = found;
                        } catch (IOException probeError) { debugLog("preview-marker probe: " + probeError); }
                        initialParts.put(id, first);
                        debugLog("PREVIEW initial-part=" + (first + 1) + " marker-only=true");
                        return segmentUrl(id, first);
                    }
                } catch (IOException unsafe) {
                    incomingIndexes.remove(id);
                    incomingSources.remove(id);
                    throw new IOException("Safe segmentation refused; no full-file fallback", unsafe);
                }
            }
            if (enableStaticSegmentation || length < 0 || length > 8L * 1024 * 1024)
                throw new IOException("Full-file mode requires known size <= 8MiB");
            incomingSources.put(id, new IncomingSource(uri, name, length));
            debugLog("IMPORT full-stream name=" + name + " bytes=" + length);
            return "http://127.0.0.1:" + localServer.getPort() + LOCAL_FILE_PREFIX
                    + id + "/" + Uri.encode(name);
        } catch (Exception failure) {
            debugLog("Could not open incoming file", failure);
            if (output != null) output.delete();
            return null;
        }
    }

    private void openFilePicker() {
        openNativeFilePicker();
    }

    private void openNativeFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra("android.intent.extra.ALLOW_MULTIPLE", false);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_FILE);
        } catch (RuntimeException failure) {
            Log.e(TAG, "No document picker available", failure);
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(null);
                pendingFileCallback = null;
            }
            showError("设备没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_FILE) return;
        ArrayList<Uri> selected = new ArrayList<Uri>();
        if (resultCode == RESULT_OK && data != null) {
            if (data.getData() != null) selected.add(data.getData());
            ClipData clips = data.getClipData();
            if (clips != null) {
                selected.clear();
                for (int i = 0; i < clips.getItemCount(); i++) {
                    selected.add(clips.getItemAt(i).getUri());
                }
            }
            if (Build.VERSION.SDK_INT >= 19 && data.getData() != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Some document providers grant only a one-shot permission.
                }
            }
        }
        ValueCallback<Uri[]> callback = pendingFileCallback;
        pendingFileCallback = null;
        // Feed files through the same native cache path as external shares. This
        // avoids WebView-specific content:// and large-file handling differences.
        if (!selected.isEmpty()) {
            loadIncomingUris(selected);
            if (callback != null) callback.onReceiveValue(null);
        } else if (callback != null) {
            callback.onReceiveValue(null);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String displayName(ContentResolver resolver, Uri uri) {
        Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (cursor == null) return null;
        try {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        } finally {
            cursor.close();
        }
    }

    private String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static final class IncomingSource {
        final Uri uri;
        final String name;
        final long length;
        IncomingSource(Uri uri, String name, long length) {
            this.uri = uri;
            this.name = name;
            this.length = length;
        }
    }

    private String segmentUrl(String id, int part) {
        return "http://127.0.0.1:" + localServer.getPort() + LOCAL_FILE_PREFIX
                + id + "/part-" + part + ".rrd";
    }

    private void scheduleAutomaticSegmentAdvance() {
        // EOF is transfer completion, never playback completion.
        if (destroyed || currentSegmentId == null || importStatus == null) return;
        importStatus.setText("分段 " + (currentSegmentPart + 1) + "/" + segmentCount()
                + " 已传输；点击选择分段，长按停止");
        importStatus.setVisibility(View.VISIBLE);

    }

    private int segmentCount() {
        RrdFrames.Index index = currentSegmentId == null ? null : incomingIndexes.get(currentSegmentId);
        return index == null ? 0 : index.segments.size();
    }

    private void captureTransitionCover() {
        if (viewer == null || viewer.getWidth() <= 0 || viewer.getHeight() <= 0) return;
        try {
            Bitmap snapshot = Bitmap.createBitmap(viewer.getWidth(), viewer.getHeight(), Bitmap.Config.ARGB_8888);
            viewer.draw(new Canvas(snapshot));
            if (transitionBitmap != null) transitionBitmap.recycle();
            transitionBitmap = snapshot;
        } catch (Throwable failure) {
            Log.w(TAG, "Unable to capture segment transition", failure);
        }
    }

    private void removeTransitionCover() {
        if (transitionCover != null) {
            transitionCover.animate().alpha(0.0f).setDuration(180L).withEndAction(new Runnable() {
                @Override public void run() {
                    if (transitionCover != null) {
                        transitionCover.setVisibility(View.GONE);
                        transitionCover = null;
                    }
                    if (transitionBitmap != null) {
                        transitionBitmap.recycle();
                        transitionBitmap = null;
                    }
                }
            }).start();
        } else if (transitionBitmap != null) {
            transitionBitmap.recycle();
            transitionBitmap = null;
        }
    }

    private String windowUrl(String id, int start, int count) {
        return "http://127.0.0.1:" + localServer.getPort() + "/local/"
                + id + "/frames?start=" + start + "&count=" + count;
    }

    private int getWindowId(int tick) {
        if (tick < 0) return 0;
        return tick / windowSize;
    }
    
    private void loadInitialWindow(String fileId) {
        RrdFrames.Index index = incomingIndexes.get(fileId);
        if (index == null || index.frames == null || index.frames.isEmpty()) {
            debugLog("ERROR: No frame index for " + fileId);
            return;
        }
        
        // The first indexed frame is the metadata frame already included in
        // every window preamble. Window positions refer only to data frames.
        totalFrames = Math.max(0, index.frames.size() - 1);
        currentWindowId = fileId;
        pendingDynamicWindowId = null;
        currentTimelinePosition = 0;
        loadedWindows.clear();
        
        debugLog("FIXED_WINDOW: Total " + totalFrames + " frames, window_size=" + windowSize);
        debugLog("FIXED_WINDOW: lookBehind=" + lookBehind + ", lookAhead=" + lookAhead + ", max=" + maxWindows);
        
        if (viewerReady && viewer != null) {
            checkAndLoadWindows();
            setupTimelineMonitoring();
        }
    }

    private void scheduleWindowCheck() {
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                checkAndLoadWindows();
            }
        }, 1000);
    }

    private void checkAndLoadWindows() {
        if (currentWindowId == null || !useDynamicWindows) return;
        
        int currentTick = currentTimelinePosition;
        
        int startTick = Math.max(0, currentTick - lookBehind);
        int endTick = Math.min(totalFrames - 1, currentTick + lookAhead);
        
        int startWindow = getWindowId(startTick);
        int endWindow = getWindowId(endTick);
        
        debugLog("FIXED_WINDOW: Position " + currentTick + ", need windows [" + startWindow + "-" + endWindow + "]");
        
        for (int winId = startWindow; winId <= endWindow; winId++) {
            if (loadedWindows.size() >= maxWindows) {
                cleanupDistantWindows(startWindow, endWindow);
            }
            loadWindow(winId);
        }
        
        if (loadedWindows.size() > maxWindows / 2) {
            cleanupDistantWindows(startWindow, endWindow);
        }
        
        scheduleWindowCheck();
    }
    
    private void loadWindow(int windowId) {
        if (loadedWindows.containsKey(windowId)) {
            loadedWindows.get(windowId).lastAccessTime = System.currentTimeMillis();
            return;
        }
        
        int start = windowId * windowSize;
        if (start >= totalFrames) return;
        
        int count = Math.min(windowSize, totalFrames - start);
        final String url = windowUrl(currentWindowId, start, count);
        final int winId = windowId;
        
        debugLog("FIXED_WINDOW: Loading window " + windowId + " [" + start + "-" + (start + count - 1) + "]");
        
        if (viewer != null && viewerReady) {
            final String addScript = "(function(){if(!window._handle)return 'not-ready';"
                    + "window._handle.add_receiver(" + org.json.JSONObject.quote(url)
                    + ",false);return 'submitted';})()";
            
            viewer.evaluateJavascript(addScript, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if ("\"submitted\"".equals(value)) {
                        WindowRecord record = new WindowRecord(winId, url, start, start + count);
                        loadedWindows.put(winId, record);
                        activeWindows.add(url);
                        activeReceivers.add(url);
                        debugLog("FIXED_WINDOW: Loaded window " + winId + ", total=" + loadedWindows.size());
                    }
                }
            });
        }
    }
    
    private void cleanupDistantWindows(int keepStart, int keepEnd) {
        java.util.ArrayList<Integer> toRemove = new java.util.ArrayList<Integer>();
        
        for (WindowRecord rec : loadedWindows.values()) {
            if (rec.windowId < keepStart || rec.windowId > keepEnd) {
                toRemove.add(rec.windowId);
            }
        }
        
        for (int winId : toRemove) {
            WindowRecord rec = loadedWindows.get(winId);
            if (rec != null) {
                debugLog("FIXED_WINDOW: Removing window " + winId + " [" + rec.startFrame + "-" + rec.endFrame + "]");
                
                if (viewer != null) {
                    final String removeScript = "(function(){if(!window._handle)return;"
                            + "window._handle.remove_receiver("
                            + org.json.JSONObject.quote(rec.url) + ");})()";
                    viewer.evaluateJavascript(removeScript, null);
                }
                
                loadedWindows.remove(winId);
                activeWindows.remove(rec.url);
                activeReceivers.remove(rec.url);
            }
        }
    }
    
    private void setupTimelineMonitoring() {
        if (viewer == null) return;
        
        // Experimental windows must never fabricate playback position.
        debugLog("FIXED_WINDOW: Playback position API unavailable; automatic tracking disabled");
    }

    private void locatePreviewSegment(final boolean boxes) {
        final String id = currentSegmentId;
        final IncomingSource source = id == null ? null : incomingSources.get(id);
        final RrdFrames.Index index = id == null ? null : incomingIndexes.get(id);
        if (source == null || index == null || importPreparing) {
            new AlertDialog.Builder(this).setMessage("请先完成安全分段加载。")
                    .setPositiveButton("确定", null).show();
            return;
        }
        importPreparing = true;
        final int generation = importGeneration;
        fileWorkers.execute(new Runnable() {
            @Override public void run() {
                int found = -1;
                try (InputStream input = getContentResolver().openInputStream(source.uri)) {
                    if (input != null) found = RrdPreview.findSegment(input, index, boxes);
                } catch (IOException failure) { debugLog("marker probe", failure); }
                final int part = found;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        importPreparing = false;
                        if (destroyed || generation != importGeneration || !id.equals(currentSegmentId)) return;
                        if (part >= 0) openSegment(part);
                        else new AlertDialog.Builder(MainActivity.this)
                                .setMessage("有界扫描中未发现目标标识，可手动选择后续分段。标识定位不等于解码或时间对齐验证。")
                                .setPositiveButton("确定", null).show();
                    }
                });
            }
        });
    }

    private void showSegmentPicker() {
        if (currentSegmentId == null) return;
        final String expectedId = currentSegmentId;
        final int count = segmentCount();
        final EditText field = new EditText(this);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        field.setText(String.valueOf(Math.min(currentSegmentPart + 2, count)));
        new AlertDialog.Builder(this).setTitle("选择分段 (1-" + count + ")")
                .setMessage("当前第 " + (currentSegmentPart + 1)
                        + " 段。切换会重建 Viewer，不保留当前播放位置。")
                .setView(field).setNegativeButton("取消", null)
                .setPositiveButton("打开", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        if (!expectedId.equals(currentSegmentId)) return;
                        try {
                            int part = Integer.parseInt(field.getText().toString().trim()) - 1;
                            if (part < 0 || part >= count) throw new NumberFormatException();
                            openSegment(part);
                        } catch (NumberFormatException invalid) {
                            new AlertDialog.Builder(MainActivity.this).setMessage("分段编号无效")
                                    .setPositiveButton("确定", null).show();
                        }
                    }
                }).show();
    }

    private void openSegment(int part) {
        if (destroyed || viewer == null || currentSegmentId == null) return;
        RrdFrames.Index index = incomingIndexes.get(currentSegmentId);
        if (index == null || part < 0 || part >= index.segments.size()) return;
        currentSegmentPart = part;
        currentSegmentUrl = segmentUrl(currentSegmentId, part);
        pendingReceivers.clear();
        activeReceivers.clear();
        pendingReceivers.add(currentSegmentUrl);
        viewerReady = false;
        importStatusActiveReset();
        viewer.stopLoading();
        FrameLayout parent = (FrameLayout) viewer.getParent();
        if (parent != null) parent.removeView(viewer);
        viewer.destroy();
        viewer = null;
        buildView();
        loadViewerPage(viewerPage);
        importStatus.setText("正在载入分段 " + (part + 1) + "/" + index.segments.size()
                + "；点击选择分段，长按停止");
        importStatus.setVisibility(View.VISIBLE);
    }


    private static final class SegmentEofInputStream extends java.io.FilterInputStream {
        private final long expected;
        private final Runnable onEof;
        private long readBytes;
        private boolean notified;
        SegmentEofInputStream(InputStream input, long expected, Runnable onEof) {
            super(input);
            this.expected = expected;
            this.onEof = onEof;
        }
        private void complete(int count) {
            if (count > 0) readBytes += count;
            if (!notified && (count < 0 || (expected >= 0 && readBytes >= expected))) {
                notified = true;
                onEof.run();
            }
        }
        @Override public int read() throws IOException {
            int result = super.read();
            complete(result < 0 ? -1 : 1);
            return result;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int result = super.read(b, off, len);
            complete(result);
            return result;
        }
        @Override public void close() throws IOException {
            try { super.close(); } finally {
                if (!notified && expected >= 0 && readBytes >= expected) {
                    notified = true;
                    onEof.run();
                }
            }
        }
    }

    private static final class ChunkedOutput extends OutputStream {
        private final OutputStream target;
        ChunkedOutput(OutputStream target) { this.target = target; }
        @Override public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }
        @Override public void write(byte[] data, int off, int len) throws IOException {
            if (len == 0) return;
            target.write((Integer.toHexString(len) + "\r\n").getBytes("US-ASCII"));
            target.write(data, off, len);
            target.write("\r\n".getBytes("US-ASCII"));
            target.flush();
        }
        @Override public void flush() throws IOException { target.flush(); }
        @Override public void close() { }
    }

    private final class LocalViewerServer {
        private ServerSocket serverSocket;
        private Thread thread;
        private final ExecutorService workers = Executors.newFixedThreadPool(4);

        void start() throws IOException {
            serverSocket = new ServerSocket(0, 16,
                    java.net.InetAddress.getByName("127.0.0.1"));
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    while (!serverSocket.isClosed()) {
                        try {
                            final Socket socket = serverSocket.accept();
                            workers.execute(new Runnable() {
                                @Override public void run() { serve(socket); }
                            });
                        } catch (IOException failure) {
                            if (!serverSocket.isClosed()) Log.e(TAG, "Local server accept failed", failure);
                        }
                    }
                }
            }, "rerun-local-http");
            thread.setDaemon(true);
            thread.start();
        }

        int getPort() { return serverSocket.getLocalPort(); }

        void verifyTcpConnection() throws IOException {
            Socket socket = new Socket(java.net.InetAddress.getByName("127.0.0.1"), getPort());
            try {
                socket.setSoTimeout(3000);
                OutputStream output = socket.getOutputStream();
                output.write(("GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                        .getBytes("US-ASCII"));
                output.flush();
                BufferedReader response = new BufferedReader(new java.io.InputStreamReader(
                        socket.getInputStream(), "US-ASCII"));
                String status = response.readLine();
                if (status == null || !status.contains(" 200 ")) {
                    throw new IOException("Loopback TCP health check failed: " + status);
                }
                Log.i(TAG, "Loopback TCP health check passed on port " + getPort());
            } finally {
                socket.close();
            }
        }

        void stop() {
            try {
                if (serverSocket != null) serverSocket.close();
                workers.shutdownNow();
            } catch (IOException ignored) { }
        }

        private void serve(Socket socket) {
            try {
                socket.setSoTimeout(15000);
                BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                        socket.getInputStream(), "UTF-8"));
                String request = reader.readLine();
                if (request == null) { socket.close(); return; }
                String[] requestParts = request.split(" ");
                if (requestParts.length < 2 || !"GET".equals(requestParts[0])) {
                    writeError(socket, 405, "Method Not Allowed");
                    return;
                }
                String path = URLDecoder.decode(requestParts[1], "UTF-8");
                String header;
                while ((header = reader.readLine()) != null && header.length() > 0) { }
                if ("/health".equals(path)) {
                    writeResponse(socket, 200, "OK", "text/plain; charset=utf-8", 2,
                            new java.io.ByteArrayInputStream("OK".getBytes("US-ASCII")));
                } else if (path.startsWith("/live/")) {
                    serveLive(socket, path);
                } else if (path.startsWith("/local/")) {
                    String localRest = path.substring("/local/".length());
                    int localSlash = localRest.indexOf('/');
                    String localResource = localSlash < 0 ? ""
                            : localRest.substring(localSlash + 1);
                    if ("frames".equals(localResource) || localResource.startsWith("frames?")) {
                        serveFrameWindow(socket, path);
                    } else {
                        serveFile(socket, path);
                    }
                } else {
                    serveAsset(socket, path);
                }
            } catch (IOException failure) {
                Log.e(TAG, "Local viewer request failed", failure);
                try { socket.close(); } catch (IOException ignored) { }
            }
        }

        private void serveAsset(Socket socket, String path) throws IOException {
            String name = path;
            int query = name.indexOf('?');
            if (query >= 0) name = name.substring(0, query);
            if (name.length() == 0 || "/".equals(name)) name = "/index.html";
            if (name.startsWith("/")) name = name.substring(1);
            if (!"index.html".equals(name) && !"re_viewer.js".equals(name)
                    && !"mobile.js".equals(name)
                    && !"re_viewer_bg.wasm".equals(name)) {
                writeError(socket, 404, "Not Found");
                return;
            }
            String requestUrl = "http://127.0.0.1" + path;
            debugLog("HTTP asset request " + requestUrl);
            InputStream input = getAssets().open("rerun/" + name);
            long length;
            AssetFileDescriptor descriptor = getAssets().openFd("rerun/" + name);
            try { length = descriptor.getLength(); }
            finally { descriptor.close(); }
            String type = name.endsWith(".wasm") ? "application/wasm"
                    : name.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/html; charset=utf-8";
            writeAssetResponse(socket, type, length, input);
        }

        private void serveLive(Socket socket, String path) throws IOException {
            String rest = path.substring("/live/".length());
            int slash = rest.indexOf('/');
            String key = slash < 1 ? "" : rest.substring(0, slash);
            RrdLiveSession session = liveSessions.get(key);
            if (session == null) { writeError(socket, 404, "Not Found"); return; }
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                    + "Transfer-Encoding: chunked\r\nAccess-Control-Allow-Origin: *\r\n"
                    + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            try {
                session.streamTo(new ChunkedOutput(out));
            } finally {
                socket.close();
            }
        }

        private void serveFile(Socket socket, String path) throws IOException {
            WebResourceResponse response = openIncomingWebResource(
                    "http://127.0.0.1:" + getPort() + path);
            if (response == null) { writeError(socket, 404, "Not Found"); return; }
            String length = response.getResponseHeaders().get("Content-Length");
            writeResponse(socket, 200, "OK", response.getMimeType(),
                    Long.parseLong(length), response.getData());
        }

        private void serveFrameWindow(Socket socket, String path) throws IOException {
            // Parse: /local/{id}/frames?start=0&count=1000
            String rest = path.substring("/local/".length());
            int slash = rest.indexOf('/');
            if (slash < 1) { writeError(socket, 404, "Not Found"); return; }
            String id = rest.substring(0, slash);
            
            // Parse query parameters
            int queryStart = path.indexOf('?');
            if (queryStart < 0) { writeError(socket, 400, "Missing query parameters"); return; }
            String query = path.substring(queryStart + 1);
            int frameStart = 0;
            int frameCount = 1000;  // Default window size
            try {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", -1);
                    if (kv.length != 2) throw new NumberFormatException();
                    if ("start".equals(kv[0])) frameStart = Integer.parseInt(kv[1]);
                    else if ("count".equals(kv[0])) frameCount = Integer.parseInt(kv[1]);
                }
            } catch (NumberFormatException invalid) {
                writeError(socket, 400, "Invalid window parameters");
                return;
            }
            
            // Get the RRD index
            RrdFrames.Index index = incomingIndexes.get(id);
            IncomingSource source = incomingSources.get(id);
            if (index == null || source == null || index.frames == null) {
                writeError(socket, 404, "Frame index not available");
                return;
            }
            int dataFrameCount = Math.max(0, index.frames.size() - 1);
            if (frameCount <= 0 || frameStart < 0 || frameStart >= dataFrameCount) {
                writeError(socket, 416, "Frame range not satisfiable");
                return;
            }
            int frameEnd = (int) Math.min((long) frameStart + frameCount, dataFrameCount);
            
            // Build frame window stream
            InputStream input = getContentResolver().openInputStream(source.uri);
            if (input == null) { writeError(socket, 404, "Source unavailable"); return; }
            
            InputStream windowStream = new RrdFrames.FrameWindowStream(input, index, frameStart, frameEnd);
            long estimatedLength = index.preambleEnd + 8; // RRF2 terminator
            for (int i = frameStart; i < frameEnd; i++) {
                estimatedLength += index.frames.get(i + 1).encodedSize;
            }
            
            debugLog("Window: frames " + frameStart + "-" + frameEnd + ", ~" + (estimatedLength/1024) + "KB");
            writeResponse(socket, 200, "OK", "application/octet-stream", estimatedLength, windowStream);
        }
        private void writeAssetResponse(Socket socket, String type, long length,
                                        InputStream input) throws IOException {
            try {
                OutputStream output = socket.getOutputStream();
                String headers = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: " + type + "\r\n"
                        + "Content-Length: " + length + "\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Cache-Control: public, max-age=31536000, immutable\r\n"
                        + "Connection: close\r\n\r\n";
                output.write(headers.getBytes("US-ASCII"));
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            } finally {
                try { input.close(); } catch (IOException ignored) { }
                socket.close();
            }
        }

        private void writeResponse(Socket socket, int code, String reason, String type,
                                   long length, InputStream input) throws IOException {
            try {
                OutputStream output = socket.getOutputStream();
                Log.d(TAG, "HTTP " + code + " " + type + " length=" + length);
                String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                        + "Content-Type: " + type + "\r\n"
                        + "Content-Length: " + length + "\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Cache-Control: no-store\r\n"
                        + "Connection: close\r\n\r\n";
                output.write(headers.getBytes("US-ASCII"));
                byte[] buffer = new byte[65536];
                int count;
                long sent = 0, lastUpdate = 0;
                boolean recording = "application/octet-stream".equals(type);
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    sent += count;
                    if (recording && sent - lastUpdate >= 8L * 1024 * 1024) {
                        lastUpdate = sent;
                        final long sentBytes = sent;
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                if (!destroyed && importStatus != null)
                                    importStatus.setText("正在传输记录：" + (sentBytes / (1024 * 1024)) + " MiB");
                            }
                        });
                    }
                }
                output.flush();
                if (recording) mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (!destroyed && importStatus != null)
                            importStatus.setText("传输完成，等待 Viewer 解析记录…");
                    }
                });
            } finally {
                input.close();
                socket.close();
            }
        }

        private void writeError(Socket socket, int code, String reason) throws IOException {
            byte[] body = reason.getBytes("UTF-8");
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 " + code + " " + reason + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes("US-ASCII"));
            output.write(body);
            output.flush();
            socket.close();
        }
    }

    private void stopImportAndReload() {
        if (destroyed) return;
        importGeneration++;
        importStatusActiveReset();
        initialParts.clear();
        pendingIntent = null;
        currentSegmentId = null;
        currentSegmentUrl = null;
        currentWindowId = null;
        pendingDynamicWindowId = null;
        totalFrames = 0;
        loadedWindows.clear();
        activeWindows.clear();
        removeTransitionCover();
        incomingIndexes.clear();
        incomingSegmentFiles.clear();
        if (automaticSegmentAdvance != null) mainHandler.removeCallbacks(automaticSegmentAdvance);
        automaticSegmentAdvance = null;
        segmentSwitchScheduled = false;
        segmentEofAt.clear();
        liveStreamUrl = null;
        for (RrdLiveSession session : liveSessions.values()) session.stop();
        liveSessions.clear();
        liveSources.clear();
        pendingReceivers.clear();
        activeReceivers.clear();
        for (File file : incomingFiles.values()) {
            if (file != null) file.delete();
        }
        incomingFiles.clear();
        incomingSources.clear();
        try {
            if (viewer != null) {
                viewer.stopLoading();
                View parent = (View) viewer.getParent();
                if (parent instanceof FrameLayout) ((FrameLayout) parent).removeView(viewer);
                viewer.destroy();
                viewer = null;
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Failed to stop current import cleanly", failure);
        }
        viewerStarted = false;
        viewerReady = false;
        rendererRecoveryAttempted = false;
        forceSoftwareLayer = false;
        buildView();
        startViewerFromIntent();
    }

    private void importStatusActiveReset() {
        importActiveSince = 0;
        lastJsHeartbeat = 0;
        mainHandler.removeCallbacks(importWatchdog);
        if (importStatus != null) importStatus.setVisibility(View.GONE);
    }

    private void recoverFromRendererGone() {
        try {
            mainHandler.removeCallbacks(startupWatchdog);
            viewerStarted = false;
            viewerReady = false;
            if (viewer != null) {
                View parent = (View) viewer.getParent();
                if (parent instanceof FrameLayout) ((FrameLayout) parent).removeView(viewer);
                viewer.destroy();
                viewer = null;
            }
            buildView();
            startViewerFromIntent();
        } catch (Throwable failure) {
            Log.e(TAG, "Failed to recover WebView renderer", failure);
            showFatalError(failure);
        }
    }

    private void initDebugLog() {
        try {
            File dir = new File("/sdcard/Download/RerunViewer");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed: " + dir);
            debugLogFile = new File(dir, "rerun-viewer-debug.log");
            debugLog("log-file=" + debugLogFile.getAbsolutePath());
        } catch (Throwable failure) {
            Log.e(TAG, "Unable to initialize public debug log", failure);
        }
    }

    private void debugLog(String message) {
        Log.d(TAG, message);
        if (debugLogFile == null) return;
        synchronized (debugLogLock) {
            try {
                if (debugLogFile.length() > 2L * 1024 * 1024) {
                    File previous = new File(debugLogFile.getParentFile(), "rerun-viewer-debug.previous.log");
                    previous.delete();
                    debugLogFile.renameTo(previous);
                }
                FileWriter writer = new FileWriter(debugLogFile, true);
                writer.write(String.format(java.util.Locale.US, "%d %s\n",
                        System.currentTimeMillis(), message));
                writer.close();
            } catch (IOException ignored) { }
        }
    }

    private void debugLog(String message, Throwable failure) {
        debugLog(message + "\\n" + android.util.Log.getStackTraceString(failure));
    }

    private void showError(String message) {
        if (error != null) {
            error.setText(message);
            error.setVisibility(View.VISIBLE);
        }
    }

    private void showFatalError(Throwable failure) {
        debugLog("fatal-error", failure);
        TextView message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setTextSize(16);
        message.setGravity(android.view.Gravity.CENTER);
        message.setPadding(dp(24), dp(24), dp(24), dp(24));
        message.setBackgroundColor(Color.rgb(13, 16, 17));
        message.setText("Rerun 无法启动。\n\n" + failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage())
                + "\n\n若提示 WebView 渲染错误，请检查系统 WebView；若提示本地连接错误，请反馈此画面。");
        setContentView(message);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (viewerStarted) handleIncomingIntent(intent);
        else pendingIntent = intent;
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (viewer != null) viewer.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override public void onBackPressed() {
        if (error != null && error.getVisibility() == View.VISIBLE) {
            error.setVisibility(View.GONE);
            stopImportAndReload();
        } else if (viewer != null && viewer.canGoBack()) {
            viewer.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(heartbeatCheck);
        mainHandler.removeCallbacks(importWatchdog);
        for (RrdLiveSession session : liveSessions.values()) session.stop();
        liveSessions.clear();
        fileWorkers.shutdownNow();
        mainHandler.removeCallbacks(startupWatchdog);
        if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
        if (viewer != null) viewer.destroy();
        if (localServer != null) localServer.stop();
        super.onDestroy();
    }
}
