package ricky.easybrowser.web.legacy;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.ContentLoadingProgressBar;

import java.io.InputStream;

import ricky.easybrowser.R;
import ricky.easybrowser.common.BrowserConst;
import ricky.easybrowser.entity.bo.TabInfo;
import ricky.easybrowser.entity.dao.History;
import ricky.easybrowser.contract.IBrowser;
import ricky.easybrowser.utils.EasyLog;
import ricky.easybrowser.utils.SharedPreferencesUtils;
import ricky.easybrowser.utils.StringUtils;
import ricky.easybrowser.contract.IWebView;
import ricky.easybrowser.web.webkit.AddressBar;
import ricky.easybrowser.web.webkit.WebNavListener;
import ricky.easybrowser.widget.BrowserNavBar;

public class PageWebView extends FrameLayout implements IWebView {

    private EasyWebView webView;
    private RelativeLayout webLinear;

    private AddressBar addressBar;
    private PlaceholderView addressBarPlaceholder;
    private PlaceholderView navBarPlaceholder;
    private ImageView goButton;
    private EditText webAddress;
    private ContentLoadingProgressBar progressBar;

    private BrowserNavBar browserNavBar;

    private OnWebInteractListener onWebInteractListener;

    private Context mContext;

    private boolean noPicMode;

    private int orgAddressBarHeight = 0;
    private int orgBrowserNavBarHeight = 0;
    private AlertDialog imageActionsDialog = null;
    private AlertDialog urlActionsDialog = null;
    private String hitResultExtra = null;

    public static PageWebView newInstance(Context context) {
        PageWebView view = new PageWebView(context);
        return view;
    }

    public PageWebView(Context context) {
        this(context, null);
    }

    public PageWebView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PageWebView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mContext = context;
        LayoutInflater.from(context).inflate(R.layout.fragment_web_page_v1, this);
        initViews();
    }

    private void initViews() {
        configureWebView();

        addressBar = findViewById(R.id.web_address_bar);
        addressBar.post(new Runnable() {
            @Override
            public void run() {
                orgAddressBarHeight = addressBar.getMeasuredHeight();
            }
        });

        webLinear = findViewById(R.id.web_linear);
        addressBarPlaceholder = findViewById(R.id.address_bar_placeholder);
        navBarPlaceholder = findViewById(R.id.nav_bar_placeholder);

        goButton = findViewById(R.id.goto_button);
        goButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadInputUrl();
            }
        });

        webAddress = findViewById(R.id.page_url_edittext);
        webAddress.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_UNSPECIFIED
                        || actionId == EditorInfo.IME_ACTION_SEND
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    webAddress.clearFocus();
                    if (getContext() instanceof Activity) {
                        Activity activity = (Activity) getContext();
                        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0);
                    }

                    loadInputUrl();
                }
                return false;
            }
        });
        progressBar = findViewById(R.id.web_loading_progress_bar);

        browserNavBar = findViewById(R.id.web_nav_bar);
        browserNavBar.post(new Runnable() {
            @Override
            public void run() {
                orgBrowserNavBarHeight = browserNavBar.getMeasuredHeight();
            }
        });
        browserNavBar.setNavListener(new WebNavListener(getContext()));
    }

    private void configureWebView() {
        webView = findViewById(R.id.page_webview);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100) {
                    progressBar.setProgress(0);
                    progressBar.hide();
                    return;
                }

                if ((newProgress > 0) && (progressBar.getVisibility() == View.INVISIBLE
                        || progressBar.getVisibility() == View.GONE)) {
                    progressBar.show();
                }
                progressBar.setProgress(newProgress);

            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return super.shouldOverrideUrlLoading(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webAddress.setText(url);
                if (onWebInteractListener != null) {
                    onWebInteractListener.onPageTitleChange(TabInfo.create("", view.getTitle()));
                }

                boolean isBrowserController = mContext instanceof IBrowser;
                if (!isBrowserController) {
                    return;
                }
                // FIXME 通过进度 == 100 判断，避免网页重定向生成多条无效历史记录
                // https://stackoverflow.com/questions/3149216/how-to-listen-for-a-webview-finishing-loading-a-url
                if (webView.getProgress() == 100) {
                    IBrowser browser = (IBrowser) mContext;
                    IBrowser.IHistoryController historyController = (IBrowser.IHistoryController)
                            browser.provideBrowserComponent(BrowserConst.HISTORY_COMPONENT);
                    History history = new History();
                    history.title = view.getTitle();
                    history.url = view.getUrl();
                    history.time = System.currentTimeMillis();
                    historyController.addHistory(history);
                }
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    String targetPath = request.getUrl().getPath();
                    if (StringUtils.isEmpty(targetPath)) {
                        return super.shouldInterceptRequest(view, request);
                    }
                    if (noPicMode && isPicResources(targetPath)) {
                        InputStream placeHolderIS = mContext.getAssets().open("emptyplaceholder.png");
                        return new WebResourceResponse("image/png", "UTF-8", placeHolderIS);
                    }
                } catch (Exception e) {

                }

                return super.shouldInterceptRequest(view, request);
            }

            private boolean isPicResources(String path) {
                if (path.endsWith(".jpg")
                        || path.endsWith(".jpeg")
                        || path.endsWith(".png")
                        || path.endsWith(".gif")) {
                    return true;
                }
                return false;
            }
        });
        webView.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                final WebView.HitTestResult result = ((WebView) v).getHitTestResult();
                if (result == null) {
                    return false;
                }
                final int type = result.getType();
                final String extra = result.getExtra();
                hitResultExtra = result.getExtra();
                switch (type) {
                    case WebView.HitTestResult.IMAGE_TYPE:
                        EasyLog.i("test", "press image: " + extra);
                        showImageActionsDialog();
                        break;
                    case WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE:
                        EasyLog.i("test", "press image anchor: " + extra);
                        // TODO 实现image anchor类型弹窗，需要获取图片url及父节点<a>标签的url
                        break;
                    case WebView.HitTestResult.SRC_ANCHOR_TYPE:
                        EasyLog.i("test", "press url: " + extra);
                        showUrlActionsDialog();
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
        webView.setWebViewScrollListener(new EasyWebView.WebViewScrollListener() {
            @Override
            public void onScrollUp() {
                hideAddressBar();
            }

            @Override
            public void onScrollDown() {
                showAddressBar();
            }
        });
    }

    private void loadInputUrl() {
        if (webAddress.getText() != null) {
            String url = webAddress.getText().toString();
            this.loadUrl(url);
        }
    }

    @Override
    public void loadUrl(String url) {
        SharedPreferences sp = SharedPreferencesUtils.getSettingSP(getContext());
        if (sp != null) {
            noPicMode = sp.getBoolean(SharedPreferencesUtils.KEY_NO_PIC_MODE, false);
        }
        webView.loadUrl(url);
    }

    @Override
    public boolean canGoBack() {
        return webView.canGoBack();
    }

    @Override
    public void goBack() {
        webView.goBack();
    }

    @Override
    public void goForward() {
        webView.goForward();
    }

    @Override
    public boolean canGoForward() {
        return webView.canGoForward();
    }

    @Override
    public void setOnWebInteractListener(OnWebInteractListener listener) {
        this.onWebInteractListener = listener;
    }

    @Override
    public OnWebInteractListener getOnWebInteractListener() {
        return this.onWebInteractListener;
    }

    @Override
    public void releaseSession() {
        // donothing, for geckoView
    }

    @Override
    public void onResume() {
        webView.onResume();
        webView.resumeTimers();
    }

    @Override
    public void onPause() {
        webView.onPause();
        webView.pauseTimers();
    }

    @Override
    public void onDestroy() {
        webView.stopLoading();
        webView.getSettings().setJavaScriptEnabled(false);
        webView.clearHistory();
        webView.clearCache(true);
        webView.loadUrl("about:blank");
        webView.pauseTimers();
        webView.removeAllViews();
        webView.destroy();
        webView = null;
    }

    @Override
    public Bitmap capturePreview() {
        return null;
    }

    /**
     * 点击图片弹窗
     */
    private void showImageActionsDialog() {
        if (imageActionsDialog != null) {
            imageActionsDialog.show();
            return;
        }
        AlertDialog.Builder imageDialogbuilder = new AlertDialog.Builder(mContext);
        imageDialogbuilder.setItems(R.array.image_actions, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {  // backstage
                    notifyAddNewTab(true);
                } else if (which == 1) {
                    notifyAddNewTab(false);
                }
            }
        });
        imageActionsDialog = imageDialogbuilder.create();
        imageActionsDialog.show();
    }

    /**
     * 点击网页链接弹窗
     */
    private void showUrlActionsDialog() {
        if (urlActionsDialog != null) {
            urlActionsDialog.show();
            return;
        }
        AlertDialog.Builder urlDialogbuilder = new AlertDialog.Builder(mContext);
        urlDialogbuilder.setItems(R.array.url_actions, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {  // backstage
                    notifyAddNewTab(true);
                } else if (which == 1) {
                    notifyAddNewTab(false);
                }
            }
        });
        urlActionsDialog = urlDialogbuilder.create();
        urlActionsDialog.show();
    }

    private void notifyAddNewTab(boolean backStage) {
        IBrowser browser = null;
        IBrowser.ITabController tabController = null;
        if (mContext instanceof IBrowser) {
            browser = (IBrowser) mContext;
            tabController = (IBrowser.ITabController)
                    browser.provideBrowserComponent(BrowserConst.TAB_COMPONENT);
        }
        if (tabController == null) {
            return;
        }
        if (StringUtils.isEmpty(hitResultExtra)) {
            return;
        }
        Uri uri = null;
        try {
            uri = Uri.parse(hitResultExtra);
        } catch (Exception e) {
            uri = null;
        }
        if (uri == null) {
            return;
        }
        TabInfo tabInfo = TabInfo.create(
                System.currentTimeMillis() + "",
                mContext.getResources().getString(R.string.new_tab_welcome),
                uri);
        tabController.onTabCreate(tabInfo, backStage);
    }

    /**
     * translation动画较流畅，需要优化下拉手势判断，避免网页底部经常不可见
     * 动态调整LayoutParam方式频繁调用requestLayout，性能稍差
     */
    private void hideAddressBar() {
        ObjectAnimator animatorAddressBar = ObjectAnimator.ofFloat(addressBar, "translationY", 0, -orgAddressBarHeight);
        animatorAddressBar.setDuration(300);
        animatorAddressBar.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                int scrollY = webView.getScrollY();
                addressBarPlaceholder.setVisibility(View.GONE);
                navBarPlaceholder.setVisibility(View.GONE);
                webView.setScrollY(scrollY - orgAddressBarHeight);
                webView.setAnimating(true);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                addressBar.setVisibility(View.GONE);
                webView.setAnimating(false);
                browserNavBar.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                addressBar.setVisibility(View.GONE);
                webView.setAnimating(false);
                browserNavBar.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        });

        ObjectAnimator animatorNavBar = ObjectAnimator.ofFloat(browserNavBar, "translationY", 0, orgBrowserNavBarHeight);
        animatorNavBar.setDuration(300);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(animatorAddressBar, animatorNavBar);
        animatorSet.start();
    }

    private void showAddressBar() {
        ObjectAnimator animatorAddressBar = ObjectAnimator.ofFloat(addressBar, "translationY", -orgAddressBarHeight, 0);
        animatorAddressBar.setDuration(300);
        animatorAddressBar.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                addressBar.setVisibility(View.VISIBLE);
                browserNavBar.setVisibility(View.VISIBLE);
                webView.setAnimating(true);

                int scrollY = webView.getScrollY();
                addressBarPlaceholder.setVisibility(View.VISIBLE);
                navBarPlaceholder.setVisibility(View.VISIBLE);
                webView.setScrollY(scrollY + orgAddressBarHeight);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                webView.setAnimating(false);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                addressBar.setTranslationY(0);
                webView.setAnimating(false);
            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        });
        ObjectAnimator animatorNavBar = ObjectAnimator.ofFloat(browserNavBar, "translationY", orgBrowserNavBarHeight, 0);
        animatorNavBar.setDuration(300);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(animatorAddressBar, animatorNavBar);
        animatorSet.start();
    }
}
