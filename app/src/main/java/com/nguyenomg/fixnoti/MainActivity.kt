package com.nguyenomg.fixnoti

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.nguyenomg.fixnoti.shizuku.ShizukuManager
import com.nguyenomg.fixnoti.ui.MainViewModel
import com.nguyenomg.fixnoti.ui.components.AppListScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var shizukuManager: ShizukuManager

    /** Chỉ tải lại danh sách khi quyền chuyển từ chưa cấp sang đã cấp, tránh tải lặp. */
    private var wasGranted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        shizukuManager = ShizukuManager { isGranted ->
            viewModel.updateShizukuStatus(isGranted)
            if (isGranted && !wasGranted) {
                viewModel.loadApps(applicationContext)
            }
            wasGranted = isGranted
        }
        shizukuManager.registerListeners()

        // Tải danh sách ngay cả khi chưa có Shizuku (dùng PackageManager, có thể thiếu app).
        viewModel.loadApps(applicationContext)

        setContent {
            FixNotiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppListScreen(
                        viewModel = viewModel,
                        onRequestShizukuPermission = { shizukuManager.requestPermissionByUser() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Chỉ đọc lại trạng thái. Việc xin quyền do người dùng chủ động bấm nút,
        // nếu không app sẽ hiện hộp thoại Shizuku mỗi lần quay lại màn hình.
        shizukuManager.refreshStatusOnly()
    }

    override fun onDestroy() {
        super.onDestroy()
        shizukuManager.unregisterListeners()
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF00639B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCEE5FF),
    onPrimaryContainer = Color(0xFF001D33),
    secondary = Color(0xFF51606F),
    secondaryContainer = Color(0xFFD4E4F6),
    onSecondaryContainer = Color(0xFF0D1D2A),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF96CCFF),
    onPrimary = Color(0xFF003354),
    primaryContainer = Color(0xFF004A77),
    onPrimaryContainer = Color(0xFFCEE5FF),
    secondary = Color(0xFFB8C8DA),
    secondaryContainer = Color(0xFF394857),
    onSecondaryContainer = Color(0xFFD4E4F6),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun FixNotiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
