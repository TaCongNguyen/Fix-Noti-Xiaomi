package com.nguyenomg.fixnoti.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Màu trạng thái đạt/không đạt.
 *
 * Material3 không có "success" trong bảng màu chuẩn, mà xanh/đỏ đậm của bản sáng lại
 * quá tối để đọc trên nền tối. Nên định nghĩa riêng theo từng chế độ thay vì hardcode
 * một giá trị dùng chung cho cả hai.
 */
object StatusColors {

    @Composable
    @ReadOnlyComposable
    fun success(): Color = if (isSystemInDarkTheme()) Color(0xFF7BD88F) else Color(0xFF2E7D32)

    @Composable
    @ReadOnlyComposable
    fun danger(): Color = if (isSystemInDarkTheme()) Color(0xFFFF8A80) else Color(0xFFC62828)

    @Composable
    @ReadOnlyComposable
    fun warning(): Color = if (isSystemInDarkTheme()) Color(0xFFFFCC80) else Color(0xFFE65100)

    @Composable
    @ReadOnlyComposable
    fun of(isOk: Boolean): Color = if (isOk) success() else danger()

    /** Nền nhạt tương ứng, đủ tương phản ở cả hai chế độ. */
    @Composable
    @ReadOnlyComposable
    fun containerOf(isOk: Boolean): Color =
        of(isOk).copy(alpha = if (isSystemInDarkTheme()) 0.20f else 0.14f)

    @Composable
    @ReadOnlyComposable
    fun warningContainer(): Color =
        warning().copy(alpha = if (isSystemInDarkTheme()) 0.20f else 0.14f)
}
