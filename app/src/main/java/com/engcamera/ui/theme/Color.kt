package com.engcamera.ui.theme

import androidx.compose.ui.graphics.Color

// 品牌色：工程蓝（应用图标、水印标题条、主题主色同源）
val EngBlue = Color(0xFF1D4E89)
val EngBlueLight = Color(0xFF5B8BC0)

// ================= 日间主题（蓝调中性色，M3 角色） =================
val LightPrimary = Color(0xFF1D4E89)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD3E3FD)
val LightOnPrimaryContainer = Color(0xFF001C38)
val LightInversePrimary = Color(0xFFA2C9FF)
val LightSecondary = Color(0xFF545F70)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFD8E3F8)
val LightOnSecondaryContainer = Color(0xFF111C2B)
val LightTertiary = Color(0xFF3D6373)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFC1E8F9)
val LightOnTertiaryContainer = Color(0xFF001F2A)
val LightBackground = Color(0xFFF8FAFD)
val LightOnBackground = Color(0xFF1A1C1F)
val LightSurface = Color(0xFFF8FAFD)
val LightOnSurface = Color(0xFF1A1C1F)
val LightSurfaceVariant = Color(0xFFE0E4EC)
val LightOnSurfaceVariant = Color(0xFF44474E)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF2F4F9)
val LightSurfaceContainer = Color(0xFFECF0F5)
val LightSurfaceContainerHigh = Color(0xFFE6EAEF)
val LightSurfaceContainerHighest = Color(0xFFE0E5EA)
val LightOutline = Color(0xFF74777F)
val LightOutlineVariant = Color(0xFFC4C6D0)
val LightInverseSurface = Color(0xFF2F3033)
val LightInverseOnSurface = Color(0xFFF1F0F4)
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

// ================= 夜间主题（同源蓝调，品牌蓝作容器色） =================
val DarkPrimary = Color(0xFFA2C9FF)
val DarkOnPrimary = Color(0xFF00315D)
val DarkPrimaryContainer = Color(0xFF1D4E89)
val DarkOnPrimaryContainer = Color(0xFFD3E3FD)
val DarkInversePrimary = Color(0xFF1D4E89)
val DarkSecondary = Color(0xFFBBC7DB)
val DarkOnSecondary = Color(0xFF253140)
val DarkSecondaryContainer = Color(0xFF3B4758)
val DarkOnSecondaryContainer = Color(0xFFD8E3F8)
val DarkTertiary = Color(0xFFA4CDE3)
val DarkOnTertiary = Color(0xFF043542)
val DarkTertiaryContainer = Color(0xFF244C59)
val DarkOnTertiaryContainer = Color(0xFFC1E8F9)
val DarkBackground = Color(0xFF111418)
val DarkOnBackground = Color(0xFFE0E2E8)
val DarkSurface = Color(0xFF111418)
val DarkOnSurface = Color(0xFFE0E2E8)
val DarkSurfaceVariant = Color(0xFF23272E)
val DarkOnSurfaceVariant = Color(0xFFC3C6CF)
val DarkSurfaceContainerLowest = Color(0xFF0C0E12)
val DarkSurfaceContainerLow = Color(0xFF191C20)
val DarkSurfaceContainer = Color(0xFF1D2025)
val DarkSurfaceContainerHigh = Color(0xFF282A2F)
val DarkSurfaceContainerHighest = Color(0xFF33353B)
val DarkOutline = Color(0xFF8D9099)
val DarkOutlineVariant = Color(0xFF43474E)
val DarkInverseSurface = Color(0xFFE0E2E8)
val DarkInverseOnSurface = Color(0xFF2E3033)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

// ================= 取景器浮层（压在实时画面/照片上，恒深色，不随主题） =================
// 相机页行业惯例：取景画面不可预测，浮层控件必须自带深色底保证可读性
val ChromeScrim = Color(0x88000000)      // 抽屉/面板遮罩
val ChromeBadge = Color(0x66000000)      // 角标/胶囊底（录像计时、画质、镜头未选中）
val ChromeSoft = Color(0x33000000)       // 模式切换胶囊容器底
val ChromeNeutral = Color(0x66808080)    // 中性浮层底（工程组快捷按钮）
val ChromeDim = Color(0x99FFFFFF)        // 未激活图标
val ChromeHint = Color(0xB3FFFFFF)       // 提示文字/占位图标
val ChromeIcon = Color(0xCCFFFFFF)       // 浮层图标
val ChromeDanger = Color(0xFFFF5252)     // 录像红
val ChromeAmber = Color(0xFFFFD54F)      // 闪光灯开启
val ChromeBrand = Color(0xCC1D4E89)      // 品牌蓝浮层（扫码气泡）
val ChromeThumbBg = Color(0xFF333333)    // 缩略图占位底
