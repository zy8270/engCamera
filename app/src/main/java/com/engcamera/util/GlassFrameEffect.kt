package com.engcamera.util

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.engcamera.watermark.WatermarkRenderer

/**
 * 视频液态玻璃（方案 B，用户拍板 2026-10-16）：自定义 Media3 帧效果，在 GPU 上
 * 逐帧把视频画面本身作为折射背景——SDF 圆角矩形、边缘折射带、RGB 三通道色散、
 * 着色，公式对齐 WatermarkRenderer.renderGlassCard 的 CPU 移植（同源于库的
 * liquidglass_effect.agsl）。卡片区域外的帧像素原样通过；文字块由 OverlayEffect
 * 单独叠加（media3 自身的合成路径，方向契约已验证），两者在 effects 列表中按
 * [本效果, OverlayEffect] 顺序执行。
 *
 * 几何逐帧取自 geometryAt(presentationTimeUs)（录像中旋转同步：锚点/角度随
 * 「当时 roll − 开录 roll」变化，与预览所见一致）；某时刻几何为 null 时按
 * 半宽高=0 处理（SDF 恒在外 → 帧原样通过）。
 *
 * 坐标系：shader 采样 uv 为 GL 系（y 向上）；几何中心/局部坐标为图像系（y 向下），
 * 进出各翻转一次 y。卡片随 rotationDeg 旋转：局部坐标=图像坐标绕中心旋 -rot，
 * 采样点=中心+rot·(局部+折射偏移)（CPU 版 mapU/mapV 的 GLSL 等价）。
 */
class GlassFrameEffect(
    private val geometryAt: (Long) -> WatermarkRenderer.GlassGeometry?,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try {
            ShaderProgram(geometryAt)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

    private class ShaderProgram(
        private val geoAt: (Long) -> WatermarkRenderer.GlassGeometry?,
    ) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1) {

        private val program: GlProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        private var frameW = 0
        private var frameH = 0

        init {
            // 四顶点三角形条带铺满 NDC，输出帧与输入帧逐点对应
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            frameW = inputWidth
            frameH = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val g = geoAt(presentationTimeUs)
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                program.setFloatsUniform("uFrameSize", floatArrayOf(frameW.toFloat(), frameH.toFloat()))
                if (g != null) {
                    program.setFloatsUniform("uCenter", floatArrayOf(g.centerX, g.centerY))
                    program.setFloatsUniform("uHalf", floatArrayOf(g.width / 2f, g.height / 2f))
                    program.setFloatUniform("uRotRad", Math.toRadians(g.rotationDeg.toDouble()).toFloat())
                    program.setFloatUniform("uCorner", g.cornerPx)
                    program.setFloatUniform("uRh", g.refractionHeightPx)
                    program.setFloatUniform("uRa", g.refractionOffsetPx)
                    program.setFloatUniform("uDisp", g.dispersion)
                    program.setFloatUniform("uTintA", g.tintAlpha)
                    program.setFloatsUniform("uTint", floatArrayOf(g.tintR, g.tintG, g.tintB))
                    program.setFloatUniform("uBlur", g.blurPx)
                } else {
                    // 无几何（空块等）：半宽高=0 → SDF 恒在外，帧原样通过
                    program.setFloatsUniform("uCenter", floatArrayOf(0f, 0f))
                    program.setFloatsUniform("uHalf", floatArrayOf(0f, 0f))
                    program.setFloatUniform("uRotRad", 0f)
                    program.setFloatUniform("uCorner", 1f)
                    program.setFloatUniform("uRh", 1f)
                    program.setFloatUniform("uRa", 0f)
                    program.setFloatUniform("uDisp", 0f)
                    program.setFloatUniform("uTintA", 0f)
                    program.setFloatsUniform("uTint", floatArrayOf(0f, 0f, 0f))
                    program.setFloatUniform("uBlur", 0f)
                }
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    private companion object {
        private val VERTEX_SHADER = """
            #version 100
            attribute vec4 aFramePosition;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 100
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexCoord;
            uniform vec2 uFrameSize;
            uniform vec2 uCenter;
            uniform vec2 uHalf;
            uniform float uRotRad;
            uniform float uCorner;
            uniform float uRh;
            uniform float uRa;
            uniform float uDisp;
            uniform float uTintA;
            uniform vec3 uTint;
            uniform float uBlur;

            float sdRoundRect(vec2 p, vec2 b, float r) {
              vec2 q = abs(p) - (b - vec2(r));
              return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }
            vec2 gradSdRoundRect(vec2 p, vec2 b, float r) {
              vec2 q = abs(p) - (b - vec2(r));
              vec2 sp = sign(p);
              if (q.x >= 0.0 || q.y >= 0.0) {
                vec2 o = max(q, vec2(0.0));
                float len = length(o);
                if (len > 0.001) return sp * o / len;
                return (q.y <= q.x) ? vec2(sp.x, 0.0) : vec2(0.0, sp.y);
              }
              return (q.y <= q.x) ? vec2(sp.x, 0.0) : vec2(0.0, sp.y);
            }
            float circleMap(float x) {
              return 1.0 - sqrt(max(1.0 - x * x, 0.0));
            }
            vec2 rotV(vec2 v, float a) {
              float c = cos(a);
              float s = sin(a);
              return vec2(c * v.x - s * v.y, s * v.x + c * v.y);
            }
            vec3 frameAt(vec2 imgPos) {
              vec2 uv = clamp(vec2(imgPos.x / uFrameSize.x, 1.0 - imgPos.y / uFrameSize.y), 0.002, 0.998);
              return texture2D(uTexSampler, uv).rgb;
            }
            // 模糊：blur>0 时中心+四角 5 点均值（近似 CPU 版盒式模糊）
            vec3 sampleBlurred(vec2 imgPos) {
              vec3 c = frameAt(imgPos);
              if (uBlur < 0.5) return c;
              float j = uBlur * 0.5;
              c += frameAt(imgPos + vec2(j, j));
              c += frameAt(imgPos + vec2(-j, j));
              c += frameAt(imgPos + vec2(j, -j));
              c += frameAt(imgPos + vec2(-j, -j));
              return c / 5.0;
            }
            void main() {
              // GL 采样系（y 上）→ 图像系（y 下）
              vec2 imgPos = vec2(vTexCoord.x, 1.0 - vTexCoord.y) * uFrameSize;
              // 卡随 uRotRad 旋转：局部坐标 = 图像坐标绕中心旋 -rot
              vec2 lc = rotV(imgPos - uCenter, -uRotRad);
              float sd = sdRoundRect(lc, uHalf, uCorner);
              vec3 color;
              if (sd > 0.0) {
                color = frameAt(imgPos);
              } else if (-sd >= uRh) {
                // 内部区：直采内容（含模糊）+ 着色
                color = sampleBlurred(uCenter + rotV(lc, uRotRad));
                if (uTintA > 0.0) color = mix(color, uTint, uTintA);
              } else {
                // 边缘折射带：d = circleMap(1-(-sd)/rh) × 偏移，沿梯度内拉
                float d = circleMap(1.0 + sd / uRh) * uRa;
                float rGrad = min(max(uCorner * 1.5, 30.0), min(uHalf.x, uHalf.y));
                vec2 shapeGrad = gradSdRoundRect(lc, uHalf, rGrad);
                vec2 grad = shapeGrad;
                float depthLen = length(lc);
                if (depthLen > 0.001) grad = grad + 0.3 * (lc / depthLen);
                float gl = length(grad);
                if (gl > 0.001) grad /= gl;
                vec2 off = d * grad;
                // 色散：折射偏移按角点系数缩放，红蓝各偏一侧（7 通道采样的 RGB 近似）
                float k = uDisp * ((lc.x * lc.y) / (uHalf.x * uHalf.y));
                vec2 dd = off * k;
                vec2 p = uCenter + rotV(lc + off, uRotRad);
                color = vec3(frameAt(p + dd).r, frameAt(p).g, frameAt(p - dd).b);
                if (uTintA > 0.0) color = mix(color, uTint, uTintA);
              }
              gl_FragColor = vec4(color, 1.0);
            }
        """.trimIndent()
    }
}
