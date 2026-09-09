package com.nguyenomg.fixnoti.shizuku

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * Kết quả thực thi một lệnh shell qua Shizuku.
 * Tách riêng stdout/stderr/exitCode để phía trên biết được lệnh nào thực sự thất bại.
 */
data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
) {
    val isSuccess: Boolean get() = exitCode == 0 && stderr.isBlank()

    /** Thông điệp lỗi ngắn gọn để hiển thị trong log, null nếu thành công. */
    val errorMessage: String?
        get() = when {
            isSuccess -> null
            stderr.isNotBlank() -> stderr.lines().firstOrNull { it.isNotBlank() }?.trim()
            else -> "exit code $exitCode"
        }

    companion object {
        fun failure(reason: String) = ShellResult("", reason, -1)
    }
}

object ShizukuShellExecutor {

    /** Dấu phân cách giữa các lệnh khi chạy gộp. Chuỗi lạ để không đụng output thật. */
    private const val BATCH_SEPARATOR = "___FIXNOTI_CMD_SEP___"
    private const val BATCH_EXIT_MARKER = "___FIXNOTI_EXIT___"

    private var newProcessMethod: Method? = null

    init {
        findNewProcessMethod()
    }

    private fun findNewProcessMethod() {
        try {
            val methods = Shizuku::class.java.declaredMethods
            for (m in methods) {
                if (m.name == "newProcess") {
                    m.isAccessible = true
                    newProcessMethod = m
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            if (Shizuku.isPreV11()) return false
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Thực thi một lệnh, trả về stdout đã trim.
     * Giữ lại cho các chỗ chỉ quan tâm output; dùng [run] nếu cần biết lệnh có lỗi hay không.
     */
    fun executeCommand(command: String): String = run(command).stdout

    /** Thực thi một lệnh và trả về đầy đủ stdout/stderr/exitCode. */
    fun run(command: String): ShellResult {
        if (!isPermissionGranted()) {
            return ShellResult.failure("Shizuku chưa được cấp quyền")
        }

        return try {
            if (newProcessMethod == null) findNewProcessMethod()

            val process = newProcessMethod?.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
                ?: return ShellResult.failure("Không gọi được Shizuku newProcess")

            // Đọc stdout và stderr song song. Nếu chỉ đọc một luồng, luồng còn lại
            // có thể đầy buffer và làm process bị treo vĩnh viễn.
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val errThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        reader.forEachLine { stderr.append(it).append('\n') }
                    }
                } catch (ignored: Throwable) {
                }
            }
            errThread.start()

            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.forEachLine { stdout.append(it).append('\n') }
                }
            } catch (ignored: Throwable) {
            }

            // Phải dùng waitFor() thuần, KHÔNG dùng waitFor(timeout, TimeUnit).
            // Bản có timeout là cài đặt mặc định của java.lang.Process, dựa trên việc
            // hỏi exitValue() liên tục; ShizukuRemoteProcess không hợp với cách đó nên
            // nó báo đã xong quá sớm rồi exitValue() ném IllegalThreadStateException,
            // khiến mọi lệnh trả về rỗng. waitFor() thuần chặn đúng tới khi tiến trình kết thúc.
            // Việc đọc stdout ở trên đã chạy tới EOF nên tới đây tiến trình gần như đã xong.
            val exitCode = process.waitFor()
            errThread.join(1000)

            ShellResult(
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim(),
                exitCode = exitCode
            )
        } catch (e: Throwable) {
            e.printStackTrace()
            ShellResult.failure(e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    /**
     * Chạy nhiều lệnh trong MỘT process shell duy nhất, tách output và exit code từng lệnh.
     *
     * Mỗi lần [run] phải spawn một process qua binder, rất tốn kém: kiểm tra một app cần
     * 9 lệnh, nhân với hàng trăm app là hàng nghìn lần spawn. Gộp lại như thế này giảm
     * số lần spawn xuống đúng bằng số lần gọi hàm.
     *
     * Trả về danh sách kết quả tương ứng từng lệnh, cùng độ dài với [commands].
     */
    fun runBatch(commands: List<String>): List<BatchResult> {
        if (commands.isEmpty()) return emptyList()
        if (!isPermissionGranted()) {
            return List(commands.size) { BatchResult("Shizuku chưa được cấp quyền", -1) }
        }

        // In dấu phân cách trước mỗi lệnh, kể cả lệnh đầu, để việc tách luôn đều đặn.
        // stderr gộp vào stdout để bắt được thông báo lỗi của các lệnh trả exit code 0.
        val script = commands.joinToString("\n") { cmd ->
            "echo $BATCH_SEPARATOR\n{ $cmd ; } 2>&1\necho $BATCH_EXIT_MARKER\$?"
        }

        val output = run(script).stdout
        // parts[0] là phần trước dấu phân cách đầu tiên (luôn rỗng), nên bỏ đi.
        val parts = output.split(BATCH_SEPARATOR)

        return List(commands.size) { index ->
            val raw = parts.getOrNull(index + 1)
                ?: return@List BatchResult("Không nhận được output", -1)

            val markerAt = raw.lastIndexOf(BATCH_EXIT_MARKER)
            if (markerAt < 0) {
                BatchResult(raw.trim(), -1)
            } else {
                val code = raw.substring(markerAt + BATCH_EXIT_MARKER.length).trim().toIntOrNull() ?: -1
                BatchResult(raw.substring(0, markerAt).trim(), code)
            }
        }
    }

}

/** Kết quả một lệnh trong batch. [output] gồm cả stdout lẫn stderr. */
data class BatchResult(val output: String, val exitCode: Int) {

    /**
     * Nhiều lệnh của Android (`appops set`, `settings put`) trả exit code 0 nhưng vẫn in
     * thông báo lỗi, nên phải soi cả nội dung output chứ không chỉ tin vào exit code.
     */
    val isSuccess: Boolean
        get() = exitCode == 0 && FAILURE_MARKERS.none { output.contains(it, ignoreCase = true) }

    val errorMessage: String?
        get() = if (isSuccess) null
        else output.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "exit code $exitCode"

    private companion object {
        val FAILURE_MARKERS = listOf(
            "Exception",
            "Error:",
            "error:",
            "Failure",
            "Unknown command",
            "not found",
            "Permission Denial",
            "Bad "
        )
    }
}
