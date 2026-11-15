package com.meapet.mobile.live2d.overlay

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 悬浮窗透明度的「整幅合成」器：模型先以不透明画进离屏 FBO，再把 FBO 贴图
 * 以全局 alpha 画回默认帧缓冲。
 *
 * ## 为什么不能逐层乘 alpha
 *
 * 原先的做法是在 Cubism 的每个 drawable 上乘一次 alpha（`CubismRendererAndroid.opacity`）。
 * 混合是按 drawable 逐个和帧缓冲做的，凡是同一像素上有**多层半透明**叠在一起的地方
 * （嘴部内腔/牙齿叠在脸上、腮红、阴影、发丝边等），alpha 会累积：
 * 两层各自 alpha=a 叠出来是 `1-(1-a)²`，比 `a` 更不透明。于是调低透明度时这些部位
 * 比其它部位更实，看起来像「图层叠加处更暗」。
 *
 * 把模型先合成成一张不透明的图、再对整张图乘一次 alpha，才等价于用户理解的
 * 「整个悬浮窗变透明」。
 *
 * ## 实现要点
 *
 * - 模型贴图与渲染器都开 premultiplied alpha（`Live2dDefine.PREMULTIPLIED_ALPHA_ENABLE`），
 *   所以离屏结果本身就带预乘 RGB，合成时用 `GL_ONE / GL_ONE_MINUS_SRC_ALPHA` 并同时
 *   乘 alpha 即可，不会出现二次乘 alpha 的边缘发黑。
 * - 离屏只用于 `opacity < 1`，全不透明时零额外开销（调用方直接走原路径）。
 * - 不加深度附件：Cubism 渲染器绘制时会 `glDisable(GL_DEPTH_TEST)`。
 * - GL 资源随 EGL 上下文销毁而释放；`onContextCreated()` 只重置句柄，不做删除。
 */
internal class OverlayAlphaCompositor(private val tag: String) {

    private var framebufferId = 0
    private var textureId = 0
    private var program = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var textureUniform = -1
    private var alphaUniform = -1
    private var width = 0
    private var height = 0
    private var targetReady = false

    private val quad = createQuadBuffer()

    /** 离屏目标与着色器都可用时才能走整幅合成，否则调用方回退到逐层乘 alpha。 */
    val isReady: Boolean
        get() = targetReady && program != 0

    /** GL 上下文创建/重建后调用（GL 线程）。旧上下文的资源已随上下文销毁。 */
    fun onContextCreated() {
        framebufferId = 0
        textureId = 0
        program = 0
        targetReady = false
        width = 0
        height = 0
        program = buildProgram()
    }

    /** 视图尺寸变化后调用（GL 线程）。尺寸没变时是 no-op。 */
    fun ensureTarget(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (targetReady && w == width && h == height) return

        deleteTarget()
        width = w
        height = h

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val tex = textures[0]
        if (tex == 0) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        val fbo = framebuffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tex, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(tag, "透明度离屏合成不可用（FBO 状态 0x${Integer.toHexString(status)}），回退逐层 alpha")
            if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            if (tex != 0) GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            return
        }

        framebufferId = fbo
        textureId = tex
        targetReady = true
        Log.d(tag, "透明度离屏合成就绪：${w}x$h")
    }

    /** 绑定离屏目标并清成透明，之后调用方正常绘制模型（按不透明画）。 */
    fun begin() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    /** 把离屏结果按 [alpha] 整体合成回默认帧缓冲。 */
    fun end(alpha: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        // 离屏结果已预乘 alpha：源直接 ONE，目标按 (1 - 源alpha) 衰减
        GLES20.glBlendFuncSeparate(
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA
        )

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glUniform1f(alphaUniform, alpha)

        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        // 顶点数据是交错的 x,y,u,v，步长必须是 4 个 float（16 字节），否则会把 u,v 当成下一个顶点的坐标，四边形错位而整幅不可见
        quad.position(0)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, QUAD_STRIDE_BYTES, quad)
        quad.position(2)
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, QUAD_STRIDE_BYTES, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun deleteTarget() {
        if (framebufferId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
            framebufferId = 0
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        targetReady = false
    }

    private fun buildProgram(): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertexShader == 0 || fragmentShader == 0) {
            if (vertexShader != 0) GLES20.glDeleteShader(vertexShader)
            if (fragmentShader != 0) GLES20.glDeleteShader(fragmentShader)
            return 0
        }

        val p = GLES20.glCreateProgram()
        if (p == 0) return 0
        GLES20.glAttachShader(p, vertexShader)
        GLES20.glAttachShader(p, fragmentShader)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        val linked = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] != GLES20.GL_TRUE) {
            Log.w(tag, "透明度合成着色器链接失败：${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            return 0
        }

        positionLocation = GLES20.glGetAttribLocation(p, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(p, "aTexCoord")
        textureUniform = GLES20.glGetUniformLocation(p, "uTexture")
        alphaUniform = GLES20.glGetUniformLocation(p, "uAlpha")
        if (positionLocation < 0 || texCoordLocation < 0 || textureUniform < 0 || alphaUniform < 0) {
            Log.w(tag, "透明度合成着色器缺少 attribute/uniform，回退逐层 alpha")
            GLES20.glDeleteProgram(p)
            return 0
        }
        return p
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            Log.w(tag, "透明度合成着色器编译失败：${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private companion object {
        /** 每个顶点 x, y, u, v 共 4 个 float。 */
        const val QUAD_STRIDE_BYTES = 4 * 4

        /** 两个三角形拼成全屏四边形：x, y, u, v（顶点顺序与 TRIANGLE_STRIP 一致）。 */
        fun createQuadBuffer(): FloatBuffer {
            val data = floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            )
            return ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data)
                    position(0)
                }
        }

        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        /** 实测统一为预乘 alpha：RGB 与 A 同时乘 [uAlpha] 后按 ONE 混合。 */
        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(color.rgb * uAlpha, color.a * uAlpha);
            }
        """
    }
}
