/*
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at http://live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

package com.live2d.sdk.cubism.framework.rendering.android;

import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.ICubismLoadFileFunction;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.model.CubismModel;
import com.live2d.sdk.cubism.framework.model.CubismModelMultiplyAndScreenColor;
import com.live2d.sdk.cubism.framework.rendering.CubismRenderer;
import com.live2d.sdk.cubism.framework.rendering.android.shaderindex.CubismShaderIndexCalculator;
import com.live2d.sdk.cubism.framework.rendering.android.shaderindex.CubismShaderIndexConstants;
import com.live2d.sdk.cubism.framework.rendering.android.shaderindex.CubismShaderIndexFactors;
import com.live2d.sdk.cubism.framework.rendering.csmBlendMode;
import com.live2d.sdk.cubism.framework.type.csmRectF;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.*;
import static com.live2d.sdk.cubism.framework.utils.CubismDebug.cubismLogError;

/**
 * This class manage a shader program for Android(OpenGL ES 2.0). This is singleton.
 */
public class CubismShaderAndroid {
    /**
     * Tegra processor support. Enable/Disable drawing by extension method.
     *
     * @param extMode   Whether to draw using the extended method.
     * @param extPAMode Enables/disables the PA setting for the extension method.
     */
    public static void setExtShaderMode(boolean extMode, boolean extPAMode) {
        CubismShaderAndroid.EXT_MODE = extMode;
        CubismShaderAndroid.EXT_PA_MODE = extPAMode;
    }

    /**
     * Get this singleton instance.
     *
     * @return singleton instance
     */
    public static CubismShaderAndroid getInstance() {
        if (s_instance == null) {
            s_instance = new CubismShaderAndroid();
            s_instance.generateShaders();
        }

        return s_instance;
    }

    /**
     * Delete this singleton instance.
     */
    public static void deleteInstance() {
        if (s_instance != null) {
            s_instance.releaseShaderProgram();
            s_instance = null;
        }
    }

    /**
     * Setup shader program for a drawable.
     *
     * @param renderer renderer instance
     * @param model    rendered model
     * @param index    target drawable index
     */
    public void setupShaderProgramForDrawable(
        CubismRendererAndroid renderer,
        CubismModel model,
        int index
    ) {
        // Blending
        int srcColor;
        int dstColor;
        int srcAlpha;
        int dstAlpha;

        final boolean isMasked = renderer.getClippingContextBufferForDrawable() != null;  // この描画オブジェクトはマスク対象か？
        final boolean isInvertedMask = model.getDrawableInvertedMask(index);
        final boolean isPremultipliedAlpha = renderer.isPremultipliedAlpha();

        final csmBlendMode blendMode = model.getDrawableBlendModeType(index);

        final int shaderIndex = CubismShaderIndexCalculator.calculateShaderIndex(
            blendMode,
            determineMaskState(isMasked, isInvertedMask),
            isPremultipliedAlpha
        );
        CubismShaderSet shaderSet = shaderSets.get(shaderIndex);

        boolean isBlendMode = false;
        int blendTexture = 0;

        // 5.3以降の高度なブレンドモードを使用している場合の処理
        if (blendMode.isBlendMode()) {
            isBlendMode = true;
            srcColor = GL_ONE;
            dstColor = GL_ZERO;
            srcAlpha = GL_ONE;
            dstAlpha = GL_ZERO;

            // HACK: Copy用のShaderProgramに切り替わるのでここで処理を行う。
            blendTexture = renderer.getCurrentOffscreen() != null
                ? renderer.copyRenderTarget(renderer.getCurrentOffscreen().getRenderTarget()).getColorBuffer()[0]
                : renderer.copyOffscreenRenderTarget().getColorBuffer()[0];
        }
        // 5.2以前のブレンドモードの場合以前の処理を実行する。
        else {
            switch (blendMode.getColorBlendType()) {
                // Normal+Overの場合の処理。
                // Over以外のアルファブレンドは、blendMode.isBlendMode()がtrueのときの分岐内で処理される。
                case NORMAL:
                    srcColor = GL_ONE;
                    dstColor = GL_ONE_MINUS_SRC_ALPHA;
                    srcAlpha = GL_ONE;
                    dstAlpha = GL_ONE_MINUS_SRC_ALPHA;
                    break;
                case ADD_COMPATIBLE:
                    srcColor = GL_ONE;
                    dstColor = GL_ONE;
                    srcAlpha = GL_ZERO;
                    dstAlpha = GL_ONE;
                    break;
                case MULTIPLY_COMPATIBLE:
                    srcColor = GL_DST_COLOR;
                    dstColor = GL_ONE_MINUS_SRC_ALPHA;
                    srcAlpha = GL_ZERO;
                    dstAlpha = GL_ONE;
                    break;
                default:
                    throw new IllegalStateException("Unknown blend type: " + blendMode.getColorBlendType());
            }
        }

        glUseProgram(shaderSet.shaderProgram);

        // テクスチャ設定
        setUpTexture(renderer, model, index, shaderSet);

        // 頂点属性設定
        setVertexAttributes(renderer, model, index, shaderSet);

        if (isMasked) {
            glActiveTexture(GL_TEXTURE1);

            // RenderTargetに描かれたテクスチャ
            int tex = renderer.getDrawableMaskBuffer(renderer.getClippingContextBufferForDrawable().bufferIndex).getColorBuffer()[0];
            glBindTexture(GL_TEXTURE_2D, tex);
            glUniform1i(shaderSet.samplerTexture1Location, 1);

            // set up a matrix to convert View-coordinates to ClippingContext coordinates
            glUniformMatrix4fv(
                shaderSet.uniformClipMatrixLocation,
                1,
                false,
                renderer.getClippingContextBufferForDrawable().matrixForDraw.getArray(),
                0
            );

            // Set used color channel.
            final int channelIndex = renderer.getClippingContextBufferForDrawable().layoutChannelIndex;
            CubismRenderer.CubismTextureColor colorChannel = renderer
                .getClippingContextBufferForDrawable()
                .getClippingManager()
                .getChannelFlagAsColor(channelIndex);
            glUniform4f(
                shaderSet.uniformChannelFlagLocation,
                colorChannel.r,
                colorChannel.g,
                colorChannel.b,
                colorChannel.a
            );
        }

        // ブレンド設定
        if (isBlendMode) {
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, blendTexture);
            glUniform1i(shaderSet.samplerBlendTextureLocation, 2);
        }

        // coordinate transformation
        CubismMatrix44 matrix44 = renderer.getMvpMatrix();
        glUniformMatrix4fv(
            shaderSet.uniformMatrixLocation,
            1,
            false,
            matrix44.getArray(),
            0
        );

        CubismRenderer.CubismTextureColor baseColor = reusableBaseColor;
        // 初期化
        baseColor.r = 1.0f;
        baseColor.g = 1.0f;
        baseColor.b = 1.0f;
        baseColor.a = 1.0f;

        if (model.isBlendModeEnabled()) {
            // ブレンドモードではモデルカラーは最後に処理するため不透明度のみ対応させる。
            float drawableOpacity = model.getDrawableOpacity(index);
            baseColor.a = drawableOpacity;

            if (isPremultipliedAlpha) {
                baseColor.r = drawableOpacity;
                baseColor.g = drawableOpacity;
                baseColor.b = drawableOpacity;
            }
        } else {
            // ブレンドモードを使用しない場合はDrawable単位でモデルカラーを処理する。
            baseColor = renderer.getModelColorWithOpacity(
                model.getDrawableOpacity(index)
            );
        }

        final CubismModelMultiplyAndScreenColor overrideMultiplyAndScreenColor = model.getOverrideMultiplyAndScreenColor();
        CubismRenderer.CubismTextureColor multiplyColor = overrideMultiplyAndScreenColor.getDrawableMultiplyColor(index);
        CubismRenderer.CubismTextureColor screenColor = overrideMultiplyAndScreenColor.getDrawableScreenColor(index);

        // 渲染器级整体不透明度的**兜底路径**（正常走离屏整幅合成，见
        // app 侧 OverlayAlphaCompositor）：逐 drawable 乘 alpha 会让同一像素上多层
        // 半透明叠加处（嘴部内腔、腮红、阴影）alpha 累积而偏实。仅在 1.0 时零开销；
        // baseColor 是局部/复用对象，安全可改。
        final float rendererOpacity = renderer.getOpacity();
        if (rendererOpacity < 1.0f) {
            baseColor.a *= rendererOpacity;
            if (isPremultipliedAlpha) {
                baseColor.r *= rendererOpacity;
                baseColor.g *= rendererOpacity;
                baseColor.b *= rendererOpacity;
            }
        }

        glUniform4f(
            shaderSet.uniformBaseColorLocation,
            baseColor.r,
            baseColor.g,
            baseColor.b,
            baseColor.a
        );
        glUniform4f(
            shaderSet.uniformMultiplyColorLocation,
            multiplyColor.r,
            multiplyColor.g,
            multiplyColor.b,
            multiplyColor.a
        );
        glUniform4f(
            shaderSet.uniformScreenColorLocation,
            screenColor.r,
            screenColor.g,
            screenColor.b,
            screenColor.a
        );

        glBlendFuncSeparate(srcColor, dstColor, srcAlpha, dstAlpha);
    }

    /**
     * Setup shader program for a clipping mask.
     *
     * @param renderer renderer instance
     * @param model    rendered model
     * @param index    target drawable index
     */
    public void setupShaderProgramForMask(
        CubismRendererAndroid renderer,
        CubismModel model,
        int index
    ) {
        // Blending
        int srcColor = GL_ZERO;
        int dstColor = GL_ONE_MINUS_SRC_COLOR;
        int srcAlpha = GL_ZERO;
        int dstAlpha = GL_ONE_MINUS_SRC_ALPHA;

        CubismShaderSet shaderSet = shaderSets.get(CubismShaderIndexFactors.UtilityShaderType.SETUP_MASK.index);

        glUseProgram(shaderSet.shaderProgram);

        // テクスチャ設定
        setUpTexture(renderer, model, index, shaderSet);

        // 頂点属性設定
        setVertexAttributes(renderer, model, index, shaderSet);

        // 使用するカラーチャンネルを設定
        setColorChannelUniformVariables(
            shaderSet,
            renderer.getClippingContextBufferForMask()
        );

        glUniformMatrix4fv(
            shaderSet.uniformClipMatrixLocation,
            1,
            false,
            renderer.getClippingContextBufferForMask().matrixForMask.getArray(),
            0
        );

        csmRectF rect = renderer.getClippingContextBufferForMask().layoutBounds;
        CubismRenderer.CubismTextureColor baseColor = reusableBaseColor;
        baseColor.r = rect.getX() * 2.0f - 1.0f;
        baseColor.g = rect.getY() * 2.0f - 1.0f;
        baseColor.b = rect.getRight() * 2.0f - 1.0f;
        baseColor.a = rect.getBottom() * 2.0f - 1.0f;

        glUniform4f(
            shaderSet.uniformBaseColorLocation,
            baseColor.r,
            baseColor.g,
            baseColor.b,
            baseColor.a
        );

        glBlendFuncSeparate(srcColor, dstColor, srcAlpha, dstAlpha);
    }

    /**
     * Setup shader program for offscreen render target.
     *
     * @param renderer renderer instance
     */
    public void setupShaderProgramForOffscreenRenderTarget(CubismRendererAndroid renderer) {
        int texture = renderer.copyOffscreenRenderTarget().getColorBuffer()[0];

        // この時点のテクスチャはPMAになっているはずなので計算を行う。
        CubismRenderer.CubismTextureColor baseColor = renderer.getModelColor();
        baseColor.r *= baseColor.a;
        baseColor.g *= baseColor.a;
        baseColor.b *= baseColor.a;
        copyTexture(
            texture,
            GL_ONE,
            GL_ONE_MINUS_SRC_ALPHA,
            GL_ONE,
            GL_ONE_MINUS_SRC_ALPHA,
            baseColor
        );
    }

    public void setupShaderProgramForOffscreen(
        CubismRendererAndroid renderer,
        final CubismModel model,
        final CubismOffscreenRenderTargetAndroid offscreen
    ) {
        // Blending
        int srcColor;
        int dstColor;
        int srcAlpha;
        int dstAlpha;

        int offscreenIndex = offscreen.getOffscreenIndex();

        final boolean isMasked = renderer.getClippingContextBufferForOffscreen() != null;  // この描画オブジェクトはマスク対象か？
        final boolean isInvertedMask = model.getOffscreenInvertedMask(offscreenIndex);
        final csmBlendMode blendMode = model.getOffscreenBlendModeType(offscreenIndex);

        final int shaderIndex = CubismShaderIndexCalculator.calculateShaderIndex(
            blendMode,
            determineMaskState(isMasked, isInvertedMask),
            true    // オフスクリーン描画の場合は常にPremultipliedAlphaとして扱う。
        );
        CubismShaderSet shaderSet = shaderSets.get(shaderIndex);

        boolean isBlendMode = false;
        int blendTexture = 0;

        // 5.3以降の高度なブレンドモードを使用している場合の処理
        if (blendMode.isBlendMode()) {
            isBlendMode = true;
            srcColor = GL_ONE;
            dstColor = GL_ZERO;
            srcAlpha = GL_ONE;
            dstAlpha = GL_ZERO;

            // 以前のオフスクリーンのテクスチャを取得。
            // HACK: ES でCopy用の ShaderProgram に切り替わるのでここで処理を行う。
            blendTexture = offscreen.getOldOffscreen() != null
                ? renderer.copyRenderTarget(offscreen.getOldOffscreen().getRenderTarget()).getColorBuffer()[0]
                : renderer.copyOffscreenRenderTarget().getColorBuffer()[0];
        }
        // 5.2以前のブレンドモードの場合以前の処理を実行する。
        else {
            switch (blendMode.getColorBlendType()) {
                case NORMAL:
                    srcColor = GL_ONE;
                    dstColor = GL_ONE_MINUS_SRC_ALPHA;
                    srcAlpha = GL_ONE;
                    dstAlpha = GL_ONE_MINUS_SRC_ALPHA;
                    break;
                case ADD_COMPATIBLE:
                    srcColor = GL_ONE;
                    dstColor = GL_ONE;
                    srcAlpha = GL_ZERO;
                    dstAlpha = GL_ONE;
                    break;
                case MULTIPLY_COMPATIBLE:
                    srcColor = GL_DST_COLOR;
                    dstColor = GL_ONE_MINUS_SRC_ALPHA;
                    srcAlpha = GL_ZERO;
                    dstAlpha = GL_ONE;
                    break;
                default:
                    throw new IllegalStateException("Unknown blend type: " + blendMode.getColorBlendType());
            }
        }

        glUseProgram(shaderSet.shaderProgram);

        // オフスクリーンのテクスチャ設定
        glActiveTexture(GL_TEXTURE0);
        int offscreenTex = offscreen.getRenderTarget().getColorBuffer()[0];
        glBindTexture(GL_TEXTURE_2D, offscreenTex);
        glUniform1i(shaderSet.samplerTexture0Location, 0);

        // 頂点位置属性の設定
        glEnableVertexAttribArray(shaderSet.attributePositionLocation);
        glVertexAttribPointer(
            shaderSet.attributePositionLocation,
            2,
            GL_FLOAT,
            false,
            Float.SIZE / Byte.SIZE * 2,
            RENDER_TARGET_VERTEX_BUFFER
        );

        // テクスチャ座標属性の設定
        glEnableVertexAttribArray(shaderSet.attributeTexCoordLocation);
        glVertexAttribPointer(
            shaderSet.attributeTexCoordLocation,
            2,
            GL_FLOAT,
            false,
            Float.SIZE / Byte.SIZE * 2,
            RENDER_TARGET_REVERSE_UV_BUFFER
        );

        if (isMasked) {
            glActiveTexture(GL_TEXTURE1);

            // FrameBufferに描かれたテクスチャ
            int tex = renderer.getOffscreenMaskBuffer(renderer.getClippingContextBufferForOffscreen().bufferIndex).getColorBuffer()[0];
            glBindTexture(GL_TEXTURE_2D, tex);
            glUniform1i(shaderSet.samplerTexture1Location, 1);

            // View座標をClippingContextの座標に変換するための行列を設定
            glUniformMatrix4fv(
                shaderSet.uniformClipMatrixLocation,
                1,
                false,
                renderer.getClippingContextBufferForOffscreen().matrixForDraw.getArray(),
                0
            );

            // 使用するカラーチャンネルを設定
            setColorChannelUniformVariables(shaderSet, renderer.getClippingContextBufferForOffscreen());
        }

        // ブレンド設定
        if (isBlendMode) {
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, blendTexture);
            glUniform1i(shaderSet.samplerBlendTextureLocation, 2);
        }

        // 座標変換
        CubismMatrix44 mvpMatrix = reusableMatrix;
        mvpMatrix.loadIdentity();
        glUniformMatrix4fv(
            shaderSet.uniformMatrixLocation,
            1,
            false,
            mvpMatrix.getArray(),
            0
        );

        // ユニフォーム変数設定
        float offscreenOpacity = model.getOffscreenOpacity(offscreenIndex);

        // PMAなのと不透明度だけを変更したいためすべてOpacityで初期化
        CubismRenderer.CubismTextureColor baseColor = reusableBaseColor;

        // 初期化
        baseColor.r = offscreenOpacity;
        baseColor.g = offscreenOpacity;
        baseColor.b = offscreenOpacity;
        baseColor.a = offscreenOpacity;

        final CubismModelMultiplyAndScreenColor overrideMultiplyAndScreenColor = model.getOverrideMultiplyAndScreenColor();
        CubismRenderer.CubismTextureColor multiplyColor = overrideMultiplyAndScreenColor.getOffscreenMultiplyColor(offscreenIndex);
        CubismRenderer.CubismTextureColor screenColor = overrideMultiplyAndScreenColor.getOffscreenScreenColor(offscreenIndex);

        setColorUniformVariables(renderer, model, offscreenIndex, shaderSet, baseColor, multiplyColor, screenColor);

        glBlendFuncSeparate(srcColor, dstColor, srcAlpha, dstAlpha);
    }

    /**
     * Copy the render target using a shader.
     *
     * @param texture texture
     */
    public void copyTexture(int texture) {
        CubismRenderer.CubismTextureColor baseColor = reusableBaseColor;
        // 初期化して渡す。
        baseColor.r = 1.0f;
        baseColor.g = 1.0f;
        baseColor.b = 1.0f;
        baseColor.a = 1.0f;

        copyTexture(
            texture,
            GL_ONE,
            GL_ZERO,
            GL_ONE,
            GL_ZERO,
            baseColor
        );
    }

    /**
     * Copy the render target using a shader.
     *
     * @param texture   texture
     * @param srcColor  source color blend factor
     * @param dstColor  destination color blend factor
     * @param srcAlpha  source alpha blend factor
     * @param dstAlpha  destination alpha blend factor
     * @param baseColor base color blend factor
     */
    public void copyTexture(
        int texture,
        int srcColor,
        int dstColor,
        int srcAlpha,
        int dstAlpha,
        CubismRenderer.CubismTextureColor baseColor
    ) {
        CubismShaderSet shaderSet = shaderSets.get(CubismShaderIndexFactors.UtilityShaderType.COPY.index);
        glUseProgram(shaderSet.shaderProgram);

        // オフスクリーンの内容を設定
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(shaderSet.samplerTexture0Location, 0);

        // 頂点位置属性の設定
        glEnableVertexAttribArray(shaderSet.attributePositionLocation);
        glVertexAttribPointer(
            shaderSet.attributePositionLocation,
            2,
            GL_FLOAT,
            false,
            Float.SIZE / Byte.SIZE * 2,
            RENDER_TARGET_VERTEX_BUFFER
        );

        // テクスチャ座標属性の設定
        glEnableVertexAttribArray(shaderSet.attributeTexCoordLocation);
        glVertexAttribPointer(
            shaderSet.attributeTexCoordLocation,
            2,
            GL_FLOAT,
            false,
            Float.SIZE / Byte.SIZE * 2,
            RENDER_TARGET_UV_BUFFER
        );

        // ベースカラーの設定
        glUniform4f(
            shaderSet.uniformBaseColorLocation,
            baseColor.r,
            baseColor.g,
            baseColor.b,
            baseColor.a
        );

        glBlendFuncSeparate(srcColor, dstColor, srcAlpha, dstAlpha);
    }

    /**
     * Base path for shader files used in the Android renderer.
     * This path is relative to the Framework's assets folder.
     * <p>
     * NOTE: Uses a unique path to avoid conflicts with the application's assets.
     */
    private static final String SHADER_BASE_PATH = "com/live2d/sdk/cubism/framework/shaders/standardES";

    /**
     * Path to the color blend fragment shader file.
     */
    private static final String COLOR_BLEND_SHADER_PATH = SHADER_BASE_PATH + "/FragShaderSrcColorBlend.frag";

    /**
     * Path to the alpha blend fragment shader file.
     */
    private static final String ALPHA_BLEND_SHADER_PATH = SHADER_BASE_PATH + "/FragShaderSrcAlphaBlend.frag";

    /**
     * Singleton instance.
     */
    private static CubismShaderAndroid s_instance;

    /**
     * Vertex buffer for render target.
     * Four vertex coordinates (bottom-left, bottom-right, top-left, top-right) of a rectangle
     * that covers the entire screen, used during texture copying.
     * Defined in normalized device coordinates (-1.0 to 1.0).
     */
    private static final FloatBuffer RENDER_TARGET_VERTEX_BUFFER = toNativeFloatBuffer(new float[]{
        -1.0f, -1.0f,
        1.0f, -1.0f,
        -1.0f, 1.0f,
        1.0f, 1.0f,
    });

    /**
     * UV buffer for render target.
     * Texture coordinates (bottom-left, bottom-right, top-left, top-right) used during texture copying.
     * Defined in UV coordinate system (0.0 to 1.0).
     */
    private static final FloatBuffer RENDER_TARGET_UV_BUFFER = toNativeFloatBuffer(new float[]{
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f,
    });

    /**
     * Reversed UV buffer for render target.
     * Texture coordinates (top-left, top-right, bottom-left, bottom-right) used during texture copying.
     * Defined in UV coordinate system (0.0 to 1.0).
     */
    private static final FloatBuffer RENDER_TARGET_REVERSE_UV_BUFFER = toNativeFloatBuffer(new float[]{
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f,
    });

    /**
     * Tegra support. Drawing with Extended Method.
     */
    private static boolean EXT_MODE;
    /**
     * Variable for setting the PA of the extension method.
     */
    private static boolean EXT_PA_MODE;

    /**
     * Data class that holds the addresses of shader programs and shader variables
     */
    private static class CubismShaderSet {
        /**
         * address of shader program.
         */
        int shaderProgram;
        /**
         * Address of the variable to be passed to the shader program (Position)
         */
        int attributePositionLocation;
        /**
         * Address of the variable to be passed to the shader program (TexCoord)
         */
        int attributeTexCoordLocation;
        /**
         * Address of the variable to be passed to the shader program (Matrix)
         */
        int uniformMatrixLocation;
        /**
         * Address of the variable to be passed to the shader program (ClipMatrix)
         */
        int uniformClipMatrixLocation;
        /**
         * Address of the variable to be passed to the shader program (Texture0)
         */
        int samplerTexture0Location;
        /**
         * Address of the variable to be passed to the shader program (Texture1)
         */
        int samplerTexture1Location;
        /**
         * Address of the variable to be passed to the shader program(BlendTexture)
         */
        int samplerBlendTextureLocation;
        /**
         * Address of the variable to be passed to the shader program (BaseColor)
         */
        int uniformBaseColorLocation;
        /**
         * Address of the variable to be passed to the shader program (MultiplyColor)
         */
        int uniformMultiplyColorLocation;
        /**
         * Address of the variable to be passed to the shader program (ScreenColor)
         */
        int uniformScreenColorLocation;
        /**
         * Address of the variable to be passed to the shader program (ChannelFlag)
         */
        int uniformChannelFlagLocation;
    }

    /**
     *
     * Convert the given float array to a native byte order FloatBuffer for OpenGL ES API.
     *
     * @param array float array to convert
     * @return converted FloatBuffer(position is set to 0)
     */
    private static FloatBuffer toNativeFloatBuffer(float[] array) {
        FloatBuffer buffer = ByteBuffer
            .allocateDirect(array.length * Float.BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(array);
        buffer.position(0);
        return buffer;
    }

    /**
     * Set the CubismShaderSet.
     *
     * @param shaderSets   shader configuration
     * @param maskType    mask type
     */
    private static void setShaderSet(
        CubismShaderSet shaderSets,
        CubismShaderIndexFactors.MaskType maskType
    ) {
        setShaderSet(shaderSets, maskType, false);
    }

    /**
     * Retrieves and sets the required shader variable locations in the shaderSet based on mask type and blend mode.
     *
     * @param shaderSet   shader configuration
     * @param maskType    mask type
     * @param isBlendMode whether blend mode is active
     * @throws IllegalArgumentException if {@code maskType} is invalid
     */
    private static void setShaderSet(
        CubismShaderSet shaderSet,
        CubismShaderIndexFactors.MaskType maskType,
        boolean isBlendMode
    ) {
        shaderSet.attributePositionLocation = glGetAttribLocation(shaderSet.shaderProgram, "a_position");
        shaderSet.attributeTexCoordLocation = glGetAttribLocation(shaderSet.shaderProgram, "a_texCoord");
        shaderSet.samplerTexture0Location = glGetUniformLocation(shaderSet.shaderProgram, "s_texture0");

        if (isBlendMode) {
            shaderSet.samplerBlendTextureLocation = glGetUniformLocation(shaderSet.shaderProgram, "s_blendTexture");
        }

        shaderSet.uniformMatrixLocation = glGetUniformLocation(shaderSet.shaderProgram, "u_matrix");
        shaderSet.uniformBaseColorLocation = glGetUniformLocation(shaderSet.shaderProgram, "u_baseColor");
        shaderSet.uniformMultiplyColorLocation = glGetUniformLocation(shaderSet.shaderProgram, "u_multiplyColor");
        shaderSet.uniformScreenColorLocation = glGetUniformLocation(shaderSet.shaderProgram, "u_screenColor");

        switch (maskType) {
            case NONE:
                break;
            case MASKED:    // クリッピング
                shaderSet.samplerTexture1Location = glGetUniformLocation(shaderSet.shaderProgram, "s_texture1");
                shaderSet.uniformClipMatrixLocation = glGetUniformLocation(shaderSet.shaderProgram, "u_clipMatrix");
                shaderSet.uniformChannelFlagLocation = glGetUniformLocation(shaderSet.shaderProgram, "u_channelFlag");
                break;
            case MASKED_INVERTED:   // クリッピング・反転
                shaderSet.samplerTexture1Location = glGetUniformLocation(shaderSet.shaderProgram, "s_texture1");
                shaderSet.uniformClipMatrixLocation = glGetUniformLocation(shaderSet.shaderProgram, "u_clipMatrix");
                shaderSet.uniformChannelFlagLocation = glGetUniformLocation(shaderSet.shaderProgram, "u_channelFlag");
                break;
            case PREMULTIPLIED_ALPHA:
                break;
            case MASKED_PREMULTIPLIED_ALPHA:    // クリッピング、PremultipliedAlpha
                shaderSet.samplerTexture1Location = glGetUniformLocation(shaderSet.shaderProgram, "s_texture1");
                shaderSet.uniformClipMatrixLocation = glGetUniformLocation(shaderSet.shaderProgram, "u_clipMatrix");
                shaderSet.uniformChannelFlagLocation = glGetUniformLocation(shaderSet.shaderProgram, "u_channelFlag");
                break;
            case MASKED_INVERTED_PREMULTIPLIED_ALPHA:   // クリッピング・反転、PremultipliedAlpha
                shaderSet.samplerTexture1Location = glGetUniformLocation(shaderSet.shaderProgram, "s_texture1");
                shaderSet.uniformClipMatrixLocation = glGetUniformLocation(shaderSet.shaderProgram, "u_clipMatrix");
                shaderSet.uniformChannelFlagLocation = glGetUniformLocation(shaderSet.shaderProgram, "u_channelFlag");
                break;
            default:
                throw new IllegalArgumentException("Invalid mask type: " + maskType);
        }
    }

    /**
     * Determine the clipping mask state based on mask flags.
     *
     * @param isMasked       whether the clipping mask is active
     * @param isInvertedMask whether the clipping mask is inverted
     * @return clipping mask state(NONE/NORMAL/INVERTED)
     */
    private static CubismShaderIndexFactors.MaskState determineMaskState(
        boolean isMasked,
        boolean isInvertedMask
    ) {
        if (isMasked) {
            if (isInvertedMask) {
                return CubismShaderIndexFactors.MaskState.INVERTED;
            } else {
                return CubismShaderIndexFactors.MaskState.NORMAL;
            }
        } else {
            return CubismShaderIndexFactors.MaskState.NONE;
        }
    }

    /**
     * private constructor.
     */
    private CubismShaderAndroid() {
    }

    /**
     * Release shader programs.
     */
    private void releaseShaderProgram() {
        for (int i = 0; i < shaderSets.size(); i++) {
            if (shaderSets.get(i).shaderProgram != 0) {
                glDeleteProgram(shaderSets.get(i).shaderProgram);
                shaderSets.get(i).shaderProgram = 0;
            }
        }
    }

    /**
     * Release invalidated shader program information. Call this when the GL context has been destroyed.
     */
    public void releaseInvalidShaderProgram() {
        shaderSets.clear();
    }

    /**
     * Initialize and generate shader programs.
     */
    private void generateShaders() {
        if (!shaderSets.isEmpty()) {
            return;
        }

        for (int i = 0; i < CubismShaderIndexConstants.SHADER_COUNT; i++) {
            shaderSets.add(new CubismShaderSet());
        }

        int normalBaseIndex = CubismShaderIndexConstants.NORMAL_OVER_BASE_INDEX;

        if (EXT_MODE) {
            shaderSets.get(CubismShaderIndexFactors.UtilityShaderType.COPY.index).shaderProgram = loadShaderProgramFromFile("VertShaderSrcCopy.vert", "FragShaderSrcCopyTegra.frag");
            shaderSets.get(CubismShaderIndexFactors.UtilityShaderType.SETUP_MASK.index).shaderProgram = loadShaderProgramFromFile("VertShaderSrcSetupMask.vert", "FragShaderSrcSetupMaskTegra.frag");

            shaderSets.get(normalBaseIndex).shaderProgram = loadShaderProgramFromFile("VertShaderSrc.vert", "FragShaderSrcTegra.frag");
            shaderSets.get(normalBaseIndex + 1).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMasked.vert", "FragShaderSrcMaskTegra.frag");
            shaderSets.get(normalBaseIndex + 2).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMasked.vert", "FragShaderSrcMaskInvertedTegra.frag");
            shaderSets.get(normalBaseIndex + 3).shaderProgram = loadShaderProgramFromFile("VertShaderSrc.vert", "FragShaderSrcPremultipliedAlphaTegra.frag");
            shaderSets.get(normalBaseIndex + 4).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMasked.vert", "FragShaderSrcMaskPremultipliedAlphaTegra.frag");
            shaderSets.get(normalBaseIndex + 5).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMasked.vert", "FragShaderSrcMaskInvertedPremultipliedAlphaTegra.frag");

            // ブレンドモードの組み合わせ分作成
            {
                int offset = CubismShaderIndexConstants.BLEND_MODE_START_INDEX;

                CubismShaderIndexFactors.ColorBlendMode[] colorBlendModes = CubismShaderIndexFactors.ColorBlendMode.values();
                CubismShaderIndexFactors.AlphaBlendMode[] alphaBlendModes = CubismShaderIndexFactors.AlphaBlendMode.values();

                for (int i = 0; i < colorBlendModes.length; i++) {
                    // Normal Overはシェーダーを作る必要がないため、1から始める。
                    final boolean isNormal = colorBlendModes[i] == CubismShaderIndexFactors.ColorBlendMode.NORMAL;
                    final int start = isNormal ? 1 : 0;

                    for (int j = start; j < CubismShaderIndexConstants.ALPHA_BLEND_COUNT; j++) {
                        shaderSets.get(offset++).shaderProgram = loadShaderProgramFromFile("VertShaderSrcBlend.vert", "FragShaderSrcBlendTegra.frag", colorBlendModes[i], alphaBlendModes[j]);
                        shaderSets.get(offset++).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMaskedBlend.vert", "FragShaderSrcMaskBlendTegra.frag", colorBlendModes[i], alphaBlendModes[j]);
                        shaderSets.get(offset++).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMaskedBlend.vert", "FragShaderSrcMaskInvertedBlendTegra.frag", colorBlendModes[i], alphaBlendModes[j]);
                        shaderSets.get(offset++).shaderProgram = loadShaderProgramFromFile("VertShaderSrcBlend.vert", "FragShaderSrcPremultipliedAlphaBlendTegra.frag", colorBlendModes[i], alphaBlendModes[j]);
                        shaderSets.get(offset++).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMaskedBlend.vert", "FragShaderSrcMaskPremultipliedAlphaBlendTegra.frag", colorBlendModes[i], alphaBlendModes[j]);
                        shaderSets.get(offset++).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMaskedBlend.vert", "FragShaderSrcMaskInvertedPremultipliedAlphaBlendTegra.frag", colorBlendModes[i], alphaBlendModes[j]);
                    }
                }
            }
        } else {
            shaderSets.get(CubismShaderIndexFactors.UtilityShaderType.COPY.index).shaderProgram = loadShaderProgramFromFile("VertShaderSrcCopy.vert", "FragShaderSrcCopy.frag");
            shaderSets.get(CubismShaderIndexFactors.UtilityShaderType.SETUP_MASK.index).shaderProgram = loadShaderProgramFromFile("VertShaderSrcSetupMask.vert", "FragShaderSrcSetupMask.frag");

            shaderSets.get(normalBaseIndex).shaderProgram = loadShaderProgramFromFile("VertShaderSrc.vert", "FragShaderSrc.frag");
            shaderSets.get(normalBaseIndex + 1).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMasked.vert", "FragShaderSrcMask.frag");
            shaderSets.get(normalBaseIndex + 2).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMasked.vert", "FragShaderSrcMaskInverted.frag");
            shaderSets.get(normalBaseIndex + 3).shaderProgram = loadShaderProgramFromFile("VertShaderSrc.vert", "FragShaderSrcPremultipliedAlpha.frag");
            shaderSets.get(normalBaseIndex + 4).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMasked.vert", "FragShaderSrcMaskPremultipliedAlpha.frag");
            shaderSets.get(normalBaseIndex + 5).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMasked.vert", "FragShaderSrcMaskInvertedPremultipliedAlpha.frag");

            // ブレンドモードの組み合わせ分作成
            {
                int offset = CubismShaderIndexConstants.BLEND_MODE_START_INDEX;

                CubismShaderIndexFactors.ColorBlendMode[] colorBlendModes = CubismShaderIndexFactors.ColorBlendMode.values();
                CubismShaderIndexFactors.AlphaBlendMode[] alphaBlendModes = CubismShaderIndexFactors.AlphaBlendMode.values();

                for (int i = 0; i < colorBlendModes.length; i++) {
                    // Normal Overはシェーダーを作る必要がないため、1から始める。
                    final boolean isNormal = colorBlendModes[i] == CubismShaderIndexFactors.ColorBlendMode.NORMAL;
                    final int start = isNormal ? 1 : 0;

                    for (int j = start; j < CubismShaderIndexConstants.ALPHA_BLEND_COUNT; j++) {
                        shaderSets.get(offset++).shaderProgram = loadShaderProgramFromFile("VertShaderSrcBlend.vert", "FragShaderSrcBlend.frag", colorBlendModes[i], alphaBlendModes[j]);
                        shaderSets.get(offset++).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMaskedBlend.vert", "FragShaderSrcMaskBlend.frag", colorBlendModes[i], alphaBlendModes[j]);
                        shaderSets.get(offset++).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMaskedBlend.vert", "FragShaderSrcMaskInvertedBlend.frag", colorBlendModes[i], alphaBlendModes[j]);
                        shaderSets.get(offset++).shaderProgram = loadShaderProgramFromFile("VertShaderSrcBlend.vert", "FragShaderSrcPremultipliedAlphaBlend.frag", colorBlendModes[i], alphaBlendModes[j]);
                        shaderSets.get(offset++).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMaskedBlend.vert", "FragShaderSrcMaskPremultipliedAlphaBlend.frag", colorBlendModes[i], alphaBlendModes[j]);
                        shaderSets.get(offset++).shaderProgram = loadShaderProgramFromFile("VertShaderSrcMaskedBlend.vert", "FragShaderSrcMaskInvertedPremultipliedAlphaBlend.frag", colorBlendModes[i], alphaBlendModes[j]);
                    }
                }
            }
        }

        // 加算も通常と同じシェーダーを利用する
        int addBaseIndex = CubismShaderIndexConstants.ADD_COMPATIBLE_BASE_INDEX;
        shaderSets.get(addBaseIndex).shaderProgram = shaderSets.get(normalBaseIndex).shaderProgram;
        shaderSets.get(addBaseIndex + 1).shaderProgram = shaderSets.get(normalBaseIndex + 1).shaderProgram;
        shaderSets.get(addBaseIndex + 2).shaderProgram = shaderSets.get(normalBaseIndex + 2).shaderProgram;
        shaderSets.get(addBaseIndex + 3).shaderProgram = shaderSets.get(normalBaseIndex + 3).shaderProgram;
        shaderSets.get(addBaseIndex + 4).shaderProgram = shaderSets.get(normalBaseIndex + 4).shaderProgram;
        shaderSets.get(addBaseIndex + 5).shaderProgram = shaderSets.get(normalBaseIndex + 5).shaderProgram;

        // 乗算も通常と同じシェーダーを利用する
        int multiplyBaseIndex = CubismShaderIndexConstants.MULTIPLY_COMPATIBLE_BASE_INDEX;
        shaderSets.get(multiplyBaseIndex).shaderProgram = shaderSets.get(normalBaseIndex).shaderProgram;
        shaderSets.get(multiplyBaseIndex + 1).shaderProgram = shaderSets.get(normalBaseIndex + 1).shaderProgram;
        shaderSets.get(multiplyBaseIndex + 2).shaderProgram = shaderSets.get(normalBaseIndex + 2).shaderProgram;
        shaderSets.get(multiplyBaseIndex + 3).shaderProgram = shaderSets.get(normalBaseIndex + 3).shaderProgram;
        shaderSets.get(multiplyBaseIndex + 4).shaderProgram = shaderSets.get(normalBaseIndex + 4).shaderProgram;
        shaderSets.get(multiplyBaseIndex + 5).shaderProgram = shaderSets.get(normalBaseIndex + 5).shaderProgram;

        // Copy
        final int copyShaderIndex = CubismShaderIndexFactors.UtilityShaderType.COPY.index;
        shaderSets.get(copyShaderIndex).attributePositionLocation = glGetAttribLocation(shaderSets.get(copyShaderIndex).shaderProgram, "a_position");
        shaderSets.get(copyShaderIndex).attributeTexCoordLocation = glGetAttribLocation(shaderSets.get(copyShaderIndex).shaderProgram, "a_texCoord");
        shaderSets.get(copyShaderIndex).samplerTexture0Location = glGetUniformLocation(shaderSets.get(copyShaderIndex).shaderProgram, "s_texture0");
        shaderSets.get(copyShaderIndex).uniformBaseColorLocation = glGetUniformLocation(shaderSets.get(copyShaderIndex).shaderProgram, "u_baseColor");

        // Setup mask
        final int setupMaskShaderIndex = CubismShaderIndexFactors.UtilityShaderType.SETUP_MASK.index;
        shaderSets.get(setupMaskShaderIndex).attributePositionLocation = glGetAttribLocation(shaderSets.get(setupMaskShaderIndex).shaderProgram, "a_position");
        shaderSets.get(setupMaskShaderIndex).attributeTexCoordLocation = glGetAttribLocation(shaderSets.get(setupMaskShaderIndex).shaderProgram, "a_texCoord");
        shaderSets.get(setupMaskShaderIndex).samplerTexture0Location = glGetUniformLocation(shaderSets.get(setupMaskShaderIndex).shaderProgram, "s_texture0");
        shaderSets.get(setupMaskShaderIndex).uniformClipMatrixLocation = glGetUniformLocation(shaderSets.get(setupMaskShaderIndex).shaderProgram, "u_clipMatrix");
        shaderSets.get(setupMaskShaderIndex).uniformChannelFlagLocation = glGetUniformLocation(shaderSets.get(setupMaskShaderIndex).shaderProgram, "u_channelFlag");
        shaderSets.get(setupMaskShaderIndex).uniformBaseColorLocation = glGetUniformLocation(shaderSets.get(setupMaskShaderIndex).shaderProgram, "u_baseColor");
        shaderSets.get(setupMaskShaderIndex).uniformMultiplyColorLocation = glGetUniformLocation(shaderSets.get(setupMaskShaderIndex).shaderProgram, "u_multiplyColor");
        shaderSets.get(setupMaskShaderIndex).uniformScreenColorLocation = glGetUniformLocation(shaderSets.get(setupMaskShaderIndex).shaderProgram, "u_screenColor");

        // 通常
        setShaderSet(shaderSets.get(normalBaseIndex), CubismShaderIndexFactors.MaskType.NONE);
        // 通常（クリッピング）
        setShaderSet(shaderSets.get(normalBaseIndex + 1), CubismShaderIndexFactors.MaskType.MASKED);
        // 通常（クリッピング・反転）
        setShaderSet(shaderSets.get(normalBaseIndex + 2), CubismShaderIndexFactors.MaskType.MASKED_INVERTED);
        // 通常（PremultipliedAlpha）
        setShaderSet(shaderSets.get(normalBaseIndex + 3), CubismShaderIndexFactors.MaskType.PREMULTIPLIED_ALPHA);
        // 通常（クリッピング、PremultipliedAlpha）
        setShaderSet(shaderSets.get(normalBaseIndex + 4), CubismShaderIndexFactors.MaskType.MASKED_PREMULTIPLIED_ALPHA);
        // 通常（クリッピング・反転、PremultipliedAlpha）
        setShaderSet(shaderSets.get(normalBaseIndex + 5), CubismShaderIndexFactors.MaskType.MASKED_INVERTED_PREMULTIPLIED_ALPHA);

        // 加算
        setShaderSet(shaderSets.get(addBaseIndex), CubismShaderIndexFactors.MaskType.NONE);
        // 加算（クリッピング）
        setShaderSet(shaderSets.get(addBaseIndex + 1), CubismShaderIndexFactors.MaskType.MASKED);
        // 加算（クリッピング・反転）
        setShaderSet(shaderSets.get(addBaseIndex + 2), CubismShaderIndexFactors.MaskType.MASKED_INVERTED);
        // 加算（PremultipliedAlpha）
        setShaderSet(shaderSets.get(addBaseIndex + 3), CubismShaderIndexFactors.MaskType.PREMULTIPLIED_ALPHA);
        // 加算（クリッピング、PremultipliedAlpha）
        setShaderSet(shaderSets.get(addBaseIndex + 4), CubismShaderIndexFactors.MaskType.MASKED_PREMULTIPLIED_ALPHA);
        // 加算（クリッピング・反転、PremultipliedAlpha）
        setShaderSet(shaderSets.get(addBaseIndex + 5), CubismShaderIndexFactors.MaskType.MASKED_INVERTED_PREMULTIPLIED_ALPHA);

        // 乗算
        setShaderSet(shaderSets.get(multiplyBaseIndex), CubismShaderIndexFactors.MaskType.NONE);
        // 乗算（クリッピング）
        setShaderSet(shaderSets.get(multiplyBaseIndex + 1), CubismShaderIndexFactors.MaskType.MASKED);
        // 乗算（クリッピング・反転）
        setShaderSet(shaderSets.get(multiplyBaseIndex + 2), CubismShaderIndexFactors.MaskType.MASKED_INVERTED);
        // 乗算（PremultipliedAlpha）
        setShaderSet(shaderSets.get(multiplyBaseIndex + 3), CubismShaderIndexFactors.MaskType.PREMULTIPLIED_ALPHA);
        // 乗算（クリッピング、PremultipliedAlpha）
        setShaderSet(shaderSets.get(multiplyBaseIndex + 4), CubismShaderIndexFactors.MaskType.MASKED_PREMULTIPLIED_ALPHA);
        // 乗算（クリッピング・反転、PremultipliedAlpha）
        setShaderSet(shaderSets.get(multiplyBaseIndex + 5), CubismShaderIndexFactors.MaskType.MASKED_INVERTED_PREMULTIPLIED_ALPHA);

        // ブレンドモードの組み合わせ分作成
        {
            int offset = CubismShaderIndexConstants.BLEND_MODE_START_INDEX;

            for (int i = 0; i < CubismShaderIndexConstants.COLOR_BLEND_COUNT; i++) {
                // Normal Overはシェーダーを作る必要がないため、1から始める。
                final int start = (i == 0 ? 1 : 0);

                for (int j = start; j < CubismShaderIndexConstants.ALPHA_BLEND_COUNT; j++) {
                    setShaderSet(shaderSets.get(offset++), CubismShaderIndexFactors.MaskType.NONE, true);
                    // クリッピング
                    setShaderSet(shaderSets.get(offset++), CubismShaderIndexFactors.MaskType.MASKED, true);
                    // クリッピング・反転
                    setShaderSet(shaderSets.get(offset++), CubismShaderIndexFactors.MaskType.MASKED_INVERTED, true);
                    // PremultipliedAlpha
                    setShaderSet(shaderSets.get(offset++), CubismShaderIndexFactors.MaskType.PREMULTIPLIED_ALPHA, true);
                    // クリッピング、PremultipliedAlpha
                    setShaderSet(shaderSets.get(offset++), CubismShaderIndexFactors.MaskType.MASKED_PREMULTIPLIED_ALPHA, true);
                    // クリッピング・反転、PremultipliedAlpha
                    setShaderSet(shaderSets.get(offset++), CubismShaderIndexFactors.MaskType.MASKED_INVERTED_PREMULTIPLIED_ALPHA, true);
                }
            }
        }
    }

    /**
     * Load a shader program from files and return the shader object number.
     *
     * @param vertShaderName vertex shader file name
     * @param fragShaderName fragment shader file name
     * @return shader object number
     */
    private int loadShaderProgramFromFile(final String vertShaderName, final String fragShaderName) {
        return loadShaderProgramFromFile(vertShaderName, fragShaderName, null, null);
    }

    /**
     * Load a shader program from files and return the shader object number.
     * Append blend mode definitions to the fragment shader code
     * based on the specified color and alpha blend modes.
     *
     * @param vertShaderName vertex shader file name
     * @param fragShaderName fragment shader file name
     * @param colorBlendMode color blend mode
     * @param alphaBlendMode alpha blend mode
     * @return shader object number
     */
    private int loadShaderProgramFromFile(
        final String vertShaderName,
        final String fragShaderName,
        CubismShaderIndexFactors.ColorBlendMode colorBlendMode,
        CubismShaderIndexFactors.AlphaBlendMode alphaBlendMode
    ) {
        ICubismLoadFileFunction fileLoader = CubismFramework.getLoadFileFunction();

        if (fileLoader == null) {
            cubismLogError("File loader is not set.");
            return 0;
        }

        // ファイルからシェーダーのソースコードを読み込み
        byte[] vertSrc = fileLoader.load(SHADER_BASE_PATH + "/" + vertShaderName);
        if (vertSrc == null) {
            cubismLogError("Failed to load vertex shader.");
            return 0;
        }

        byte[] fragSrc = fileLoader.load(SHADER_BASE_PATH + "/" + fragShaderName);
        if (fragSrc == null) {
            cubismLogError("Failed to load fragment shader.");
            return 0;
        }

        String vertString = new String(vertSrc, StandardCharsets.UTF_8);
        String fragString = new String(fragSrc, StandardCharsets.UTF_8);

        // ブレンドモードの記述の必要があれば追記
        if (colorBlendMode != null) {
            byte[] colorBlendSrc = fileLoader.load(COLOR_BLEND_SHADER_PATH);
            if (colorBlendSrc == null) {
                cubismLogError("Failed to load color blend shader.");
                return 0;
            }

            byte[] alphaBlendSrc = fileLoader.load(ALPHA_BLEND_SHADER_PATH);
            if (alphaBlendSrc == null) {
                cubismLogError("Failed to load alpha blend shader.");
                return 0;
            }

            // ブレンド
            StringBuilder buffer = new StringBuilder();
            buffer.append("\n#define CSM_COLOR_BLEND_MODE ").append(colorBlendMode.offset).append("\n");
            fragString += buffer.toString();
            fragString += new String(colorBlendSrc, StandardCharsets.UTF_8);

            // オーバーラップ
            if (alphaBlendMode != null) {
                buffer = new StringBuilder();
                buffer.append("\n#define CSM_ALPHA_BLEND_MODE ").append(alphaBlendMode.offset).append("\n");
                fragString += buffer.toString();
            } else {
                fragString += "\n#define CSM_ALPHA_BLEND_MODE 0\n";
            }
            fragString += new String(alphaBlendSrc, StandardCharsets.UTF_8);
        }

        // シェーダーオブジェクトを作成
        return loadShaderProgram(vertString, fragString);
    }

    private void setAttribLocation(final int shaderIndex) {
        CubismShaderSet shader = shaderSets.get(shaderIndex);

        shader.attributePositionLocation = glGetAttribLocation(shader.shaderProgram, "a_position");
        shader.attributeTexCoordLocation = glGetAttribLocation(shader.shaderProgram, "a_texCoord");
        shader.samplerTexture0Location = glGetUniformLocation(shader.shaderProgram, "s_texture0");
        shader.uniformMatrixLocation = glGetUniformLocation(shader.shaderProgram, "u_matrix");
        shader.uniformBaseColorLocation = glGetUniformLocation(shader.shaderProgram, "u_baseColor");
    }

    private void setAttribLocationClipping(final int shaderIndex) {
        CubismShaderSet shader = shaderSets.get(shaderIndex);

        shader.attributePositionLocation = glGetAttribLocation(shader.shaderProgram, "a_position");
        shader.attributeTexCoordLocation = glGetAttribLocation(shader.shaderProgram, "a_texCoord");
        shader.samplerTexture0Location = glGetUniformLocation(shader.shaderProgram, "s_texture0");
        shader.samplerTexture1Location = glGetUniformLocation(shader.shaderProgram, "s_texture1");
        shader.uniformMatrixLocation = glGetUniformLocation(shader.shaderProgram, "u_matrix");
        shader.uniformClipMatrixLocation = glGetUniformLocation(shader.shaderProgram, "u_clipMatrix");
        shader.uniformChannelFlagLocation = glGetUniformLocation(shader.shaderProgram, "u_channelFlag");
        shader.uniformBaseColorLocation = glGetUniformLocation(shader.shaderProgram, "u_baseColor");
    }

    /**
     * Load shader program.
     *
     * @param vertShaderSrc source of vertex shader
     * @param fragShaderSrc source of fragment shader
     * @return reference value to the shader program
     */
    private int loadShaderProgram(final String vertShaderSrc, final String fragShaderSrc) {
        int[] vertShader = new int[1];
        int[] fragShader = new int[1];

        // Create shader program.
        int shaderProgram = glCreateProgram();

        if (!compileShaderSource(vertShader, GL_VERTEX_SHADER, vertShaderSrc)) {
            cubismLogError("Vertex shader compile error!");
            return 0;
        }

        // Create and compile fragment shader.
        if (!compileShaderSource(fragShader, GL_FRAGMENT_SHADER, fragShaderSrc)) {
            cubismLogError("Fragment shader compile error!");
            return 0;
        }

        // Attach vertex shader to program.
        glAttachShader(shaderProgram, vertShader[0]);
        // Attach fragment shader to program.
        glAttachShader(shaderProgram, fragShader[0]);

        // Link program.
        if (!linkProgram(shaderProgram)) {
            cubismLogError("Failed to link program: " + shaderProgram);

            glDeleteShader(vertShader[0]);
            glDeleteShader(fragShader[0]);
            glDeleteProgram(shaderProgram);

            return 0;
        }

        // Release vertex and fragment shaders.
        glDetachShader(shaderProgram, vertShader[0]);
        glDeleteShader(vertShader[0]);

        glDetachShader(shaderProgram, fragShader[0]);
        glDeleteShader(fragShader[0]);

        return shaderProgram;
    }

    /**
     * Compile shader program.
     *
     * @param shader       reference value to compiled shader program
     * @param shaderType   shader type(Vertex/Fragment)
     * @param shaderSource source of shader program
     * @return If compilling succeeds, return true
     */
    private boolean compileShaderSource(int[] shader, int shaderType, final String shaderSource) {
        if (shader == null || shader.length == 0) {
            return false;
        }

        shader[0] = glCreateShader(shaderType);

        glShaderSource(shader[0], shaderSource);
        glCompileShader(shader[0]);

        int[] logLength = new int[1];
        glGetShaderiv(shader[0], GL_INFO_LOG_LENGTH, IntBuffer.wrap(logLength));
        if (logLength[0] > 0) {
            String log = glGetShaderInfoLog(shader[0]);
            cubismLogError("Shader compile log: " + log);
        }

        int[] status = new int[1];
        glGetShaderiv(shader[0], GL_COMPILE_STATUS, IntBuffer.wrap(status));
        if (status[0] == GL_FALSE) {
            glDeleteShader(shader[0]);
            return false;
        }
        return true;
    }

    /**
     * Link shader program.
     *
     * @param shaderProgram reference value to a shader program to link
     * @return If linking succeeds, return true
     */
    private boolean linkProgram(int shaderProgram) {
        glLinkProgram(shaderProgram);

        int[] logLength = new int[1];
        glGetProgramiv(shaderProgram, GL_INFO_LOG_LENGTH, IntBuffer.wrap(logLength));
        if (logLength[0] > 0) {
            String log = glGetProgramInfoLog(shaderProgram);
            cubismLogError("Program link log: " + log);
        }

        int[] status = new int[1];
        glGetProgramiv(shaderProgram, GL_LINK_STATUS, IntBuffer.wrap(status));
        return status[0] != GL_FALSE;
    }

    /**
     * Validate shader program.
     *
     * @param shaderProgram reference value to shader program to be validated
     * @return If there is no problem, return true
     */
    private boolean validateProgram(int shaderProgram) {
        glValidateProgram(shaderProgram);

        int[] logLength = new int[1];
        glGetProgramiv(shaderProgram, GL_INFO_LOG_LENGTH, IntBuffer.wrap(logLength));
        if (logLength[0] > 0) {
            String log = glGetProgramInfoLog(shaderProgram);
            cubismLogError("Validate program log: " + log);
        }

        int[] status = new int[1];
        glGetProgramiv(shaderProgram, GL_VALIDATE_STATUS, IntBuffer.wrap(status));
        return status[0] != GL_FALSE;
    }

    /**
     * Set up vertex attributes for drawing.
     *
     * @param renderer  renderer instance
     * @param model     rendered model
     * @param index     target drawable index
     * @param shaderSet shader program set
     */
    private void setVertexAttributes(CubismRendererAndroid renderer, CubismModel model, int index, CubismShaderSet shaderSet) {
        CubismDrawableInfoCachesHolder drawableInfoCachesHolder = renderer.getDrawableInfoCachesHolder();

        // 頂点位置属性の設定
        FloatBuffer vertexArrayBuffer = drawableInfoCachesHolder.setUpVertexArray(
            index,
            model.getDrawableVertices(index)
        );
        glEnableVertexAttribArray(shaderSet.attributePositionLocation);
        glVertexAttribPointer(
            shaderSet.attributePositionLocation,
            2,
            GL_FLOAT,
            false,
            Float.SIZE / Byte.SIZE * 2,
            vertexArrayBuffer
        );

        // テクスチャ座標属性の設定
        FloatBuffer uvArrayBuffer = drawableInfoCachesHolder.setUpUvArray(
            index,
            model.getDrawableVertexUvs(index)
        );
        glEnableVertexAttribArray(shaderSet.attributeTexCoordLocation);
        glVertexAttribPointer(
            shaderSet.attributeTexCoordLocation,
            2,
            GL_FLOAT,
            false,
            Float.SIZE / Byte.SIZE * 2,
            uvArrayBuffer
        );
    }

    /**
     * Set up the texture for drawing.
     *
     * @param renderer  renderer instance
     * @param model     rendered model
     * @param index     target drawable index
     * @param shaderSet shader program set
     */
    private void setUpTexture(CubismRendererAndroid renderer, CubismModel model, int index, CubismShaderSet shaderSet) {
        int textureIndex = model.getDrawableTextureIndex(index);
        int textureId = renderer.getBoundTextureId(textureIndex);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glUniform1i(shaderSet.samplerTexture0Location, 0);
    }

    /**
     * 色関連のユニフォーム変数の設定を行う
     *
     * @param renderer      レンダラー
     * @param model         描画対象のモデル
     * @param index         描画対象のメッシュのインデックス
     * @param shaderSet     シェーダープログラムのセット
     * @param baseColor     ベースカラー
     * @param multiplyColor 乗算カラー
     * @param screenColor   スクリーンカラー
     */
    private void setColorUniformVariables(
        CubismRendererAndroid renderer,
        final CubismModel model,
        final int index,
        CubismShaderSet shaderSet,
        CubismRenderer.CubismTextureColor baseColor,
        CubismRenderer.CubismTextureColor multiplyColor,
        CubismRenderer.CubismTextureColor screenColor
    ) {
        glUniform4f(
            shaderSet.uniformBaseColorLocation,
            baseColor.r,
            baseColor.g,
            baseColor.b,
            baseColor.a
        );

        glUniform4f(
            shaderSet.uniformMultiplyColorLocation,
            multiplyColor.r,
            multiplyColor.g,
            multiplyColor.b,
            multiplyColor.a
        );

        glUniform4f(
            shaderSet.uniformScreenColorLocation,
            screenColor.r,
            screenColor.g,
            screenColor.b,
            screenColor.a
        );
    }

    /**
     * カラーチャンネル関連のユニフォーム変数の設定を行う。
     *
     * @param shaderSet     シェーダープログラムのセット
     * @param contextBuffer 描画コンテクスト
     */
    private void setColorChannelUniformVariables(CubismShaderSet shaderSet, CubismClippingContextAndroid contextBuffer) {
        final int channelIndex = contextBuffer.layoutChannelIndex;
        CubismRenderer.CubismTextureColor colorChannel = contextBuffer.getClippingManager().getChannelFlagAsColor(channelIndex);
        glUniform4f(
            shaderSet.uniformChannelFlagLocation,
            colorChannel.r,
            colorChannel.g,
            colorChannel.b,
            colorChannel.a
        );
    }

    /**
     * Variable that holds the loaded shader program.
     */
    private final List<CubismShaderSet> shaderSets = new ArrayList<CubismShaderSet>();

    /**
     * Reusable CubismTextureColor instance for rendering.
     * Optimization to avoid memory allocation per frame.
     */
    private final CubismRenderer.CubismTextureColor reusableBaseColor = new CubismRenderer.CubismTextureColor();

    /**
     * Reusable CubismMatrix44 instance for rendering.
     * Optimization to avoid memory allocation per frame.
     */
    private final CubismMatrix44 reusableMatrix = CubismMatrix44.create();
}
