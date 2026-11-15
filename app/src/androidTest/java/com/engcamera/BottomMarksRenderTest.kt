package com.engcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.engcamera.util.BottomMarks
import com.engcamera.util.BrandBadgeImage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 右下角品牌角标的渲染验证（v0.6.31）。
 *
 * 为什么用 instrumented test 而不是界面截图：
 * 真机锁屏时无法通过 adb 解锁（设备安全限制），但渲染器是纯函数、
 * 不依赖界面——直接调 BottomMarks 出图并拉回本地看像素，等价于成片效果。
 *
 * 产物落在 app 的 external files dir，测试后用 adb pull 取回。
 */
@RunWith(AndroidJUnit4::class)
class BottomMarksRenderTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 与成片同尺寸：1920×2560 */
    private fun render(
        name: String,
        code: String?,
        badge: Bitmap?,
        roll: Float = 0f,
        line1: String = "",
        line2: String = "",
        pill: String = "",
    ): Bitmap {
        val w = 1920
        val h = 2560
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // 灰底模拟水泥墙背景（参考图就是这种底），便于看白字对比
        canvas.drawColor(Color.rgb(150, 150, 148))
        BottomMarks.drawBottomCorner(canvas, w, h, code, badge, roll, line1, line2, pill)
        val out = File(ctx.getExternalFilesDir(null), name)
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    @Test
    fun fontsLoaded() {
        // 字体预热必须成功，否则角标会回退系统细体（观感差很多）
        BottomMarks.ensureFonts(ctx)
        BrandBadgeImage.init(ctx)
    }

    @Test
    fun renderDefaultBadgeWithCode() {
        BottomMarks.ensureFonts(ctx)
        val bmp = render("badge_default.png", "CYRHDX9N9212P9", null)
        assertNotNull(bmp)
        assertTrue(bmp.width == 1920)
    }

    @Test
    fun renderBadgeOnly() {
        BottomMarks.ensureFonts(ctx)
        render("badge_only.png", null, null)
    }

    @Test
    fun renderRotated() {
        BottomMarks.ensureFonts(ctx)
        // 重力角 90°：整块应贴到屏幕系的另一角，且文字跟着转
        render("badge_roll90.png", "CYRHDX9N9212P9", null, roll = 90f)
    }

    @Test
    fun renderImportedImage() {
        BottomMarks.ensureFonts(ctx)
        // 造一张假的「公司 logo」验证外部导入路径（不依赖相册）
        val logo = Bitmap.createBitmap(400, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(logo)
        c.drawColor(Color.rgb(250, 250, 252))
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 40, 60)
            textSize = 52f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        c.drawText("公司 LOGO", 200f, 95f, p)
        render("badge_image.png", "CYRHDX9N9212P9", logo)
    }

    @Test
    fun renderCustomText() {
        BottomMarks.ensureFonts(ctx)
        // 自定义三行文字（v0.6.31 用户需求）：验证字宽变化时两行仍对齐
        render(
            "badge_custom.png",
            "CYRHDX9N9212P9",
            null,
            line1 = "中建八局",
            line2 = "项目",
            pill = "真实施工",
        )
    }

    /**
     * 用户实测反馈的案例（2026-11-15）：把第一行改成「今日水印」后，
     * 「今日」与「水印」字形不一致——根因是旧字体只子集了 10 个字，
     * 「今日」缺字被系统字体回退。改成 GB2312 全量后本用例必须整行同字体。
     */
    @Test
    fun renderCustomTextToday() {
        BottomMarks.ensureFonts(ctx)
        render("badge_today.png", "CYRHDX9N9212P9", null, line1 = "今日水印")
    }

    @Test
    fun renderCustomTextShort() {
        BottomMarks.ensureFonts(ctx)
        // 极短文案（单字）：验证 L1 不会被撑得过大
        render("badge_short.png", "CYRHDX9N9212P9", null, line1 = "建", line2 = "工", pill = "验")
    }
}
