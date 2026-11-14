package com.example.wechatlocspoofer

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.random.Random

data class PresetSpot(
    val name: String,
    val lat: Double,
    val lon: Double,
    val alt: Double
)

/**
 * 免 Root 模拟位置核心引擎：
 * 直接接管 Android 系统原生 LocationManager 的 gps / network / fused 通道，
 * 每 500ms 持续向系统注入带 O-U 自然微抖动的 WGS-84 国外坐标，无需 Root 即可让正版微信直接读取。
 */
object MockLocationEngine {
    @Volatile var isMocking = false
    @Volatile var latitude = 35.658581
    @Volatile var longitude = 139.745433
    @Volatile var altitude = 42.5
    @Volatile var speed = 0.0f
    @Volatile var bearing = 0.0f
    @Volatile var enableJitter = true

    private var driftNorth = 0.0
    private var driftEast = 0.0
    private val handler = Handler(Looper.getMainLooper())
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isMocking) return
            pushMockLocations()
            handler.postDelayed(this, 500)
        }
    }

    private var appContext: Context? = null

    fun checkMockPermission(context: Context): Boolean {
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    fun start(context: Context): Boolean {
        appContext = context.applicationContext
        if (!checkMockPermission(context)) return false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        for (p in providers) {
            try {
                try { lm.removeTestProvider(p) } catch (_: Throwable) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val props = ProviderProperties.Builder()
                        .setHasAltitudeSupport(true)
                        .setHasSpeedSupport(true)
                        .setHasBearingSupport(true)
                        .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .build()
                    lm.addTestProvider(p, props)
                } else {
                    @Suppress("DEPRECATION")
                    lm.addTestProvider(p, false, true, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                }
                lm.setTestProviderEnabled(p, true)
            } catch (_: Throwable) {
            }
        }

        isMocking = true
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
        return true
    }

    fun stop(context: Context) {
        isMocking = false
        handler.removeCallbacks(tickRunnable)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (p in providers) {
            try {
                lm.setTestProviderEnabled(p, false)
                lm.removeTestProvider(p)
            } catch (_: Throwable) {}
        }
    }

    private fun pushMockLocations() {
        val ctx = appContext ?: return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // O-U 自然微抖动（控制在 2.5 米以内，让微信实时位置共享自然逼真）
        if (enableJitter && speed == 0f) {
            driftNorth = (driftNorth * 0.92 + Random.nextDouble(-0.25, 0.25)).coerceIn(-2.5, 2.5)
            driftEast = (driftEast * 0.92 + Random.nextDouble(-0.25, 0.25)).coerceIn(-2.5, 2.5)
        } else {
            driftNorth = 0.0
            driftEast = 0.0
        }

        val effLat = latitude + (driftNorth / 111320.0)
        val effLon = longitude + (driftEast / (111320.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.0001)))

        for (p in providers) {
            try {
                val loc = Location(p).apply {
                    latitude = effLat
                    longitude = effLon
                    altitude = this@MockLocationEngine.altitude + Random.nextDouble(-0.3, 0.3)
                    accuracy = 5.5f + Random.nextFloat() * 1.2f
                    speed = this@MockLocationEngine.speed
                    bearing = this@MockLocationEngine.bearing
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = 3.5f
                        speedAccuracyMetersPerSecond = 0.2f
                        bearingAccuracyDegrees = 5.0f
                    }
                    extras = Bundle().apply { putInt("satellites", 15) }
                }
                lm.setTestProviderLocation(p, loc)
            } catch (_: Throwable) {
            }
        }
    }
}

class MainActivity : Activity() {

    private lateinit var latInput: EditText
    private lateinit var lonInput: EditText
    private lateinit var altInput: EditText
    private lateinit var statusBanner: TextView

    private val presets = listOf(
        PresetSpot("🇯🇵 日本 · 东京塔 (港区芝公园)", 35.658581, 139.745433, 42.5),
        PresetSpot("🇯🇵 日本 · 东京涩谷十字路口", 35.659495, 139.700554, 36.0),
        PresetSpot("🇯🇵 日本 · 大阪道顿堀格力高广告牌", 34.668724, 135.501297, 14.0),
        PresetSpot("🇰🇷 韩国 · 首尔明洞圣堂商圈", 37.563280, 126.987320, 48.0),
        PresetSpot("🇹🇭 泰国 · 曼谷大皇宫 & 湄南河畔", 13.750034, 100.491302, 8.5),
        PresetSpot("🇸🇬 新加坡 · 滨海湾金沙酒店", 1.283375, 103.860726, 15.0),
        PresetSpot("🇫🇷 法国 · 巴黎埃菲尔铁塔战神广场", 48.858370, 2.294481, 35.0),
        PresetSpot("🇬🇧 英国 · 伦敦大本钟 & 泰晤士河", 51.500729, -0.124625, 18.0),
        PresetSpot("🇺🇸 美国 · 纽约时代广场", 40.758896, -73.985130, 24.0)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 100)
            }
        }

        val rootScroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F5F7FA")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(18)
            setPadding(pad, dp(24), pad, pad)
        }

        val titleView = TextView(this).apply {
            text = "WeChat 免 Root 虚拟定位"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
        }
        val subtitleView = TextView(this).apply {
            text = "免 Root 系统级三通道注入 · 悬浮摇杆实时行走 · 零封号风险"
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, dp(4), 0, dp(14))
        }
        container.addView(titleView)
        container.addView(subtitleView)

        statusBanner = TextView(this).apply {
            text = "状态：请先点下方第 1 个按钮，在【开发者选项 -> 选择模拟位置信息应用】中选中本软件"
            textSize = 13f
            setTextColor(Color.parseColor("#92400E"))
            background = roundedBg("#FEF3C7", 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        container.addView(statusBanner)

        val presetCard = buildCardLayout("🌍 海外热门景点一键切换 (WGS-84)")
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets.map { it.name })
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val spot = presets[position]
                latInput.setText(String.format("%.6f", spot.lat))
                lonInput.setText(String.format("%.6f", spot.lon))
                altInput.setText(String.format("%.1f", spot.alt))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        presetCard.addView(spinner)
        container.addView(presetCard, cardLayoutParams())

        val coordCard = buildCardLayout("📍 经纬度与海拔参数")
        latInput = buildLabeledInput(coordCard, "纬度 (Latitude, WGS-84):", MockLocationEngine.latitude.toString())
        lonInput = buildLabeledInput(coordCard, "经度 (Longitude, WGS-84):", MockLocationEngine.longitude.toString())
        altInput = buildLabeledInput(coordCard, "海拔高度 (米):", MockLocationEngine.altitude.toString())

        val jitterSwitch = Switch(this).apply {
            text = "开启自然 GPS 微抖动（实时共享更逼真）"
            isChecked = MockLocationEngine.enableJitter
            setPadding(0, dp(10), 0, dp(4))
            setOnCheckedChangeListener { _, isChecked -> MockLocationEngine.enableJitter = isChecked }
        }
        coordCard.addView(jitterSwitch)
        container.addView(coordCard, cardLayoutParams())

        val btnDevSettings = Button(this).apply {
            text = "⚙️ 1. 打开【开发者选项】选定本应用为模拟位置应用"
            setTextColor(Color.parseColor("#1E40AF"))
            background = roundedBg("#DBEAFE", 10)
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: Throwable) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
        container.addView(btnDevSettings, cardLayoutParams())

        val btnStart = Button(this).apply {
            text = "⚡ 2. 启动 / 更新 免 Root 虚拟定位"
            setTextColor(Color.WHITE)
            background = roundedBg("#2563EB", 10)
            setOnClickListener { startOrUpdateMocking() }
        }
        container.addView(btnStart, cardLayoutParams())

        val btnJoystick = Button(this).apply {
            text = "🕹️ 3. 开启 / 关闭 悬浮窗全向移动摇杆"
            setTextColor(Color.WHITE)
            background = roundedBg("#059669", 10)
            setOnClickListener { toggleFloatingJoystick() }
        }
        container.addView(btnJoystick, cardLayoutParams())

        val btnStop = Button(this).apply {
            text = "⏹️ 停止模拟位置并恢复真实定位"
            setTextColor(Color.parseColor("#B91C1C"))
            background = roundedBg("#FEE2E2", 10)
            setOnClickListener {
                MockLocationEngine.stop(this@MainActivity)
                stopService(Intent(this@MainActivity, JoystickOverlayService::class.java))
                statusBanner.text = "已停止模拟位置，手机已恢复真实 GPS"
                statusBanner.setTextColor(Color.parseColor("#374151"))
                statusBanner.background = roundedBg("#E5E7EB", 10)
            }
        }
        container.addView(btnStop, cardLayoutParams())

        rootScroll.addView(container)
        setContentView(rootScroll)
    }

    private fun startOrUpdateMocking(): Boolean {
        val lat = latInput.text.toString().toDoubleOrNull()
        val lon = lonInput.text.toString().toDoubleOrNull()
        val alt = altInput.text.toString().toDoubleOrNull() ?: 35.0
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            Toast.makeText(this, "请输入合法的经纬度数字", Toast.LENGTH_SHORT).show()
            return false
        }
        MockLocationEngine.latitude = lat
        MockLocationEngine.longitude = lon
        MockLocationEngine.altitude = alt

        val ok = MockLocationEngine.start(this)
        if (ok) {
            statusBanner.text = String.format("免 Root 定位运行中 ✅ 当前坐标: %.5f, %.5f\n提示：共享前建议开启飞行模式再连 Wi-Fi 以防国内基站拉回", lat, lon)
            statusBanner.setTextColor(Color.parseColor("#065F46"))
            statusBanner.background = roundedBg("#D1FAE5", 10)
            Toast.makeText(this, "虚拟定位已注入系统！现在可打开微信共享位置", Toast.LENGTH_SHORT).show()
        } else {
            statusBanner.text = "未授权 ❌ 请先点下方第 1 个按钮进入【开发者选项 -> 选择模拟位置信息应用】选中 WeChatLocSpoofer"
            statusBanner.setTextColor(Color.parseColor("#991B1B"))
            statusBanner.background = roundedBg("#FEE2E2", 10)
            Toast.makeText(this, "请先在开发者选项中选择本应用为模拟位置应用", Toast.LENGTH_LONG).show()
        }
        return ok
    }

    private fun toggleFloatingJoystick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!startOrUpdateMocking()) return
        val intent = Intent(this, JoystickOverlayService::class.java)
        if (JoystickOverlayService.isRunning) {
            stopService(intent)
            Toast.makeText(this, "已关闭悬浮摇杆", Toast.LENGTH_SHORT).show()
        } else {
            startService(intent)
            Toast.makeText(this, "悬浮摇杆已启动！现在切回微信发起实时位置共享即可", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildCardLayout(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#FFFFFF", 12)
            val p = dp(14)
            setPadding(p, p, p, p)
            val header = TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#1F2937"))
                setPadding(0, 0, 0, dp(8))
            }
            addView(header)
        }
    }

    private fun buildLabeledInput(parent: LinearLayout, label: String, initial: String): EditText {
        val tv = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#4B5563"))
            setPadding(0, dp(4), 0, dp(2))
        }
        val et = EditText(this).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            textSize = 14f
        }
        parent.addView(tv)
        parent.addView(et)
        return et
    }

    private fun cardLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }

    private fun roundedBg(hexColor: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

class JoystickOverlayService : Service() {

    companion object {
        @Volatile var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayRoot: LinearLayout
    private lateinit var coordReadout: TextView
    private val loopHandler = Handler(Looper.getMainLooper())

    @Volatile private var joyVecX = 0f
    @Volatile private var joyVecY = 0f
    private val maxWalkSpeedMetersPerSec = 3.0

    private val walkTickRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val magnitude = hypot(joyVecX.toDouble(), joyVecY.toDouble()).coerceAtMost(1.0)
            if (magnitude > 0.08) {
                val currentSpeed = (magnitude * maxWalkSpeedMetersPerSec).toFloat()
                val moveNorthMeters = -joyVecY * maxWalkSpeedMetersPerSec * 0.5
                val moveEastMeters = joyVecX * maxWalkSpeedMetersPerSec * 0.5

                val dLat = moveNorthMeters / 111320.0
                val dLon = moveEastMeters / (111320.0 * cos(Math.toRadians(MockLocationEngine.latitude)).coerceAtLeast(0.0001))

                MockLocationEngine.latitude += dLat
                MockLocationEngine.longitude += dLon
                MockLocationEngine.speed = currentSpeed

                var angleDeg = Math.toDegrees(atan2(moveEastMeters, moveNorthMeters)).toFloat()
                if (angleDeg < 0) angleDeg += 360f
                MockLocationEngine.bearing = angleDeg

                coordReadout.text = String.format("%.5f, %.5f (%.1fm/s)", MockLocationEngine.latitude, MockLocationEngine.longitude, currentSpeed)
            } else {
                MockLocationEngine.speed = 0f
            }
            loopHandler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val winParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 320
        }

        overlayRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC111827"))
                cornerRadius = 28f
            }
            setPadding(18, 14, 18, 18)
        }

        coordReadout = TextView(this).apply {
            text = String.format("✥ 拖动 | %.4f, %.4f", MockLocationEngine.latitude, MockLocationEngine.longitude)
            textSize = 10f
            setTextColor(Color.WHITE)
            setPadding(6, 2, 6, 8)
        }

        coordReadout.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = winParams.x
                        startY = winParams.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        winParams.x = startX + (event.rawX - touchX).toInt()
                        winParams.y = startY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(overlayRoot, winParams)
                        return true
                    }
                }
                return false
            }
        })

        val joystickView = JoystickView(this) { nx, ny ->
            joyVecX = nx
            joyVecY = ny
        }

        val sizePx = (110 * resources.displayMetrics.density).toInt()
        overlayRoot.addView(coordReadout)
        overlayRoot.addView(joystickView, LinearLayout.LayoutParams(sizePx, sizePx))

        windowManager.addView(overlayRoot, winParams)
        loopHandler.post(walkTickRunnable)
    }

    override fun onDestroy() {
        isRunning = false
        loopHandler.removeCallbacksAndMessages(null)
        if (::overlayRoot.isInitialized) {
            try { windowManager.removeView(overlayRoot) } catch (_: Throwable) {}
        }
        super.onDestroy()
    }
}

class JoystickView(
    context: Context,
    private val onVectorChanged: (normX: Float, normY: Float) -> Unit
) : View(context) {

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9910B981")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF10B981")
        style = Paint.Style.FILL
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f
    private var knobX = 0f
    private var knobY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = minOf(w, h) * 0.42f
        knobRadius = baseRadius * 0.42f
        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(centerX, centerY, baseRadius, outerPaint)
        canvas.drawCircle(centerX, centerY, baseRadius, borderPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - centerX
                val dy = event.y - centerY
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (dist <= baseRadius) {
                    knobX = event.x
                    knobY = event.y
                } else {
                    val ratio = baseRadius / dist
                    knobX = centerX + dx * ratio
                    knobY = centerY + dy * ratio
                }
                invalidate()
                onVectorChanged(
                    ((knobX - centerX) / baseRadius).coerceIn(-1f, 1f),
                    ((knobY - centerY) / baseRadius).coerceIn(-1f, 1f)
                )
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = centerX
                knobY = centerY
                invalidate()
                onVectorChanged(0f, 0f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
