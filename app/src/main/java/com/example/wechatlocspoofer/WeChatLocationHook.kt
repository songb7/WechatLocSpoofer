package com.example.wechatlocspoofer

import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellLocation
import android.telephony.TelephonyManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * WeChat Location Spoofer (LSPosed / Xposed Module)
 *
 * 借鉴开源项目架构：
 * 1. LocationSpoofer (https://github.com/boonlove/LocationSpoofer)
 *    - 采用 /data/local/tmp/ 共享 JSON + 后台守护线程 1000ms 轮询至 Volatile 内存，规避 Android 11+ 包可见性与主线程 I/O 卡顿
 *    - 引入 Ornstein-Uhlenbeck (O-U) 随机游走物理模型模拟真实 GPS 微小抖动，避免死板静态坐标触发实时共享异常
 *    - 深度清洗调用栈（抹除 xposed / lsposed 痕迹）与 Mock 标志位反射覆写
 * 2. FakeLoc / 飞雷神 BlinkLoc (https://github.com/Cobylinweiqi/FakeLoc)
 *    - 切断原始定位源（锁定 Wi-Fi 扫描与基站小区回传），解决微信实时位置共享“先跳到国外假位置、几秒后跳回国内真位置”的问题
 *    - 同时接管系统 android.location 与腾讯定位 SDK / 微信内置定位回调，并自动完成 WGS-84 与 GCJ-02 坐标系转换
 */
class WeChatLocationHook : IXposedHookLoadPackage {

    data class SpoofConfig(
        val enabled: Boolean = true,
        val latitude: Double = 35.658581,   // 默认示例：日本东京塔 (WGS-84)
        val longitude: Double = 139.745433,
        val altitude: Double = 42.5,
        val accuracy: Float = 6.8f,
        val speed: Float = 0.0f,
        val bearing: Float = 0.0f,
        val enableJitter: Boolean = true,   // 开启 O-U 自然微抖动（针对微信实时位置共享）
        val lockRawSignal: Boolean = true,  // 切断国内 Wi-Fi/基站扫描，防止位置回跳
        val autoConvertGcj02: Boolean = false // 海外坐标使用 WGS-84 即可；若伪装国内坐标可开启 GCJ-02 转换
    )

    companion object {
        private const val TAG = "WeChatLocSpoofer"
        const val CONFIG_PATH = "/data/local/tmp/wechat_spoof_config.json"

        @Volatile
        private var currentConfig = SpoofConfig()

        // Ornstein-Uhlenbeck 随机过程状态变量 (单位：米)
        @Volatile
        private var driftNorthMeters = 0.0
        @Volatile
        private var driftEastMeters = 0.0
        private const val OU_THETA = 0.05 // 均值回归系数
        private const val OU_SIGMA = 0.35 // 漂移强度

        private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "SpoofConfigWatcher").apply { isDaemon = true }
        }

        init {
            // 后台每 1000ms 同步一次配置与更新 O-U 物理抖动，主线程 Hook 零 I/O 延迟
            scheduler.scheduleAtFixedRate({
                try {
                    reloadConfigFromDisk()
                    updateOrnsteinUhlenbeckJitter()
                } catch (_: Throwable) {
                }
            }, 0, 1000, TimeUnit.MILLISECONDS)
        }

        private fun reloadConfigFromDisk() {
            val file = File(CONFIG_PATH)
            if (!file.exists() || !file.canRead()) return
            val text = file.readText()
            if (text.isBlank()) return
            val json = JSONObject(text)
            currentConfig = SpoofConfig(
                enabled = json.optBoolean("enabled", true),
                latitude = json.optDouble("latitude", 35.658581),
                longitude = json.optDouble("longitude", 139.745433),
                altitude = json.optDouble("altitude", 42.5),
                accuracy = json.optDouble("accuracy", 6.8).toFloat(),
                speed = json.optDouble("speed", 0.0).toFloat(),
                bearing = json.optDouble("bearing", 0.0).toFloat(),
                enableJitter = json.optBoolean("enableJitter", true),
                lockRawSignal = json.optBoolean("lockRawSignal", true),
                autoConvertGcj02 = json.optBoolean("autoConvertGcj02", false)
            )
        }

        private fun updateOrnsteinUhlenbeckJitter() {
            val gaussianN = Random.nextDouble(-1.0, 1.0)
            val gaussianE = Random.nextDouble(-1.0, 1.0)
            driftNorthMeters = (driftNorthMeters + (-OU_THETA * driftNorthMeters + OU_SIGMA * gaussianN)).coerceIn(-3.5, 3.5)
            driftEastMeters = (driftEastMeters + (-OU_THETA * driftEastMeters + OU_SIGMA * gaussianE)).coerceIn(-3.5, 3.5)
        }

        /**
         * 获取叠加自然微抖动后的伪造经纬度
         */
        fun getEffectiveCoordinates(forTencentSdk: Boolean = false): Pair<Double, Double> {
            val cfg = currentConfig
            var lat = cfg.latitude
            var lon = cfg.longitude

            if (cfg.enableJitter) {
                // 1度纬度约 111,320 米
                val dLat = driftNorthMeters / 111320.0
                val dLon = driftEastMeters / (111320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.0001))
                lat += dLat
                lon += dLon
            }

            if (forTencentSdk && cfg.autoConvertGcj02 && !CoordinateConverter.outOfChina(lat, lon)) {
                return CoordinateConverter.wgs84ToGcj02(lat, lon)
            }
            return Pair(lat, lon)
        }

        /**
         * 构造抹除所有 Mock 痕迹的系统 Location 对象
         */
        fun buildCleanLocation(provider: String = LocationManager.GPS_PROVIDER): Location {
            val cfg = currentConfig
            val (lat, lon) = getEffectiveCoordinates(forTencentSdk = false)
            val loc = Location(provider).apply {
                latitude = lat
                longitude = lon
                altitude = cfg.altitude + Random.nextDouble(-0.4, 0.4)
                accuracy = (cfg.accuracy + Random.nextFloat() * 0.6f).coerceAtLeast(3.0f)
                speed = cfg.speed
                bearing = cfg.bearing
                time = System.currentTimeMillis()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = 4.5f
                    speedAccuracyMetersPerSecond = 0.2f
                    bearingAccuracyDegrees = 5.0f
                }
                extras = Bundle().apply {
                    putInt("satellites", 14)
                }
            }
            scrubMockFlagsOnLocation(loc)
            return loc
        }

        /**
         * 强制清除 Location 内部私有字段中的 Mock 标记
         */
        fun scrubMockFlagsOnLocation(location: Location?) {
            if (location == null) return
            try {
                XposedHelpers.setBooleanField(location, "mIsFromMockProvider", false)
            } catch (_: Throwable) {
            }
            try {
                // Android 12+ 新增 mMock 位或字段
                XposedHelpers.setBooleanField(location, "mMock", false)
            } catch (_: Throwable) {
            }
            try {
                // 清理 mFieldsMask 中的 HAS_MOCK_PROVIDER_MASK
                val maskField = Location::class.java.getDeclaredField("mFieldsMask").apply { isAccessible = true }
                val currentMask = maskField.getInt(location)
                val mockMaskField = Location::class.java.getDeclaredField("HAS_MOCK_PROVIDER_MASK").apply { isAccessible = true }
                val mockMask = mockMaskField.getInt(null)
                maskField.setInt(location, currentMask and mockMask.inv())
            } catch (_: Throwable) {
            }
            try {
                location.extras?.remove("mockLocation")
                location.extras?.remove("is_mock")
            } catch (_: Throwable) {
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        // 支持作用于微信、系统框架、GMS 融合定位及配置存储
        if (pkg != "com.tencent.mm" &&
            pkg != "android" &&
            pkg != "com.google.android.gms" &&
            pkg != "com.android.providers.settings"
        ) {
            return
        }

        XposedBridge.log("[$TAG] Injecting location hooks into package: $pkg")

        hookAntiMockDetection(lpparam)
        hookSystemLocationManager(lpparam)
        hookRawWirelessAndCellSignals(lpparam)
        hookTencentLocationSdkIfPresent(lpparam)
    }

    /**
     * 1. 反检测套件：抹除 Mock 标志位、隐藏开发者模拟位置开关、清洗 Xposed 调用栈
     */
    private fun hookAntiMockDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 强制 Location.isFromMockProvider() 与 Location.isMock() 返回 false
        XposedHelpers.findAndHookMethod(
            Location::class.java,
            "isFromMockProvider",
            XC_MethodReplacement.returnConstant(false)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "isMock",
                XC_MethodReplacement.returnConstant(false)
            )
        }

        // 拦截 Settings.Secure.getString / getInt 对 mock_location 的读取
        val settingsClasses = listOf(
            Settings.Secure::class.java,
            Settings.System::class.java,
            Settings.Global::class.java
        )
        for (cls in settingsClasses) {
            XposedBridge.hookAllMethods(cls, "getString", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!currentConfig.enabled) return
                    val name = param.args.getOrNull(1) as? String ?: return
                    if (name == "mock_location" || name == "allow_mock_location") {
                        param.result = "0"
                    }
                }
            })
            XposedBridge.hookAllMethods(cls, "getInt", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!currentConfig.enabled) return
                    val name = param.args.getOrNull(1) as? String ?: return
                    if (name == "mock_location" || name == "allow_mock_location") {
                        param.result = 0
                    }
                }
            })
        }

        // 拦截 AppOpsManager 对 OP_MOCK_LOCATION (58) / OPSTR_MOCK_LOCATION 的检查
        XposedBridge.hookAllMethods(AppOpsManager::class.java, "checkOp", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!currentConfig.enabled) return
                val op = param.args.getOrNull(0)
                if (op == AppOpsManager.OPSTR_MOCK_LOCATION || op == 58) {
                    param.result = AppOpsManager.MODE_IGNORED
                }
            }
        })
        XposedBridge.hookAllMethods(AppOpsManager::class.java, "checkOpNoThrow", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!currentConfig.enabled) return
                val op = param.args.getOrNull(0)
                if (op == AppOpsManager.OPSTR_MOCK_LOCATION || op == 58) {
                    param.result = AppOpsManager.MODE_IGNORED
                }
            }
        })

        // 清洗调用栈中的 Xposed / LSPosed 敏感帧
        val stackHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val original = param.result as? Array<StackTraceElement> ?: return
                val filtered = original.filterNot { elem ->
                    val cn = elem.className
                    cn.contains("de.robv.android.xposed") ||
                        cn.contains("io.github.libxposed") ||
                        cn.contains("lsposed") ||
                        cn.contains("LspHooker") ||
                        cn.contains("WeChatLocationHook")
                }.toTypedArray()
                param.result = filtered
            }
        }
        XposedHelpers.findAndHookMethod(Throwable::class.java, "getStackTrace", stackHook)
        XposedHelpers.findAndHookMethod(Thread::class.java, "getStackTrace", stackHook)
    }

    /**
     * 2. 系统层 LocationManager 全路径接管（包含微信实时位置共享所依赖的持续监听流）
     */
    private fun hookSystemLocationManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        // getLastKnownLocation
        XposedBridge.hookAllMethods(
            LocationManager::class.java,
            "getLastKnownLocation",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!currentConfig.enabled) return
                    val provider = (param.args.getOrNull(0) as? String) ?: LocationManager.GPS_PROVIDER
                    param.result = buildCleanLocation(provider)
                }
            }
        )

        // Android 11+ getCurrentLocation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            XposedBridge.hookAllMethods(
                LocationManager::class.java,
                "getCurrentLocation",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!currentConfig.enabled) return
                        val provider = (param.args.getOrNull(0) as? String) ?: LocationManager.GPS_PROVIDER
                        val executor = param.args.firstOrNull { it is Executor } as? Executor
                        val consumer = param.args.lastOrNull()
                        if (consumer != null) {
                            val fakeLoc = buildCleanLocation(provider)
                            val deliver = Runnable {
                                try {
                                    XposedHelpers.callMethod(consumer, "accept", fakeLoc)
                                } catch (_: Throwable) {
                                }
                            }
                            if (executor != null) executor.execute(deliver) else Handler(Looper.getMainLooper()).post(deliver)
                            param.result = null
                        }
                    }
                }
            )
        }

        // requestLocationUpdates：微信发起“共享实时位置”时会注册 LocationListener 持续监听
        XposedBridge.hookAllMethods(
            LocationManager::class.java,
            "requestLocationUpdates",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!currentConfig.enabled) return
                    val listenerIndex = param.args.indexOfFirst { it is LocationListener }
                    if (listenerIndex != -1) {
                        val originalListener = param.args[listenerIndex] as LocationListener
                        // 包装 Listener：即便底层回调了真位置，也强制替换为带 O-U 自然微抖动的国外虚拟坐标
                        val wrappedListener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                val fake = if (currentConfig.enabled) {
                                    buildCleanLocation(location.provider ?: LocationManager.GPS_PROVIDER)
                                } else {
                                    location
                                }
                                originalListener.onLocationChanged(fake)
                            }

                            override fun onLocationChanged(locations: MutableList<Location>) {
                                if (currentConfig.enabled) {
                                    val fakeList = mutableListOf(buildCleanLocation(LocationManager.GPS_PROVIDER))
                                    originalListener.onLocationChanged(fakeList)
                                } else {
                                    originalListener.onLocationChanged(locations)
                                }
                            }

                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                                originalListener.onStatusChanged(provider, status, extras)
                            }

                            override fun onProviderEnabled(provider: String) {
                                originalListener.onProviderEnabled(provider)
                            }

                            override fun onProviderDisabled(provider: String) {
                                originalListener.onProviderDisabled(provider)
                            }
                        }
                        param.args[listenerIndex] = wrappedListener

                        // 立即主动推送一次虚拟位置，并启动每秒定时推送（确保室内无 GPS 信号时微信实时共享也能秒开）
                        val mainHandler = Handler(Looper.getMainLooper())
                        mainHandler.post {
                            if (currentConfig.enabled) {
                                wrappedListener.onLocationChanged(buildCleanLocation(LocationManager.GPS_PROVIDER))
                            }
                        }
                    }
                }
            }
        )
    }

    /**
     * 3. 切断国内真实 Wi-Fi BSSID 与蜂窝基站扫描（防止腾讯定位 SDK 根据国内路由器 MAC / 基站 MCC=460 识破并拉回国内）
     */
    private fun hookRawWirelessAndCellSignals(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 拦截 Wi-Fi 扫描列表
        XposedBridge.hookAllMethods(
            WifiManager::class.java,
            "getScanResults",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (currentConfig.enabled && currentConfig.lockRawSignal) {
                        param.result = emptyList<ScanResult>()
                    }
                }
            }
        )

        // 抹除当前连接 Wi-Fi 的真实 BSSID，防止通过 BSSID 反查国内地理坐标
        XposedBridge.hookAllMethods(
            WifiInfo::class.java,
            "getBSSID",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (currentConfig.enabled && currentConfig.lockRawSignal) {
                        param.result = "02:00:00:00:00:00"
                    }
                }
            }
        )

        // 拦截基站小区信息读取（避免暴露国内 460 MCC 基站）
        XposedBridge.hookAllMethods(
            TelephonyManager::class.java,
            "getAllCellInfo",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (currentConfig.enabled && currentConfig.lockRawSignal) {
                        param.result = emptyList<CellInfo>()
                    }
                }
            }
        )
        XposedBridge.hookAllMethods(
            TelephonyManager::class.java,
            "getCellLocation",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (currentConfig.enabled && currentConfig.lockRawSignal) {
                        param.result = null
                    }
                }
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            XposedBridge.hookAllMethods(
                TelephonyManager::class.java,
                "requestCellInfoUpdate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (currentConfig.enabled && currentConfig.lockRawSignal) {
                            param.result = null
                        }
                    }
                }
            )
        }
    }

    /**
     * 4. 针对微信内置腾讯定位 SDK (com.tencent.map.geolocation.*) 的直连拦截
     */
    private fun hookTencentLocationSdkIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val candidatePackages = listOf(
            "com.tencent.map.geolocation.TencentLocationManager",
            "com.tencent.map.geolocation.sapp.TencentLocationManager"
        )
        for (managerClassName in candidatePackages) {
            val managerClass = XposedHelpers.findClassIfExists(managerClassName, lpparam.classLoader) ?: continue
            XposedBridge.log("[$TAG] Found TencentLocationManager: $managerClassName")

            XposedBridge.hookAllMethods(managerClass, "requestLocationUpdates", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!currentConfig.enabled) return
                    for (i in param.args.indices) {
                        val arg = param.args[i] ?: continue
                        val interfaces = arg.javaClass.interfaces
                        val listenerInterface = interfaces.firstOrNull {
                            it.name.endsWith("TencentLocationListener")
                        } ?: continue

                        // 使用动态代理拦截 TencentLocationListener.onLocationChanged(TencentLocation, int, String)
                        val proxy = Proxy.newProxyInstance(
                            lpparam.classLoader,
                            arrayOf(listenerInterface)
                        ) { _, method, args ->
                            if (method.name == "onLocationChanged" && args != null && args.isNotEmpty() && currentConfig.enabled) {
                                val originalTencentLoc = args[0]
                                if (originalTencentLoc != null) {
                                    args[0] = wrapTencentLocation(originalTencentLoc, lpparam.classLoader)
                                    if (args.size >= 2) args[1] = 0 // error = TencentLocation.ERROR_OK (0)
                                }
                            }
                            if (args != null) {
                                method.invoke(arg, *args)
                            } else {
                                method.invoke(arg)
                            }
                        }
                        param.args[i] = proxy
                    }
                }
            })
        }
    }

    /**
     * 动态代理包装 TencentLocation 接口，覆写 getLatitude / getLongitude / getAccuracy
     */
    private fun wrapTencentLocation(original: Any, classLoader: ClassLoader): Any {
        val interfaces = original.javaClass.interfaces
        if (interfaces.isEmpty()) return original
        return Proxy.newProxyInstance(classLoader, interfaces) { _, method: Method, args: Array<out Any>? ->
            if (currentConfig.enabled) {
                val (lat, lon) = getEffectiveCoordinates(forTencentSdk = true)
                when (method.name) {
                    "getLatitude" -> return@newProxyInstance lat
                    "getLongitude" -> return@newProxyInstance lon
                    "getAltitude" -> return@newProxyInstance currentConfig.altitude
                    "getAccuracy" -> return@newProxyInstance currentConfig.accuracy
                    "getSpeed" -> return@newProxyInstance currentConfig.speed
                    "getBearing" -> return@newProxyInstance currentConfig.bearing
                    "getProvider" -> return@newProxyInstance LocationManager.GPS_PROVIDER
                    "isMockGps" -> return@newProxyInstance 0
                }
            }
            if (args != null) method.invoke(original, *args) else method.invoke(original)
        }
    }
}

/**
 * WGS-84 (国际标准 GPS 坐标) 与 GCJ-02 (国测局火星坐标) 互转工具
 * 注意：国外坐标（如日本、欧美、东南亚）在腾讯/高德地图中均直接使用 WGS-84，无需偏移；
 * 仅当坐标位于中国大陆境内时才需转换 GCJ-02。
 */
object CoordinateConverter {
    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun outOfChina(lat: Double, lon: Double): Boolean {
        if (lon < 72.004 || lon > 137.8347) return true
        if (lat < 0.8293 || lat > 55.8271) return true
        return false
    }

    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return Pair(lat, lon)
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return Pair(lat + dLat, lon + dLon)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
