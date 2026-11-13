package com.example.wechatlocspoofer

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 预设海外热门旅游坐标数据结构 (WGS-84 国际标准坐标)
 */
data class PresetSpot(
    val name: String,
    val lat: Double,
    val lon: Double,
    val alt: Double
)

/**
 * 跨进程配置写入器：
 * 借鉴 LocationSpoofer (https://github.com/boonlove/LocationSpoofer) 的 IPC 架构，
 * 通过 Root 权限将配置写入 /data/local/tmp/wechat_spoof_config.json 并赋予 644 全局可读与 shell_data_file 上下文，
 * 使微信进程内的 Hook 守护线程可以零延迟读取，规避 Android 11+ 包可见性限制。
 */
object RootConfigBridge {
    const val CONFIG_PATH = "/data/local/tmp/wechat_spoof_config.json"

    @Volatile var enabled: Boolean = true
    @Volatile var latitude: Double = 35.658581
    @Volatile var longitude: Double = 139.745433
    @Volatile var altitude: Double = 42.5
    @Volatile var accuracy: Float = 6.5f
    @Volatile var speed: Float = 0.0f
    @Volatile var bearing: Float = 0.0f
    @Volatile var enableJitter: Boolean = true
    @Volatile var lockRawSignal: Boolean = true
    @Volatile var autoConvertGcj02: Boolean = false

    fun loadFromLocalOrTmp(context: Context) {
        try {
            val file = File(CONFIG_PATH)
            if (file.exists() && file.canRead()) {
                val json = JSONObject(file.readText())
                enabled = json.optBoolean("enabled", true)
                latitude = json.optDouble("latitude", 35.658581)
                longitude = json.optDouble("longitude", 139.745433)
                altitude = json.optDouble("altitude", 42.5)
                accuracy = json.optDouble("accuracy", 6.5).toFloat()
                speed = json.optDouble("speed", 0.0).toFloat()
                bearing = json.optDouble("bearing", 0.0).toFloat()
                enableJitter = json.optBoolean("enableJitter", true)
                lockRawSignal = json.optBoolean("lockRawSignal", true)
                autoConvertGcj02 = json.optBoolean("autoConvertGcj02", false)
            }
        } catch (_: Throwable) {
        }
    }

    fun pushToTmpAsync(onResult: ((Boolean) -> Unit)? = null) {
        val payload = JSONObject().apply {
            put("enabled", enabled)
            put("latitude", latitude)
            put("longitude", longitude)
            put("altitude", altitude)
            put("accuracy", accuracy.toDouble())
            put("speed", speed.toDouble())
            put("bearing", bearing.toDouble())
            put("enableJitter", enableJitter)
            put("lockRawSignal", lockRawSignal)
            put("autoConvertGcj02", autoConvertGcj02)
        }.toString()

        Thread {
            val ok = try {
                val proc = Runtime.getRuntime().exec("su")
                proc.outputStream.bufferedWriter().use { writer ->
                    writer.write("cat << 'EOF' > $CONFIG_PATH\n$payload\nEOF\n")
                    writer.write("chmod 644 $CONFIG_PATH\n")
                    writer.write("chcon u:object_r:shell_data_file:s0 $CONFIG_PATH 2>/dev/null || true\n")
                    writer.write("exit\n")
                    writer.flush()
                }
                proc.waitFor() == 0
            } catch (_: Throwable) {
                false
            }
            if (onResult != null) {
                Handler(Looper.getMainLooper()).post { onResult(ok) }
            }
        }.start()
    }

    fun forceStopWeChat() {
        Thread {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.tencent.mm")).waitFor()
            } catch (_: Throwable) {
            }
        }.start()
    }
}

/**
 * 主控 Activity：纯代码构建 UI，无需额外创建 res/layout XML 文件，直接放入工程即可编译。
 * 支持海外热门景点一键切换、剪贴板/手输坐标、防回跳开关、以及开启悬浮窗摇杆。
 */
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
        RootConfigBridge.loadFromLocalOrTmp(this)

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(18)
            setPadding(pad, dp(24), pad, pad)
        }

        // 顶部标题与状态横幅
        val titleView = TextView(this).apply {
            text = "WeChat 虚拟定位控制台"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
        }
        val subtitleView = TextView(this).apply {
            text = "LSPosed 双通道劫持 · 切断国内 Wi-Fi/基站 · O-U 物理微抖动"
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, dp(4), 0, dp(14))
        }
        container.addView(titleView)
        container.addView(subtitleView)

        statusBanner = TextView(this).apply {
            text = "状态：等待同步配置至 /data/local/tmp..."
            textSize = 13f
            setTextColor(Color.parseColor("#065F46"))
            background = roundedBg("#D1FAE5", 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        container.addView(statusBanner)

        // 卡片 1：预设国外旅游目的地一键选点
        val presetCard = buildCardLayout("🌍 海外热门景点快速切换 (WGS-84)")
        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets.map { it.name })
        spinner.adapter = adapter
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

        // 卡片 2：经纬度与海拔精确输入
        val coordCard = buildCardLayout("📍 经纬度与海拔参数")
        latInput = buildLabeledInput(coordCard, "纬度 (Latitude, WGS-84):", RootConfigBridge.latitude.toString())
        lonInput = buildLabeledInput(coordCard, "经度 (Longitude, WGS-84):", RootConfigBridge.longitude.toString())
        altInput = buildLabeledInput(coordCard, "海拔高度 (米):", RootConfigBridge.altitude.toString())
        container.addView(coordCard, cardLayoutParams())

        // 卡片 3：防检测与信号屏蔽开关
        val switchCard = buildCardLayout("🛡️ 微信实时共享防穿帮设置")
        val enableSwitch = Switch(this).apply {
            text = "启用虚拟定位总开关"
            isChecked = RootConfigBridge.enabled
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, isChecked -> RootConfigBridge.enabled = isChecked }
        }
        val lockSignalSwitch = Switch(this).apply {
            text = "锁定定位源（拦截国内 Wi-Fi 扫描与 460 基站，防回跳）"
            isChecked = RootConfigBridge.lockRawSignal
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, isChecked -> RootConfigBridge.lockRawSignal = isChecked }
        }
        val jitterSwitch = Switch(this).apply {
            text = "开启 O-U 自然微抖动（实时共享时模拟真实 GPS 呼吸波动）"
            isChecked = RootConfigBridge.enableJitter
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, isChecked -> RootConfigBridge.enableJitter = isChecked }
        }
        val gcjSwitch = Switch(this).apply {
            text = "国内坐标自动转 GCJ-02（定位在国外请保持关闭）"
            isChecked = RootConfigBridge.autoConvertGcj02
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, isChecked -> RootConfigBridge.autoConvertGcj02 = isChecked }
        }
        switchCard.addView(enableSwitch)
        switchCard.addView(lockSignalSwitch)
        switchCard.addView(jitterSwitch)
        switchCard.addView(gcjSwitch)
        container.addView(switchCard, cardLayoutParams())

        // 操作按钮组
        val btnSave = Button(this).apply {
            text = "⚡ 保存并热更新坐标（1秒内生效）"
            setTextColor(Color.WHITE)
            background = roundedBg("#2563EB", 10)
            setOnClickListener { applyAndSyncCoordinates() }
        }
        container.addView(btnSave, cardLayoutParams())

        val btnJoystick = Button(this).apply {
            text = "🕹️ 开启/关闭 悬浮窗全向移动摇杆"
            setTextColor(Color.WHITE)
            background = roundedBg("#059669", 10)
            setOnClickListener { toggleFloatingJoystick() }
        }
        container.addView(btnJoystick, cardLayoutParams())

        val btnRestartWeChat = Button(this).apply {
            text = "🔄 强行停止微信（首次勾选作用域后点击）"
            setTextColor(Color.parseColor("#B91C1C"))
            background = roundedBg("#FEE2E2", 10)
            setOnClickListener {
                RootConfigBridge.forceStopWeChat()
                Toast.makeText(this@MainActivity, "已发送强行停止微信指令", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(btnRestartWeChat, cardLayoutParams())

        rootScroll.addView(container)
        setContentView(rootScroll)
    }

    private fun applyAndSyncCoordinates() {
        val lat = latInput.text.toString().toDoubleOrNull()
        val lon = lonInput.text.toString().toDoubleOrNull()
        val alt = altInput.text.toString().toDoubleOrNull() ?: 35.0
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            Toast.makeText(this, "请输入合法的经纬度范围", Toast.LENGTH_SHORT).show()
            return
        }
        RootConfigBridge.latitude = lat
        RootConfigBridge.longitude = lon
        RootConfigBridge.altitude = alt
        RootConfigBridge.pushToTmpAsync { ok ->
            if (ok) {
                statusBanner.text = String.format("已生效 ✅ 当前锚点: %.6f, %.6f", lat, lon)
                statusBanner.setTextColor(Color.parseColor("#065F46"))
                statusBanner.background = roundedBg("#D1FAE5", 10)
                Toast.makeText(this, "坐标已通过 Root 写入共享内存", Toast.LENGTH_SHORT).show()
            } else {
                statusBanner.text = "写入失败 ❌ 请确认已授予本应用 Root (su) 权限"
                statusBanner.setTextColor(Color.parseColor("#991B1B"))
                statusBanner.background = roundedBg("#FEE2E2", 10)
            }
        }
    }

    private fun toggleFloatingJoystick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限以在微信上方显示摇杆", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        applyAndSyncCoordinates()
        val intent = Intent(this, JoystickOverlayService::class.java)
        if (JoystickOverlayService.isRunning) {
            stopService(intent)
            Toast.makeText(this, "已关闭悬浮摇杆", Toast.LENGTH_SHORT).show()
        } else {
            startService(intent)
            Toast.makeText(this, "已启动悬浮摇杆，可切回微信共享实时位置边走边聊", Toast.LENGTH_SHORT).show()
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
        ).apply {
            topMargin = dp(12)
        }
    }

    private fun roundedBg(hexColor: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/**
 * 悬浮窗摇杆服务：
 * 叠加在微信窗口上方，拖动摇杆时根据拉动幅度以 0 ~ 3.5 m/s 的步行/慢跑速度平滑更新经纬度与航向角 (Bearing)，
 * 让朋友在微信“共享实时位置”中看到你的头像正在国外的街道上自然行走。
 */
class JoystickOverlayService : Service() {

    companion object {
        @Volatile var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayRoot: LinearLayout
    private lateinit var coordReadout: TextView
    private val loopHandler = Handler(Looper.getMainLooper())

    // 摇杆归一化向量 (-1.0 .. 1.0) 与最大步行速度 (3.0 m/s)
    @Volatile private var joyVecX = 0f
    @Volatile private var joyVecY = 0f
    private val maxWalkSpeedMetersPerSec = 3.0

    private val walkTickRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val magnitude = hypot(joyVecX.toDouble(), joyVecY.toDouble()).coerceAtMost(1.0)
            if (magnitude > 0.08) {
                val currentSpeed = (magnitude * maxWalkSpeedMetersPerSec).toFloat()
                // 屏幕上方为北 (joyVecY < 0 表示向北移动)，屏幕右方为东 (joyVecX > 0 表示向东移动)
                val moveNorthMeters = -joyVecY * maxWalkSpeedMetersPerSec * 0.5 // 每 500ms 步进一次
                val moveEastMeters = joyVecX * maxWalkSpeedMetersPerSec * 0.5

                val dLat = moveNorthMeters / 111320.0
                val dLon = moveEastMeters / (111320.0 * cos(Math.toRadians(RootConfigBridge.latitude)).coerceAtLeast(0.0001))

                RootConfigBridge.latitude += dLat
                RootConfigBridge.longitude += dLon
                RootConfigBridge.speed = currentSpeed

                // 计算正北顺时针航向角 (0° - 360°)，使微信实时共享中的箭头方向跟随移动方向旋转
                var angleDeg = Math.toDegrees(atan2(moveEastMeters, moveNorthMeters)).toFloat()
                if (angleDeg < 0) angleDeg += 360f
                RootConfigBridge.bearing = angleDeg

                coordReadout.text = String.format("%.5f, %.5f (%.1fm/s)", RootConfigBridge.latitude, RootConfigBridge.longitude, currentSpeed)
                RootConfigBridge.pushToTmpAsync()
            } else if (RootConfigBridge.speed != 0f) {
                RootConfigBridge.speed = 0f
                RootConfigBridge.pushToTmpAsync()
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
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#CC111827"))
                cornerRadius = 28f
            }
            background = bg
            setPadding(18, 14, 18, 18)
        }

        // 顶部拖拽条与实时坐标简报
        coordReadout = TextView(this).apply {
            text = String.format("✥ 拖动窗体 | %.4f, %.4f", RootConfigBridge.latitude, RootConfigBridge.longitude)
            textSize = 10f
            setTextColor(Color.WHITE)
            setPadding(6, 2, 6, 8)
        }

        // 支持按住顶部文字栏拖动整个悬浮窗位置
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

        // 自定义全向摇杆视图
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
            try {
                windowManager.removeView(overlayRoot)
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }
}

/**
 * 2D 虚拟摇杆绘制组件
 */
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
