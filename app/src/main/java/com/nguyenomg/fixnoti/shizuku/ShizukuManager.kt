package com.nguyenomg.fixnoti.shizuku

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku

class ShizukuManager(
    private val onPermissionResult: (Boolean) -> Unit
) {

    private val requestCode = 1001
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Chỉ tự động hiện hộp thoại xin quyền một lần cho mỗi phiên chạy.
     *
     * Trước đây onResume gọi xin quyền mỗi lần quay lại app, nên người dùng đã từ chối
     * vẫn bị hỏi lại liên tục. Sau lần đầu, chỉ xin quyền khi người dùng chủ động bấm nút.
     */
    private var hasAutoRequested = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { reqCode, grantResult ->
        if (reqCode == requestCode) {
            onPermissionResult(grantResult == PackageManager.PERMISSION_GRANTED)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        // Shizuku vừa khởi động: kiểm tra lại, và xin quyền nếu chưa từng tự động xin.
        refresh(userInitiated = false)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        onPermissionResult(false)
    }

    fun registerListeners() {
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)

            requestWithRetry(userInitiated = false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun unregisterListeners() {
        try {
            handler.removeCallbacksAndMessages(null)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Người dùng chủ động bấm "Cấp quyền Shizuku": luôn hiện hộp thoại,
     * kèm cơ chế thử lại phòng khi Shizuku vừa mới được bật.
     */
    fun requestPermissionByUser() {
        hasAutoRequested = false
        requestWithRetry(userInitiated = true)
    }

    /** Chỉ đọc lại trạng thái quyền, không bao giờ hiện hộp thoại. Dùng cho onResume. */
    fun refreshStatusOnly() {
        onPermissionResult(ShizukuShellExecutor.isPermissionGranted())
    }

    private fun requestWithRetry(userInitiated: Boolean, retryCount: Int = 3) {
        if (refresh(userInitiated)) return

        // Shizuku có thể chưa kịp kết nối binder ngay lúc app khởi động.
        if (retryCount > 0 && !ShizukuShellExecutor.isShizukuAvailable()) {
            handler.postDelayed({ requestWithRetry(userInitiated, retryCount - 1) }, 500)
        }
    }

    /** Trả về true nếu đã xác định xong trạng thái (đã cấp quyền, hoặc vừa hiện hộp thoại). */
    private fun refresh(userInitiated: Boolean): Boolean {
        if (!ShizukuShellExecutor.isShizukuAvailable()) {
            onPermissionResult(false)
            return false
        }

        return try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                onPermissionResult(true)
                true
            } else {
                onPermissionResult(false)
                if (userInitiated || !hasAutoRequested) {
                    hasAutoRequested = true
                    Shizuku.requestPermission(requestCode)
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            onPermissionResult(false)
            false
        }
    }
}
