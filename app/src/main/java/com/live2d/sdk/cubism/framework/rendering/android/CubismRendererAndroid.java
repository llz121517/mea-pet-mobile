/*
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at http://live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

package com.live2d.sdk.cubism.framework.rendering.android;

import android.opengl.GLES11Ext;

import com.live2d.sdk.cubism.framework.math.CubismVector2;
import com.live2d.sdk.cubism.framework.model.CubismModel;
import com.live2d.sdk.cubism.framework.rendering.CubismRenderer;
import com.live2d.sdk.cubism.framework.utils.CubismDebug;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.opengl.GLES20.*;
import static com.live2d.sdk.cubism.framework.CubismFrameworkConfig.CSM_DEBUG;

/**
 * The class that implements drawing instructions for Android.
 */
public class CubismRendererAndroid extends CubismRenderer {
    /**
     * Create the renderer instance for Android platform.
     *
     * @param width buffer width to draw the model
     * @param height buffer height to draw the model
     * @return renderer instance
     */
    public static CubismRenderer create(int width, int height) {
        return new CubismRendererAndroid(width, height);
    }

    /**
     * Release static resources that this renderer keeps.
     */
    public static void staticRelease() {
        CubismRendererAndroid.doStaticRelease();
    }

    /**
     * Tegra processor support.
     * Enable/Disable drawing by extension method.
     *
     * @param extMode   Whether to draw using the extended method
     * @param extPAMode Whether to enable the PA setting for the extended method
     */
    public static void setExtShaderMode(boolean extMode, boolean extPAMode) {
        CubismShaderAndroid.setExtShaderMode(extMode, extPAMode);
        CubismShaderAndroid.deleteInstance();
    }

    /**
     * Android-Tegra support. Reload shader programs.
     */
    public static void reloadShader() {
        CubismShaderAndroid.deleteInstance();
    }

    @Override
    public void initialize(CubismModel model) {
        initialize(model, 1);
    }

    @Override
    public void initialize(CubismModel model, int maskBufferCount) {
        // 頂点情報をキャッシュする。
        drawableInfoCachesHolder = new CubismDrawableInfoCachesHolder(model);

        modelRenderTargets.clear();
        if (model.isBlendModeEnabled()) {
            // オフスクリーンの作成。
            // 添え字 0 は描画先となる。
            // 添え字 1 はTextureBarrierの代替用。
            for (int i = 0; i < 2; i++) {
                CubismRenderTargetAndroid renderTarget = new CubismRenderTargetAndroid();

                renderTarget.createRenderTarget(
                    modelRenderTargetWidth,
                    modelRenderTargetHeight,
                    null
                );
                modelRenderTargets.add(renderTarget);
            }
        }

        if (model.isUsingMasking()) {
            // マスクバッファの枚数として、0または負の値が指定されている場合は強制的に1枚と設定し、警告ログを出力する。
            if (maskBufferCount < 1) {
                maskBufferCount = 1;
                CubismDebug.cubismLogWarning("The number of render textures must be an integer greater than or equal to 1. Set the number of render textures to 1.");
            }

            // Initialize clipping mask and buffer preprocessing method
            drawableClippingManager = new CubismClippingManagerAndroid();
            drawableClippingManager.initialize(
                RendererType.ANDROID,
                model,
                maskBufferCount,
                CubismRenderer.DrawableObjectType.DRAWABLE
            );

            drawableMasks = new CubismRenderTargetAndroid[maskBufferCount];

            for (int i = 0; i < maskBufferCount; i++) {
                CubismRenderTargetAndroid mask = new CubismRenderTargetAndroid();
                mask.createRenderTarget(drawableClippingManager.getClippingMaskBufferSize(), null);

                drawableMasks[i] = mask;
            }
        }

        if (model.isUsingMaskingForOffscreen()) {
            // クリッピングマスク・バッファ前処理方式を初期化
            offscreenClippingManager = new CubismClippingManagerAndroid();
            offscreenClippingManager.initialize(
                RendererType.ANDROID,
                model,
                maskBufferCount,
                CubismRenderer.DrawableObjectType.OFFSCREEN
            );

            offscreenMasks = new CubismRenderTargetAndroid[maskBufferCount];
            for (int i = 0; i < maskBufferCount; i++) {
                CubismRenderTargetAndroid offscreenMask = new CubismRenderTargetAndroid();
                offscreenMask.createRenderTarget(offscreenClippingManager.getClippingMaskBufferSize());
                offscreenMasks[i] = offscreenMask;
            }
        }

        int objectsCount = model.getDrawableCount() + model.getOffscreenCount();

        sortedObjectsIndexList = new int[objectsCount];
        sortedObjectsTypeList = new DrawableObjectType[objectsCount];

        for (int i = 0; i < objectsCount; i++) {
            sortedObjectsTypeList[i] = DrawableObjectType.DRAWABLE;
        }

        final int offscreenCount = model.getOffscreenCount();

        // オフスクリーンの数が0の場合は何もしない。
        if (offscreenCount > 0) {
            offscreenList = new ArrayList<>(offscreenCount);
            for (int offscreenIndex = 0; offscreenIndex < offscreenCount; offscreenIndex++) {
                CubismOffscreenRenderTargetAndroid renderTarget = new CubismOffscreenRenderTargetAndroid();
                renderTarget.setOffscreenIndex(offscreenIndex);
                offscreenList.add(renderTarget);
            }

            // 全てのオフスクリーンを登録し終わってから行う。
            setupParentOffscreens(model, offscreenCount);
        }

        super.initialize(model);

        // シェーダの事前初期化
        CubismShaderAndroid.getInstance();
    }

    /**
     * Sets up the hierarchy of offscreen render targets.
     * <p>
     * This method traverses the model's part hierarchy to build parent-child relationships between offscreens.
     *
     * @param model          the model instance
     * @param offscreenCount the number of offscreens
     */
    public void setupParentOffscreens(final CubismModel model, int offscreenCount) {
        CubismOffscreenRenderTargetAndroid parentOffscreen;

        for (int offscreenIndex = 0; offscreenIndex < offscreenCount; offscreenIndex++) {
            parentOffscreen = null;
            final int ownerIndex = model.getOffscreenOwnerIndices()[offscreenIndex];
            int parentIndex = model.getPartParentPartIndex(ownerIndex);

            // 親のオフスクリーンを探す
            while (parentIndex != CubismModel.CubismNoIndex.PARENT.index) {
                for (int i = 0; i < offscreenCount; i++) {
                    if (model.getOffscreenOwnerIndices()[offscreenList.get(i).getOffscreenIndex()] != parentIndex) {
                        continue; // オフスクリーンのインデックスが親と一致しなければスキップ
                    }

                    parentOffscreen = offscreenList.get(i);
                    break;
                }

                if (parentOffscreen != null) {
                    break; // 親のオフスクリーンが見つかった場合はループを抜ける
                }

                parentIndex = model.getPartParentPartIndex(parentIndex);
            }

            // 親のオフスクリーンを設定
            offscreenList.get(offscreenIndex).setParentPartOffscreen(parentOffscreen);
        }
    }

    @Override
    public void close() {
        super.close();

        if (drawableClippingManager != null) {
            drawableClippingManager.close();
        }
        if (offscreenClippingManager != null) {
            offscreenClippingManager.close();
        }

        for (int i = 0; i < modelRenderTargets.size(); i++) {
            if (modelRenderTargets.get(i).isValid()) {
                modelRenderTargets.get(i).destroyRenderTarget();
            }
        }
        modelRenderTargets.clear();

        if (drawableMasks != null) {
            for (int i = 0; i < drawableMasks.length; i++) {
                if (drawableMasks[i].isValid()) {
                    drawableMasks[i].destroyRenderTarget();
                }
            }
        }
        if (offscreenMasks != null) {
            for (int i = 0; i < offscreenMasks.length; i++) {
                if (offscreenMasks[i].isValid()) {
                    offscreenMasks[i].destroyRenderTarget();
                }
            }
        }

        drawableInfoCachesHolder = null;
    }

    /**
     * Bind processing of OpenGL textures.
     *
     * @param modelTextureIndex number of the model texture to set
     * @param glTextureIndex    number of the OpenGL texture to bind
     */
    public void bindTexture(int modelTextureIndex, int glTextureIndex) {
        textures.put(modelTextureIndex, glTextureIndex);
        areTexturesChanged = true;
    }

    /**
     * Get textures list bound to OpenGL.
     *
     * @return textures list
     */
    public Map<Integer, Integer> getBoundTextures() {
        if (areTexturesChanged) {
            cachedImmutableTextures = Collections.unmodifiableMap(textures);
            areTexturesChanged = false;
        }
        return cachedImmutableTextures;
    }

    /**
     * Set the size of clipping mask buffer for drawable.
     * This method's processing cost is high because the MaskBuffer for the mask is destroyed and recreated.
     *
     * @param width  width of clipping mask buffer size
     * @param height height of clipping mask buffer size
     */
    public void setDrawableClippingMaskBufferSize(final float width, final float height) {
        if (drawableClippingManager == null) {
            return;
        }

        // インスタンス破棄前にレンダーテクスチャの数を保存
        final int renderTextureCount = this.drawableClippingManager.getRenderTextureCount();

        // Destroy and recreate instances to change the size of MaskBuffer
        drawableClippingManager.close();
        drawableClippingManager = new CubismClippingManagerAndroid();
        drawableClippingManager.setClippingMaskBufferSize(width, height);

        drawableClippingManager.initialize(
            RendererType.ANDROID,
            getModel(),
            renderTextureCount,
            CubismRenderer.DrawableObjectType.DRAWABLE
        );
    }

    /**
     * Set the size of clipping mask buffer for offscreen.
     * This method's processing cost is high because the MaskBuffer for the mask is destroyed and recreated.
     *
     * @param width  width of clipping mask buffer size
     * @param height height of clipping mask buffer size
     */
    public void setOffscreenClippingMaskBufferSize(final float width, final float height) {
        if (offscreenClippingManager == null) {
            return;
        }

        // インスタンス破棄前にレンダーテクスチャの数を保存
        final int renderTextureCount = this.offscreenClippingManager.getRenderTextureCount();

        // RenderTargetのサイズを変更するためにインスタンスを破棄・再作成する。
        offscreenClippingManager.close();
        offscreenClippingManager = new CubismClippingManagerAndroid();
        offscreenClippingManager.setClippingMaskBufferSize(width, height);

        offscreenClippingManager.initialize(
            RendererType.ANDROID,
            getModel(),
            renderTextureCount,
            CubismRenderer.DrawableObjectType.OFFSCREEN
        );
    }

    /**
     * Returns the number of render textures for drawing objects.
     *
     * @return number of render textures for drawing objects
     */
    public int getDrawableRenderTextureCount() {
        return drawableClippingManager.getRenderTextureCount();
    }

    /**
     * Returns the number of render textures for offscreens.
     *
     * @return number of render textures for offscreens
     */
    public int getOffscreenRenderTextureCount() {
        return offscreenClippingManager.getRenderTextureCount();
    }

    /**
     * Returns the clipping mask buffer size for drawing objects.
     *
     * @return clipping mask buffer size for drawing objects
     */
    public CubismVector2 getDrawableClippingMaskBufferSize() {
        return drawableClippingManager.getClippingMaskBufferSize();
    }

    /**
     * Returns the clipping mask buffer size for offscreens.
     *
     * @return clipping mask buffer size for offscreens
     */
    public CubismVector2 getOffscreenClippingMaskBufferSize() {
        return offscreenClippingManager.getClippingMaskBufferSize();
    }

    /**
     * Copies an offscreen buffer.
     *
     * @return copied offscreen buffer
     */
    public CubismRenderTargetAndroid copyOffscreenRenderTarget() {
        return copyRenderTarget(modelRenderTargets.get(0));
    }

    /**
     * Copy an arbitrary buffer.
     *
     * @param srcBuffer source buffer to copy
     * @return copied buffer
     */
    public CubismRenderTargetAndroid copyRenderTarget(final CubismRenderTargetAndroid srcBuffer) {
        // オフスクリーンの内容をコピーしてから描画する。
        modelRenderTargets.get(1).beginDraw();

        CubismShaderAndroid.getInstance().copyTexture(srcBuffer.getColorBuffer()[0]);
        glDrawElements(
            GL_TRIANGLES,
            MODEL_RENDER_TARGET_INDEX_BUFFER.capacity(),
            GL_UNSIGNED_SHORT,
            MODEL_RENDER_TARGET_INDEX_BUFFER
        );

        modelRenderTargets.get(1).endDraw();

        return modelRenderTargets.get(1);
    }

    /**
     * Returns the drawable mask buffer at the specified index.
     *
     * @param index the index of the drawable mask buffer to get
     * @return drawable mask buffer corresponding to the specified index
     */
    CubismRenderTargetAndroid getDrawableMaskBuffer(int index) {
        return drawableMasks[index];
    }

    /**
     * Returns the offscreen mask buffer at the specified index.
     *
     * @param index the index of the offscreen mask buffer to get
     * @return offscreen mask buffer corresponding to the specified index
     */
    public CubismRenderTargetAndroid getOffscreenMaskBuffer(int index) {
        return offscreenMasks[index];
    }

    /**
     * Returns the current offscreen frame buffer.
     *
     * @return current offscreen frame buffer
     */
    public CubismOffscreenRenderTargetAndroid getCurrentOffscreen() {
        return currentOffscreen;
    }

    /**
     * Draw the drawing objects (ArtMesh). <br>
     * Both polygon mesh and the texture number is given to this method.
     *
     * @param model number of the drawed texture
     * @param index index of the drawing object
     */
    protected void drawMeshAndroid(
        final CubismModel model,
        final int index
    ) {
        if (!CSM_DEBUG) {
            // If the texture referenced by the model is not bound, skip drawing.
            if (textures.get(model.getDrawableTextureIndex(index)) == null) {
                return;
            }
        }

        // Enabling/disabling culling
        if (isCulling()) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }

        // In Cubism3 OpenGL, CCW becomes surface for both masks and art meshes.
        glFrontFace(GL_CCW);

        // マスク生成時
        if (isGeneratingMask()) {
            CubismShaderAndroid.getInstance().setupShaderProgramForMask(this, model, index);
        } else {
            CubismShaderAndroid.getInstance().setupShaderProgramForDrawable(this, model, index);
        }

        // ポリゴンメッシュを描画する
        glGetIntegerv(GL_CURRENT_PROGRAM, currentProgram, 0);
        if (currentProgram[0] != 0) {
            final int indexCount = model.getDrawableVertexIndexCount(index);
            final ShortBuffer indexArrayBuffer = drawableInfoCachesHolder.setUpIndexArray(
                index,
                model.getDrawableVertexIndices(index)
            );
            glDrawElements(
                GL_TRIANGLES,
                indexCount,
                GL_UNSIGNED_SHORT,
                indexArrayBuffer
            );
        }

        // post-processing
        glUseProgram(0);
        setClippingContextBufferForDrawable(null);
        setClippingContextBufferForMask(null);
    }

    // This is only used by 'drawMeshAndroid' method.
    // Avoid creating a new CubismTextureColor instance.
    private final CubismTextureColor modelColorRGBA = new CubismTextureColor();

    /**
     * Draw offscreens.
     *
     * @param model     model to draw
     * @param offscreen offscreen to draw
     */
    protected void drawOffscreenAndroid(final CubismModel model, CubismOffscreenRenderTargetAndroid offscreen) {
        // 裏面描画の有効・無効
        if (isCulling()) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }

        glFrontFace(GL_CCW);    // Cubism SDK OpenGLはマスク・アートメッシュ共にCCWが表面

        offscreen.getRenderTarget().endDraw();
        currentOffscreen = currentOffscreen.getOldOffscreen();
        currentFBO = offscreen.getRenderTarget().getOldFBO();

        CubismShaderAndroid.getInstance().setupShaderProgramForOffscreen(
            this,
            model,
            offscreen
        );

        // ポリゴンメッシュを描画する。
        glDrawElements(
            GL_TRIANGLES,
            MODEL_RENDER_TARGET_INDEX_BUFFER.capacity(),
            GL_UNSIGNED_SHORT,
            MODEL_RENDER_TARGET_INDEX_BUFFER
        );

        // 後処理
        offscreen.stopUsingRenderTexture();
        glUseProgram(0);
        setClippingContextBufferForOffscreen(null);
        setClippingContextBufferForMask(null);
    }

    @Override
    protected void doDrawModel() {
        int[] lastFBO = new int[1];
        int[] lastViewport = new int[4];

        beforeDrawModelRenderTarget();

        // モデル描画直前のFBOとビューポートを保存。
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, lastFBO, 0);
        glGetIntegerv(GL_VIEWPORT, lastViewport, 0);

        // In the case of clipping mask and buffer preprocessing method
        if (drawableClippingManager != null) {
            preDraw();

            // If offscreen frame buffer size is different from clipping mask buffer size, recreate it.
            for (int i = 0; i < drawableClippingManager.getRenderTextureCount(); i++) {
                CubismRenderTargetAndroid drawableMask = drawableMasks[i];

                if (!drawableMask.isSameSize(drawableClippingManager.getClippingMaskBufferSize())) {
                    drawableMask.createRenderTarget(drawableClippingManager.getClippingMaskBufferSize(), null);
                }
            }

            if (isUsingHighPrecisionMask()) {
                drawableClippingManager.setupMatrixForHighPrecision(getModel(), false, DrawableObjectType.DRAWABLE);
            } else {
                drawableClippingManager.setupClippingContext(
                    getModel(),
                    this,
                    lastFBO,
                    lastViewport,
                    DrawableObjectType.DRAWABLE
                );
            }
        }

        if (offscreenClippingManager != null) {
            preDraw();

            // サイズが違う場合はここで作成しなおし
            for (int i = 0; i < offscreenClippingManager.getRenderTextureCount(); i++) {
                CubismRenderTargetAndroid offscreenMask = offscreenMasks[i];

                if (!offscreenMask.isSameSize(offscreenClippingManager.getClippingMaskBufferSize())) {
                    offscreenMask.createRenderTarget(offscreenClippingManager.getClippingMaskBufferSize(), null);
                }
            }

            if (isUsingHighPrecisionMask()) {
                offscreenClippingManager.setupMatrixForHighPrecision(
                    getModel(),
                    false,
                    DrawableObjectType.OFFSCREEN,
                    getMvpMatrix()
                );
            } else {
                offscreenClippingManager.setupClippingContext(
                    getModel(),
                    this,
                    lastFBO,
                    lastViewport,
                    DrawableObjectType.OFFSCREEN
                );
            }
        }

        // preDraw() method is called twice.
        preDraw();

        // モデルの描画順に従って描画する。
        drawObjectLoop(lastFBO, lastViewport);

        postDraw();

        afterDrawModelRenderTarget();
    }

    /**
     * Loop processing to draw drawing objects (ArtMesh, offscreen).
     *
     * @param lastFBO      frame buffer just before drawing the model
     * @param lastViewport viewport just before drawing the model
     */
    protected void drawObjectLoop(int[] lastFBO, int[] lastViewport) {
        final int drawableCount = getModel().getDrawableCount();
        final int offscreenCount = getModel().getOffscreenCount();
        final int totalCount = drawableCount + offscreenCount;
        final int[] renderOrder = getModel().getRenderOrders();

        currentOffscreen = null;
        currentFBO = lastFBO;
        modelRootFBO = lastFBO;

        // インデックスを描画順でソート
        for (int i = 0; i < totalCount; i++) {
            final int order = renderOrder[i];

            if (i < drawableCount) {
                sortedObjectsIndexList[order] = i;
                sortedObjectsTypeList[order] = DrawableObjectType.DRAWABLE;
            } else if (i < totalCount) {
                sortedObjectsIndexList[order] = i - drawableCount;
                sortedObjectsTypeList[order] = DrawableObjectType.OFFSCREEN;
            }
        }

        // 描画
        for (int i = 0; i < totalCount; i++) {
            final int objectIndex = sortedObjectsIndexList[i];
            final DrawableObjectType objectType = sortedObjectsTypeList[i];

            renderObject(objectIndex, objectType);
        }

        while (currentOffscreen != null) {
            submitDrawToParentOffscreen(currentOffscreen.getOffscreenIndex(), DrawableObjectType.OFFSCREEN);
        }
    }

    /**
     * Renders the object at the specified index.
     * This method invokes the appropriate rendering logic based on the object type.
     *
     * @param objectIndex index of the object to render
     * @param objectType  type of the object to render
     */
    protected void renderObject(int objectIndex, DrawableObjectType objectType) {
        switch (objectType) {
            case DRAWABLE:
                drawDrawable(objectIndex);
                break;
            case OFFSCREEN:
                addOffscreen(objectIndex);
                break;
            default:
                // 不明なタイプはエラーログを出す。
                CubismDebug.cubismLogError("Unknown drawable type: %s", objectType);
                break;
        }
    }

    /**
     * Draws the drawing object(Artmesh) at the specified index.
     *
     * @param drawableIndex index of the drawing object to draw
     */
    protected void drawDrawable(int drawableIndex) {
        // Drawableが表示状態でなければ処理をパスする。
        if (!getModel().getDrawableDynamicFlagIsVisible(drawableIndex)) {
            return;
        }

        submitDrawToParentOffscreen(drawableIndex, DrawableObjectType.DRAWABLE);

        // クリッピングマスク
        CubismClippingContextAndroid clipContext = (drawableClippingManager != null)
            ? drawableClippingManager.getClippingContextListForDraw().get(drawableIndex)
            : null;

        // マスクを描く必要がある
        if (clipContext != null && isUsingHighPrecisionMask()) {
            // 描くことになっていた
            if (clipContext.isUsing) {
                // 生成したRenderTargetと同じサイズでビューポートを設定
                glViewport(
                    0,
                    0,
                    (int) drawableClippingManager.getClippingMaskBufferSize().x,
                    (int) drawableClippingManager.getClippingMaskBufferSize().y
                );

                // バッファをクリアする
                preDraw();

                // マスク描画処理
                // マスク用RenderTextureをactiveにセット
                getDrawableMaskBuffer(clipContext.bufferIndex).beginDraw(currentFBO);

                // マスクをクリアする。
                // 1が無効（描かれない領域）、0が有効（描かれる）領域。（シェーダーでCd*Csで0に近い値をかけてマスクを作る。1をかけると何も起こらない。）
                glClearColor(1.0f, 1.0f, 1.0f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT);
            }

            final int clipDrawCount = clipContext.clippingIdCount;
            for (int index = 0; index < clipDrawCount; index++) {
                final int clipDrawIndex = clipContext.clippingIdList[index];

                // 頂点情報が更新されておらず、信頼性がない場合は描画をパスする
                if (!getModel().getDrawableDynamicFlagVertexPositionsDidChange(clipDrawIndex)) {
                    continue;
                }

                isCulling(getModel().getDrawableCulling(clipDrawIndex));

                // 今回専用の変換を適用して描く
                // チャンネルも切り替える必要がある（A,R,G,B）
                setClippingContextBufferForMask(clipContext);

                drawMeshAndroid(getModel(), clipDrawIndex);
            }

            // --- 後処理 ---
            getDrawableMaskBuffer(clipContext.bufferIndex).endDraw();
            setClippingContextBufferForMask(null);
            glViewport(
                0,
                0,
                modelRenderTargetWidth,
                modelRenderTargetHeight
            );

            preDraw();
        }

        // クリッピングマスクをセットする
        setClippingContextBufferForDrawable(clipContext);

        isCulling(getModel().getDrawableCulling(drawableIndex));

        drawMeshAndroid(getModel(), drawableIndex);
    }

    /**
     * Attempts to propagate the offscreen drawing result to the parent offscreen.
     *
     * @param objectIndex index of the object to be processed
     * @param objectType  type of the object to be processed
     */
    protected void submitDrawToParentOffscreen(int objectIndex, DrawableObjectType objectType) {
        if (currentOffscreen == null ||
            objectIndex == CubismModel.CubismNoIndex.OFFSCREEN.index) {
            return;
        }

        int currentOwnerIndex = getModel().getOffscreenOwnerIndices()[currentOffscreen.getOffscreenIndex()];

        // オーナーが不明な場合は処理を終了
        if (currentOwnerIndex == CubismModel.CubismNoIndex.OFFSCREEN.index) {
            return;
        }

        int targetParentIndex = CubismModel.CubismNoIndex.PARENT.index;

        // 描画オブジェクトのタイプ別に親パーツのインデックスを取得
        switch (objectType) {
            case DRAWABLE:
                targetParentIndex = getModel().getDrawableParentPartIndex(objectIndex);
                break;
            case OFFSCREEN:
                targetParentIndex = getModel().getPartParentPartIndex(getModel().getOffscreenOwnerIndices()[objectIndex]);
                break;
            default:
                // 不明なタイプだった場合は処理を終了
                return;
        }

        // 階層を辿って現在のオフスクリーンのオーナーのパーツがいたら処理を終了する。
        while (targetParentIndex != CubismModel.CubismNoIndex.PARENT.index) {
            // オブジェクトの親が現在のオーナーと同じ場合は処理を終了
            if (targetParentIndex == currentOwnerIndex) {
                return;
            }

            targetParentIndex = getModel().getPartParentPartIndex(targetParentIndex);
        }

        // 呼び出し元の描画オブジェクトは現オフスクリーンの描画対象でない。
        // つまり描画順グループの仕様により、現オフスクリーンの描画対象は全て描画完了しているので
        // 現オフスクリーンを描画する。
        drawOffscreen(currentOffscreen);

        // さらに親のオフスクリーンに伝搬可能なら伝搬する。
        submitDrawToParentOffscreen(objectIndex, objectType);
    }

    /**
     * Adds the drawing object(offscreen) at the specified index.
     *
     * @param offscreenIndex index of the offscreen to add
     */
    protected void addOffscreen(int offscreenIndex) {
        if (currentOffscreen != null && currentOffscreen.getOffscreenIndex() != offscreenIndex) {
            boolean isParent = false;
            int ownerIndex = getModel().getOffscreenOwnerIndices()[offscreenIndex];
            int parentIndex = getModel().getPartParentPartIndex(ownerIndex);

            int currentOffscreenIndex = currentOffscreen.getOffscreenIndex();
            int currentOffscreenOwnerIndex = getModel().getOffscreenOwnerIndices()[currentOffscreenIndex];

            while (parentIndex != CubismModel.CubismNoIndex.PARENT.index) {
                if (parentIndex == currentOffscreenOwnerIndex) {
                    isParent = true;
                    break;
                }
                parentIndex = getModel().getPartParentPartIndex(parentIndex);
            }

            if (!isParent) {
                // 現在のオフスクリーンレンダリングターゲットがあるなら、親に伝搬する。
                submitDrawToParentOffscreen(offscreenIndex, DrawableObjectType.OFFSCREEN);
            }
        }

        CubismOffscreenRenderTargetAndroid offscreen = offscreenList.get(offscreenIndex);
        offscreen.setOffscreenRenderTarget(modelRenderTargetWidth, modelRenderTargetHeight);

        // 以前のオフスクリーンレンダリングターゲットを取得
        CubismOffscreenRenderTargetAndroid oldOffscreen = offscreen.getParentPartOffscreen();

        offscreen.setOldOffscreen(oldOffscreen);

        int[] oldFBO = new int[1];
        if (oldOffscreen != null) {
            oldFBO[0] = oldOffscreen.getRenderTarget().getRenderTexture()[0];
        }

        if (oldFBO[0] == 0) {
            oldFBO[0] = modelRootFBO[0];    // ルートのFBOを使用する。
        }

        // 別バッファに描画を開始
        offscreen.getRenderTarget().beginDraw(oldFBO);
        glViewport(0, 0, modelRenderTargetWidth, modelRenderTargetHeight);
        offscreen.getRenderTarget().clear(0.0f, 0.0f, 0.0f, 0.0f);

        // 現在のオフスクリーンレンダリングターゲットを設定
        currentOffscreen = offscreen;
        currentFBO = offscreen.getRenderTarget().getRenderTexture();
    }

    /**
     * Draws the drawing object(offscreen) at the specified index.
     *
     * @param currentOffscreen the offscreen to draw
     */
    protected void drawOffscreen(CubismOffscreenRenderTargetAndroid currentOffscreen) {
        int offscreenIndex = currentOffscreen.getOffscreenIndex();

        // クリッピングマスク
        CubismClippingContextAndroid clipContext = (offscreenClippingManager != null)
            ? offscreenClippingManager.getClippingContextListForOffscreen().get(offscreenIndex)
            : null;

        // マスクを描く必要がある。
        if (clipContext != null && isUsingHighPrecisionMask()) {
            // 描くことになっていた。
            if (clipContext.isUsing) {
                // 生成したRenderTargetと同じサイズでビューポートを設定
                glViewport(
                    0,
                    0,
                    (int) offscreenClippingManager.getClippingMaskBufferSize().x,
                    (int) offscreenClippingManager.getClippingMaskBufferSize().y
                );

                // バッファをクリアする。
                preDraw();

                // ----- マスク描画処理 ----- //
                // マスク用RenderTargetをactiveにセット
                getOffscreenMaskBuffer(clipContext.bufferIndex).beginDraw(currentFBO);

                // マスクをクリアする。
                // 1が無効（描かれない領域）、0が有効（描かれる）領域。（シェーダーでCd*Csで0に近い値をかけてマスクを作る。1をかけると何も起こらない。）
                glClearColor(1.0f, 1.0f, 1.0f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT);
            }

            final int clipDrawCount = clipContext.clippingIdCount;
            for (int index = 0; index < clipDrawCount; index++) {
                final int clipDrawIndex = clipContext.clippingIdList[index];

                // 頂点情報が更新されておらず、信頼性がない場合は描画をパスする
                if (!getModel().getDrawableDynamicFlagVertexPositionsDidChange(clipDrawIndex)) {
                    continue;
                }

                isCulling(getModel().getDrawableCulling(clipDrawIndex));

                // 今回専用の変換を適用して描く
                // チャンネルも切り替える必要がある（A,R,G,B）
                setClippingContextBufferForMask(clipContext);

                drawMeshAndroid(getModel(), clipDrawIndex);
            }

            // --- 後処理 ---
            getOffscreenMaskBuffer(clipContext.bufferIndex).endDraw();
            setClippingContextBufferForMask(null);
            glViewport(
                0,
                0,
                modelRenderTargetWidth,
                modelRenderTargetHeight
            );

            // バッファをクリアする。
            preDraw();
        }

        // クリッピングマスクをセットする。
        setClippingContextBufferForOffscreen(clipContext);

        isCulling(getModel().getOffscreenCulling(offscreenIndex));

        drawOffscreenAndroid(getModel(), currentOffscreen);
    }

    @Override
    protected void saveProfile() {
        rendererProfile.save();
    }

    @Override
    protected void restoreProfile() {
        rendererProfile.restore();
    }

    @Override
    protected void beforeDrawModelRenderTarget() {
        if (modelRenderTargets.isEmpty()) {
            return;
        }

        // オフスクリーンのバッファのサイズが違う場合は作り直し
        for (int i = 0; i < modelRenderTargets.size(); i++) {
            CubismRenderTargetAndroid renderTarget = modelRenderTargets.get(i);

            if (!renderTarget.isSameSize(modelRenderTargetWidth, modelRenderTargetHeight)) {
                renderTarget.createRenderTarget(
                    modelRenderTargetWidth,
                    modelRenderTargetHeight,
                    null
                );
            }
        }

        // 別バッファに描画を開始
        modelRenderTargets.get(0).beginDraw();
        modelRenderTargets.get(0).clear(0.0f, 0.0f, 0.0f, 0.0f);
    }

    @Override
    protected void afterDrawModelRenderTarget() {
        if (modelRenderTargets.isEmpty()) {
            return;
        }

        // 元のバッファに描画する
        modelRenderTargets.get(0).endDraw();

        CubismShaderAndroid.getInstance().setupShaderProgramForOffscreenRenderTarget(this);

        glDrawElements(
            GL_TRIANGLES,
            MODEL_RENDER_TARGET_INDEX_BUFFER.capacity(),
            GL_UNSIGNED_SHORT,
            MODEL_RENDER_TARGET_INDEX_BUFFER
        );

        glUseProgram(0);
    }

    /**
     * Release OpenGLES2 static shader programs.
     */
    static void doStaticRelease() {
        CubismShaderAndroid.deleteInstance();
    }

    /**
     * Get the clipping context to draw to the mask texture
     *
     * @return the clipping context to draw to the mask texture
     */
    CubismClippingContextAndroid getClippingContextBufferForMask() {
        return clippingContextBufferForMask;
    }

    /**
     * Set the clipping context to draw to the mask texture
     *
     * @param clip clipping context to draw to the mask texture
     */
    void setClippingContextBufferForMask(CubismClippingContextAndroid clip) {
        clippingContextBufferForMask = clip;
    }

    /**
     * Get the clipping context to draw on display
     *
     * @return the clipping context to draw on display
     */
    CubismClippingContextAndroid getClippingContextBufferForDrawable() {
        return clippingContextBufferForDrawable;
    }

    /**
     * Set the clipping context to draw on display
     *
     * @param clip the clipping context to draw on display
     */
    void setClippingContextBufferForDrawable(CubismClippingContextAndroid clip) {
        clippingContextBufferForDrawable = clip;
    }

    /**
     * Returns the clipping context for offscreen rendering.
     *
     * @return clipping context for offscreen rendering
     */
    CubismClippingContextAndroid getClippingContextBufferForOffscreen() {
        return clippingContextBufferForOffscreen;
    }

    /**
     * Sets the clipping context for offscreen rendering.
     *
     * @param clip clipping context for offscreen rendering
     */
    void setClippingContextBufferForOffscreen(CubismClippingContextAndroid clip) {
        clippingContextBufferForOffscreen = clip;
    }

    CubismDrawableInfoCachesHolder getDrawableInfoCachesHolder() {
        return drawableInfoCachesHolder;
    }

    /**
     * Additional proccesing at the start of drawing
     * This method implements the necessary processing for the clipping mask before drawing the model
     */
    void preDraw() {
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_DEPTH_TEST);

        glEnable(GL_BLEND);
        glColorMask(true, true, true, true);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        // If the buffer has been bound before, it needs to be destroyed
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        // Anisotropic filtering. If it is not supported, do not set it
        if (getAnisotropy() >= 1.0f) {
            for (Map.Entry<Integer, Integer> entry : textures.entrySet()) {
                glBindTexture(GL_TEXTURE_2D, entry.getValue());
                glTexParameterf(GL_TEXTURE_2D, GLES11Ext.GL_TEXTURE_MAX_ANISOTROPY_EXT, getAnisotropy());
            }
        }
    }

    /**
     * Additinal processing after drawing is completed.
     */
    void postDraw() {
    }

    /**
     * テクスチャマップにバインドされたテクスチャIDを取得する。
     * バインドされていなければダミーとして-1を返します。
     *
     * @param textureId テクスチャID
     * @return バインドされたテクスチャID
     */
    int getBoundTextureId(int textureId) {
        Integer boundTextureId = textures.get(textureId);
        return boundTextureId == null ? -1 : boundTextureId;
    }

    /**
     * Constructor.
     *
     * @param width  width of the buffer to draw the model
     * @param height height of the buffer to draw the model
     */
    private CubismRendererAndroid(int width, int height) {
        super();

        this.modelRenderTargetWidth = width;
        this.modelRenderTargetHeight = height;
    }

    /**
     * 渲染器级整体不透明度（0.0~1.0）——逐 drawable 乘 alpha 的**兜底路径**。
     *
     * 悬浮窗正常走「离屏整幅合成」（见 app 侧 OverlayAlphaCompositor）：模型按不透明画进
     * FBO 后整幅乘 alpha，避免半透明图层叠加处（嘴部内腔、腮红、阴影）alpha 累积而偏实；
     * 只有离屏目标创建失败时才回退到这里的逐层乘 alpha。
     *
     * 为什么不用 {@code View.setAlpha}：GLSurfaceView 的画面绘制在独立 Surface 上，View 层
     * alpha 只作用于占位层，在部分机型（含大量 Android 10+ 国产 ROM）的合成路径上
     * 不进入 GL 像素，导致调透明度无效。GL 内乘 alpha 对所有机型统一生效。
     *
     * 由悬浮窗服务经 GL 线程写入，默认 1.0（不透明）。
     */
    private volatile float opacity = 1.0f;

    /** 获取渲染器级整体不透明度（0.0~1.0）。 */
    public float getOpacity() {
        return opacity;
    }

    /** 设置渲染器级整体不透明度（0.0~1.0）。 */
    public void setOpacity(float value) {
        this.opacity = Math.max(0.0f, Math.min(1.0f, value));
    }

    /**
     * マスク生成時かどうかを判定する。
     *
     * @return マスク生成時かどうか。生成時ならtrue。
     */
    private boolean isGeneratingMask() {
        return getClippingContextBufferForMask() != null;
    }

    /**
     * Used when copying.
     */
    private static final ShortBuffer MODEL_RENDER_TARGET_INDEX_BUFFER = ShortBuffer.wrap(new short[]{
        0, 1, 2,
        2, 1, 3
    });

    /**
     * Map between the textures referenced by the model and the textures bound by the renderer
     */
    private final Map<Integer, Integer> textures = new HashMap<Integer, Integer>(32);

    private boolean areTexturesChanged = true;

    private Map<Integer, Integer> cachedImmutableTextures;

    /**
     * A array of drawing object indices arranged in drawing order.
     */
    private int[] sortedObjectsIndexList;

    /**
     * The array of drawing object types arranged in drawing order.
     */
    private DrawableObjectType[] sortedObjectsTypeList;

    /**
     * the object which keeps the OpenGL state
     */
    private final CubismRendererProfileAndroid rendererProfile = new CubismRendererProfileAndroid();
    /**
     * Clipping mask for drawable management object.
     */
    private CubismClippingManagerAndroid drawableClippingManager;
    /**
     * Clipping mask for offscreen management object.
     */
    private CubismClippingManagerAndroid offscreenClippingManager;
    /**
     * Clippping context for drawing on mask texture
     */
    private CubismClippingContextAndroid clippingContextBufferForMask;
    /**
     * Clipping context for drawable drawing.
     */
    private CubismClippingContextAndroid clippingContextBufferForDrawable;

    /**
     * Clipping context for offscreen drawing.
     */
    private CubismClippingContextAndroid clippingContextBufferForOffscreen;

    /**
     * Frame buffer to draw the entire model.
     */
    private final List<CubismRenderTargetAndroid> modelRenderTargets = new ArrayList<>();

    /**
     * Frame buffer for drawing mask of drawable.
     */
    private CubismRenderTargetAndroid[] drawableMasks;

    /**
     * Frame buffer for drawing mask of offscreen.
     */
    private CubismRenderTargetAndroid[] offscreenMasks;

    /**
     * The list of model's offscreen.
     */
    private List<CubismOffscreenRenderTargetAndroid> offscreenList = new ArrayList<>();

    /**
     * Current frame buffer object.
     */
    private int[] currentFBO = new int[1];

    /**
     * Current offscreen frame buffer.
     */
    private CubismOffscreenRenderTargetAndroid currentOffscreen;

    /**
     * Root frame buffer for model rendering.
     */
    private int[] modelRootFBO = new int[1];

    /**
     * Buffer for retrieving the current shader program.
     * Defined as a field to avoid allocating a new array every frame.
     */
    private final int[] currentProgram = new int[1];

    /**
     * Drawable情報のキャッシュ変数
     */
    private CubismDrawableInfoCachesHolder drawableInfoCachesHolder;
}
