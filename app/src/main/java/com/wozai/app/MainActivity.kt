package com.wozai.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.TextPaint
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 定位助手：打开 App 即显示当前位置（详细地址 + 经纬度 + 网络小区信息）。
 *
 * 定位全部使用 Android 系统原生能力（GPS / 基站 / WiFi），不依赖 Google 服务；
 * 地址解析用高德 → 腾讯 Web 服务（Key 在 App「设置」里填，不打包默认值），
 * 两家都失败才回退到系统 Geocoder。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvAddress: TextView
    private lateinit var tvLat: TextView
    private lateinit var tvLng: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvFixTime: TextView
    private lateinit var tvCell: TextView
    private lateinit var btnRefresh: LinearLayout
    private lateinit var btnWechat: LinearLayout
    private lateinit var btnSpeak: LinearLayout
    /** 「播放位置」按钮上的文字，按钮本身是 LinearLayout（图标 + 文字整体居中） */
    private lateinit var tvSpeak: TextView

    private lateinit var locationManager: LocationManager
    /** 地址解析专用单线程，保证同一时刻只解析一个坐标 */
    private val geocodeExecutor = Executors.newSingleThreadExecutor()
    /** POI 搜索/地图对比专用线程：与地址解析分开，手动搜索时不会排在定位解析后面干等 */
    private val poiExecutor = Executors.newSingleThreadExecutor()
    /** 时间格式化复用一个实例，避免每次显示/分享都 new SimpleDateFormat */
    private val fixTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    /** 系统语音朗读（TTS）：只朗读详细地址文字 */
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isSpeaking = false
    /** 播放超时兜底：个别引擎不回调 onDone 会卡在「停止播放」，到点强制复位 */
    private val speakTimeout = Runnable {
        if (isSpeaking) {
            AppLogger.log("TTS", "播放超时兜底，强制复位按钮")
            resetSpeakButton()
        }
    }

    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    /** 最后一次成功定位的时刻（毫秒）；地址下方显示时间与分享文字共用它，保持一致 */
    private var lastFixTime = 0L
    /** 当前地址来源（高德/腾讯/系统）；定位时间前展示，与定位时间一起持久化 */
    private var addressSource = ""
    private var hasFix = false
    /** 是否拿到过「实时」定位：系统缓存的上次位置不算，避免一进 App 就谎报定位成功 */
    private var hasRealFix = false
    private var lastCell: CellSnapshot? = null
    private var geocodeSeq = 0
    /** 逆地理去重：上一次真正发起地址解析的坐标与时间，避免多 provider 短时间内重复请求 */
    private var lastGeoLat = 0.0
    private var lastGeoLng = 0.0
    private var lastGeoAt = 0L
    /** 最近一次解析到的地图地标名（如小区/园区/POI），仅本次会话有效，用于分享链接显示实际地名 */
    private var currentPlaceName = ""
    /** 定位监听是否已注册：切后台注销、回前台恢复，避免重复注册 */
    private var isListening = false
    /** 手动定位模式（定位间隔留空）：只定位一次，不注册周期性回调；点刷新/回前台才重新定位 */
    private var manualLocMode = false
    /** 失败引导弹窗本次会话是否已弹过，避免超时反复弹窗打扰 */
    private var failureGuideShown = false

    // ---- 短信直接发送（点击分享即发，默认走上网卡）----
    /** 待发送的短信内容：申请 SEND_SMS 权限是异步的，授权回调里要用 */
    private var pendingSmsText: String? = null
    /** 短信发送回执广播是否已注册（onCreate 注册、onDestroy 注销） */
    private var smsReceiverRegistered = false
    /** 用包名限制的隐式广播 action，避免别的 App 伪造回执 */
    private val smsSentAction = "com.wozai.app.SMS_SENT"
    /** 接收短信发送结果，给老人明确的成功/失败提示，并写日志 */
    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val result = resultCode
            val ok = result == Activity.RESULT_OK
            // 长短信分多段时每段各来一次回执：失败立即提示；成功合并成一次，避免反复弹
            if (ok) {
                smsOkSegments += 1
                if (smsOkSegments < smsExpectedSegments) return
                smsOkSegments = 0
                smsExpectedSegments = 0
            } else {
                smsOkSegments = 0
                smsExpectedSegments = 0
            }
            val msg = when (result) {
                Activity.RESULT_OK -> "位置短信已发送给 ${smsTargetLabel()}"
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "短信发送失败，请检查信号"
                SmsManager.RESULT_ERROR_NO_SERVICE -> "无服务，短信没发出去"
                SmsManager.RESULT_ERROR_NULL_PDU -> "短信发送失败，请稍后重试"
                SmsManager.RESULT_ERROR_RADIO_OFF -> "请打开手机的移动网络"
                else -> "短信发送失败（代码 $result）"
            }
            AppLogger.log("SMS", "发送回执 result=$result $msg")
            bigToast(msg)
        }
    }
    /** 当前这次发送的期望分段数与已成功分段数，用于合并长短信的成功提示 */
    private var smsExpectedSegments = 0
    private var smsOkSegments = 0
    /** 本次短信的目标号码与姓名，用于发送后的提示「发给了谁」 */
    private var smsTargetNumber = ""
    private var smsTargetName = ""

    /** 长时间未定位成功时，诊断具体原因并给出可操作的引导 */
    private val noFixChecker = Runnable {
        if (hasRealFix || !tvStatus.text.toString().contains("正在定位")) return@Runnable
        diagnoseAndGuide()
    }

    /** 系统定位回调：GPS / 基站 / WiFi 任一来源有结果都会进来 */
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            showLocation(location.latitude, location.longitude)
            // 手动模式：拿到第一个实时定位后就注销监听，不再周期性刷新，
            // 直到用户点「重新定位」或重新回到前台才定位下一次。
            if (manualLocMode && hasRealFix) {
                stopSystemLocation()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {
            setStatus("请打开手机“位置信息”开关")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 屏幕常亮：仅 App 处于前台时保持点亮，方便老人慢慢看位置；
        // 切到后台（onPause）立即清除，恢复系统自动熄屏，不影响锁屏与耗电。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 尽早安装日志收集器（含崩溃堆栈捕获）；正式版设置里也有导出入口
        AppLogger.install(this)
        setContentView(R.layout.activity_main)

        tvTitle = findViewById(R.id.tvTitle)
        tvAddress = findViewById(R.id.tvAddress)
        tvLat = findViewById(R.id.tvLat)
        tvLng = findViewById(R.id.tvLng)
        tvStatus = findViewById(R.id.tvStatus)
        tvFixTime = findViewById(R.id.tvFixTime)
        tvCell = findViewById(R.id.tvCell)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnWechat = findViewById(R.id.btnWechat)
        btnSpeak = findViewById(R.id.btnSpeak)
        tvSpeak = findViewById(R.id.tvSpeak)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        btnRefresh.setOnClickListener { requestLocation() }
        btnWechat.setOnClickListener { showShareChooser() }
        btnSpeak.setOnClickListener { speakAddress() }

        // 地址交互（回到 1.3.0 干净基线）：点按=朗读地址，长按=直接打开高德地图。
        // 无提示文字、无下划线，布局字号完全不动。长按返回 true 消费掉，不触发点按朗读。
        tvAddress.setOnClickListener { speakAddress() }
        tvAddress.setOnLongClickListener {
            openInGaode()
            true
        }

        // 经纬度：点按朗读经纬度（和「播放位置」共用同一套 TTS）
        tvLat.setOnClickListener { speakCoordinates() }
        tvLng.setOnClickListener { speakCoordinates() }

        // 设置菜单入口：只有长按标题第一个字「我」才弹，避免误触整行标题。
        // 记录按下的横坐标，长按回调里用 Paint 量出「我」字宽度做范围判断。
        var downX = 0f
        var downY = 0f
        tvTitle.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                downX = event.x
                downY = event.y
            }
            false // 不消费，交给系统继续识别长按
        }
        tvTitle.setOnLongClickListener {
            val firstCharW = tvTitle.paint.measureText("我")
            val withinHeight = downY in 0f..tvTitle.height.toFloat()
            if (downX in 0f..firstCharW && withinHeight) {
                showSettingsMenu()
            }
            // 无论是否命中都消费掉，长按标题其他位置不弹设置、也不触发系统文本选择弹窗
            true
        }

        // 丢弃旧版持久化的历史定位信息（地址/地标名/定位时间/来源），
        // 彻底避免旧数据被误当成当前位置。
        SettingsStore.discardHistoricalLocation(this)
        // 老版本把地图 Key 存在 app_settings（在备份范围里），这里搬到独立的 app_keys
        //（备份规则已排除该文件），升级后第一次启动就完成迁移
        SettingsStore.migrateMapKeys(this)

        // 重启后地址、定位时间、来源三者一律不用旧值：
        // lastFixTime=0、addressSource="" 是字段初始值，updateFixTimeText() 据此把时间行清空；
        // 等本次真实定位成功后，再由 recordFixTime()/recordAddressSource() 刷新成新值。
        updateFixTimeText()

        initTts()

        // 注册短信发送回执接收器（Android 13+ 必须显式声明导出标志，这里仅限本 App 广播）
        try {
            val filter = IntentFilter(smsSentAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(smsSentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(smsSentReceiver, filter)
            }
            smsReceiverRegistered = true
        } catch (e: Exception) {
            AppLogger.log("SMS", "回执接收器注册失败 ${e.message}")
        }

        // 一打开就申请权限并定位，老人无需任何操作
        requestLocation()
    }

    /**
     * 初始化语音：直接使用系统默认 TTS 引擎——也就是用户在系统设置
     * 「文字转语音输出」里选定、并用“播放”按钮测试成功的那个引擎。
     *
     * 不再枚举引擎、也不根据 setLanguage 的返回值拦截：小米等国产机的
     * setLanguage 检测常误报“缺语音数据/不支持”，实际却能正常朗读，
     * 严格判断反而会把本来可用的引擎挡掉。这里只管初始化成功即可朗读。
     */
    private fun initTts() {
        val holder = arrayOfNulls<TextToSpeech>(1)
        holder[0] = TextToSpeech(this) { status ->
            val engine = holder[0] ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                // 尝试设为中文；即便返回值不理想也不拦截，系统设置里已配好中文语音
                try { engine.setLanguage(Locale.CHINA) } catch (_: Exception) { }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { runOnUiThread { resetSpeakButton() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { runOnUiThread { resetSpeakButton() } }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        runOnUiThread { resetSpeakButton() }
                    }
                })
                tts = engine
                ttsReady = true
                AppLogger.log("TTS", "初始化成功")
            } else {
                ttsReady = false
                AppLogger.log("TTS", "初始化失败 status=$status")
            }
        }
    }

    private fun resetSpeakButton() {
        isSpeaking = false
        tvSpeak.text = "播放位置"
        btnSpeak.removeCallbacks(speakTimeout)
    }

    /**
     * 朗读详细地址：只取 tvAddress 的中文地址文字，
     * 不朗读经纬度、运营商、基站等内容。再点一次停止。
     */
    private fun speakAddress() {
        val address = tvAddress.text.toString().trim()
        if (address.isEmpty() || address == "获取中…" || address.contains("正在获取") ||
            address.contains("暂无法") || address.contains("失败")) {
            bigToast("详细地址还没准备好，请稍后再试")
            return
        }
        speakText("您的位置：$address", "address_tts")
    }

    /** 点按经纬度朗读：只读「纬度/经度 + 数字」，再点一次停止。 */
    private fun speakCoordinates() {
        if (!hasFix) {
            bigToast("还没有定位成功，请稍后再试")
            return
        }
        speakText("纬度 ${coordForSpeech(currentLat)}，经度 ${coordForSpeech(currentLng)}", "coord_tts")
    }

    /**
     * 把坐标转成适合 TTS 逐位朗读的形式：每位数字用空格隔开、小数点读成「点」。
     * 不能把小数点直接换成「点」字——那样 TTS 会把后面的 123456 连读成「十二万三千四百五十六」。
     * 例：30.123456 → "3 0 点 1 2 3 4 5 6"，读出来就是「三零点一二三四五六」。
     */
    private fun coordForSpeech(v: Double): String {
        val raw = String.format(Locale.CHINA, "%.6f", v)
        val sb = StringBuilder()
        for (ch in raw) {
            when (ch) {
                '.' -> sb.append(" 点 ")
                '-' -> sb.append("负 ")
                else -> sb.append(ch).append(' ')
            }
        }
        return sb.toString().trim()
    }

    /**
     * 统一的语音播放入口：地址朗读和经纬度朗读共用同一套 TTS 与按钮状态。
     * 正在播放时再点任意朗读入口即停止。
     */
    private fun speakText(text: String, utteranceId: String) {
        val engine = tts
        if (engine == null) {
            bigToast("语音正在准备，请稍等一下再点")
            return
        }
        // 不因 ttsReady 标志拦截：TextToSpeech 内部会排队，引擎就绪后自动播放。
        // 即便初始化回调晚到或未到，只要对象存在就交给系统处理，避免用户一直被提示挡住。
        if (isSpeaking) {
            engine.stop()
            resetSpeakButton()
            return
        }
        isSpeaking = true
        tvSpeak.text = "停止播放"
        // 兜底：朗读一般不超过 30 秒，若 40 秒内仍没收到 onDone/onError，
        // 强制复位按钮，避免个别引擎不回调导致永久卡在「停止播放」。
        btnSpeak.removeCallbacks(speakTimeout)
        btnSpeak.postDelayed(speakTimeout, 40_000L)
        val params = Bundle()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    /** 长按地址：直接打开高德地图 App 主图，由高德自己定位；未安装时回退网页版。 */
    private fun openInGaode() {
        // 高德官方「地图主图」URI：androidamap://rootmap，打开后高德自行定位，无需传经纬度
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("androidamap://rootmap?sourceApplication=wozaina")).apply {
            setPackage("com.autonavi.minimap")
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        // 只有确实装了高德才直接调 App，否则走网页兜底，避免弹「无法打开」
        val installed = packageManager.queryIntentActivities(appIntent, 0).isNotEmpty()
        if (installed) {
            try {
                startActivity(appIntent)
                return
            } catch (_: Exception) {
                // 落空则继续走网页兜底
            }
        }
        if (!hasFix) {
            bigToast("还没有定位成功，请稍后再试")
            return
        }
        val (gcjLat, gcjLng) = GeoUtils.wgs84ToGcj02(currentLat, currentLng)
        val pos = String.format(Locale.CHINA, "%.6f,%.6f", gcjLng, gcjLat)
        val link = "https://uri.amap.com/marker?position=$pos&src=wozaina&coordinate=gaode&callnative=1"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (_: Exception) {
            bigToast("请先安装高德地图")
        }
    }

    private fun requestLocation() {
        if (!hasLocPermission()) {
            requestLocPermissionOrGuide()
            return
        }

        // 读取双卡运营商/基站需要 READ_PHONE_STATE：单独申请，即便用户拒绝也不影响定位主流程。
        // 只有毫秒级的运营商识别会用到，不读取短信、通讯录、通话记录等任何内容。
        val phone = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
        if (phone != PackageManager.PERMISSION_GRANTED) {
            try {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.READ_PHONE_STATE), 101
                )
            } catch (_: Exception) { }
        }

        hasRealFix = false          // 重新开始计时，超时提示才能再次生效
        setStatus("正在定位…请稍等几秒")
        updateCellInfo()
        // 先注销再注册：只有重新注册，系统才会立刻重新定位并回调一次当前位置；
        // 否则监听已在运行，只能干等「间隔 5 秒且移动 5 米」的下一次变化（室内不动就永远等不到）
        stopSystemLocation()
        startSystemLocation()
    }

    /** 精确定位与粗略定位都拿到才算有权限（与原有判断保持一致） */
    private fun hasLocPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED && coarse == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 没有定位权限时：从没申请过、或拒绝过但系统还允许再问 → 弹系统框；
     * 已被「拒绝且不再询问」永久拒绝 → 系统框不会再弹，只能大字引导去系统设置里打开。
     * 和短信权限同一套规则，避免老人误点「拒绝」后彻底没救。
     */
    private fun requestLocPermissionOrGuide() {
        val asked = SettingsStore.hasAskedLocPermission(this)
        val canAskAgain = !asked ||
            ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) ||
            ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            )
        if (canAskAgain) {
            SettingsStore.setAskedLocPermission(this, true)
            setStatus("请在弹窗中选择“允许”，才能显示位置")
            AppLogger.log("PERM", "申请定位权限（已申请过=$asked）")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                100
            )
            return
        }
        AppLogger.log("PERM", "定位权限已被永久拒绝，引导去系统设置")
        showLocPermissionGuide()
    }

    /** 大字引导：定位权限被永久拒绝后，带老人去系统设置里打开 */
    private fun showLocPermissionGuide() {
        setStatus("未获得定位权限", R.color.status_cache)
        if (isFinishing || isDestroyed) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(bigSpan("需要定位权限", 24))
            .setMessage(
                bigSpan(
                    "没有定位权限，就看不到当前位置。\n\n" +
                        "请点「去设置」，在权限里把「位置信息」设为「允许」，" +
                        "返回后再点「重新定位」。",
                    20
                )
            )
            .setPositiveButton(bigSpan("去设置", 22)) { _, _ -> openAppPermissionSettings() }
            .setNegativeButton(bigSpan("知道了", 22), null)
            .show()
    }

    /**
     * 长按标题的设置菜单：地图对比 / 常用位置 / 短信默认号码。
     * 备份、导出日志不是老人用的功能，移到「地图 Key 与定位设置」弹窗底部的小灰字里。
     */
    private fun showSettingsMenu() {
        val items = arrayOf("地图对比", "常用位置", "短信默认号码")
        val dp = resources.displayMetrics.density

        // 隐藏入口：长按弹窗标题「设置」打开地图 Key 设置。
        // 之前用 findViewById(android.R.id.title) 在 AppCompat 主题下取不到标题（实际 id 是
        // androidx.appcompat.R.id.alertTitle），导致长按监听没挂上、点击无反应。
        // 改为自己构造标题 View 并用 setCustomTitle 设置，直接持有引用挂监听，不依赖系统内部 id。
        val titleView = TextView(this).apply {
            text = "设置"
            textSize = 26f   // 与「分享位置」弹窗标题一致
            // 标题和下面的菜单项同色同字重（不用主题默认的灰色细体），一眼看成一组
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF212121.toInt())
            setPadding((24f * dp).toInt(), (14f * dp).toInt(), (24f * dp).toInt(), (10f * dp).toInt())
            isLongClickable = true
            setOnLongClickListener {
                AppLogger.log("KEY", "长按设置标题 → 打开地图Key设置")
                showMapKeySetting()
                true
            }
        }

        // 系统 setItems 的列表项字号改不了，这里自己拼一份大字列表：
        // 每项 22sp、整行宽都可点、上下各 16dp，老人在小屏上也能看清点准。
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }
        items.forEachIndexed { i, label ->
            list.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = label
                textSize = 22f
                setTextColor(0xFF212121.toInt())
                setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
                setOnClickListener {
                    dialog.dismiss()
                    when (i) {
                        0 -> showThreeGeocode()
                        1 -> showPlacesManager()
                        2 -> showSmsNumberSetting()
                    }
                }
            })
        }

        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setCustomTitle(titleView)
            .setView(android.widget.ScrollView(this).apply { addView(list) })
            .setNegativeButton(bigSpan("取消", 22), null)
            .create()
        dialog.show()
    }

    /** 地图 Key 设置：填入高德/腾讯 Key 保存到本机，位置采集读取这里的值 */
    private fun showMapKeySetting() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        // 输入框回显已保存的 Key，方便查看/修改；留空 = 清空该家的 Key（见 checkAndSaveKeys）。
        // 未保存过（空）时，灰色 hint 给出各自的申请网址。
        val etAmap = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
            setText(SettingsStore.getAmapKey(this@MainActivity))
            setSelection(text.length)
            // hint 用带字号的 span 单独设成 14sp，输入进去的字是 15sp
            hint = bigSpan("申请网址:console.amap.com", 14)
        }
        val etTencent = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
            setText(SettingsStore.getTencentKey(this@MainActivity))
            setSelection(text.length)
            hint = bigSpan("申请网址:lbs.qq.com", 14)
        }
        val etInterval = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 15f
            setText(SettingsStore.getLocIntervalText(this@MainActivity))
            setSelection(text.length)
        }
        val etDistance = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 15f
            setText(SettingsStore.getMinDistanceText(this@MainActivity))
            setSelection(text.length)
        }
        val etTimeout = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 15f
            setText(SettingsStore.getLocTimeoutText(this@MainActivity))
            setSelection(text.length)
        }
        // 超时框的灰色提示跟着「定位间隔」联动：
        // 间隔留空（手动模式）→ 留空则自动 15 秒；间隔有值 → 留空则自动间隔+5秒
        fun refreshTimeoutHint(intervalText: String) {
            val hasInterval = intervalText.trim().toLongOrNull()?.let { it >= 5L } == true
            etTimeout.hint = bigSpan(
                if (hasInterval) "留空则自动间隔+5秒" else "留空则自动 15 秒", 14
            )
        }
        refreshTimeoutHint(SettingsStore.getLocIntervalText(this@MainActivity))
        // 输入框下方只留三个定位参数的说明（仅删掉用户指定的「XX：单位X」前缀，其余原样保留）
        val intervalTip = TextView(this).apply {
            text = "默认 15；留空则手动——只在点「重新定位」或回到前台时定位一次。"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val distanceTip = TextView(this).apply {
            text = "默认 5；指移动满该距离才刷新位置，配合间隔一起生效。留空按 5 米。"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        // 超时说明统一成一句，不再随间隔分两套说法
        val timeoutTip = TextView(this).apply {
            text = "超过这个时间还没定位成功，会出失败原因；填太短可能会误报。"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        etInterval.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // 间隔变化只影响超时框的灰色提示（自动 15 秒 / 间隔+5 秒）
                refreshTimeoutHint(s?.toString() ?: "")
            }
        })
        // 标题统一黑色、括号前加粗、括号及括号内不加粗（复用 labelWithNote）
        fun fieldLabel(main: String, note: String, topDp: Int = 0): TextView =
            TextView(this@MainActivity).apply {
                text = labelWithNote(main, note)
                textSize = 16f
                setTextColor(0xFF000000.toInt())
                setPadding(0, (topDp * resources.displayMetrics.density).toInt(), 0, 0)
            }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(fieldLabel("高德Key", "（Web服务）"))
            addView(etAmap)
            addView(fieldLabel("腾讯Key", "（WebService）", 12))
            addView(etTencent)
            addView(fieldLabel("定位间隔", "（秒）", 8))
            addView(etInterval)
            addView(intervalTip)
            addView(fieldLabel("定位距离", "（米）", 8))
            addView(etDistance)
            addView(distanceTip)
            addView(fieldLabel("定位超时", "（秒）", 8))
            addView(etTimeout)
            addView(timeoutTip)
        }
        val settingDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("地图 Key 与定位设置")
            .setView(box)
            // 先设 null，校验逻辑放到 OnShowListener 里，失败时可保留弹窗让用户直接改
            .setPositiveButton(bigSpan("保存", 16), null)
            .setNegativeButton(bigSpan("取消", 16), null)
            .create()
        settingDialog.setOnShowListener {
            settingDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener {
                    val amap = etAmap.text.toString().trim()
                    val tencent = etTencent.text.toString().trim()
                    val interval = etInterval.text.toString().trim()
                    val distance = etDistance.text.toString().trim()
                    val timeout = etTimeout.text.toString().trim()
                    // 定位间隔：留空=手动模式；填了必须是 5~600 秒的整数
                    if (interval.isNotEmpty()) {
                        val sec = interval.toLongOrNull()
                        if (sec == null || sec < 5L || sec > 600L) {
                            etInterval.error = "定位间隔请填 5~600 秒，或留空手动定位"
                            Toast.makeText(
                                this@MainActivity,
                                "定位间隔请填 5~600 秒，或留空手动定位",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnClickListener  // 校验失败：不关闭弹窗，让用户直接改
                        }
                    }
                    // 定位距离：填了必须是 1~1000 米；留空按默认 5 米
                    if (distance.isNotEmpty()) {
                        val meters = distance.toFloatOrNull()
                        if (meters == null || meters < 1f || meters > 1000f) {
                            etDistance.error = "定位距离请填 1~1000 米，或留空按 5 米"
                            Toast.makeText(
                                this@MainActivity,
                                "定位距离请填 1~1000 米，或留空按 5 米",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnClickListener
                        }
                    }
                    // 定位超时：留空=自动（间隔+5秒）；填了必须 5~180 秒，太短会误报失败
                    if (timeout.isNotEmpty()) {
                        val sec = timeout.toLongOrNull()
                        if (sec == null || sec < 5L || sec > 180L) {
                            etTimeout.error = "请填 5~180 秒，或留空自动"
                            Toast.makeText(
                                this@MainActivity,
                                "定位超时请填 5~180 秒，或留空自动",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnClickListener
                        }
                    }
                    settingDialog.dismiss()
                    checkAndSaveKeys(amap, tencent, interval, distance, timeout)
                }
        }
        settingDialog.show()
        addMaintenanceLinks(settingDialog)
    }

    /**
     * 在弹窗按钮栏「取消」左边插入两个小灰字入口：备份 / 导出日志。
     *
     * 这两项不是给老人用的：放在这里不占主菜单，也不会像放在内容区那样被滚动区挡住。
     * 做法是取「取消」按钮的父容器（按钮栏 LinearLayout），把自己的行插到它前面，
     * 这样不管主题里按钮怎么排，位置都在「取消」左侧。
     */
    private fun addMaintenanceLinks(dialog: androidx.appcompat.app.AlertDialog) {
        val negative = try {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
        } catch (_: IllegalStateException) {
            null
        }
        val panel = negative?.parent as? android.view.ViewGroup
        if (negative == null || panel == null) {
            AppLogger.log("BACKUP", "取不到按钮栏，备份/导出日志入口未显示")
            return
        }
        val dp = resources.displayMetrics.density
        fun link(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(0xFF000000.toInt())
            gravity = android.view.Gravity.CENTER
            // 文字小灰字不显眼，但点击区域给足，免得手指按不准
            setPadding((10f * dp).toInt(), (10f * dp).toInt(), (10f * dp).toInt(), (10f * dp).toInt())
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(link("备份") {
                AppLogger.log("BACKUP", "从「地图Key与定位设置」按钮栏打开备份")
                showBackupMenu()
            })
            addView(link("导出日志") {
                AppLogger.log("EXPORT", "从「地图Key与定位设置」按钮栏打开日志")
                showLogExport()
            })
        }
        // 插到按钮栏最前面（第一个是占位 Space，靠它把按钮推到右边）：
        // 这样两个入口贴在左边，右侧仍是「取消 保存」，与按钮同一行的垂直中线上。
        panel.addView(
            row, 0,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    /**
     * 保存地图 Key 前先联网校验可用性：用固定坐标真实请求一次对应服务，
     * 按返回码判断 Key 是否有效。网络请求放后台线程，避免卡住界面。
     * 输入框留空 = 清空该家的 Key；填入新值才更新并校验。
     */
    private fun checkAndSaveKeys(
        amap: String, tencent: String, interval: String, distance: String, timeout: String
    ) {
        // 定位间隔/距离/超时是本地设置，无需联网校验，先存下来
        // （间隔空=手动；距离空回退默认5米；超时空=自动取「间隔+5秒」）
        SettingsStore.setLocIntervalText(this, interval)
        SettingsStore.setMinDistanceText(this, distance)
        SettingsStore.setLocTimeoutText(this, timeout)
        failureGuideShown = false   // 设置变更后给一次新的失败引导机会

        // 输入框留空即清空对应 Key
        if (amap.isEmpty() && tencent.isEmpty()) {
            doSaveKeys(amap, tencent)
            return
        }
        // 校验中进度框：不可点外部取消，避免校验结果回来时界面状态混乱
        val waiting = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("正在校验 Key")
            .setMessage("正在联网测试填写的 Key，请稍候…")
            .setCancelable(false)
            .show()
        // 复用 poiExecutor（单线程，与地址解析分离），网络请求不占主线程
        poiExecutor.execute {
            val amapResult = if (amap.isNotEmpty()) ReverseGeocoder.checkKey("amap", amap) else null
            val tencentResult = if (tencent.isNotEmpty()) ReverseGeocoder.checkKey("tencent", tencent) else null
            runOnUiThread {
                try { waiting.dismiss() } catch (_: Exception) { }
                if (isFinishing || isDestroyed) return@runOnUiThread

                val lines = mutableListOf<String>()
                var allOk = true
                if (amapResult != null) {
                    lines.add("高德：${amapResult.message}")
                    if (!amapResult.ok) allOk = false
                }
                if (tencentResult != null) {
                    lines.add("腾讯：${tencentResult.message}")
                    if (!tencentResult.ok) allOk = false
                }
                if (allOk) {
                    AppLogger.log("KEY", "地图Key校验通过并保存 高德=${Mask.key(amap)} 腾讯=${Mask.key(tencent)}")
                    doSaveKeys(amap, tencent)
                    Toast.makeText(this, "校验通过，已保存并重新定位", Toast.LENGTH_SHORT).show()
                } else {
                    // 有 Key 不可用：把每家结果列出来提示，由用户决定是否仍保存
                    AppLogger.log("KEY", "地图Key校验未通过 高德=${amapResult?.message ?: "空"} 腾讯=${tencentResult?.message ?: "空"}")
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Key 校验结果")
                        .setMessage(lines.joinToString("\n") + "\n\n校验不通过将无法使用该家的地址解析，是否仍要保存？")
                        .setPositiveButton("仍然保存") { _, _ ->
                            doSaveKeys(amap, tencent)
                            Toast.makeText(this, "已保存并重新定位", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("返回修改", null)
                        .show()
                }
            }
        }
    }

    /** 真正写入 Key 并用新 Key 重新定位 */
    private fun doSaveKeys(amap: String, tencent: String) {
        SettingsStore.setAmapKey(this, amap)
        SettingsStore.setTencentKey(this, tencent)
        AppLogger.log("KEY", "地图Key已更新 高德=${Mask.key(amap)} 腾讯=${Mask.key(tencent)}")
        // 用新 Key 重新取地址：重置解析坐标记录，重新走一次定位
        lastGeoAt = 0L
        requestLocation()
    }

    /** 运行日志：不显示全文，只保留「清空」与「导出到下载目录」两个操作 */
    private fun showLogExport() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("运行日志")
            .setMessage("日志已自动记录在本机，导出为 txt 后可在「下载」目录查看。")
            .setPositiveButton("导出到下载目录") { _, _ -> exportLogToDownloads() }
            .setNegativeButton("清空") { _, _ -> AppLogger.clear() }
            .show()
    }

    /**
     * 把文本写进公共「下载」目录：日志导出与备份导出共用。
     *
     * @return true=已写入；false=Android 9 及以下缺存储权限，已发起申请，需在权限回调里重试
     * @throws Exception 写入失败（创建文件失败、磁盘不可写等）
     */
    private fun saveToDownloads(
        fileName: String,
        mime: String,
        content: String,
        permRequestCode: Int
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：MediaStore 写入 Download 集合，无需存储权限
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("创建文件失败")
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw Exception("写入文件失败")
            return true
        }
        // Android 9 及以下：需要 WRITE_EXTERNAL_STORAGE 权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), permRequestCode
            )
            return false
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        java.io.File(dir, fileName).writeText(content, Charsets.UTF_8)
        return true
    }

    /** 导出日志为 txt 到公共「下载」目录：Android 10+ 用 MediaStore 免权限，旧版走存储权限 */
    private fun exportLogToDownloads() {
        val content = AppLogger.getLogText()
        if (content.isBlank()) {
            Toast.makeText(this, "暂无日志可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val fileName = "我在哪-运行日志-$stamp.txt"
        try {
            if (!saveToDownloads(fileName, "text/plain", content, 200)) return
            AppLogger.log("EXPORT", "日志已导出到 下载/$fileName")
            Toast.makeText(this, "日志已导出到「下载/$fileName」", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AppLogger.log("EXPORT", "导出失败：${e.message}")
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 备份：导出/导入地图 Key、联系人、常用位置（导航地址）。
     * 这项不是给老人用的功能，全部用正常字号，不做老人大字适配。
     */
    private fun showBackupMenu() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("备份")
            .setMessage(
                "备份内容：地图 Key、联系人姓名和号码、常用位置（导航地址）。\n\n" +
                    "导出：存成一个备份文件到「下载」目录，换手机时可以拷过去。\n" +
                    "导入：从备份文件恢复，会覆盖当前已填的内容。"
            )
            .setPositiveButton("导出备份") { _, _ -> exportBackup() }
            .setNeutralButton("导入备份") { _, _ -> importBackup() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 导出备份到「下载」目录；Android 9 及以下缺存储权限时先申请，回调里重试 */
    private fun exportBackup() {
        val amap = SettingsStore.getAmapKey(this)
        val tencent = SettingsStore.getTencentKey(this)
        val name = SettingsStore.getSmsName(this)
        val number = SettingsStore.getSmsTo(this)
        val places = PlacesStore.all(this).filterNotNull()
        if (amap.isEmpty() && tencent.isEmpty() && name.isEmpty() &&
            number.isEmpty() && places.isEmpty()
        ) {
            Toast.makeText(this, "现在什么都还没填，没有可备份的内容", Toast.LENGTH_LONG).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val fileName = "我在哪-备份-$stamp.json"
        try {
            val json = Backup.buildJson(this)
            if (!saveToDownloads(fileName, "application/json", json, 201)) return
            AppLogger.log(
                "BACKUP",
                "备份已导出 高德=${Mask.key(amap)} 腾讯=${Mask.key(tencent)} " +
                    "姓名=${Mask.name(name)} 号码=${Mask.phone(number)} 位置=${places.size}个"
            )
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("备份已导出")
                .setMessage(
                    "文件在「下载」目录，名字是：\n$fileName\n\n" +
                        "包含 ${places.size} 个常用位置。\n" +
                        "换手机时把这个文件拷到新手机上，用「导入备份」恢复即可。"
                )
                .setPositiveButton("知道了", null)
                .show()
        } catch (e: Exception) {
            AppLogger.log("BACKUP", "备份导出失败：${e.message}")
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 选备份文件（系统文件选择器，无需存储权限） */
    private fun importBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // 不同文件管理器对 .json 的识别不一样，多列几个类型提高可选中率
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "text/plain", "application/octet-stream")
            )
        }
        try {
            startActivityForResult(intent, 400)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "没找到文件管理器，无法选择备份文件", Toast.LENGTH_LONG).show()
        }
    }

    /** 读到备份文件后先解析并让用户确认，再覆盖当前设置 */
    private fun onBackupFilePicked(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) {
            AppLogger.log("BACKUP", "读取备份文件失败：${e.message}")
            Toast.makeText(this, "读取备份文件失败：${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "这个文件是空的，读不到备份内容", Toast.LENGTH_LONG).show()
            return
        }
        val data = try {
            Backup.parse(text)
        } catch (e: Exception) {
            AppLogger.log("BACKUP", "备份文件不合法：${e.message}")
            Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        // 确认弹窗里显示完整内容，不打码：用户要看着原文核对「是不是我要恢复的那份」，
        // 打码后 Key 只剩前后几位反而没法判断。日志里仍然打码（日志会被导出、外发）。
        val amapText = data.amapKey.ifEmpty { "空" }
        val tencentText = data.tencentKey.ifEmpty { "空" }
        val contactText = when {
            data.smsName.isEmpty() && data.smsNumber.isEmpty() -> "空"
            data.smsNumber.isEmpty() -> data.smsName
            data.smsName.isEmpty() -> data.smsNumber
            else -> "${data.smsName}  ${data.smsNumber}"
        }
        val placesText = if (data.places.isEmpty()) {
            "空"
        } else {
            data.places.joinToString("\n") { p ->
                val poi = if (p.poiName.isEmpty()) "" else "（${p.poiName}）"
                "${p.slot}. ${p.name}$poi"
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("确认导入")
            .setMessage(
                "备份文件里的内容：\n\n" +
                    "高德 Key：$amapText\n" +
                    "腾讯 Key：$tencentText\n" +
                    "联系人：$contactText\n" +
                    "常用位置：$placesText\n\n" +
                    "导入后会覆盖现在填的内容。"
            )
            .setPositiveButton("导入") { _, _ -> applyBackup(data) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 写入备份内容：Key 变了就重新取地址，立刻能看到效果 */
    private fun applyBackup(data: Backup.Data) {
        Backup.apply(this, data)
        AppLogger.log(
            "BACKUP",
            "备份已导入 高德=${Mask.key(data.amapKey)} 腾讯=${Mask.key(data.tencentKey)} " +
                "姓名=${Mask.name(data.smsName)} 号码=${Mask.phone(data.smsNumber)} " +
                "位置=${data.places.size}个"
        )
        Toast.makeText(
            this,
            "备份已导入，常用位置 ${data.places.size} 个，正在重新定位…",
            Toast.LENGTH_LONG
        ).show()
        // 用恢复后的 Key 重新解析当前地址
        lastGeoAt = 0L
        requestLocation()
    }

    /** 设置发短信时的默认收件人（姓名 + 号码），只保存在本机 */
    private fun showSmsNumberSetting() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val savedName = SettingsStore.getSmsName(this)
        val savedNumber = SettingsStore.getSmsTo(this)
        val etName = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 22f
            setText(savedName)
            setSelection(text.length)
            hint = "收件人姓名（选填）"
        }
        val etNumber = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            textSize = 22f
            setText(savedNumber)
            setSelection(text.length)
            hint = "收件人号码"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(etName)
            addView(etNumber)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(bigBoldSpan("短信默认号码", 26))
            .setView(box)
            .setPositiveButton(bigSpan("保存", 22)) { _, _ ->
                val name = etName.text.toString().trim()
                val number = etNumber.text.toString().trim()
                    .replace(Regex("[\\s-]"), "")
                if (number.isNotBlank() && !number.matches(Regex("\\+?\\d{5,20}"))) {
                    bigToast("号码格式不对，请重新输入")
                    return@setPositiveButton
                }
                SettingsStore.setSmsTo(this, number)
                // 号码清空时姓名也没意义，一并清掉
                SettingsStore.setSmsName(this, if (number.isBlank()) "" else name)
                Toast.makeText(
                    this,
                    when {
                        number.isBlank() -> "已清空，发短信时自己选联系人"
                        name.isNotBlank() -> "已保存：$name $number"
                        else -> "已保存默认号码"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(bigSpan("取消", 22), null)
            .show()
    }

    /** 常用位置管理：列出 5 个槽位，未设置的显示「未设置」 */
    private fun showPlacesManager() {
        val places = PlacesStore.all(this)
        val dp = resources.displayMetrics.density
        // 系统列表 setItems 的字号改不了，这里自己拼一份大字列表：
        // 老人从「导航 → 去设置」进来时能看清，已设项深色、未设项浅灰。
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (6 * dp).toInt(), (20 * dp).toInt(), (2 * dp).toInt())
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(bigSpan("常用位置", 24))
            .setView(android.widget.ScrollView(this).apply { addView(list) })
            .setPositiveButton(bigSpan("关闭", 22), null)
            .create()

        places.forEachIndexed { i, p ->
            val color = if (p == null) 0xFF9E9E9E.toInt() else 0xFF212121.toInt()
            // 编号单独占一列（38dp 固定宽），名称统一从同一位置开始：
            // 原来把「1.」和名称拼成一个字符串，1~5 的字宽不同会左右错位。
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, (16 * dp).toInt(), 0, (16 * dp).toInt())
                setOnClickListener {
                    dialog.dismiss()
                    showPlaceEditor(i + 1, p)
                }
            }
            row.addView(TextView(this).apply {
                text = "${i + 1}."
                textSize = 22f
                setTextColor(color)
                minWidth = (38 * dp).toInt()
            })
            row.addView(TextView(this).apply {
                text = p?.name ?: "未设置"
                textSize = 22f
                setTextColor(color)
            })
            list.addView(row)
        }
        dialog.show()
    }

    /** 编辑单个槽位：改名（选填）/ 用当前位置设定 / 手输经纬度 / 手动输入地址 / 清空 */
    private fun showPlaceEditor(index: Int, place: PlacesStore.Place?) {
        val view = layoutInflater.inflate(R.layout.dialog_place_editor, null)
        val etName = view.findViewById<android.widget.EditText>(R.id.etPlaceName)
        val tvCoord = view.findViewById<TextView>(R.id.tvPlaceCoord)
        val tvPreview = view.findViewById<TextView>(R.id.tvPoiPreview)
        // 两行输入框右侧有按钮，提示语按 0.9 倍字号显示：小屏也能整句露出、不被截断
        shrinkHint(view.findViewById(R.id.etCoord))
        shrinkHint(view.findViewById(R.id.etAddress))
        // 名称框正文 22sp，提示语缩到 17sp，和上面两行搜索框的提示一样大
        shrinkHint(etName, 17f / 22f)
        // 两行分组标题：只有主文案加粗，括号里的说明保持常规字重
        view.findViewById<TextView>(R.id.tvNameLabel).text = labelWithNote("名称", "（只在列表显示）")
        view.findViewById<TextView>(R.id.tvSearchLabel).text = labelWithNote("地址或坐标搜索", "（二选一）")
        etName.setText(place?.name.orEmpty())
        etName.setSelection(etName.text.length)

        // 编辑中的临时坐标（WGS-84）和地图实际名称；初始用已存的值
        var lat = place?.lat ?: 0.0
        var lng = place?.lng ?: 0.0
        var mapName = place?.poiName.orEmpty()
        fun refreshCoord() {
            tvCoord.text = if (lat != 0.0 && lng != 0.0)
                "坐标：${String.format(Locale.CHINA, "%.5f", lat)}, ${String.format(Locale.CHINA, "%.5f", lng)}"
            else "坐标：未设置"
        }
        refreshCoord()
        if (mapName.isNotBlank()) {
            tvPreview.visibility = android.view.View.VISIBLE
            tvPreview.text = "当前地图名称：$mapName\n导航将按此名称显示"
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(bigSpan("位置 $index", 24))
            .setView(view)
            .setNegativeButton(bigSpan("取消", 22), null)
            .setPositiveButton(bigSpan("保存", 22), { _, _ ->
                if (lat == 0.0 || lng == 0.0) {
                    bigToast("请先设定坐标：用当前位置、按地址，或直接输经纬度")
                    return@setPositiveButton
                }
                // 名称选填：不填就用地图实际名称；再没有就用坐标本身当名字，
                // 这样列表里仍然看得见是哪个点，不必强制老人想名字。
                val name = etName.text.toString().trim().ifBlank {
                    mapName.ifBlank { String.format(Locale.CHINA, "%.3f,%.3f", lat, lng) }
                }
                PlacesStore.set(this, index, name, lat, lng, mapName)
                bigToast("已保存")
            })
            .create()

        // 按坐标反查地图名称：手输坐标和「用当前位置」都走这一条，行为才一致。
        // POI 名优先（小区/园区/楼宇），只有详细地址时 found 仍是地址，但 mapName 不填，
        // 免得导航里出现一整串地址当地标。名称框已经自己写过就不覆盖。
        fun lookupMapName(pLat: Double, pLng: Double, onResult: (String?, Boolean) -> Unit) {
            poiExecutor.execute {
                val r = ReverseGeocoder.resolveFull(this, pLat, pLng)
                val poi = r.placeName?.takeIf { it.isNotBlank() }
                val found = poi ?: r.address?.takeIf { it.isNotBlank() }
                runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (found == null) {
                        onResult(null, false)
                        return@runOnUiThread
                    }
                    mapName = poi.orEmpty()
                    val autoFilled = etName.text.toString().isBlank()
                    if (autoFilled) {
                        etName.setText(found)
                        etName.setSelection(etName.text.length)
                    }
                    onResult(found, autoFilled)
                }
            }
        }

        // 用当前位置设定：坐标取当前定位，再反查一次地图名称自动填进「名称」
        view.findViewById<android.view.View>(R.id.btnUseCurrent).setOnClickListener {
            if (!hasFix) {
                bigToast("还没有定位到位置")
            } else {
                lat = currentLat
                lng = currentLng
                mapName = ""
                refreshCoord()
                tvPreview.visibility = android.view.View.VISIBLE
                tvPreview.text = "已按当前位置设定坐标\n正在查地图名称…"
                bigToast("已填入当前位置")
                lookupMapName(lat, lng) { found, autoFilled ->
                    tvPreview.text = buildString {
                        append("已按当前位置设定坐标\n")
                        if (found == null) {
                            append("没查到地图名称，可自己写名称或留空")
                        } else {
                            append("地图名称：$found")
                            if (!autoFilled) append("\n（名称已自己填过，没有覆盖）")
                        }
                        append("\n确认无误后点右下角「保存」")
                    }
                }
            }
        }

        // 直接手输经纬度：只有坐标、不知道地址名时用（比如别人发来的一串坐标）。
        // 坐标设好后再反查一次地图名称，自动填进「名称」，省得老人自己想名字。
        val etCoord = view.findViewById<android.widget.EditText>(R.id.etCoord)
        view.findViewById<android.view.View>(R.id.btnFromCoord).setOnClickListener {
            val parsed = parseCoordInput(etCoord.text.toString())
            if (parsed == null) {
                bigToast("请按「纬度, 经度」写，例如 39.90800, 116.39700")
                return@setOnClickListener
            }
            val pLat = parsed.first
            val pLng = parsed.second
            lat = pLat
            lng = pLng
            mapName = ""
            refreshCoord()
            tvPreview.visibility = android.view.View.VISIBLE
            // 坐标统一只在下面那行显示一次，这里只说「选了什么、要不要保存」
            tvPreview.text = "已按经纬度设定坐标\n正在查地图名称…"
            bigToast("已按经纬度设定坐标")

            lookupMapName(pLat, pLng) { found, autoFilled ->
                tvPreview.text = buildString {
                    append("已按经纬度设定坐标\n")
                    if (found == null) {
                        append("没查到地图名称，可自己写名称或留空")
                    } else {
                        append("地图名称：$found")
                        // 名称框已经自己写过就不覆盖，免得冲掉「家」「医院」这种自己起的名
                        if (!autoFilled) append("\n（名称已自己填过，没有覆盖）")
                    }
                    append("\n确认无误后点右下角「保存」")
                }
                if (found != null) bigToast(if (autoFilled) "已填入地图名称" else "已查到地图名称")
            }
        }

        // 按名称搜索 POI（高德主腾讯备）→ 列候选 → 选中后预览确认
        val etAddress = view.findViewById<android.widget.EditText>(R.id.etAddress)
        view.findViewById<android.view.View>(R.id.btnFromAddress).setOnClickListener {
            val kw = etAddress.text.toString().trim()
            if (kw.isEmpty()) {
                bigToast("请先填入地址")
                return@setOnClickListener
            }
            // 只认第一个明确写出的「××市」：成都市南充 → 在成都搜「南充」，
            // 南充市成都 → 在南充搜「成都」。没写「市」（如北京朝阳区）不认，
            // 仍走老逻辑：优先搜当前定位所在城市，宽松优先不锁死。
            val parsedCity = ReverseGeocoder.parseCity(kw)
            val searchKw = parsedCity?.keyword ?: kw
            val cLat = currentLat
            val cLng = currentLng
            val fixOk = hasFix
            bigToast("正在搜索地点…")
            poiExecutor.execute {
                val city = parsedCity?.city
                    ?: if (fixOk) ReverseGeocoder.cityName(this, cLat, cLng) else ""
                val strict = parsedCity != null
                val pois = ReverseGeocoder.searchPoi(this, searchKw, city, strict)
                runOnUiThread {
                    if (pois.isEmpty()) {
                        bigToast(
                            if (strict) "在$city 没搜到「$searchKw」，请确认城市是否写对"
                            else "没搜到地点，请换个写法或检查网络"
                        )
                        return@runOnUiThread
                    }
                    val labels = pois.map { p ->
                        if (p.address.isNotBlank()) "${p.name}\n    ${p.address}" else p.name
                    }.toTypedArray()
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        // 只在用户明确写了「××市」（严格限定）时标题才带城市；
                        // 没写城市时虽然优先当前城市，但结果是全国范围，标题就不带括号，
                        // 免得写着成都市却列一堆南充、北京的地点，让人看不懂
                        .setTitle(if (strict) "选择搜索到的地点（$city）" else "选择搜索到的地点")
                        .setItems(labels) { _, which ->
                            val p = pois[which]
                            lat = p.lat
                            lng = p.lng
                            mapName = p.name   // 导航目的地名用地图实际名称，不用上面填的本地名称
                            refreshCoord()
                            tvPreview.visibility = android.view.View.VISIBLE
                            // 坐标只在下面「坐标：」那一行显示，这里不重复，免得两处都写一遍
                            val lines = mutableListOf("已选：${p.name}")
                            if (p.address.isNotBlank()) lines.add(p.address)
                            lines.add("导航将按此地图名称显示，确认无误后点右下角「保存」")
                            tvPreview.text = lines.joinToString("\n")
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }

        // 清空槽位
        view.findViewById<android.view.View>(R.id.btnClearPlace).setOnClickListener {
            PlacesStore.clear(this, index)
            dialog.dismiss()
            bigToast("已清空位置 $index")
        }

        dialog.show()
    }

    /**
     * 把输入框的提示语按比例缩小显示（默认 0.9 倍），正文输入仍是原字号。
     * 提示语不参与输入，宁可小一点也要整句看得完，不被右侧按钮挤成半截。
     */
    private fun shrinkHint(et: EditText, scale: Float = 0.9f) {
        val hint = et.hint ?: return
        et.hint = SpannableString(hint.toString()).apply {
            setSpan(RelativeSizeSpan(scale), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * 分组标题 + 括号说明：只有前面的标题加粗，括号里的补充说明保持常规字重，
     * 免得整行都粗、看不出哪个是重点。
     */
    private fun labelWithNote(main: String, note: String): CharSequence {
        return SpannableStringBuilder(main + note).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, main.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * 解析手输的经纬度。整串必须正好是「纬度, 经度」两个数，
     * 分隔符支持中英文逗号、分号、斜杠、顿号、空格；
     * 有人习惯写成「经度, 纬度」，第一个数超过 ±90 时自动调换回来。
     * 数量不对、越界、或正好落在 (0,0) 都返回 null（(0,0) 是空值哨兵，存进去会被当成未设置）。
     */
    private fun parseCoordInput(raw: String): Pair<Double, Double>? {
        val pattern = Regex(
            """^\s*(-?\d{1,3}(?:\.\d{1,8})?)\s*[,，;；/|、\s]\s*(-?\d{1,3}(?:\.\d{1,8})?)\s*[,，;；/|、\s]*$"""
        )
        val m = pattern.find(raw) ?: return null
        var a = m.groupValues[1].toDoubleOrNull() ?: return null
        var b = m.groupValues[2].toDoubleOrNull() ?: return null
        if (kotlin.math.abs(a) > 90.0 && kotlin.math.abs(b) <= 90.0) {
            val t = a
            a = b
            b = t
        }
        if (kotlin.math.abs(a) > 90.0 || kotlin.math.abs(b) > 180.0) return null
        if (a == 0.0 || b == 0.0) return null
        return a to b
    }

    /** 分享弹窗里点「导航到常用位置」：只列出已设置的槽位，点名称直接步行导航 */
    private fun showNavigatePicker() {
        val places = PlacesStore.all(this)
        val setIndices = places.mapIndexed { i, p -> i to p }.filter { it.second != null }
        if (setIndices.isEmpty()) {
            // 一个都没设：不要只提示「去别处设置」让老人自己找路，
            // 直接给一个「去设置」按钮，点了就进常用位置管理，设好回来即可导航。
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(bigSpan("还没有常用位置", 24))
                .setMessage(
                    bigSpan(
                        "先设好至少一个常用位置，以后点一下就能导航。",
                        20
                    )
                )
                .setPositiveButton(bigSpan("去设置", 22)) { _, _ ->
                    AppLogger.log("NAV", "常用位置为空，从导航弹窗跳转去设置")
                    showPlacesManager()
                }
                .setNegativeButton(bigSpan("取消", 22), null)
                .show()
            return
        }
        // 自定义大字布局（老人用）：标题醒目，每个位置做成一个大按钮
        val view = layoutInflater.inflate(R.layout.dialog_navigate_picker, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .create()
        val container = view.findViewById<android.widget.LinearLayout>(R.id.navPlacesContainer)
        val amapIcon = amapAppIcon()
        setIndices.forEach { entry ->
            val item = layoutInflater.inflate(R.layout.item_nav_place, container, false)
            val name = "${entry.second!!.name}（${entry.first + 1}）"
            item.findViewById<android.widget.TextView>(R.id.navItemName).text = name
            amapIcon?.let { item.findViewById<android.widget.ImageView>(R.id.navItemIcon).setImageDrawable(it) }
            item.setOnClickListener {
                dialog.dismiss()
                navigateTo(entry.second!!)
            }
            container.addView(item)
        }
        view.findViewById<android.view.View>(R.id.btnCancelNav).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 直接调起高德地图 App 步行导航到目标；起点为当前位置。只调 App，不打开网页。 */
    private fun navigateTo(place: PlacesStore.Place) {
        if (!hasFix) {
            bigToast("还没有定位到位置，请稍等")
            return
        }
        // 高德 App 内部 URI：坐标用已加密的 GCJ-02（dev=0），t=2 表示步行
        val (fromLat, fromLng) = GeoUtils.wgs84ToGcj02(currentLat, currentLng)
        val (toLat, toLng) = GeoUtils.wgs84ToGcj02(place.lat, place.lng)
        // 目的地名用 POI 的地图实际名称；用当前位置设定或旧数据时给通用名称
        val toName = android.net.Uri.encode(place.poiName.ifBlank { "目的地" })
        val url = "amapuri://route/plan/?" +
                "sourceApplication=wozaina" +
                "&slat=${String.format(Locale.CHINA, "%.6f", fromLat)}" +
                "&slon=${String.format(Locale.CHINA, "%.6f", fromLng)}" +
                "&sname=${android.net.Uri.encode("当前位置")}" +
                "&dlat=${String.format(Locale.CHINA, "%.6f", toLat)}" +
                "&dlon=${String.format(Locale.CHINA, "%.6f", toLng)}" +
                "&dname=$toName" +
                "&dev=0&t=2"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.autonavi.minimap")
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            // 同时指定包名，未安装高德时直接抛异常，给明确提示，不退回网页
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                bigToast("请先安装高德地图 App")
            }
        } catch (e: ActivityNotFoundException) {
            bigToast("请先安装高德地图 App")
        }
    }

    private fun showThreeGeocode() {
        if (!hasFix) {
            bigToast("还没有定位到位置，请稍等")
            return
        }
        val lat = currentLat
        val lng = currentLng

        // 自定义标题：默认标题字号小、位置也偏内缩，这里统一成 24sp 加粗靠左，
        // 和内容左边对齐，老人一眼就能看出这是弹窗的标题。
        val tvTitle = TextView(this).apply {
            text = "当前位置对比"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF212121.toInt())
            gravity = android.view.Gravity.START
            setPadding(8, 8, 8, 0)
        }
        // 自定义内容视图：先显示「查询中…」，结果回来后原地更新，不另弹窗
        val tvResult = TextView(this).apply {
            text = threeSourceText("查询中…", "查询中…", "查询中…")
            textSize = 18f
            setTextColor(0xFF222222.toInt())
            setPadding(8, 8, 8, 8)
        }
        var resultText = tvResult.text.toString()
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setCustomTitle(tvTitle)
            .setView(tvResult)
            .setPositiveButton("复制", { _, _ ->
                val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("三地图地址", resultText))
                bigToast("已复制")
            })
            .setNegativeButton("关闭", null)
            .show()

        poiExecutor.execute {
            val (amapRes, tencentRes) = ReverseGeocoder.resolveBothDetail(this, lat, lng)
            val systemAddr = systemGeocode(lat, lng)
            runOnUiThread {
                // 失败时把原因写出来（没配 Key、Key 无效、次数用完…），
                // 只写「获取失败」的话，用户不知道该去设置里填 Key 还是该等网络
                val amap = amapRes.address ?: amapRes.reason
                val tencent = tencentRes.address ?: tencentRes.reason
                // 三个来源之间空一行，老人一眼能分出哪句是哪个地图给的
                resultText = "高德：$amap\n\n腾讯：$tencent\n\n系统：$systemAddr"
                // 用户若已关闭弹窗就不必再更新；复制按钮用的 resultText 仍保留最新值
                if (dialog.isShowing) tvResult.text = threeSourceText(amap, tencent, systemAddr)
            }
        }
    }

    /**
     * 三来源对比文本：只把「高德 / 腾讯 / 系统」三个来源名加粗，
     * 地址本身保持常规字重，长地址不至于糊成一片。
     */
    private fun threeSourceText(amap: String, tencent: String, system: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        listOf("高德" to amap, "腾讯" to tencent, "系统" to system)
            .forEachIndexed { i, (label, body) ->
                if (i > 0) sb.append("\n\n")
                sb.append(label)
                sb.setSpan(
                    StyleSpan(Typeface.BOLD), sb.length - label.length, sb.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.append("：").append(body)
            }
        return sb
    }

    @SuppressLint("MissingPermission")
    private fun startSystemLocation() {
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,       // 卫星定位：户外最准
                LocationManager.NETWORK_PROVIDER    // 基站 / WiFi 定位：室内可用
            ).filter { locationManager.isProviderEnabled(it) }

            if (providers.isEmpty()) {
                setStatus("请打开手机“位置信息”开关")
                bigToast("请到设置里打开“位置信息”")
                return
            }

            tvStatus.removeCallbacks(noFixChecker)
            // 超时时间用户在「地图 Key 设置」里可改；留空自动按「定位间隔+5秒」，
            // 手动模式 15 秒。超时到点才诊断原因、弹失败引导，不打断正常的首次定位。
            val timeoutMs = SettingsStore.getLocTimeoutMillis(this)
            tvStatus.postDelayed(noFixChecker, timeoutMs)

            if (!isListening) {
                // 定位间隔由用户在「地图 Key 设置」里填写（单位秒），默认 15 秒；
                // 留空/0 为手动模式：设一个很大的 minTime，拿到第一个实时 fix 后即注销，
                // 之后只在点「重新定位」或回到前台时才再定位一次，不周期刷新。
                val intervalMs = SettingsStore.getLocIntervalMillis(this)
                manualLocMode = intervalMs == 0L
                val minTime = if (manualLocMode) 60 * 60 * 1000L else intervalMs
                // 定位距离由用户在「地图 Key 设置」里填写（单位米），默认 5 米、不允许 0
                val minDistance = SettingsStore.getMinDistanceMeters(this)
                providers.forEach { provider ->
                    locationManager.requestLocationUpdates(
                        provider, minTime, minDistance, locationListener, mainLooper
                    )
                }
                isListening = true
                AppLogger.log("LOC", "注册定位监听 间隔=${if (manualLocMode) "手动" else "${intervalMs / 1000}秒"} 距离=${minDistance}米 超时=${timeoutMs / 1000}秒 providers=${providers}")
            }
            // 还没有任何位置时，先用系统缓存的最近位置占位，界面立刻有内容。
            // 注意这个缓存由系统定位服务维护，任何 App 定位都会更新它，并不属于本 App 的数据，
            // 所以清空数据、重装之后照样读得到，不代表 App 没清干净。
            // 太旧的（超过 2 小时）不再拿来显示，免得把几天前的坐标当成本次结果。
            if (!hasFix) {
                val freshEnough = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
                var best: android.location.Location? = null
                for (provider in providers) {
                    val known = locationManager.getLastKnownLocation(provider) ?: continue
                    if (known.time < freshEnough) continue
                    if (best == null || known.time > best!!.time) best = known
                }
                best?.let { showLocation(it.latitude, it.longitude, fromCache = true) }
            }
        } catch (e: SecurityException) {
            setStatus("未获得定位权限，请点击“重新定位”再试")
        } catch (e: Exception) {
            setStatus("定位服务不可用，请检查系统设置")
        }
    }

    /** 注销定位监听并取消超时提示：切到后台 / 销毁时调用，避免后台耗电 */
    private fun stopSystemLocation() {
        if (!isListening) return
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) { }
        isListening = false
        if (::tvStatus.isInitialized) tvStatus.removeCallbacks(noFixChecker)
    }

    /**
     * 定位超时后诊断具体原因，给老人明确文案 + 可点击的操作引导。
     * 优先级：没授权 → 位置总开关关闭 → 没联网 → 有定位条件但没拿到结果（信号弱）。
     * 弹窗本次会话只弹一次，避免反复打扰；但状态栏文案每次都会更新到最贴切的一句。
     */
    private fun diagnoseAndGuide() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        val noPermission = fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED

        // 位置总开关：GPS 与网络定位都关闭才视为「位置没开」
        val gpsOn = try { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        val netOn = try { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }

        val (title, detail, settingsAction) = when {
            noPermission -> {
                setStatus("未获得定位权限", R.color.status_cache)
                GuideData(
                    "定位失败：没有定位权限",
                    "App 还没有获得定位权限，所以拿不到位置。\n\n怎么操作：\n点下方「去设置」→ 权限 → 允许「位置信息」，返回后点「重新定位」。",
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                )
            }
            !gpsOn && !netOn -> {
                setStatus("手机位置开关没打开", R.color.status_cache)
                GuideData(
                    "定位失败：位置开关没打开",
                    "手机的位置信息总开关是关闭的，定位功能用不了。\n\n怎么操作：\n点下方「去设置」打开位置信息（建议选「高精度」），返回后点「重新定位」。",
                    Settings.ACTION_LOCATION_SOURCE_SETTINGS
                )
            }
            !isOnline() -> {
                setStatus("网络未连接，地址解析受影响", R.color.status_cache)
                GuideData(
                    "当前没有网络",
                    "没联网时，室内基站/WiFi 定位和详细地址都拿不到。\n\n怎么操作：\n点下方「去设置」打开 WiFi 或移动数据；在户外即使没网，卫星定位通常仍可拿到经纬度。",
                    Settings.ACTION_WIRELESS_SETTINGS
                )
            }
            else -> {
                setStatus("定位有点慢，请到窗边或室外", R.color.status_cache)
                GuideData(
                    "暂时没定位成功",
                    "已发出定位请求，但还没拿到结果。通常是环境信号弱导致的。\n\n怎么操作：\n走到窗边或室外，确认已打开「高精度定位」，稍等几秒；仍不行可点「重新定位」。",
                    null
                )
            }
        }

        if (isFinishing || isDestroyed) return
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(detail)
            .setNegativeButton("知道了", null)
        if (settingsAction != null) {
            builder.setPositiveButton("去设置") { _, _ ->
                try {
                    if (settingsAction == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                        val uri = android.net.Uri.fromParts("package", packageName, null)
                        startActivity(Intent(settingsAction, uri))
                    } else {
                        startActivity(Intent(settingsAction))
                    }
                } catch (e: Exception) {
                    bigToast("打不开设置，请手动到系统设置里操作")
                }
            }
        }
        if (!failureGuideShown) {
            builder.show()
            failureGuideShown = true
            AppLogger.log("LOC", "定位失败引导：${title}")
        }
    }

    /** 定位失败引导弹窗所需的数据 */
    private data class GuideData(
        val title: String,
        val detail: String,
        val settingsAction: String?
    )

    /**
     * 当前是否有可用网络。
     * 基站/WiFi 定位与地址解析都依赖联网，断网时室内基本等不到结果，
     * 需要据此给出明确提示，而不是让界面一直停在「正在定位」。
     */
    private fun isOnline(): Boolean = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        if (network == null) {
            false
        } else {
            cm.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        }
    } catch (e: Exception) {
        true    // 判断不出来就当有网，宁可多等一会也不误报
    }

    /** 统一设置状态文字与颜色，避免各处只改文字漏改颜色 */
    private fun setStatus(text: String, colorRes: Int = R.color.status_normal) {
        if (!::tvStatus.isInitialized) return
        tvStatus.text = text
        tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    /** 记录本次真实定位时刻：只更新内存并刷新时间显示，不落盘（重启后不复用旧值） */
    private fun recordFixTime() {
        lastFixTime = System.currentTimeMillis()
        updateFixTimeText()
    }

    /** 地址下方显示最后定位时间（带来源前缀）；本机还没定位过时留空。
     *  前缀「高德定位：」用常规字重，时间数字加粗，让时间一眼能看清，
     *  又不靠加深颜色去抢地址的注意力。 */
    private fun updateFixTimeText() {
        if (!::tvFixTime.isInitialized) return
        if (lastFixTime <= 0) {
            tvFixTime.text = ""
            return
        }
        val label = fixTimeLabel()
        val time = formatFixTime(lastFixTime)
        val styled = SpannableStringBuilder(label + time)
        styled.setSpan(
            StyleSpan(Typeface.BOLD),
            label.length,
            styled.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvFixTime.text = styled
        fitFixTimeText()
    }

    /** 定位标签：带来源前缀，如「高德定位: 」「系统定位: 」；无来源时退回「定位: 」
     *  冒号统一用英文半角 + 一个空格，和运营商、基站那两行保持同一种标点风格。 */
    private fun fixTimeLabel(): String = "${addressSource}定位: "

    /** 最后定位时间的统一格式，地址下方与分享文字共用，保证两处完全一致 */
    private fun formatFixTime(timeMillis: Long): String = fixTimeFormat.format(Date(timeMillis))

    /** 记录地址来源（高德/腾讯/系统）：只更新内存并刷新时间显示，不落盘（重启后不复用旧值） */
    private fun recordAddressSource(source: String) {
        if (source.isEmpty()) return
        addressSource = source
        updateFixTimeText()
    }

    /**
     * 显示位置。
     * @param fromCache true 表示这是系统缓存的上次位置，只用于让界面先有内容，
     *                  不能算「定位成功」，否则老人会误以为这就是此刻的位置
     */
    private fun showLocation(lat: Double, lng: Double, fromCache: Boolean = false) {
        AppLogger.log("LOC", "收到坐标 lat=$lat lng=$lng fromCache=$fromCache online=${isOnline()}")
        currentLat = lat
        currentLng = lng
        hasFix = true

        tvLat.text = String.format(Locale.CHINA, "纬度: %.6f", lat)
        tvLng.text = String.format(Locale.CHINA, "经度: %.6f", lng)
        tvStatus.removeCallbacks(noFixChecker)

        if (fromCache && !hasRealFix) {
            // 这个坐标来自系统定位缓存（任何 App 定位都会更新它），不是本 App 的历史，
            // 首次安装、清空数据后同样会有，所以措辞不说「上次」，避免误以为没清干净
            setStatus("○ 正在定位，先显示最近位置…", R.color.status_cache)
            // 缓存位置不算定位成功：重新开始计时，超时后要能给出提示
            // （前面刚 removeCallbacks 过，不补这一句超时提示就永远不会出现）
            tvStatus.postDelayed(noFixChecker, 12000L)
        } else {
            hasRealFix = true
            // 记录这次真实定位的时刻，地址下方与分享文字共用它（缓存位置不算真实定位）
            recordFixTime()
            // 坐标已拿到，但地址还要联网解析。先如实说明在取地址，避免
            // 「定位成功」和「正在获取地址…」同时出现造成矛盾；地址回来后
            // 由 startGeocode 改回「✔ 定位成功」（取地址失败也会改回，不留半成品状态）。
            setStatus("○ 已定位，正在获取地址…", R.color.status_ok)
        }

        updateCellInfo()

        // 逆地理去重：GPS 与网络定位会在毫秒内各回调一次、坐标几乎相同；
        // 若本次与上次成功解析的坐标相差不足 10 米且间隔不足 3 秒，就复用上次结果，
        // 不再重复请求高德/腾讯，省额度也省流量。缓存定位(fromCache)同样参与判断。
        if (shouldSkipGeocode(lat, lng)) {
            AppLogger.log("GEO", "跳过重复解析：距上次不足10米且不到3秒，复用上次地址")
            if (hasRealFix) setStatus("✔ 定位成功", R.color.status_ok)
        } else {
            lastGeoLat = lat
            lastGeoLng = lng
            lastGeoAt = System.currentTimeMillis()
            startGeocode(lat, lng)
        }
    }

    /**
     * 是否需要跳过本次逆地理解析。
     * 条件：上次解析距今不足 3 秒，且新旧坐标距离不足 10 米。
     * 首次解析（lastGeoAt==0）从不跳过。
     */
    private fun shouldSkipGeocode(lat: Double, lng: Double): Boolean {
        if (lastGeoAt == 0L) return false
        if (System.currentTimeMillis() - lastGeoAt >= 3000L) return false
        // 经纬度近似换算成米：纬度 1°≈111320m，经度按当前纬度做余弦修正
        val dLat = (lat - lastGeoLat) * 111320.0
        val dLng = (lng - lastGeoLng) * 111320.0 * Math.cos(Math.toRadians(lat))
        return dLat * dLat + dLng * dLng < 10.0 * 10.0
    }

    /**
     * 反地理编码：后台线程执行，避免阻塞界面。
     * 顺序：高德 → 腾讯 → 系统 Geocoder → 提示无法获取。
     */
    private fun startGeocode(lat: Double, lng: Double) {
        // 地址栏只显示「本次定位实时解析出来的地址」，不再用任何历史地址兜底：
        // 旧地址对应的坐标可能已经和当前坐标不同，拿它冒充当前位置会误导。
        // 断网时联网解析必然超时（高德、腾讯各等 6 秒），只试系统 Geocoder
        // （部分机型带离线地址库，失败也很快），拿不到就如实显示失败文案。
        if (!isOnline()) {
            val seqNoNet = ++geocodeSeq
            tvAddress.text = "正在获取地址…"
            geocodeExecutor.execute {
                val addr = systemGeocode(lat, lng)
                runOnUiThread {
                    if (seqNoNet == geocodeSeq) {
                        tvAddress.text = addr
                        if (isRealAddress(addr)) recordAddressSource("系统")
                        if (hasRealFix) setStatus("✔ 定位成功", R.color.status_ok)
                    }
                }
            }
            return
        }

        val seq = ++geocodeSeq
        tvAddress.text = "正在获取地址…"

        geocodeExecutor.execute {
            // 地址 + 地标名一次请求拿全（高德 extensions=all，失败再腾讯），
            // 超时 3s，最坏也只要几秒，不会再出现长时间卡住。
            val result = ReverseGeocoder.resolveFull(this, lat, lng)
            AppLogger.log("GEO", "解析结果 address=${result.address} placeName=${result.placeName} source=${result.source}")
            // 实际来源：高德/腾讯给出地址就用它；否则系统 Geocoder 兜底记为「系统」
            val effectiveSource = if (!result.address.isNullOrEmpty()) result.source else "系统"
            val addressText = result.address ?: systemGeocode(lat, lng)

            runOnUiThread {
                // 只显示最新一次请求的结果，避免地址错位；解析失败就如实显示失败文案，
                // 绝不回退到历史地址。
                if (seq == geocodeSeq) {
                    if (isRealAddress(addressText)) recordAddressSource(effectiveSource)
                    tvAddress.text = addressText
                    if (!result.placeName.isNullOrEmpty()) {
                        // 地标名只留本次会话，用于当前这一次分享链接的 name；不落盘、重启即弃。
                        currentPlaceName = result.placeName
                    }
                    // 地址这一环结束了（成功或失败），状态收尾为定位成功
                    if (hasRealFix) setStatus("✔ 定位成功", R.color.status_ok)
                }
            }
        }
    }

    /** 是否真正解析出地址：过滤掉「获取中」「正在获取」「暂无法获取」这类占位与失败文案 */
    private fun isRealAddress(s: String?): Boolean {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return false
        return !t.contains("获取中") && !t.contains("正在获取") && !t.contains("暂无法获取")
    }

    /** 系统自带 Geocoder（免费兜底，无需 Key） */
    private fun systemGeocode(lat: Double, lng: Double): String = try {
        val geocoder = Geocoder(this, Locale.CHINA)
        @Suppress("DEPRECATION")
        val list = geocoder.getFromLocation(lat, lng, 1)
        val addr = list?.firstOrNull()
        if (addr == null) {
            "暂无法获取地址（经纬度已显示）"
        } else {
            val parts = mutableListOf<String>()
            addr.adminArea?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            addr.locality?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            addr.subLocality?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            addr.thoroughfare?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            addr.subThoroughfare?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            if (parts.isEmpty()) {
                addr.getAddressLine(0)?.takeIf { it.isNotBlank() }
                    ?: "暂无法获取地址（经纬度已显示）"
            } else {
                parts.joinToString("")
            }
        }
    } catch (e: Exception) {
        "暂无法获取地址（经纬度已显示）"
    }

    /** 读取并显示当前网络信息（运营商 / 制式 / 小区标识） */
    private fun updateCellInfo() {
        val snapshot = CellInfoReader.read(this)
        if (snapshot == null) {
            AppLogger.log("CELL", "读不到基站信息")
            tvCell.text = "当前未连接 4G / 5G 网络\n暂时读不到基站信息"
            lastCell = null
            fitCellText()
            return
        }
        AppLogger.log("CELL", "运营商=${snapshot.operator}${snapshot.rat} 基站=${snapshot.cidWithBreakdown()}")
        lastCell = snapshot
        tvCell.text = buildString {
            append("运营商: ${snapshot.operator}${snapshot.rat}")
            append("\n基站: ${snapshot.cidWithBreakdown()}")
        }
        fitCellText()
    }

    /**
     * 让一行文字在任何屏幕宽度、任何系统字体倍率下都保持单行：
     * 按父容器的实际可用宽度量一次最长的那一行，放不下就按像素把字号压到刚好放下。
     * 用像素（COMPLEX_UNIT_PX）而不是 sp 设置，是因为系统字体放大后 sp 会跟着再乘一次倍率，
     * 光靠「从 20sp 往下降」永远追不上（之前基站那行就是这么折成两行的）。
     */
    private fun fitOneLine(tv: TextView, baseSp: Float, retry: Int = 0) {
        tv.post {
            // 宽度基准取父容器的内容宽：tv 自身多是 wrap_content，
            // 用它自己的宽度会越量越窄、把字越缩越小。
            val parent = tv.parent as? android.view.View
            val avail = if (parent != null) {
                parent.width - parent.paddingStart - parent.paddingEnd
            } else {
                tv.width - tv.paddingStart - tv.paddingEnd
            }
            val text = tv.text?.toString().orEmpty()
            // 首次调用可能还没完成布局（宽度为 0），隔 150ms 重试，最多 3 次
            if (avail <= 0 || text.isEmpty()) {
                if (retry < 3) tv.postDelayed({ fitOneLine(tv, baseSp, retry + 1) }, 150L)
                return@post
            }
            val unit = resources.displayMetrics.scaledDensity
            val basePx = baseSp * unit
            val paint = TextPaint(tv.paint)
            var px = basePx
            // 每次都从基准字号开始量，避免上一次缩过之后越缩越小
            while (px > basePx * 0.5f) {
                paint.textSize = px
                // 粗体、字体差异会让实际排版比测量略宽，留 2% 余量
                val widest = (text.split('\n').maxOfOrNull { paint.measureText(it) } ?: 0f) * 1.02f
                if (widest <= avail) break
                px -= 0.5f
            }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        }
    }

    /** 定位时间行：系统字体调大后原本会折成两行，这里保持单行、字号自适应 */
    private fun fitFixTimeText() {
        if (!::tvFixTime.isInitialized) return
        fitOneLine(tvFixTime, 20f)
    }

    /** 运营商 / 基站两行：各自保持一行，内容最长的基站行决定字号 */
    private fun fitCellText() {
        if (!::tvCell.isInitialized) return
        fitOneLine(tvCell, 20f)
    }

    /** 分享/复制共用的位置文本；includeName 决定地图链接是否带 name（微信带，短信不带） */
    private fun locationText(includeName: Boolean = true): String {
        val lat = String.format(Locale.CHINA, "%.6f", currentLat)
        val lng = String.format(Locale.CHINA, "%.6f", currentLng)
        // 时间取「最后定位时间」，与地址下方显示完全一致；极端情况（尚未定位成功）用当前时刻
        val timeSource = if (lastFixTime > 0) lastFixTime else System.currentTimeMillis()
        // 与界面「定位时间」行共用同一标签（含高德/腾讯/系统来源前缀），两处保持一致
        val timeLine = "${fixTimeLabel()}${formatFixTime(timeSource)}"
        // 直接发位置，不再加「我的位置：」前缀
        val base = "${tvAddress.text}\n纬度: $lat\n经度: $lng\n$timeLine"
        val cellText = lastCell?.let { "\n\n${it.detailText()}" } ?: ""
        // 末尾附一条高德地图链接：对方点开即可看到标注了当前位置的地图。
        // 微信带 name（网页显示实际地名）；短信不带 name（链接短，避免中文编码后占用多条短信）。
        val mapLine = "\n\n地图： ${mapLink(includeName)}"
        return "$base$cellText$mapLine\n（来自我在哪 App）"
    }

    /**
     * 高德地图单点标注链接（Web URI API，无需 key）。
     * includeName=true 且本次解析到地标名（小区/园区/POI）时才带 name，网页显示这个实际地名；
     * 本次没解析到地名就不带 name，网页显示高德默认名，不再用「我的位置」兜底。
     * includeName=false 时同样不带 name，链接更短。
     * coordinate=gaode 要求 GCJ-02，先把 GPS 的 WGS-84 转成 GCJ-02，避免图钉偏移。
     */
    private fun mapLink(includeName: Boolean): String {
        val (gcjLat, gcjLng) = GeoUtils.wgs84ToGcj02(currentLat, currentLng)
        val pos = String.format(Locale.CHINA, "%.6f,%.6f", gcjLng, gcjLat)
        val namePart = if (includeName && currentPlaceName.isNotBlank()) {
            "&name=" + android.net.Uri.encode(currentPlaceName)
        } else ""
        return "https://uri.amap.com/marker?position=$pos$namePart&src=wozaina&coordinate=gaode&callnative=1"
    }

    /** 点"分享位置"先弹窗二选一：发微信 / 发短信（自定义大字布局 + 真实 App 图标） */
    private fun showShareChooser() {
        if (!hasFix) {
            bigToast("还没有定位成功，请稍后再试")
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_share_chooser, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .create()

        // 图标取本机真实 App 图标：短信=系统默认短信 App（小米最新样式），微信=微信，导航=高德地图
        view.findViewById<android.widget.ImageView>(R.id.iconSms).setImageDrawable(smsAppIcon())
        view.findViewById<android.widget.ImageView>(R.id.iconWechat).setImageDrawable(wechatAppIcon())
        amapAppIcon()?.let { view.findViewById<android.widget.ImageView>(R.id.iconNavigate).setImageDrawable(it) }

        view.findViewById<android.view.View>(R.id.rowWechat).setOnClickListener {
            dialog.dismiss()
            shareToWechat()
        }
        view.findViewById<android.view.View>(R.id.rowSms).setOnClickListener {
            dialog.dismiss()
            shareToSms()
        }
        view.findViewById<android.view.View>(R.id.rowNavigate).setOnClickListener {
            dialog.dismiss()
            showNavigatePicker()
        }
        view.findViewById<android.view.View>(R.id.btnCancelShare).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /** 系统默认短信 App 的图标 */
    private fun smsAppIcon(): Drawable? = try {
        val sendTo = Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("smsto:") }
        val info = packageManager.resolveActivity(sendTo, PackageManager.MATCH_DEFAULT_ONLY)
        val pkg = info?.activityInfo?.packageName
        if (pkg != null) packageManager.getApplicationIcon(pkg) else null
    } catch (_: Exception) {
        null
    }

    /** 微信图标 */
    private fun wechatAppIcon(): Drawable? = try {
        packageManager.getApplicationIcon("com.tencent.mm")
    } catch (_: Exception) {
        null
    }

    /** 高德地图图标（未安装时保留布局里的系统地图图标兜底） */
    private fun amapAppIcon(): Drawable? = try {
        packageManager.getApplicationIcon("com.autonavi.minimap")
    } catch (_: Exception) {
        null
    }

    /**
     * 分享到微信：先自动复制到剪贴板，再跳转微信。
     * 进入微信聊天框长按粘贴即可发送；未安装微信时退回系统分享面板。
     */
    private fun shareToWechat() {
        if (!hasFix) {
            bigToast("还没有定位成功，请稍后再试")
            return
        }
        val text = locationText()

        // 第一步：自动复制位置信息
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("位置", text))
        bigToast("位置已复制，正在打开微信…")

        // 第二步：直接打开微信"选朋友"界面（跳过系统/微信的中间选择面板）
        // ShareImgUI 是微信接收文字分享、进入选联系人页的入口，长期使用且稳定。
        fun openWechat() {
            val direct = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_TITLE, "我的实时位置")
                component = ComponentName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI")
            }
            try {
                startActivity(direct)
                return
            } catch (_: Exception) {
                // 微信版本变化导致直接入口失效时，退回通用分享（仍会弹选择面板，但不至于用不了）
            }

            try {
                val fallback = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    setPackage("com.tencent.mm")
                }
                startActivity(fallback)
            } catch (e: ActivityNotFoundException) {
                bigToast("未找到微信，位置已复制，可粘贴发送")
            }
        }

        // 复制完立即唤起微信，不做停留。提示能看到就算，看不到也没关系（切后台后
        // 自定义提示可能被系统拦掉，这是已知限制，优先保证跳转利落）。
        openWechat()
    }

    /**
     * 发短信：点击即发，不再跳转系统短信 App 二次确认。
     * 默认用当前上网卡（默认数据卡的 subscriptionId）发送；无 SEND_SMS 权限时先申请。
     */
    private fun shareToSms() {
        if (!hasFix) {
            bigToast("还没有定位成功，请稍后再试")
            return
        }
        // 短信不带 name：中文经 URL 编码后每个字占 9 字符，去掉后链接更短、少占短信条数
        pendingSmsText = locationText(includeName = false)
        if (!hasSmsPermission()) {
            requestSmsPermissionOrGuide()
            return
        }
        sendSmsNow()
    }

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 没有短信权限时的处理：
     * - 从没申请过，或拒绝过但系统还允许再问 → 弹系统权限框；
     * - 已被「拒绝且不再询问」永久拒绝 → 系统框不会再弹，只能大字引导去系统设置里打开。
     */
    private fun requestSmsPermissionOrGuide() {
        val asked = SettingsStore.hasAskedSmsPermission(this)
        val canAskAgain = !asked ||
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.SEND_SMS)
        if (canAskAgain) {
            SettingsStore.setAskedSmsPermission(this, true)
            AppLogger.log("SMS", "申请 SEND_SMS 权限（已申请过=$asked）")
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.SEND_SMS), 300
            )
            return
        }
        AppLogger.log("SMS", "SEND_SMS 已被永久拒绝，引导去系统设置")
        showSmsPermissionGuide()
    }

    /** 大字引导：短信权限被永久拒绝后，带老人去系统设置里打开 */
    private fun showSmsPermissionGuide() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(bigSpan("需要短信权限", 24))
            .setMessage(
                bigSpan(
                    "发位置短信需要「短信」权限。\n\n" +
                        "请点「去设置」，在权限里把「短信」打开，以后就能一键发送。\n" +
                        "也可以点「直接发短信」，在短信里自己点发送。",
                    20
                )
            )
            .setPositiveButton(bigSpan("去设置", 22)) { _, _ -> openAppPermissionSettings() }
            .setNegativeButton(bigSpan("直接发短信", 22)) { _, _ -> shareToSmsViaApp() }
            .show()
    }

    /** 跳到本应用的系统设置页，老人可在「权限」里手动打开短信 */
    private fun openAppPermissionSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
            AppLogger.log("SMS", "跳转应用设置页 打开短信权限")
        } catch (e: Exception) {
            AppLogger.log("SMS", "打开应用设置失败 ${e.javaClass.simpleName}: ${e.message}")
            bigToast("请手动到 设置 → 应用 → 我在哪 → 权限 里打开短信")
        }
    }

    /**
     * 真正发送短信：收件人取默认号码（设置里存的），没设则弹输入框让用户填本次号码；
     * 卡默认走上网卡，拿不到 subId 时回退到系统默认短信卡。
     */
    private fun sendSmsNow() {
        val text = pendingSmsText ?: locationText(includeName = false)
        val number = SettingsStore.getSmsTo(this)
            .replace(Regex("[\\s-]"), "")
        if (number.isBlank()) {
            // 没设默认号码：弹输入框；用户可勾选「存为默认」，下次即一键发送不再弹窗
            promptSmsNumber { num, name, saveAsDefault ->
                if (saveAsDefault) {
                    SettingsStore.setSmsTo(this, num)
                    SettingsStore.setSmsName(this, name)
                }
                if (num.isNotBlank()) doSendSms(num, name, text)
            }
            return
        }
        // 已设默认号码：直接发送，不弹收件人输入框
        doSendSms(number, SettingsStore.getSmsName(this), text)
    }

    /** 生成放大后的文字，供 AlertDialog 的标题/正文/按钮做老人大字适配（跟随系统字号） */
    private fun bigSpan(text: String, sp: Int): android.text.SpannableString {
        val ss = android.text.SpannableString(text)
        val px = (sp * resources.displayMetrics.scaledDensity).toInt()
        ss.setSpan(
            android.text.style.AbsoluteSizeSpan(px),
            0, text.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return ss
    }

    /** 大字 + 加粗的 span，用于弹窗标题 */
    private fun bigBoldSpan(text: String, sp: Int): android.text.SpannableString {
        val ss = bigSpan(text, sp)
        ss.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            0, text.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return ss
    }

    /** 大字提示：系统 Toast 的字号改不了，这里用自定义视图放大，方便老人看清 */
    private fun bigToast(message: String) {
        val pad = (22 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = message
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(pad, pad / 2, pad, pad / 2)
            // 长句子限制宽度，避免超出屏幕被裁掉；超出后自动换行
            maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        }
        val container = LinearLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#D9000000"))
            addView(tv)
        }
        Toast(this).apply {
            duration = Toast.LENGTH_LONG
            view = container
        }.show()
    }

    /** 没设置默认号码时，输入本次收件人姓名与号码；可勾选存为默认 */
    private fun promptSmsNumber(onEntered: (String, String, Boolean) -> Unit) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val etName = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 22f
            hint = "收件人姓名（选填）"
        }
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            textSize = 22f
            hint = "收件人号码"
        }
        val cbSaveDefault = CheckBox(this).apply {
            text = "存为默认，下次一键发送"
            textSize = 22f
            isChecked = false // 默认不勾选，老人在本次特殊收件人时不会误存
            // 系统默认的勾选框只有 20dp 左右，配 22sp 文字显得又小又难认；
            // 换成自绘的 34dp 方框（未选：灰边白底；已选：蓝底白勾），并把和文字的间距拉到 10dp。
            setButtonDrawable(R.drawable.checkbox_big)
            compoundDrawablePadding = (10 * resources.displayMetrics.density).toInt()
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(bigBoldSpan("短信收件人", 26))
            .setMessage(bigSpan("请填写收件人信息。\n勾选后可存为默认，下次发短信不用再输入。", 22))
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(etName)
                addView(et)
                addView(cbSaveDefault)
            })
            .setPositiveButton(bigSpan("发送", 22)) { _, _ ->
                val name = etName.text.toString().trim()
                val num = et.text.toString().trim().replace(Regex("[\\s-]"), "")
                if (!num.matches(Regex("\\+?\\d{5,20}"))) {
                    bigToast("号码格式不对，请重新输入")
                    return@setPositiveButton
                }
                onEntered(num, name, cbSaveDefault.isChecked)
            }
            .setNegativeButton(bigSpan("取消", 22), null)
            .show()
    }

    /** 收件人的显示文字：有姓名显示「姓名 号码」，没姓名只显示号码 */
    private fun smsTargetLabel(): String =
        if (smsTargetName.isBlank()) smsTargetNumber else "$smsTargetName $smsTargetNumber"

    /** 用上网卡实际发送短信（长文本自动分段），成功/失败由 smsSentReceiver 回执提示 */
    @SuppressLint("MissingPermission") // SEND_SMS 与 READ_PHONE_STATE 已在调用前检查/申请
    private fun doSendSms(number: String, name: String, text: String) {
        smsTargetNumber = number
        smsTargetName = name.trim()
        val smsManager = smsManagerForDataCard()
        val parts = smsManager.divideMessage(text)
        smsExpectedSegments = parts.size
        smsOkSegments = 0
        // 每段都带一个回执；用请求码区分不同分段，避免 PendingIntent 被复用覆盖
        val baseCode = (System.currentTimeMillis() and 0x7fffffffL).toInt()
        val sentIntents = ArrayList<PendingIntent>()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        for (i in parts.indices) {
            val pi = PendingIntent.getBroadcast(
                this, baseCode + i,
                Intent(smsSentAction).setPackage(packageName),
                flags
            )
            sentIntents.add(pi)
        }
        try {
            if (parts.size == 1) {
                smsManager.sendTextMessage(number, null, parts[0], sentIntents[0], null)
            } else {
                smsManager.sendMultipartTextMessage(number, null, ArrayList(parts), sentIntents, null)
            }
            AppLogger.log("SMS", "提交发送 姓名=${Mask.name(name)} 号码=${Mask.phone(number)} 分段=${parts.size} 卡=${smsCardDesc()}")
            bigToast("正在发送位置短信给 ${smsTargetLabel()}…")
        } catch (e: Exception) {
            AppLogger.log("SMS", "发送异常 ${e.javaClass.simpleName}: ${e.message}")
            bigToast("发送失败：${e.message}")
        } finally {
            pendingSmsText = null
        }
    }

    /** 取当前上网卡（默认数据卡）的 SmsManager；拿不到就回退系统默认短信卡 */
    private fun smsManagerForDataCard(): SmsManager = try {
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID ||
            subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID
        ) {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subId)
        }
    } catch (e: Exception) {
        AppLogger.log("SMS", "取上网卡失败，回退默认卡 ${e.message}")
        @Suppress("DEPRECATION")
        SmsManager.getDefault()
    }

    /** 日志里用的上网卡描述 */
    private fun smsCardDesc(): String = try {
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID ||
            subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID
        ) "默认卡" else "上网卡(subId=$subId)"
    } catch (_: Exception) {
        "默认卡"
    }

    /** 系统文件选择器返回：requestCode 400 = 选备份文件 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 400) return
        if (resultCode != Activity.RESULT_OK) {
            AppLogger.log("BACKUP", "导入被取消")
            return
        }
        val uri = data?.data
        if (uri == null) {
            bigToast("没有选到文件")
            return
        }
        onBackupFilePicked(uri)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        AppLogger.log("PERM", "requestCode=$requestCode results=${grantResults.joinToString()}")
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                AppLogger.log("PERM", "已获定位权限")
                startSystemLocation()
            } else {
                SettingsStore.setAskedLocPermission(this, true)
                val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                )
                AppLogger.log("PERM", "定位权限被拒绝 可再询问=$canAskAgain")
                if (canAskAgain) {
                    // 还能再问：提示一下，下次点「重新定位」会再弹系统框
                    setStatus("未获得定位权限，请点击“重新定位”再试")
                    bigToast("未获得定位权限，请点「重新定位」再允许一次")
                } else {
                    // 勾了「不再询问」：系统框以后不会再弹，只能带老人去设置
                    showLocPermissionGuide()
                }
            }
        } else if (requestCode == 101) {
            // 拿到 READ_PHONE_STATE 后立刻重读一次基站信息（不影响定位）
            updateCellInfo()
        } else if (requestCode == 200) {
            // Android 9 及以下：拿到存储权限后重试导出日志
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                exportLogToDownloads()
            } else {
                bigToast("未获得存储权限，无法导出到下载目录")
            }
        } else if (requestCode == 201) {
            // Android 9 及以下：拿到存储权限后重试导出备份
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                exportBackup()
            } else {
                bigToast("未获得存储权限，无法导出备份到下载目录")
            }
        } else if (requestCode == 300) {
            // 发短信权限
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                AppLogger.log("SMS", "已获 SEND_SMS 权限")
                sendSmsNow()
            } else {
                SettingsStore.setAskedSmsPermission(this, true)
                // 还能再问 vs 已被「拒绝且不再询问」永久拒绝，提示不同
                val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.SEND_SMS
                )
                AppLogger.log("SMS", "SEND_SMS 被拒绝 可再询问=$canAskAgain，降级为跳转短信 App")
                if (canAskAgain) {
                    bigToast("未获得短信权限，已打开短信，请手动点发送")
                } else {
                    bigToast(
                        "短信权限已被拒绝，已打开短信，请手动点发送。\n" +
                            "想一键发送：设置 → 应用 → 我在哪 → 权限 → 短信"
                    )
                }
                shareToSmsViaApp()
            }
        }
    }

    /** 降级路径：跳转系统短信 App，收件人和内容预填（仅在无 SEND_SMS 权限时用） */
    private fun shareToSmsViaApp() {
        val text = pendingSmsText ?: locationText(includeName = false)
        val number = SettingsStore.getSmsTo(this).replace(Regex("[\\s-]"), "")
        pendingSmsText = null
        val toUri = Uri.parse(if (number.isBlank()) "smsto:" else "smsto:$number")
        val viewUri = Uri.parse(if (number.isBlank()) "sms:" else "sms:$number")
        // 不同 ROM 的短信 App 认的字段不一样：sms_body 最常见，EXTRA_TEXT 兜住部分机型
        val candidates = listOf(
            Intent(Intent.ACTION_SENDTO, toUri).apply {
                putExtra("sms_body", text)
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra("exit_on_sent", true)
            },
            Intent(Intent.ACTION_VIEW, viewUri).apply {
                putExtra("sms_body", text)
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra("exit_on_sent", true)
            }
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                AppLogger.log("SMS", "降级跳转短信 App action=${intent.action} 预填号码=${Mask.phone(number)}")
                return
            } catch (_: ActivityNotFoundException) {
                // 换下一种方式再试
            } catch (e: Exception) {
                AppLogger.log("SMS", "跳转短信 App 异常 ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        AppLogger.log("SMS", "未找到可用的短信 App")
        bigToast("未找到短信应用，请检查系统是否安装短信 App")
    }

    override fun onResume() {
        super.onResume()
        // 回到前台恢复屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // App 回到前台：已有权限时恢复定位监听（首次进入由 onCreate 的 requestLocation 负责）
        if (::tvStatus.isInitialized) {
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (fine == PackageManager.PERMISSION_GRANTED && coarse == PackageManager.PERMISSION_GRANTED) {
                // 回到前台时，界面上的位置可能已经过期：先标记为定位中，
                // 等实时结果回来再显示“定位成功”，避免把旧坐标当成本刻位置。
                // 仅在「之前确实实时定位过」时重置，否则首次启动会把“上次位置”的提示冲掉
                if (hasFix && hasRealFix) {
                    hasRealFix = false
                    setStatus("正在定位…请稍等几秒")
                }
                startSystemLocation()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // App 切到后台：清除屏幕常亮，恢复系统自动熄屏
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 注销定位监听，停止耗电；同时停止朗读
        stopSystemLocation()
        if (isSpeaking) {
            tts?.stop()
            resetSpeakButton()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSystemLocation()
        if (smsReceiverRegistered) {
            try { unregisterReceiver(smsSentReceiver) } catch (_: Exception) { }
            smsReceiverRegistered = false
        }
        btnSpeak.removeCallbacks(speakTimeout)
        if (isSpeaking) tts?.stop()
        tts?.shutdown()
        geocodeExecutor.shutdownNow()
        poiExecutor.shutdownNow()
    }
}
