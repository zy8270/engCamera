/*
 * MIT License
 *
 * Copyright (c) 2025-2026 Donny Yale
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * ===========================================
 * Project: AndroidLiquidGlassView
 * GitHub: https://github.com/QmDeve/AndroidLiquidGlassView
 * WebSite: https://liquidglass.qmdeve.com
 *
 * 内置副本（原类 LiquidGlassView，v160 起随 engCamera 分发）：
 * 与上游 1.1.0 唯一差异=修复 updateConfig 的竞态 NPE——原实现
 * `glass.post(() -> glass.updateParameters())` 在任务排队期间 glass 被
 * rebuild()/onDetachedFromWindow 置空时，残留任务执行即崩（engCamera 真机
 * 复现：启动渲染水印时视图首帧布局触发 rebuild，与组合期 setter 排队的
 * updateParameters 任务相撞，启动即闪退 2026-10-16）。修法=局部捕获引用。
 * ===========================================
 */

package com.engcamera.watermark

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

import com.qmdeve.liquidglass.LiquidGlass
import com.qmdeve.liquidglass.Config
import com.qmdeve.liquidglass.util.LiquidTracker
import com.qmdeve.liquidglass.util.Utils

public class LiquidGlassViewSafe : ViewGroup {

    private var glass: LiquidGlass? = null
    private var customSource: ViewGroup? = null
    private lateinit var context: Context
    private var cornerRadius = Utils.dp2px(getResources(), 40f)
    private var refractionHeight = Utils.dp2px(getResources(), 20f)
    private var refractionOffset = -Utils.dp2px(getResources(), 70f)
    private var tintAlpha = 0.0f
    private var tintColorRed = 1.0f
    private var tintColorGreen = 1.0f
    private var tintColorBlue = 1.0f
    private var blurRadius = 0.01f
    private var dispersion = 0.5f
    private var downX = 0f
    private var downY = 0f
    private var startTx = 0f
    private var startTy = 0f
    private var draggableEnabled = false
    private var elasticEnabled = false
    private var touchEffectEnabled = false
    private var config: Config? = null
    private lateinit var liquidTracker: LiquidTracker

    // Glow effect variables
    private lateinit var glowPaint: Paint
    private var glowX = 0f
    private var glowY = 0f
    private var isTouching = false

    private val clipPath = Path()
    private val clipRect = RectF()

    constructor(context: Context) : super(context) {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        this.context = context
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        this.context = context
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        this.context = context
        init()
    }

    private fun init() {
        setClipToPadding(false)
        setClipChildren(false)
        liquidTracker = LiquidTracker(this)

        glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        glowPaint.style = Paint.Style.FILL
    }

    override fun dispatchDraw(canvas: Canvas) {
        clipRect.set(0f, 0f, getWidth().toFloat(), getHeight().toFloat())
        clipPath.reset()
        clipPath.addRoundRect(
            clipRect,
            cornerRadius,
            cornerRadius,
            Path.Direction.CW,
        )

        val save = canvas.save()
        canvas.clipPath(clipPath)

        super.dispatchDraw(canvas)

        canvas.restoreToCount(save)

        if (touchEffectEnabled && isTouching) {
            canvas.save()
            canvas.clipPath(clipPath)

            val radius = Math.max(getWidth(), getHeight()) * 0.8f
            val colors = intArrayOf(Color.argb(60, 255, 255, 255), Color.TRANSPARENT)
            val stops = floatArrayOf(0f, 1f)

            val gradient = RadialGradient(glowX, glowY, radius, colors, stops, Shader.TileMode.CLAMP)

            glowPaint.shader = gradient
            canvas.drawRect(clipRect, glowPaint)

            canvas.restore()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until getChildCount()) {
            val child = getChildAt(i)
            if (child.getVisibility() != GONE) {
                child.layout(
                    getPaddingLeft(),
                    getPaddingTop(),
                    getPaddingLeft() + child.getMeasuredWidth(),
                    getPaddingTop() + child.getMeasuredHeight(),
                )
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        for (i in 0 until getChildCount()) {
            val child = getChildAt(i)
            if (child.getVisibility() != GONE) {
                measureChild(child, widthMeasureSpec, heightMeasureSpec)
            }
        }
    }

    /** Bind sampling source（与上游差异之二：同源重复 bind 直接跳过——Compose update 每次
     *  重组都会调用，而 init 会重建 impl 并重复挂 PreDraw 监听，反复 init 会让渲染崩掉消失） */
    fun bind(source: ViewGroup?) {
        val changed = this.customSource !== source
        this.customSource = source
        if (!changed) return
        val g = glass
        if (g != null && source != null) {
            g.init(source)
        }
    }

    /** Set the corner radius px */
    fun setCornerRadius(px: Float) {
        val maxPx = if (getHeight() > 0) getHeight() / 2f else Utils.dp2px(getResources(), 99f)
        this.cornerRadius = Math.max(0.0, Math.min(px.toDouble(), maxPx.toDouble())).toFloat()
        invalidate()
        updateConfig()
    }

    /** Set the refraction height px */
    fun setRefractionHeight(px: Float) {
        val minPx = Utils.dp2px(getResources(), 12f)
        val maxPx = Utils.dp2px(getResources(), 50f)
        this.refractionHeight = Math.max(minPx, Math.min(maxPx, px))
        updateConfig()
    }

    /** Set the refraction offset px（正值内部转负） */
    fun setRefractionOffset(px: Float) {
        val minPx = Utils.dp2px(getResources(), 20f)
        val maxPx = Utils.dp2px(getResources(), 120f)
        val p = Math.max(minPx, Math.min(maxPx, px))
        this.refractionOffset = -p
        updateConfig()
    }

    fun setTintColorRed(red: Float) {
        this.tintColorRed = red
        updateConfig()
    }

    fun setTintColorGreen(green: Float) {
        this.tintColorGreen = green
        updateConfig()
    }

    fun setTintColorBlue(blue: Float) {
        this.tintColorBlue = blue
        updateConfig()
    }

    fun setTintAlpha(alpha: Float) {
        this.tintAlpha = alpha
        updateConfig()
    }

    fun setDispersion(dispersion: Float) {
        this.dispersion = Math.max(0f, Math.min(1f, dispersion))
        updateConfig()
    }

    fun setBlurRadius(radius: Float) {
        this.blurRadius = radius.coerceIn(0.01f, 50f)
        updateConfig()
    }

    fun setDraggableEnabled(enabled: Boolean) {
        this.draggableEnabled = enabled
        if (!enabled) {
            liquidTracker.recycle()
        }
    }

    fun setElasticEnabled(enabled: Boolean) {
        this.elasticEnabled = enabled
        if (!enabled) {
            liquidTracker.recycle()
        }
    }

    fun setTouchEffectEnabled(enabled: Boolean) {
        this.touchEffectEnabled = enabled
    }

    private fun updateConfig() {
        // 修复竞态（与上游唯一差异）：局部捕获引用——任务排队期间 glass 可能被
        // rebuild()/onDetachedFromWindow 置空，经字段访问会在残留任务里 NPE
        val g = glass
        if (g == null) {
            rebuild()
            return
        }

        var w = getWidth()
        var h = getHeight()
        if (w <= 0) w = Utils.getDeviceWidthPx(context)
        if (h <= 0) h = getResources().getDisplayMetrics().heightPixels

        val cfg = config
        if (cfg == null) {
            rebuild()
            return
        }
        cfg.CORNER_RADIUS_PX = cornerRadius
        cfg.REFRACTION_HEIGHT = refractionHeight
        cfg.REFRACTION_OFFSET = refractionOffset
        cfg.BLUR_RADIUS = blurRadius
        cfg.WIDTH = w
        cfg.HEIGHT = h
        cfg.DISPERSION = dispersion
        cfg.TINT_ALPHA = tintAlpha
        cfg.TINT_COLOR_BLUE = tintColorBlue
        cfg.TINT_COLOR_GREEN = tintColorGreen
        cfg.TINT_COLOR_RED = tintColorRed

        g.post { g.updateParameters() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(this::ensureGlass)
    }

    override fun onDetachedFromWindow() {
        removeGlass()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            if (w > 0 && h > 0) {
                val maxPx = h / 2f
                if (cornerRadius > maxPx) {
                    cornerRadius = maxPx
                }
                rebuild()
            }
        }
    }

    private fun rebuild() {
        removeGlass()
        post(this::ensureGlass)
    }

    private fun ensureGlass() {
        if (glass != null) return

        var w = getWidth()
        var h = getHeight()
        if (w <= 0) w = Utils.getDeviceWidthPx(context)
        if (h <= 0) h = getResources().getDisplayMetrics().heightPixels

        val cfg = Config()
        cfg.configure(
            Config.Overrides()
                .noFilter()
                .contrast(0f)
                .whitePoint(0f)
                .chromaMultiplier(1f)
                .blurRadius(blurRadius)
                .cornerRadius(cornerRadius)
                .refractionHeight(refractionHeight)
                .refractionOffset(refractionOffset)
                .tintAlpha(tintAlpha)
                .tintColorRed(tintColorRed)
                .tintColorGreen(tintColorGreen)
                .tintColorBlue(tintColorBlue)
                .dispersion(dispersion)
                .size(w, h),
        )

        config = cfg
        val g = LiquidGlass(getContext(), cfg)
        glass = g

        val lp = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        addView(g, 0, lp)

        val source = customSource
        if (source == null && getParent() is ViewGroup) {
            return
        }
        g.init(source)
    }

    private fun removeGlass() {
        val g = glass
        if (g != null) {
            removeView(g)
            glass = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!draggableEnabled && !touchEffectEnabled) return super.onTouchEvent(e)
        if (elasticEnabled) liquidTracker.applyMovement(e)

        when (e.getActionMasked()) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (touchEffectEnabled) {
                    isTouching = true
                    liquidTracker.animateScale(1.02f)

                    glowX = e.getX()
                    glowY = e.getY()
                    invalidate()
                }

                if (draggableEnabled) {
                    downX = e.getRawX()
                    downY = e.getRawY()
                    startTx = getTranslationX()
                    startTy = getTranslationY()
                    return true
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (touchEffectEnabled) {
                    glowX = e.getX()
                    glowY = e.getY()
                    invalidate()
                }

                if (draggableEnabled) {
                    val dx = e.getRawX() - downX
                    val dy = e.getRawY() - downY
                    var tx = startTx + dx
                    var ty = startTy + dy

                    val parent = getParent() as? ViewGroup
                    if (parent != null) {
                        val pw = parent.getWidth()
                        val ph = parent.getHeight()
                        val w = getWidth()
                        val h = getHeight()
                        if (pw > 0 && ph > 0 && w > 0 && h > 0) {
                            val minX = -getLeft().toFloat()
                            val maxX = (pw - getLeft() - w).toFloat()
                            val minY = -getTop().toFloat()
                            val maxY = (ph - getTop() - h).toFloat()
                            if (tx < minX) tx = minX
                            if (tx > maxX) tx = maxX
                            if (ty < minY) ty = minY
                            if (ty > maxY) ty = maxY
                        }
                    }
                    setTranslationX(tx)
                    setTranslationY(ty)
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (touchEffectEnabled) {
                    isTouching = false
                    liquidTracker.animateScale(1f)
                    invalidate()
                }
                if (draggableEnabled) return true
            }
        }

        val superResult = super.onTouchEvent(e)
        return touchEffectEnabled || superResult
    }
}
