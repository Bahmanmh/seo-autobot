package com.seo.autobot

accessibilityPackage@{
    import android.accessibilityservice.AccessibilityService
    import android.accessibilityservice.GestureDescription
    import android.content.Context
    import android.graphics.Path
    import android.os.Build
    import android.provider.Settings
    import android.util.Log
    import android.view.accessibility.AccessibilityNodeInfo
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.delay
    import kotlinx.coroutines.withContext
}

class AirplaneAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AirplaneService"
        private var instance: AirplaneAccessibilityService? = null

        fun isInstanceActive(): Boolean = instance != null

        suspend fun toggleAirplaneMode(context: Context, calibratedX: Float, calibratedY: Float): Boolean {
            val service = instance ?: run {
                Log.e(TAG, "Accessibility Service not active!")
                return false
            }

            return service.executeAirplaneToggle(calibratedX, calibratedY)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AirplaneAccessibilityService connected successfully.")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Not used for capturing events, purely command-driven via gestures & node inspection.
    }

    override fun onInterrupt() {
        instance = null
        Log.w(TAG, "AirplaneAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    private suspend fun executeAirplaneToggle(calibratedX: Float, calibratedY: Float): Boolean = withContext(Dispatchers.IO) {
        val packageName = packageName
        var success = false

        for (attempt in 1..4) {
            try {
                Log.i(TAG, "Airplane toggle attempt $attempt/4")

                // 1. Open Quick Settings panel
                val opened = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                delay(600)

                if (!opened) {
                    // Fallback swipe down from top right if quick settings global action is unsupported on custom ROMs (MIUI/HyperOS/OneUI)
                    val metrics = resources.displayMetrics
                    val startX = metrics.widthPixels * 0.85f
                    val startY = 0f
                    val endY = metrics.heightPixels * 0.6f
                    performSwipe(startX, startY, startX, endY, 200)
                    delay(800)
                }

                // 2. Find and click Airplane tile via Accessibility Node inspection or calibrated coordinates
                val rootNode = rootInActiveWindow
                var clicked = false

                if (rootNode != null) {
                    val targetNode = findAirplaneNode(rootNode, packageName)
                    if (targetNode != null) {
                        clicked = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (!clicked) {
                            // Click parent if node itself is not directly clickable
                            var parent = targetNode.parent
                            while (parent != null) {
                                if (parent.isClickable) {
                                    clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    break
                                }
                                parent = parent.parent
                            }
                        }
                        targetNode.recycle()
                    }
                    rootNode.recycle()
                }

                // If node click failed or was not found, use calibrated coordinates fallback
                if (!clicked && calibratedX > 0f && calibratedY > 0f) {
                    Log.i(TAG, "Using calibrated coordinate tap at ($calibratedX, $calibratedY)")
                    performTap(calibratedX, calibratedY)
                    clicked = true
                }

                if (!clicked) {
                    Log.w(TAG, "Failed to locate or click Airplane tile on attempt $attempt")
                    closeShade()
                    delay(1000)
                    continue
                }

                // 3. Poll Settings.Global.AIRPLANE_MODE_ON every 50ms until it reports 1 (ON)
                var turnedOn = false
                val startPoll = System.currentTimeMillis()
                while (System.currentTimeMillis() - startPoll < 3000) {
                    val modeOn = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
                    if (modeOn == 1) {
                        turnedOn = true
                        break
                    }
                    delay(50)
                }

                if (!turnedOn) {
                    Log.w(TAG, "Airplane mode did not turn ON within timeout on attempt $attempt")
                    closeShade()
                    delay(1000)
                    continue
                }

                Log.i(TAG, "Airplane mode verified ON. Instantly turning OFF...")

                // 4. Instantly tap again to turn OFF
                if (rootNode != null) {
                    // re-query or tap coordinates
                }
                if (calibratedX > 0f && calibratedY > 0f) {
                    performTap(calibratedX, calibratedY)
                } else {
                    performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                    delay(400)
                }

                // 5. Poll Settings.Global.AIRPLANE_MODE_ON until it reports 0 (OFF)
                var turnedOff = false
                val offPollStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - offPollStart < 5000) {
                    val modeOn = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
                    if (modeOn == 0) {
                        turnedOff = true
                        break
                    }
                    delay(50)
                }

                closeShade()

                if (turnedOff) {
                    Log.i(TAG, "Airplane mode successfully toggled ON -> OFF!")
                    success = true
                    break
                } else {
                    Log.w(TAG, "Airplane mode failed to turn OFF on attempt $attempt")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during airplane toggle attempt $attempt", e)
                closeShade()
            }
            delay(1500)
        }

        return@withContext success
    }

    private fun findAirplaneNode(node: AccessibilityNodeInfo, selfPackage: String): AccessibilityNodeInfo? {
        if (node.packageName != null && node.packageName.toString() == selfPackage) {
            return null // Strict rule: never inspect or click own package
        }

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (text.contains("airplane") || text.contains("flight") || text.contains("حالت پرواز") || text.contains("حالت هواپیما") ||
            desc.contains("airplane") || desc.contains("flight") || desc.contains("حالت پرواز") || desc.contains("حالت هواپیما")) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findAirplaneNode(child, selfPackage)
            if (found != null) {
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun performTap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val builder = GestureDescription.Builder().addStroke(stroke)
            dispatchGesture(builder.build(), null, null)
        }
    }

    private fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val builder = GestureDescription.Builder().addStroke(stroke)
            dispatchGesture(builder.build(), null, null)
        }
    }

    private fun closeShade() {
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
            delay(200)
        } catch (_: Exception) {}
    }
}
