package com.nguyenomg.fixnoti.model

import android.graphics.drawable.Drawable

enum class OpStatus {
    ALLOWED,
    IGNORED,
    DENIED,
    DEFAULT,
    UNKNOWN;

    fun isOk(): Boolean = this == ALLOWED
}

data class AppDetailStatus(
    val isWhitelisted: Boolean = false,
    val standbyBucket: String = "UNKNOWN",
    val runInBackground: OpStatus = OpStatus.UNKNOWN,
    val runAnyInBackground: OpStatus = OpStatus.UNKNOWN,
    val autoStart: OpStatus = OpStatus.UNKNOWN,
    val autoRevokePermissions: OpStatus = OpStatus.UNKNOWN,
    val isMilletWhiteSupported: Boolean = false,
    val isMilletWhite: Boolean = false,
    val isCloudLowLatencySupported: Boolean = false,
    val isCloudLowLatency: Boolean = false,
    val isMilletNoRestrictSupported: Boolean = false,
    val isMilletNoRestrict: Boolean = false
) {
    val isBucketOk: Boolean
        get() = standbyBucket.startsWith("ACTIVE") || standbyBucket.startsWith("EXEMPTED")

    /**
     * Autostart của MIUI do Security Center thực thi, `appops set 10008 allow` không có tác dụng
     * thật dù đọc lại vẫn thấy "allow". Vì vậy app chỉ ĐỌC op này rồi nhắc người dùng bật tay.
     * DEFAULT nghĩa là ROM không có op này (máy không phải Xiaomi) nên không cần làm gì.
     */
    val needsManualAutoStart: Boolean
        get() = autoStart != OpStatus.ALLOWED && autoStart != OpStatus.DEFAULT

    /**
     * DEFAULT nghĩa là AppOp chưa từng được đặt trên ROM này, không có gì để sửa nên coi là đạt.
     * Nhưng UNKNOWN (lệnh lỗi, không đọc được) thì KHÔNG được coi là đạt — trước đây nhầm
     * chỗ này nên app báo xanh dù thực tế chưa kiểm tra được gì.
     *
     * Riêng Autostart giờ đặt thẳng được bằng appops nên yêu cầu đúng ALLOW.
     */
    fun isAllOptimized(isGms: Boolean = false): Boolean {
        val baseOk = isWhitelisted &&
                isBucketOk &&
                runInBackground.isOk() &&
                runAnyInBackground.isOk() &&
                !needsManualAutoStart &&
                autoRevokePermissions == OpStatus.IGNORED

        val milletWhiteOk = !isMilletWhiteSupported || isMilletWhite
        val cloudLowLatencyOk = !isCloudLowLatencySupported || isCloudLowLatency
        val milletNoRestrictOk = !isMilletNoRestrictSupported || isMilletNoRestrict

        return baseOk && milletWhiteOk && cloudLowLatencyOk && milletNoRestrictOk
    }
}

data class AppInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable? = null,
    val isSelected: Boolean = false,
    val isGoogleGms: Boolean = false,
    /** true khi package chỉ lấy được qua Shizuku vì PackageManager của MIUI từ chối trả về. */
    val isHiddenByMiui: Boolean = false,
    val detailStatus: AppDetailStatus? = null
)

data class FixLog(
    val appName: String,
    val packageName: String,
    val actionText: String,
    val isSuccess: Boolean = true,
    /** true khi lệnh shell thực sự thất bại — hiển thị đỏ trong nhật ký. */
    val isError: Boolean = false
)
