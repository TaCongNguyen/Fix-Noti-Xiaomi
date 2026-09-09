package com.nguyenomg.fixnoti.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.nguyenomg.fixnoti.model.AppDetailStatus
import com.nguyenomg.fixnoti.model.AppInfo
import com.nguyenomg.fixnoti.model.FixLog
import com.nguyenomg.fixnoti.model.OpStatus
import com.nguyenomg.fixnoti.shizuku.BatchResult
import com.nguyenomg.fixnoti.shizuku.ShizukuShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ảnh chụp các bảng hệ thống dùng chung cho mọi app.
 *
 * Trước đây mỗi app đều chạy lại `dumpsys deviceidle whitelist` và 3 lệnh `settings get`,
 * tức là fix 200 app sẽ đọc lại các bảng này 800 lần. Đọc một lần rồi tra trong bộ nhớ.
 * Giá trị `null` nghĩa là ROM này không có key đó, không phải là danh sách rỗng.
 */
data class SystemSnapshot(
    val idleWhitelist: Set<String> = emptySet(),
    val milletWhite: SettingTable? = null,
    val cloudLowLatency: SettingTable? = null,
    val milletNoRestrict: SettingTable? = null
)

/**
 * Một bảng danh sách trắng của MIUI.
 *
 * [separator] được giữ đúng như ROM đang dùng: trên HyperOS 3, `MILLET_NO_RESTRICT_APP`
 * ngăn cách bằng ", " còn `cloud_lowlatency_whitelist` bằng ",". Ghi sai dấu phân cách
 * có nguy cơ làm hệ thống đọc hỏng cả danh sách.
 */
data class SettingTable(
    val values: Set<String>,
    val separator: String
) {
    operator fun contains(packageName: String) = values.contains(packageName)
}

class AppRepository {

    companion object {
        private const val JSDELIVR_RAW_URL = "https://cdn.jsdelivr.net/gh/optimus0701/Fix-Noti-Xiaomi@master/user_apps.txt"
        private const val GITHUB_RAW_URL = "https://raw.githubusercontent.com/optimus0701/Fix-Noti-Xiaomi/master/user_apps.txt"

        private const val GMS_PACKAGE = "com.google.android.gms"

        const val KEY_MILLET_WHITE = "millet_white"
        const val KEY_CLOUD_LOW_LATENCY = "cloud_lowlatency_whitelist"
        const val KEY_MILLET_NO_RESTRICT = "MILLET_NO_RESTRICT_APP"

        /** Tối thiểu hai đoạn ngăn bằng dấu chấm, chỉ chữ/số/gạch dưới. */
        private val PACKAGE_NAME_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        @Volatile
        private var cachedRecommendedPackages: Set<String>? = null

        private val DEFAULT_RECOMMENDED_PACKAGES = setOf(
            // Mạng xã hội & Nhắn tin
            "com.zing.zalo",
            "com.facebook.orca",
            "com.facebook.katana",
            "org.telegram.messenger",
            "com.whatsapp",
            "com.instagram.android",
            "com.instagram.barcelona",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.locket.Locket",
            "com.discord",
            "com.viber.voip",
            "jp.naver.line.android",
            "com.tencent.mm",
            "com.twitter.android",
            "com.skype.raider",
            // Ngân hàng & Ví điện tử
            "com.mservice.momotransfer",
            "com.mbmobile",
            "com.vietcombank.phone",
            "vn.com.techcombank.bb.app",
            "com.vpb.neo",
            "com.vnpay.bidv",
            "com.vietinbank.ipay",
            "com.vnpay.agribank3g",
            "com.acb.mobile",
            "com.tpb.mb.gprsauto",
            "com.sacombank.mbanking",
            "com.msb.mb",
            "com.vib.myvib2",
            "vn.cake.app",
            "vn.vnpay.vnpaywallet",
            "vn.com.vng.zalopay",
            "com.bplus.vtpay",
            "com.airpay.consumer"
        )
    }

    // ---------------------------------------------------------------- Danh sách app

    suspend fun fetchRecommendedPackageNames(): Set<String> = withContext(Dispatchers.IO) {
        cachedRecommendedPackages?.let { return@withContext it }

        for (urlString in listOf(JSDELIVR_RAW_URL, GITHUB_RAW_URL)) {
            try {
                val result = withTimeoutOrNull(2500L) {
                    val connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 2000
                    connection.readTimeout = 2000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "FixNotiXiaomi/1.0")

                    if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                        val text = connection.inputStream.bufferedReader().use { it.readText() }
                        text.lines()
                            .map { it.trim().removePrefix("package:").trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .toSet()
                            .takeIf { it.isNotEmpty() }
                    } else null
                }
                if (result != null) {
                    cachedRecommendedPackages = result
                    return@withContext result
                }
            } catch (e: Exception) {
                // Mất mạng thì thử URL kế tiếp, cuối cùng dùng danh sách mặc định
            }
        }

        cachedRecommendedPackages = DEFAULT_RECOMMENDED_PACKAGES
        DEFAULT_RECOMMENDED_PACKAGES
    }

    /**
     * Lấy danh sách các ứng dụng NGƯỜI DÙNG TỰ CÀI (không gồm app hệ thống).
     *
     * MIUI thường chặn `PackageManager.getInstalledPackages`, nên nguồn chuẩn là
     * `pm list packages -3` chạy qua Shizuku. PackageManager chỉ dùng để bổ sung
     * (khi chưa cấp quyền Shizuku) và để lấy tên hiển thị + icon.
     */
    suspend fun getInstalledApps(context: Context, showAll: Boolean = false): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val userPackages = mutableSetOf<String>()

        // 1. Nguồn chuẩn: pm list packages -3 (chỉ app bên thứ ba) qua Shizuku.
        //    Đây là cách duy nhất thấy được app mà MIUI giấu khỏi PackageManager.
        if (ShizukuShellExecutor.isPermissionGranted()) {
            val shellOutput = ShizukuShellExecutor.executeCommand("pm list packages -3 --user 0")
            shellOutput.lines()
                .map { it.trim().removePrefix("package:").trim() }
                .filter { it.isNotEmpty() && !it.contains(' ') }
                .forEach { userPackages.add(it) }
        }

        // 2. Bổ sung từ PackageManager, phòng khi chưa có Shizuku hoặc lệnh trên thất bại.
        try {
            for (pkg in pm.getInstalledPackages(0)) {
                val appInfo = pkg.applicationInfo ?: continue
                if (isUserInstalled(appInfo)) userPackages.add(pkg.packageName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val recommendedPackages = fetchRecommendedPackageNames()

        // Ở chế độ Đề xuất, chỉ giữ các app đề xuất mà máy THỰC SỰ đã cài.
        // GMS là app hệ thống nhưng luôn hiển thị vì nó là nguồn đẩy thông báo FCM.
        val targetPackages = if (showAll) {
            userPackages + GMS_PACKAGE
        } else {
            userPackages.intersect(recommendedPackages) + GMS_PACKAGE
        }

        val apps = targetPackages.mapNotNull { packageName ->
            val isGms = packageName == GMS_PACKAGE

            // GMS phải tồn tại thật mới hiện; app còn lại đã được xác nhận ở bước trên.
            val appInfo = try {
                pm.getApplicationInfo(packageName, 0)
            } catch (e: Exception) {
                null
            }
            if (isGms && appInfo == null) return@mapNotNull null

            val label = appInfo?.let {
                try {
                    pm.getApplicationLabel(it).toString()
                } catch (e: Exception) {
                    packageName
                }
            } ?: packageName

            val icon = appInfo?.let {
                try {
                    pm.getApplicationIcon(it)
                } catch (e: Exception) {
                    null
                }
            }

            AppInfo(
                appName = if (isGms) "$label (Google Play Services)" else label,
                packageName = packageName,
                icon = icon,
                isGoogleGms = isGms,
                // MIUI giấu package này khỏi PackageManager: hiện được nhờ Shizuku,
                // nhưng không lấy được tên và icon.
                isHiddenByMiui = appInfo == null
            )
        }

        apps.sortedWith(compareByDescending<AppInfo> { it.isGoogleGms }.thenBy { it.appName.lowercase() })
    }

    private fun isUserInstalled(appInfo: ApplicationInfo): Boolean =
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    // ---------------------------------------------------------------- Kiểm tra trạng thái

    /** Đọc một lần các bảng hệ thống dùng chung, gộp 4 lệnh vào một process shell. */
    suspend fun loadSystemSnapshot(): SystemSnapshot = withContext(Dispatchers.IO) {
        // Dùng runBatch (có exit code) chứ KHÔNG dùng runBatchOutputs: khi lệnh thất bại,
        // runBatchOutputs trả về chính thông báo lỗi trong ô output. Bản trước tin vào đó,
        // parse chuỗi lỗi thành danh sách package rồi ghi ngược vào bảng hệ thống —
        // làm hỏng millet_white và MILLET_NO_RESTRICT_APP trên máy thật.
        val results = ShizukuShellExecutor.runBatch(
            listOf(
                "dumpsys deviceidle whitelist",
                "settings get system $KEY_MILLET_WHITE",
                "settings get system $KEY_CLOUD_LOW_LATENCY",
                "settings get system $KEY_MILLET_NO_RESTRICT"
            )
        )

        SystemSnapshot(
            idleWhitelist = if (results[0].isSuccess) parseIdleWhitelist(results[0].output) else emptySet(),
            milletWhite = parseSettingTable(KEY_MILLET_WHITE, results[1]),
            cloudLowLatency = parseSettingTable(KEY_CLOUD_LOW_LATENCY, results[2]),
            milletNoRestrict = parseSettingTable(KEY_MILLET_NO_RESTRICT, results[3])
        )
    }

    suspend fun checkAppDetailStatus(packageName: String): AppDetailStatus =
        checkAppDetailStatus(packageName, loadSystemSnapshot())

    /** Kiểm tra một app dựa trên snapshot có sẵn: chỉ tốn 1 process shell cho 5 lệnh. */
    suspend fun checkAppDetailStatus(
        packageName: String,
        snapshot: SystemSnapshot
    ): AppDetailStatus = withContext(Dispatchers.IO) {
        val results = ShizukuShellExecutor.runBatch(
            listOf(
                "am get-standby-bucket $packageName",
                "cmd appops get $packageName RUN_IN_BACKGROUND",
                "cmd appops get $packageName RUN_ANY_IN_BACKGROUND",
                "cmd appops get $packageName 10008",
                "cmd appops get $packageName AUTO_REVOKE_PERMISSIONS_IF_UNUSED"
            )
        )

        // Lệnh hỏng thì báo UNKNOWN, không được để thông báo lỗi lọt ra giao diện.
        fun opAt(index: Int): OpStatus =
            if (results[index].isSuccess) parseOpStatus(results[index].output) else OpStatus.UNKNOWN

        AppDetailStatus(
            isWhitelisted = snapshot.idleWhitelist.contains(packageName),
            standbyBucket = if (results[0].isSuccess) parseStandbyBucket(results[0].output) else "UNKNOWN",
            runInBackground = opAt(1),
            runAnyInBackground = opAt(2),
            autoStart = opAt(3),
            autoRevokePermissions = opAt(4),
            isMilletWhiteSupported = snapshot.milletWhite != null,
            isMilletWhite = snapshot.milletWhite?.contains(packageName) == true,
            isCloudLowLatencySupported = snapshot.cloudLowLatency != null,
            isCloudLowLatency = snapshot.cloudLowLatency?.contains(packageName) == true,
            isMilletNoRestrictSupported = snapshot.milletNoRestrict != null,
            isMilletNoRestrict = snapshot.milletNoRestrict?.contains(packageName) == true
        )
    }

    // ---------------------------------------------------------------- Tối ưu hoá

    suspend fun fixApp(app: AppInfo, onLog: suspend (FixLog) -> Unit): AppDetailStatus =
        fixApps(listOf(app), onLog).getValue(app.packageName)

    /**
     * Tối ưu hoá nhiều app cùng lúc.
     *
     * Các bảng hệ thống của MIUI (millet_white...) được ghi MỘT lần cho cả danh sách thay vì
     * đọc–sửa–ghi cho từng app. Ngoài việc nhanh hơn nhiều, cách này còn tránh nguy cơ chuỗi
     * bị cắt cụt khi thêm hàng trăm package từng cái một.
     */
    suspend fun fixApps(
        apps: List<AppInfo>,
        onLog: suspend (FixLog) -> Unit,
        onAppStart: suspend (AppInfo, Int) -> Unit = { _, _ -> }
    ): Map<String, AppDetailStatus> = withContext(Dispatchers.IO) {
        if (apps.isEmpty()) return@withContext emptyMap()

        val snapshot = loadSystemSnapshot()

        apps.forEachIndexed { index, app ->
            onAppStart(app, index)
            val name = app.appName
            val pkg = app.packageName

            onLog(FixLog(name, pkg, "Đang áp dụng 6 tối ưu hoá cấp hệ thống...", isSuccess = false))

            val commands = listOf(
                "Thêm vào DeviceIdle Whitelist" to "cmd deviceidle whitelist +$pkg",
                "Đặt Standby Bucket -> ACTIVE" to "am set-standby-bucket $pkg active",
                "Bật RUN_IN_BACKGROUND -> ALLOW" to "cmd appops set --user 0 $pkg RUN_IN_BACKGROUND allow",
                "Bật RUN_ANY_IN_BACKGROUND -> ALLOW" to "cmd appops set --user 0 $pkg RUN_ANY_IN_BACKGROUND allow",
                "Đặt Manage if unused -> IGNORE" to "cmd appops set --user 0 $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore",
                // AppOp 10008 là "Tự khởi động" của Xiaomi. Bản cũ chỉ đọc trạng thái rồi
                // mở Cài đặt cho người dùng tự bật; thực tế đặt thẳng bằng appops được.
                "Bật Tự khởi động (Autostart)" to "cmd appops set --user 0 $pkg 10008 allow"
            )

            val results = ShizukuShellExecutor.runBatch(commands.map { it.second })
            results.forEachIndexed { i, result ->
                val label = commands[i].first
                if (result.isSuccess) {
                    onLog(FixLog(name, pkg, "✓ $label", isSuccess = true))
                } else {
                    onLog(FixLog(name, pkg, "✗ $label — ${result.errorMessage}", isSuccess = false, isError = true))
                }
            }
        }

        // Ghi các bảng hệ thống MIUI một lần cho toàn bộ danh sách.
        val allPackages = apps.map { it.packageName }
        val scope = if (apps.size == 1) apps[0].appName else "${apps.size} ứng dụng"
        applySystemTable(KEY_MILLET_WHITE, snapshot.milletWhite, allPackages, add = true, scope = scope, onLog = onLog)
        applySystemTable(KEY_CLOUD_LOW_LATENCY, snapshot.cloudLowLatency, allPackages, add = true, scope = scope, onLog = onLog)
        applySystemTable(KEY_MILLET_NO_RESTRICT, snapshot.milletNoRestrict, allPackages, add = true, scope = scope, onLog = onLog)

        // Kiểm tra lại để xác nhận kết quả, dùng snapshot mới sau khi đã ghi.
        val verifySnapshot = loadSystemSnapshot()
        apps.associate { it.packageName to checkAppDetailStatus(it.packageName, verifySnapshot) }
    }

    suspend fun revokeAllPermissions(app: AppInfo, onLog: suspend (FixLog) -> Unit): AppDetailStatus =
        withContext(Dispatchers.IO) {
            val name = app.appName
            val pkg = app.packageName
            val snapshot = loadSystemSnapshot()

            val commands = listOf(
                "Loại khỏi DeviceIdle Whitelist" to "cmd deviceidle whitelist -$pkg",
                "Đặt Standby Bucket -> RARE" to "am set-standby-bucket $pkg rare",
                "Tắt RUN_IN_BACKGROUND -> IGNORE" to "cmd appops set --user 0 $pkg RUN_IN_BACKGROUND ignore",
                "Tắt RUN_ANY_IN_BACKGROUND -> IGNORE" to "cmd appops set --user 0 $pkg RUN_ANY_IN_BACKGROUND ignore",
                "Đặt lại Manage if unused -> ALLOW" to "cmd appops set --user 0 $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED allow"
            )

            val results = ShizukuShellExecutor.runBatch(commands.map { it.second })
            results.forEachIndexed { i, result ->
                val label = commands[i].first
                if (result.isSuccess) {
                    onLog(FixLog(name, pkg, "✓ $label", isSuccess = true))
                } else {
                    onLog(FixLog(name, pkg, "✗ $label — ${result.errorMessage}", isSuccess = false, isError = true))
                }
            }

            val pkgs = listOf(pkg)
            applySystemTable(KEY_MILLET_WHITE, snapshot.milletWhite, pkgs, add = false, scope = name, onLog = onLog)
            applySystemTable(KEY_CLOUD_LOW_LATENCY, snapshot.cloudLowLatency, pkgs, add = false, scope = name, onLog = onLog)
            applySystemTable(KEY_MILLET_NO_RESTRICT, snapshot.milletNoRestrict, pkgs, add = false, scope = name, onLog = onLog)

            onLog(FixLog(name, pkg, "Hoàn tất hủy bỏ cấu hình cho $name", isSuccess = true))
            checkAppDetailStatus(pkg, loadSystemSnapshot())
        }

    suspend fun revokeSinglePermission(
        app: AppInfo,
        permissionType: String,
        onLog: (suspend (FixLog) -> Unit)? = null
    ): AppDetailStatus = withContext(Dispatchers.IO) {
        val name = app.appName
        val pkg = app.packageName
        val logIt: suspend (String) -> Unit = { text -> onLog?.invoke(FixLog(name, pkg, text)) }

        when (permissionType) {
            "WHITELIST" -> {
                logIt("Đang loại bỏ khỏi DeviceIdle Whitelist...")
                ShizukuShellExecutor.run("cmd deviceidle whitelist -$pkg")
            }
            "STANDBY_BUCKET" -> {
                logIt("Đang đặt Standby Bucket -> RARE...")
                ShizukuShellExecutor.run("am set-standby-bucket $pkg rare")
            }
            "RUN_IN_BACKGROUND" -> {
                logIt("Đang đặt RUN_IN_BACKGROUND -> IGNORE...")
                ShizukuShellExecutor.run("cmd appops set --user 0 $pkg RUN_IN_BACKGROUND ignore")
            }
            "RUN_ANY_IN_BACKGROUND" -> {
                logIt("Đang đặt RUN_ANY_IN_BACKGROUND -> IGNORE...")
                ShizukuShellExecutor.run("cmd appops set --user 0 $pkg RUN_ANY_IN_BACKGROUND ignore")
            }
            "AUTO_REVOKE_IF_UNUSED" -> {
                logIt("Đang đặt Manage if unused -> ALLOW...")
                ShizukuShellExecutor.run("cmd appops set --user 0 $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED allow")
            }
            "MILLET_WHITE" -> removeFromTable(KEY_MILLET_WHITE, pkg)
            "CLOUD_LOWLATENCY" -> removeFromTable(KEY_CLOUD_LOW_LATENCY, pkg)
            "MILLET_NO_RESTRICT" -> removeFromTable(KEY_MILLET_NO_RESTRICT, pkg)
            "AUTO_START" -> openAppSettings(pkg)
        }

        checkAppDetailStatus(pkg)
    }

    // ---------------------------------------------------------------- Bảng hệ thống MIUI

    /**
     * Thêm hoặc xoá [packages] khỏi bảng hệ thống [key] bằng một lệnh `settings put` duy nhất.
     * [current] là giá trị đã đọc sẵn trong snapshot; `null` nghĩa là ROM không có key này
     * và ta không được tạo mới (tạo key lạ trên ROM không hỗ trợ là vô nghĩa và rủi ro).
     */
    private suspend fun applySystemTable(
        key: String,
        table: SettingTable?,
        packages: List<String>,
        add: Boolean,
        scope: String,
        onLog: suspend (FixLog) -> Unit
    ) {
        if (table == null) return

        val updated = if (add) table.values + packages else table.values - packages.toSet()
        if (updated == table.values) return

        ensureWriteSettingsPermission()
        val result = ShizukuShellExecutor.run(buildSettingsPut(key, updated, table.separator))
        val verb = if (add) "Thêm vào" else "Xoá khỏi"
        if (result.isSuccess) {
            onLog(FixLog(scope, key, "✓ $verb MIUI System: $key", isSuccess = true))
        } else {
            onLog(FixLog(scope, key, "✗ $verb $key thất bại — ${result.errorMessage}", isSuccess = false, isError = true))
        }
    }

    private fun removeFromTable(key: String, packageName: String) {
        val read = ShizukuShellExecutor.run("settings get system $key")
        val table = parseSettingTable(
            key,
            BatchResult(read.stdout, if (read.isSuccess) 0 else 1)
        ) ?: return

        if (!table.contains(packageName)) return
        ensureWriteSettingsPermission()
        ShizukuShellExecutor.run(buildSettingsPut(key, table.values - packageName, table.separator))
    }

    /**
     * `millet_white` dùng dấu chấm phẩy và có dấu chấm phẩy ở cuối; các key khác dùng dấu phẩy.
     * Giá trị rỗng phải ghi bằng `settings delete` vì `settings put ""` bị một số ROM từ chối.
     */
    private fun buildSettingsPut(key: String, values: Set<String>, separator: String): String {
        if (values.isEmpty()) return "settings delete system $key"

        // millet_white dùng dấu chấm phẩy VÀ có thêm một dấu chấm phẩy ở cuối chuỗi.
        val value = values.joinToString(separator) +
                if (key.equals(KEY_MILLET_WHITE, ignoreCase = true)) ";" else ""
        return "settings put system $key \"$value\""
    }

    /**
     * HyperOS thu hồi quyền `WRITE_SETTINGS` của `com.android.shell`, khiến mọi lệnh
     * `settings put system` fail với SecurityException — đây chính là cảnh báo
     * "quyền ADB bị hạn chế" mà Shizuku hiển thị.
     *
     * Chốt chặn thực ra là một AppOp, mà `appops set` thì vẫn chạy được, nên cấp lại
     * được từ chính trong app. Đo trên Redmi/POCO peridot, HyperOS 3, Android 16.
     */
    private fun ensureWriteSettingsPermission() {
        val current = parseOpStatus(
            ShizukuShellExecutor.executeCommand("cmd appops get com.android.shell WRITE_SETTINGS")
        )
        if (current == OpStatus.ALLOWED) return
        ShizukuShellExecutor.run("cmd appops set com.android.shell WRITE_SETTINGS allow")
    }

    // ---------------------------------------------------------------- Tiện ích khác

    suspend fun openGcmDiagnostics(): String = withContext(Dispatchers.IO) {
        ShizukuShellExecutor.executeCommand("am start -n $GMS_PACKAGE/.gcm.GcmDiagnostics")
    }

    suspend fun openAppSettings(packageName: String): String = withContext(Dispatchers.IO) {
        ShizukuShellExecutor.executeCommand(
            "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$packageName"
        )
    }

    // ---------------------------------------------------------------- Phân tích output

    /**
     * `dumpsys deviceidle whitelist` in ra dạng `system,com.foo,1000` hoặc chỉ tên package.
     *
     * Bản cũ dùng `output.contains(packageName)` trên toàn bộ dump nên `com.foo` khớp nhầm
     * với `com.foo.bar` — app hiện xanh "đã whitelist" trong khi thực tế chưa. Ở đây tách
     * thành từng token và so khớp chính xác.
     */
    private fun parseIdleWhitelist(output: String): Set<String> =
        output.lines()
            .flatMap { line -> line.trim().split(',', ' ', '\t') }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('.') }
            .toSet()

    /**
     * Trả về `null` khi key không tồn tại trên ROM này, phân biệt với danh sách rỗng.
     * `null` cũng có nghĩa "không đọc được" — khi đó tuyệt đối không được ghi đè bảng.
     */
    private fun parseSettingTable(key: String, result: BatchResult): SettingTable? {
        if (!result.isSuccess) return null

        val trimmed = result.output.trim()
        if (trimmed.isBlank() || trimmed == "null") return null

        // Chỉ nhận token trông đúng như tên package. Đây là chốt chặn cuối: nếu vì lý do
        // nào đó output không phải danh sách package, ta coi như đọc hỏng thay vì ghi bừa.
        val values = trimmed.split(';', ',', ':', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "null" }
            .toSet()

        if (values.isEmpty() || values.any { !PACKAGE_NAME_REGEX.matches(it) }) return null

        // Giữ nguyên dấu phân cách mà ROM đang dùng thay vì áp đặt một kiểu chung.
        // Đo trên HyperOS 3: MILLET_NO_RESTRICT_APP dùng ", ", cloud_lowlatency dùng ",".
        val separator = when {
            key.equals(KEY_MILLET_WHITE, ignoreCase = true) -> ";"
            trimmed.contains(", ") -> ", "
            trimmed.contains(",") -> ","
            trimmed.contains(";") -> ";"
            else -> ", "
        }
        return SettingTable(values, separator)
    }

    private fun parseStandbyBucket(output: String): String {
        val value = output.trim()
        return when {
            value.contains("EXEMPTED", ignoreCase = true) || value == "5" -> "EXEMPTED (5)"
            value.contains("ACTIVE", ignoreCase = true) || value == "10" -> "ACTIVE (10)"
            value.contains("WORKING_SET", ignoreCase = true) || value == "20" -> "WORKING_SET (20)"
            value.contains("FREQUENT", ignoreCase = true) || value == "30" -> "FREQUENT (30)"
            value.contains("RARE", ignoreCase = true) || value == "40" -> "RARE (40)"
            value.contains("RESTRICTED", ignoreCase = true) || value == "45" -> "RESTRICTED (45)"
            value.isNotEmpty() -> value
            else -> "UNKNOWN"
        }
    }

    /**
     * Phân tích output của `cmd appops get`. Các dạng gặp thực tế trên HyperOS 3:
     *
     *   RUN_IN_BACKGROUND: allow                          <- op đã được đặt tường minh
     *   RUN_ANY_IN_BACKGROUND: allow; time=+184d... ago
     *   MIUIOP(10008): ignore                             <- op riêng của Xiaomi
     *   No operations.                                    <- op chưa từng đặt,
     *   Default mode: allow                                  giá trị hiệu lực nằm ở đây
     *
     * Thứ tự ưu tiên rất quan trọng: dòng của riêng package phải thắng "Default mode:".
     * Nếu chỉ dò chuỗi trên toàn bộ output như trước, trường hợp op = ignore nhưng
     * default = allow sẽ bị đọc nhầm thành ALLOWED.
     */
    private fun parseOpStatus(output: String): OpStatus {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // "Uid mode:" là chế độ của cả UID chứ không phải của riêng package, bỏ qua.
        val explicit = lines.firstOrNull {
            !it.startsWith("Uid mode:", ignoreCase = true) &&
                    !it.startsWith("Default mode:", ignoreCase = true) &&
                    it.contains(':')
        }
        if (explicit != null) {
            modeOf(explicit.substringAfter(':'))?.let { return it }
        }

        lines.firstOrNull { it.startsWith("Default mode:", ignoreCase = true) }?.let { line ->
            modeOf(line.substringAfter(':'))?.let { return it }
        }

        // "No operations." mà không kèm Default mode: op chưa từng được đặt.
        if (lines.any { it.contains("No operations", ignoreCase = true) }) return OpStatus.DEFAULT

        return OpStatus.UNKNOWN
    }

    private fun modeOf(value: String): OpStatus? {
        // Cắt bỏ phần "; time=..." ở đuôi trước khi so khớp.
        val mode = value.substringBefore(';').trim().lowercase()
        return when (mode) {
            "allow" -> OpStatus.ALLOWED
            "ignore" -> OpStatus.IGNORED
            "deny" -> OpStatus.DENIED
            "default" -> OpStatus.DEFAULT
            else -> null
        }
    }
}
