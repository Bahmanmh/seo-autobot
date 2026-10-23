package com.seo.autobot

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var etKeyword: EditText
    private lateinit var etTargetDomain: EditText
    private lateinit var etChainCount: EditText
    private lateinit var etDwellTime: EditText
    private lateinit var etLoopCount: EditText
    private lateinit var btnToggleSettings: Button
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnCalibrateTile: Button
    private lateinit var btnStartStop: Button
    private lateinit var layoutSettingsContent: LinearLayout
    private lateinit var viewCalibrationOverlay: View
    private lateinit var layoutCalibrationBox: LinearLayout
    private lateinit var btnSaveCalibration: Button

    private lateinit var prefs: SharedPreferences
    private val httpClient = OkHttpClient()

    private var isBotRunning = false
    private var botJob: kotlinx.coroutines.Job? = null
    private var pageLoadDeferred: CompletableDeferred<String>? = null

    // Calibration coordinates saved in SharedPreferences
    private var calibratedX = 0f
    private var calibratedY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("SEO_AUTOBOT_PREFS", Context.MODE_PRIVATE)

        initViews()
        loadSettings()
        setupWebView()
        setupListeners()

        // Block accidental app exit while bot is running
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isBotRunning) {
                    Toast.makeText(this@MainActivity, "Bot is running! Stop the bot before exiting.", Toast.LENGTH_SHORT).show()
                } else {
                    finish()
                }
            }
        })
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        tvStatus = findViewById(R.id.tvStatus)
        etKeyword = findViewById(R.id.etKeyword)
        etTargetDomain = findViewById(R.id.etTargetDomain)
        etChainCount = findViewById(R.id.etChainCount)
        etDwellTime = findViewById(R.id.etDwellTime)
        etLoopCount = findViewById(R.id.etLoopCount)
        btnToggleSettings = findViewById(R.id.btnToggleSettings)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnCalibrateTile = findViewById(R.id.btnCalibrateTile)
        btnStartStop = findViewById(R.id.btnStartStop)
        layoutSettingsContent = findViewById(R.id.layoutSettingsContent)
        viewCalibrationOverlay = findViewById(R.id.viewCalibrationOverlay)
        layoutCalibrationBox = findViewById(R.id.layoutCalibrationBox)
        btnSaveCalibration = findViewById(R.id.btnSaveCalibration)

        calibratedX = prefs.getFloat("CAL_X", 0f)
        calibratedY = prefs.getFloat("CAL_Y", 0f)
    }

    private fun loadSettings() {
        etKeyword.setText(prefs.getString("KEYWORD", "سئو سایت"))
        etTargetDomain.setText(prefs.getString("DOMAIN", "example.com"))
        etChainCount.setText(prefs.getInt("CHAIN", 2).toString())
        etDwellTime.setText(prefs.getInt("DWELL", 15).toString())
        etLoopCount.setText(prefs.getInt("LOOPS", 5).toString())
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putString("KEYWORD", etKeyword.text.toString().trim())
            putString("DOMAIN", normalizeDomain(etTargetDomain.text.toString().trim()))
            putInt("CHAIN", etChainCount.text.toString().toIntOrNull() ?: 2)
            putInt("DWELL", etDwellTime.text.toString().toIntOrNull() ?: 15)
            putInt("LOOPS", etLoopCount.text.toString().toIntOrNull() ?: 5)
            apply()
        }
    }

    private fun normalizeDomain(rawDomain: String): String {
        return rawDomain
            .replace("https://", "")
            .replace("http://", "")
            .replace("www.", "")
            .trim('/')
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: ""
                // Intercept tel: or wtai: URLs so dialer never opens and conversion is recorded silently
                if (url.startsWith("tel:") || url.startsWith("wtai:") || url.startsWith("callto:")) {
                    tvStatus.text = "Status: Silent Call Intercepted (Analytics Recorded)"
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view?.progress == 100) {
                    pageLoadDeferred?.complete(url ?: "")
                }
            }
        }
    }

    private fun setupListeners() {
        btnToggleSettings.setOnClickListener {
            if (layoutSettingsContent.visibility == View.VISIBLE) {
                layoutSettingsContent.visibility = View.GONE
                btnToggleSettings.text = "Expand"
            } else {
                layoutSettingsContent.visibility = View.VISIBLE
                btnToggleSettings.text = "Collapse"
            }
        }

        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnCalibrateTile.setOnClickListener {
            layoutSettingsContent.visibility = View.GONE
            viewCalibrationOverlay.visibility = View.VISIBLE
            layoutCalibrationBox.visibility = View.VISIBLE
            Toast.makeText(this, "Open Quick Settings shade, drag crosshair to Airplane tile, and click Save.", Toast.LENGTH_LONG).show()
        }

        viewCalibrationOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                calibratedX = event.rawX
                calibratedY = event.rawY
                // Move calibration box near touch point
                layoutCalibrationBox.x = (calibratedX - layoutCalibrationBox.width / 2).coerceIn(0f, (webView.width - layoutCalibrationBox.width).toFloat())
                layoutCalibrationBox.y = (calibratedY - layoutCalibrationBox.height / 2).coerceIn(0f, (webView.height - layoutCalibrationBox.height).toFloat())
            }
            true
        }

        btnSaveCalibration.setOnClickListener {
            prefs.edit().putFloat("CAL_X", calibratedX).putFloat("CAL_Y", calibratedY).apply()
            viewCalibrationOverlay.visibility = View.GONE
            layoutCalibrationBox.visibility = View.GONE
            layoutSettingsContent.visibility = View.VISIBLE
            Toast.makeText(this, "Airplane Tile Coordinates Saved ($calibratedX, $calibratedY)", Toast.LENGTH_SHORT).show()
        }

        btnStartStop.setOnClickListener {
            if (isBotRunning) {
                stopBot()
            } else {
                startBot()
            }
        }
    }

    private fun startBot() {
        saveSettings()
        if (!AirplaneAccessibilityService.isInstanceActive()) {
            Toast.makeText(this, "Please enable Accessibility Service first!", Toast.LENGTH_LONG).show()
            return
        }

        isBotRunning = true
        btnStartStop.text = "Stop Bot"
        btnStartStop.setBackgroundColor(android.graphics.Color.RED)
        layoutSettingsContent.visibility = View.GONE
        btnToggleSettings.text = "Expand"

        botJob = lifecycleScope.launch {
            runBotLoop()
        }
    }

    private fun stopBot() {
        isBotRunning = false
        botJob?.cancel()
        pageLoadDeferred?.complete("")
        btnStartStop.text = "Start Bot"
        btnStartStop.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
        tvStatus.text = "Status: Stopped by User"
    }

    private suspend fun runBotLoop() {
        val keyword = prefs.getString("KEYWORD", "سئو سایت") ?: "سئو سایت"
        val targetDomain = prefs.getString("DOMAIN", "example.com") ?: "example.com"
        val chainCount = prefs.getInt("CHAIN", 2)
        val dwellTimeSec = prefs.getInt("DWELL", 15)
        val totalLoops = prefs.getInt("LOOPS", 5)

        for (loopIndex in 1..totalLoops) {
            if (!isActive || !isBotRunning) break

            updateStatus("Loop $loopIndex/$totalLoops | Fetching IP...", keyword, targetDomain)
            val currentIp = fetchPublicIp()

            updateStatus("Loop $loopIndex/$totalLoops | IP: $currentIp | Step 1: Searching Google", keyword, targetDomain)

            try {
                // Rotate User-Agent and clear storage
                rotateBrowserProfile()

                // Step 1: Google SERP Scanning & Target Finding
                val foundTarget = searchGoogleAndFindTarget(keyword, targetDomain)
                if (!foundTarget) {
                    updateStatus("Loop $loopIndex/$totalLoops | Target not found on SERP. Rotating IP...", keyword, targetDomain)
                    performIpRotation()
                    continue
                }

                // Step 2: Dwell on Landing Page & Chain Navigation
                updateStatus("Loop $loopIndex/$totalLoops | Landing Page: Dwell & Scroll", keyword, targetDomain)
                executeDwellAndScroll(dwellTimeSec)

                var chainSuccess = true
                for (chainStep in 1..chainCount) {
                    if (!isActive || !isBotRunning) break
                    updateStatus("Loop $loopIndex/$totalLoops | Chain Link $chainStep/$chainCount", keyword, targetDomain)
                    
                    val clickedInternal = clickRandomInternalLink(targetDomain)
                    if (!clickedInternal) {
                        chainSuccess = false
                        break
                    }
                    executeDwellAndScroll(dwellTimeSec)
                }

                if (!chainSuccess) {
                    updateStatus("Loop $loopIndex/$totalLoops | Chain navigation failed. Rotating IP...", keyword, targetDomain)
                    performIpRotation()
                    continue
                }

                // Step 3: Silent Call Button Click
                updateStatus("Loop $loopIndex/$totalLoops | Step 3: Clicking Call Button", keyword, targetDomain)
                clickSilentCallButton()
                delay(2000)

            } catch (e: Exception) {
                if (!isActive) break
                updateStatus("Error in loop: ${e.message}", keyword, targetDomain)
            }

            // Step 4: Airplane Mode IP Rotation
            if (loopIndex < totalLoops && isBotRunning) {
                updateStatus("Loop $loopIndex/$totalLoops | Step 4: Rotating IP via Airplane Mode...", keyword, targetDomain)
                performIpRotation()
            }
        }

        withContext(NonCancellable) {
            isBotRunning = false
            btnStartStop.text = "Start Bot"
            btnStartStop.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
            tvStatus.text = "Status: Bot Completed All Loops"
        }
    }

    private suspend fun rotateBrowserProfile() = withContext(Dispatchers.Main) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        WebStorage.getInstance().deleteAllData()

        val uaList = listOf(
            "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; Redmi Note 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        webView.settings.userAgentString = uaList.random()
    }

    private suspend fun searchGoogleAndFindTarget(keyword: String, targetDomain: String): Boolean {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        val searchUrl = "https://www.google.com/search?q=$encodedKeyword&hl=fa"

        for (page in 1..3) {
            val urlToLoad = if (page == 1) searchUrl else "$searchUrl&start=${(page - 1) * 10}"
            loadUrlAndWait(urlToLoad)

            // Progressively scroll 6-7 steps and evaluate DOM for target domain
            for (step in 1..7) {
                scrollWebView(300)
                delay(600)

                val jsCode = """
                    (function() {
                        // Remove Google Maps, location prompts, local packs
                        document.querySelectorAll('div[data-sokoban-container], div.VkpGBb, div.MjjYud div.U399gb').forEach(el => el.remove());
                        
                        const links = document.querySelectorAll('a[href]');
                        for (let a of links) {
                            let href = a.href;
                            if (!href) continue;
                            if (href.includes('/url?q=')) {
                                try {
                                    href = decodeURIComponent(href.split('/url?q=')[1].split('&')[0]);
                                } catch(e){}
                            }
                            if (href.includes('$targetDomain') && !href.includes('google.com') && !href.includes('maps.google')) {
                                a.scrollIntoView({behavior: 'smooth', block: 'center'});
                                return href;
                            }
                        }
                        return '';
                    })();
                """.trimLeft()

                val foundUrl = evaluateJs(jsCode)
                if (foundUrl.isNotBlank() && foundUrl != "null" && !foundUrl.contains("google.com")) {
                    delay(1000)
                    // Click the target link via JS click event
                    evaluateJs("""
                        (function() {
                            const links = document.querySelectorAll('a[href*="$targetDomain"]');
                            for (let a of links) {
                                if (!a.href.includes('google.com')) {
                                    a.click();
                                    return true;
                                }
                            }
                            return false;
                        })();
                    """)
                    // Wait for target landing page to load
                    waitForPageLoad(20000)
                    return true
                }
            }

            // If not found on current page, attempt pagination click
            val paginationClicked = evaluateJs("""
                (function() {
                    const nextBtn = document.querySelector('#pnnext, a[aria-label*="Next"], a[aria-label*="بعدی"], td.dtekXb + td a');
                    if (nextBtn) {
                        nextBtn.click();
                        return 'true';
                    }
                    return 'false';
                })();
            """)
            if (paginationClicked.contains("true")) {
                waitForPageLoad(15000)
                delay(1500)
            } else {
                break
            }
        }
        return false
    }

    private suspend fun executeDwellAndScroll(dwellSeconds: Int) {
        val stepMs = 1000L
        val totalSteps = dwellSeconds
        for (sec in 1..totalSteps) {
            if (!isActive || !isBotRunning) break
            tvStatus.text = "Status: Dwell Time Countdown: ${totalSteps - sec + 1}s"
            
            // Human-like smooth scroll down and slightly up
            if (sec % 3 == 0) {
                scrollWebView(250)
            } else if (sec % 5 == 0) {
                scrollWebView(-150)
            }
            delay(stepMs)
        }
    }

    private suspend fun clickRandomInternalLink(targetDomain: String): Boolean {
        val jsCode = """
            (function() {
                const anchors = document.querySelectorAll('a[href]');
                let validLinks = [];
                for (let a of anchors) {
                    let href = a.href;
                    if (!href || href.startsWith('tel:') || href.startsWith('mailto:') || href.startsWith('javascript:')) continue;
                    if (href.includes('#') || href.match(/\.(jpg|jpeg|png|pdf|zip|mp4|mp3)$/i)) continue;
                    if (a.offsetParent === null) continue; // hidden
                    
                    if (href.includes('$targetDomain')) {
                        validLinks.push(a);
                    }
                }
                if (validLinks.length > 0) {
                    const rand = validLinks[Math.floor(Math.random() * validLinks.length)];
                    rand.scrollIntoView({behavior: 'smooth', block: 'center'});
                    rand.click();
                    return 'true';
                }
                return 'false';
            })();
        """
        val result = evaluateJs(jsCode)
        if (result.contains("true")) {
            waitForPageLoad(15000)
            return true
        }
        return false
    }

    private suspend fun clickSilentCallButton() {
        val jsCode = """
            (function() {
                // Find phone call links or buttons containing phone numbers / 'تماس'
                const callEls = document.querySelectorAll('a[href^="tel:"], a[href^="wtai:"], button, a');
                for (let el of callEls) {
                    let text = el.innerText || '';
                    let href = el.href || '';
                    if (href.includes('tel:') || text.includes('تماس') || text.match(/0\d{2,3}[-\s]?\d{7,8}/)) {
                        el.scrollIntoView({behavior: 'smooth', block: 'center'});
                        
                        // Dispatch realistic touch and click events
                        const opts = { bubbles: true, cancelable: true, view: window };
                        el.dispatchEvent(new MouseEvent('mousedown', opts));
                        el.dispatchEvent(new MouseEvent('mouseup', opts));
                        el.dispatchEvent(new MouseEvent('click', opts));
                        return 'true';
                    }
                }
                return 'false';
            })();
        """
        evaluateJs(jsCode)
    }

    private suspend fun performIpRotation() {
        val toggled = AirplaneAccessibilityService.toggleAirplaneMode(this, calibratedX, calibratedY)
        if (toggled) {
            waitForMobileDataConnected()
            delay(3000)
        } else {
            delay(5000)
        }
    }

    private suspend fun waitForMobileDataConnected() = withContext(Dispatchers.IO) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 20000) {
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return@withContext true
            }
            delay(500)
        }
        false
    }

    private suspend fun fetchPublicIp(): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://api.ipify.org").build()
            httpClient.newCall(request).execute().use { response ->
                return@withContext response.body?.string() ?: "Unknown"
            }
        } catch (e: Exception) {
            return@withContext "Error"
        }
    }

    private suspend fun loadUrlAndWait(url: String) = withContext(Dispatchers.Main) {
        pageLoadDeferred = CompletableDeferred()
        webView.loadUrl(url)
        try {
            kotlinx.coroutines.withTimeout(20000) {
                pageLoadDeferred?.await()
            }
        } catch (_: Exception) {}
    }

    private suspend fun waitForPageLoad(timeoutMs: Long) = withContext(Dispatchers.Main) {
        pageLoadDeferred = CompletableDeferred()
        try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                pageLoadDeferred?.await()
            }
        } catch (_: Exception) {}
    }

    private suspend fun evaluateJs(script: String): String = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String>()
        webView.evaluateJavascript(script) { value ->
            deferred.complete(value ?: "")
        }
        return@withContext try {
            deferred.await()
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun scrollWebView(pixelDelta: Int) = withContext(Dispatchers.Main) {
        webView.scrollBy(0, pixelDelta)
    }

    private fun updateStatus(statusText: String, keyword: String, domain: String) {
        runOnUiThread {
            tvStatus.text = statusText
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBot()
    }
}
