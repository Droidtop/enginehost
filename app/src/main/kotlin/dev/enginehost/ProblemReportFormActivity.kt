package dev.enginehost

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

/**
 * The report form, opened inside Enginehost.
 *
 * The form is a web page and this is a browser for it, so filing a report
 * does not mean leaving the app. Signing in is the one thing an embedded
 * browser is sometimes refused, so the page can always be handed to the
 * device's own browser instead, with the same prefilled address.
 */
class ProblemReportFormActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_problem_report_form)
        wireBackButton()
        val url = intent.getStringExtra(EXTRA_URL) ?: ProblemReport.REPORTS_REPOSITORY
        val progress = findViewById<ProgressBar>(R.id.formProgress)

        findViewById<Button>(R.id.openInBrowserButton).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView.url ?: url))) }
        }

        webView = findViewById(R.id.formWebView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setSupportMultipleWindows(false)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // The report form lives on GitHub; anything else a page offers
                // belongs in the person's own browser, not in this window.
                val host = request.url.host.orEmpty()
                if (host.endsWith("github.com") || host.endsWith("githubusercontent.com")) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
            }
        }
        if (savedInstanceState == null) webView.loadUrl(url)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
