package com.nguyenomg.fixnoti.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.nguyenomg.fixnoti.ui.theme.StatusColors
import com.nguyenomg.fixnoti.model.AppInfo

@Composable
fun AppItemRow(
    app: AppInfo,
    onToggleSelect: () -> Unit,
    onOpenDetail: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { onToggleSelect() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (app.isGoogleGms) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Checkbox chọn app
            Checkbox(
                checked = app.isSelected,
                onCheckedChange = { onToggleSelect() }
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 2. Icon app
            val iconBitmap = app.icon?.toBitmap()?.asImageBitmap()
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.appName.take(1).uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 3. Tên app & Package Name
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.appName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (app.isGoogleGms) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(2.dp)
                        ) {
                            Text(
                                text = "GMS",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (app.isHiddenByMiui) {
                    Text(
                        text = "MIUI giấu app này — chỉ thấy được qua Shizuku",
                        fontSize = 10.sp,
                        color = StatusColors.warning()
                    )
                }

                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Badges nếu đã từng check status
                app.detailStatus?.let { status ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BadgeChip(
                            text = if (status.isWhitelisted) "Whitelist OK" else "No Whitelist",
                            isSuccess = status.isWhitelisted
                        )
                        BadgeChip(
                            text = "Bucket: ${status.standbyBucket}",
                            isSuccess = status.isBucketOk
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 4. Nút Detail (i) bên phải
            IconButton(
                onClick = { onOpenDetail() },
                modifier = Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Detail Info",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun BadgeChip(text: String, isSuccess: Boolean) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = StatusColors.containerOf(isSuccess)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = StatusColors.of(isSuccess),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
