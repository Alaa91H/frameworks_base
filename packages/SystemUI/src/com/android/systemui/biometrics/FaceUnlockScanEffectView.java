/*
 * SPDX-FileCopyrightText: 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.biometrics;

import static android.hardware.biometrics.BiometricSourceType.FACE;

import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;

import java.util.List;

/**
 * Lightweight camera-centered visualization shown only while face unlock is actively scanning.
 *
 * <p>The effect derives its geometry from the physical display cutout when available. Rendering is
 * entirely vector based and uses a small number of anti-aliased hardware-accelerated strokes; no
 * bitmap frames, blur filters, or background work are used.</p>
 */
public final class FaceUnlockScanEffectView extends View {
    private static final String SETTING_FACE_UNLOCK_SCAN_EFFECT = "face_unlock_scan_effect";

    private static final long SCAN_DURATION_MS = 1450L;
    private static final long FADE_DURATION_MS = 160L;

    private static final int COLOR_CYAN = Color.rgb(91, 229, 255);
    private static final int COLOR_BLUE = Color.rgb(82, 153, 255);
    private static final int COLOR_WHITE = Color.rgb(236, 253, 255);

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Paint mOuterGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMiddleGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mParticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path mLeftWing = new Path();
    private final Path mRightWing = new Path();
    private final PathMeasure mLeftMeasure = new PathMeasure();
    private final PathMeasure mRightMeasure = new PathMeasure();
    private final RectF mCameraBounds = new RectF();
    private final float[] mPosition = new float[2];

    @Nullable private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Nullable private ValueAnimator mAnimator;
    @Nullable private Shader mLeftShader;
    @Nullable private Shader mRightShader;

    private boolean mSettingEnabled;
    private boolean mFaceRunning;
    private boolean mKeyguardVisible;
    private boolean mScanning;
    private boolean mGeometryValid;
    private float mProgress;
    private float mDensity;

    private float mLeftOuterX;
    private float mLeftInnerX;
    private float mRightOuterX;
    private float mRightInnerX;
    private float mCenterY;

    private final ContentObserver mSettingObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            reloadSetting();
        }
    };

    private final KeyguardUpdateMonitorCallback mKeyguardCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onBiometricRunningStateChanged(
                        boolean running, BiometricSourceType biometricSourceType) {
                    if (biometricSourceType == FACE) {
                        mFaceRunning = running;
                        updateScanningState();
                    }
                }

                @Override
                public void onBiometricAuthenticated(
                        int userId,
                        BiometricSourceType biometricSourceType,
                        boolean isStrongBiometric) {
                    if (biometricSourceType == FACE) {
                        finishFaceSession();
                    }
                }

                @Override
                public void onBiometricAuthFailed(BiometricSourceType biometricSourceType) {
                    if (biometricSourceType == FACE) {
                        finishFaceSession();
                    }
                }

                @Override
                public void onBiometricError(
                        int msgId, String errString, BiometricSourceType biometricSourceType) {
                    if (biometricSourceType == FACE) {
                        finishFaceSession();
                    }
                }

                @Override
                public void onKeyguardVisibilityChanged(boolean visible) {
                    mKeyguardVisible = visible;
                    updateScanningState();
                }

                @Override
                public void onUserSwitchComplete(int userId) {
                    if (mKeyguardUpdateMonitor != null) {
                        mFaceRunning = mKeyguardUpdateMonitor.isFaceAuthOrDetectionRunning();
                    }
                    reloadSetting();
                }
            };

    public FaceUnlockScanEffectView(Context context) {
        this(context, null);
    }

    public FaceUnlockScanEffectView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FaceUnlockScanEffectView(
            Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = getResources().getDisplayMetrics().density;

        configureStroke(mOuterGlowPaint, 10f, 72);
        configureStroke(mMiddleGlowPaint, 4.5f, 178);
        configureStroke(mCorePaint, 1.65f, 255);
        configureStroke(mRailPaint, 2f, 220);
        mParticlePaint.setStyle(Paint.Style.FILL);

        setVisibility(GONE);
        setAlpha(0f);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void configureStroke(Paint paint, float widthDp, int alpha) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(widthDp));
        paint.setAlpha(alpha);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        final ContentResolver resolver = getContext().getContentResolver();
        resolver.registerContentObserver(
                Settings.System.getUriFor(SETTING_FACE_UNLOCK_SCAN_EFFECT),
                false,
                mSettingObserver,
                UserHandle.USER_ALL);

        mKeyguardUpdateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
        if (mKeyguardUpdateMonitor != null) {
            mKeyguardVisible = mKeyguardUpdateMonitor.isKeyguardVisible();
            mFaceRunning = mKeyguardUpdateMonitor.isFaceAuthOrDetectionRunning();
            mKeyguardUpdateMonitor.registerCallback(mKeyguardCallback);
        }

        reloadSetting();
        requestApplyInsets();
    }

    @Override
    protected void onDetachedFromWindow() {
        getContext().getContentResolver().unregisterContentObserver(mSettingObserver);
        if (mKeyguardUpdateMonitor != null) {
            mKeyguardUpdateMonitor.removeCallback(mKeyguardCallback);
            mKeyguardUpdateMonitor = null;
        }
        stopAnimator();
        mScanning = false;
        super.onDetachedFromWindow();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        updateCameraGeometry(insets);
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateCameraGeometry(getRootWindowInsets());
    }

    private void reloadSetting() {
        mSettingEnabled = Settings.System.getIntForUser(
                getContext().getContentResolver(),
                SETTING_FACE_UNLOCK_SCAN_EFFECT,
                0,
                UserHandle.USER_CURRENT) != 0;
        updateScanningState();
    }

    private void finishFaceSession() {
        mFaceRunning = false;
        updateScanningState();
    }

    private void updateScanningState() {
        final boolean shouldScan = isAttachedToWindow()
                && mSettingEnabled
                && mKeyguardVisible
                && mFaceRunning;

        if (shouldScan == mScanning) {
            return;
        }

        mScanning = shouldScan;
        if (shouldScan) {
            startEffect();
        } else {
            stopEffect();
        }
    }

    private void startEffect() {
        animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(FADE_DURATION_MS).start();

        if (!ValueAnimator.areAnimatorsEnabled()) {
            mProgress = 0.5f;
            invalidate();
            return;
        }

        stopAnimator();
        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(SCAN_DURATION_MS);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mAnimator.addUpdateListener(animation -> {
            mProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        mAnimator.start();
    }

    private void stopEffect() {
        stopAnimator();
        animate().cancel();
        if (getVisibility() != VISIBLE) {
            setVisibility(GONE);
            setAlpha(0f);
            return;
        }
        animate()
                .alpha(0f)
                .setDuration(FADE_DURATION_MS)
                .withEndAction(() -> {
                    if (!mScanning) {
                        setVisibility(GONE);
                    }
                })
                .start();
    }

    private void stopAnimator() {
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator.removeAllUpdateListeners();
            mAnimator = null;
        }
    }

    private void updateCameraGeometry(@Nullable WindowInsets insets) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            mGeometryValid = false;
            return;
        }

        Rect camera = null;
        if (insets != null) {
            final DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                final Rect top = cutout.getBoundingRectTop();
                if (top != null && !top.isEmpty()) {
                    camera = new Rect(top);
                } else {
                    camera = findTopMostCutout(cutout.getBoundingRects());
                }
            }
        }

        if (camera == null || camera.isEmpty()) {
            final float cameraWidth = dp(22f);
            final float cameraHeight = dp(22f);
            final float centerX = getWidth() * 0.5f;
            final float top = dp(5f);
            mCameraBounds.set(
                    centerX - cameraWidth * 0.5f,
                    top,
                    centerX + cameraWidth * 0.5f,
                    top + cameraHeight);
        } else {
            mCameraBounds.set(camera);
        }

        final float sideGap = dp(6f);
        final float screenInset = dp(8f);
        final float targetWingLength = dp(76f);

        mCenterY = mCameraBounds.centerY();
        mLeftInnerX = Math.max(screenInset, mCameraBounds.left - sideGap);
        mRightInnerX = Math.min(getWidth() - screenInset, mCameraBounds.right + sideGap);

        mLeftOuterX = Math.max(screenInset, mLeftInnerX - targetWingLength);
        mRightOuterX = Math.min(getWidth() - screenInset,
                mRightInnerX + targetWingLength);

        buildWing(mLeftWing, mLeftOuterX, mLeftInnerX, mCenterY, true);
        buildWing(mRightWing, mRightOuterX, mRightInnerX, mCenterY, false);
        mLeftMeasure.setPath(mLeftWing, false);
        mRightMeasure.setPath(mRightWing, false);

        mGeometryValid = mLeftMeasure.getLength() >= dp(18f)
                && mRightMeasure.getLength() >= dp(18f);

        if (mGeometryValid) {
            mLeftShader = createWingShader(mLeftOuterX, mLeftInnerX);
            mRightShader = createWingShader(mRightOuterX, mRightInnerX);
        } else {
            mLeftShader = null;
            mRightShader = null;
        }
        invalidate();
    }

    @Nullable
    private Rect findTopMostCutout(List<Rect> bounds) {
        Rect best = null;
        for (Rect rect : bounds) {
            if (rect == null || rect.isEmpty()) {
                continue;
            }
            if (best == null
                    || rect.top < best.top
                    || (rect.top == best.top
                    && Math.abs(rect.centerX() - getWidth() / 2)
                    < Math.abs(best.centerX() - getWidth() / 2))) {
                best = rect;
            }
        }
        return best == null ? null : new Rect(best);
    }

    private Shader createWingShader(float outerX, float innerX) {
        return new LinearGradient(
                outerX,
                mCenterY,
                innerX,
                mCenterY,
                new int[] {
                        Color.TRANSPARENT,
                        withAlpha(COLOR_BLUE, 190),
                        withAlpha(COLOR_CYAN, 255),
                        withAlpha(COLOR_WHITE, 255)
                },
                new float[] {0f, 0.38f, 0.78f, 1f},
                Shader.TileMode.CLAMP);
    }

    private void buildWing(Path path, float outerX, float innerX, float centerY, boolean left) {
        path.reset();

        final float verticalSweep = dp(2.8f);
        final float hook = dp(7f);
        final float control = dp(18f);

        path.moveTo(outerX, centerY);
        if (left) {
            path.cubicTo(
                    outerX + control,
                    centerY - verticalSweep,
                    innerX - hook,
                    centerY + verticalSweep,
                    innerX,
                    centerY);
        } else {
            path.cubicTo(
                    outerX - control,
                    centerY - verticalSweep,
                    innerX + hook,
                    centerY + verticalSweep,
                    innerX,
                    centerY);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mScanning || !mGeometryValid) {
            return;
        }

        final float pulse = 0.68f
                + 0.32f * (float) Math.sin(mProgress * Math.PI * 2.0);

        if (mLeftShader == null || mRightShader == null) {
            return;
        }

        drawWing(canvas, mLeftWing, mLeftShader, pulse);
        drawWing(canvas, mRightWing, mRightShader, pulse);
        drawCameraRails(canvas, pulse);

        drawTracer(canvas, mLeftMeasure, mProgress);
        drawTracer(canvas, mRightMeasure, mProgress);

        final float secondary = (mProgress + 0.52f) % 1f;
        drawTracer(canvas, mLeftMeasure, secondary);
        drawTracer(canvas, mRightMeasure, secondary);
    }

    private void drawWing(Canvas canvas, Path path, Shader shader, float pulse) {
        mOuterGlowPaint.setShader(shader);
        mOuterGlowPaint.setAlpha(Math.round(58f + 34f * pulse));
        canvas.drawPath(path, mOuterGlowPaint);

        mMiddleGlowPaint.setShader(shader);
        mMiddleGlowPaint.setAlpha(Math.round(138f + 58f * pulse));
        canvas.drawPath(path, mMiddleGlowPaint);

        mCorePaint.setShader(shader);
        mCorePaint.setAlpha(Math.round(215f + 40f * pulse));
        canvas.drawPath(path, mCorePaint);

        mOuterGlowPaint.setShader(null);
        mMiddleGlowPaint.setShader(null);
        mCorePaint.setShader(null);
    }

    private void drawCameraRails(Canvas canvas, float pulse) {
        final float halfHeight = Math.max(dp(7f),
                Math.min(dp(12f), mCameraBounds.height() * 0.42f));
        final float railOffset = dp(2.5f);
        final float left = mCameraBounds.left - railOffset;
        final float right = mCameraBounds.right + railOffset;

        mRailPaint.setColor(COLOR_CYAN);
        mRailPaint.setAlpha(Math.round(125f + 105f * pulse));
        mRailPaint.setStrokeWidth(dp(2f));

        canvas.drawLine(left, mCenterY - halfHeight, left, mCenterY + halfHeight, mRailPaint);
        canvas.drawLine(right, mCenterY - halfHeight, right, mCenterY + halfHeight, mRailPaint);

        mRailPaint.setColor(COLOR_WHITE);
        mRailPaint.setAlpha(Math.round(95f + 120f * pulse));
        mRailPaint.setStrokeWidth(dp(0.9f));
        canvas.drawLine(left, mCenterY - halfHeight, left, mCenterY + halfHeight, mRailPaint);
        canvas.drawLine(right, mCenterY - halfHeight, right, mCenterY + halfHeight, mRailPaint);
    }

    private void drawTracer(Canvas canvas, PathMeasure measure, float phase) {
        final float length = measure.getLength();
        if (length <= 0f || !measure.getPosTan(length * phase, mPosition, null)) {
            return;
        }

        final float envelope = 0.55f
                + 0.45f * (float) Math.sin(phase * Math.PI);

        mParticlePaint.setColor(COLOR_CYAN);
        mParticlePaint.setAlpha(Math.round(52f * envelope));
        canvas.drawCircle(mPosition[0], mPosition[1], dp(8f), mParticlePaint);

        mParticlePaint.setColor(COLOR_BLUE);
        mParticlePaint.setAlpha(Math.round(105f * envelope));
        canvas.drawCircle(mPosition[0], mPosition[1], dp(4.5f), mParticlePaint);

        mParticlePaint.setColor(COLOR_WHITE);
        mParticlePaint.setAlpha(Math.round(245f * envelope));
        canvas.drawCircle(mPosition[0], mPosition[1], dp(1.5f), mParticlePaint);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(
                alpha,
                Color.red(color),
                Color.green(color),
                Color.blue(color));
    }

    private float dp(float value) {
        return value * mDensity;
    }
}
