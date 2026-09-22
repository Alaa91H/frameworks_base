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
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
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
 * Lightweight electric-ring visualization shown around the front camera while face unlock scans.
 *
 * <p>The effect follows the physical display cutout when available and falls back to a conservative
 * centered camera location otherwise. Rendering is fully vector based: layered sweep-gradient
 * rings, moving highlights, deterministic electric bolts and orbiting particles. No bitmap frames,
 * blur filters, services or background threads are used.</p>
 */
public final class FaceUnlockScanEffectView extends View {
    private static final String SETTING_FACE_UNLOCK_SCAN_EFFECT = "face_unlock_scan_effect";

    private static final long SCAN_DURATION_MS = 1320L;
    private static final long FADE_DURATION_MS = 150L;

    private static final int BOLT_COUNT = 12;
    private static final int PARTICLE_COUNT = 6;

    private static final int COLOR_CYAN = Color.rgb(58, 221, 255);
    private static final int COLOR_BLUE = Color.rgb(42, 122, 255);
    private static final int COLOR_DEEP_BLUE = Color.rgb(22, 74, 226);
    private static final int COLOR_WHITE = Color.rgb(241, 254, 255);

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Paint mOuterGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMiddleGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBoltGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBoltCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mParticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF mCameraBounds = new RectF();
    private final RectF mRingBounds = new RectF();
    private final RectF mAnimatedRingBounds = new RectF();
    private final Matrix mGradientMatrix = new Matrix();
    private final Path mBoltPath = new Path();

    @Nullable private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Nullable private ValueAnimator mAnimator;
    @Nullable private SweepGradient mRingGradient;

    private boolean mSettingEnabled;
    private boolean mFaceRunning;
    private boolean mKeyguardVisible;
    private boolean mDeviceInteractive;
    private boolean mScanning;
    private boolean mGeometryValid;

    private float mProgress;
    private final float mDensity;
    private float mCenterX;
    private float mCenterY;
    private float mBaseRadiusX;
    private float mBaseRadiusY;

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
                        // Some face implementations immediately start another attempt after a
                        // non-match. Re-read the authoritative running state instead of forcing the
                        // effect off and causing a visible flicker between attempts.
                        mHandler.post(() -> {
                            syncFaceRunningState();
                            updateScanningState();
                        });
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
                public void onStartedGoingToSleep(int why) {
                    mDeviceInteractive = false;
                    updateScanningState();
                }

                @Override
                public void onStartedWakingUp() {
                    mDeviceInteractive = true;
                    syncFaceRunningState();
                    updateScanningState();
                }

                @Override
                public void onUserSwitchComplete(int userId) {
                    if (mKeyguardUpdateMonitor != null) {
                        mKeyguardVisible = mKeyguardUpdateMonitor.isKeyguardVisible();
                        mDeviceInteractive = mKeyguardUpdateMonitor.isDeviceInteractive();
                    }
                    syncFaceRunningState();
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

        configureStroke(mOuterGlowPaint, 9.5f);
        configureStroke(mMiddleGlowPaint, 4.4f);
        configureStroke(mCorePaint, 1.45f);
        configureStroke(mSweepPaint, 2.35f);
        configureStroke(mBoltGlowPaint, 3.2f);
        configureStroke(mBoltCorePaint, 0.95f);

        mSweepPaint.setStrokeCap(Paint.Cap.ROUND);
        mBoltGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        mBoltCorePaint.setStrokeCap(Paint.Cap.ROUND);
        mBoltGlowPaint.setColor(COLOR_BLUE);
        mBoltCorePaint.setColor(COLOR_WHITE);
        mParticlePaint.setStyle(Paint.Style.FILL);

        setVisibility(GONE);
        setAlpha(0f);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void configureStroke(Paint paint, float widthDp) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(widthDp));
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
            mDeviceInteractive = mKeyguardUpdateMonitor.isDeviceInteractive();
            syncFaceRunningState();
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
        animate().cancel();
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

    private void syncFaceRunningState() {
        if (mKeyguardUpdateMonitor != null) {
            mFaceRunning = mKeyguardUpdateMonitor.isFaceAuthOrDetectionRunning();
        }
    }

    private void finishFaceSession() {
        mFaceRunning = false;
        updateScanningState();
    }

    private void updateScanningState() {
        final boolean shouldScan = isAttachedToWindow()
                && mSettingEnabled
                && mKeyguardVisible
                && mDeviceInteractive
                && mFaceRunning
                && mGeometryValid;

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
            mProgress = 0.22f;
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
            clearGeometry();
            updateScanningState();
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
            final float cameraWidth = dp(20f);
            final float cameraHeight = dp(20f);
            final float centerX = getWidth() * 0.5f;
            final float top = dp(8f);
            mCameraBounds.set(
                    centerX - cameraWidth * 0.5f,
                    top,
                    centerX + cameraWidth * 0.5f,
                    top + cameraHeight);
        } else {
            mCameraBounds.set(camera);
        }

        final float smallerCameraSide = Math.max(
                dp(1f), Math.min(mCameraBounds.width(), mCameraBounds.height()));
        final float padding = Math.max(dp(5.5f), smallerCameraSide * 0.27f);

        mCenterX = mCameraBounds.centerX();
        mCenterY = mCameraBounds.centerY();

        mRingBounds.set(
                mCameraBounds.left - padding,
                mCameraBounds.top - padding,
                mCameraBounds.right + padding,
                mCameraBounds.bottom + padding);

        mBaseRadiusX = mRingBounds.width() * 0.5f;
        mBaseRadiusY = mRingBounds.height() * 0.5f;

        mGeometryValid = mBaseRadiusX >= dp(7f)
                && mBaseRadiusY >= dp(7f)
                && mCenterX >= 0f
                && mCenterX <= getWidth()
                && mCenterY >= 0f
                && mCenterY <= getHeight();

        if (mGeometryValid) {
            buildRingGradient();
        } else {
            clearGeometry();
        }

        updateScanningState();
        invalidate();
    }

    private void buildRingGradient() {
        mRingGradient = new SweepGradient(
                mCenterX,
                mCenterY,
                new int[] {
                        withAlpha(COLOR_BLUE, 215),
                        COLOR_CYAN,
                        COLOR_WHITE,
                        COLOR_CYAN,
                        withAlpha(COLOR_DEEP_BLUE, 230),
                        COLOR_CYAN,
                        COLOR_WHITE,
                        withAlpha(COLOR_BLUE, 215)
                },
                new float[] {0f, 0.16f, 0.25f, 0.40f, 0.58f, 0.73f, 0.86f, 1f});

        mOuterGlowPaint.setShader(mRingGradient);
        mMiddleGlowPaint.setShader(mRingGradient);
        mCorePaint.setShader(mRingGradient);
    }

    private void clearGeometry() {
        mGeometryValid = false;
        mRingGradient = null;
        mOuterGlowPaint.setShader(null);
        mMiddleGlowPaint.setShader(null);
        mCorePaint.setShader(null);
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
                    && Math.abs(rect.centerX() - getWidth() * 0.5f)
                    < Math.abs(best.centerX() - getWidth() * 0.5f))) {
                best = rect;
            }
        }
        return best == null ? null : new Rect(best);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!mScanning || !mGeometryValid || mRingGradient == null) {
            return;
        }

        final float cycle = (float) (mProgress * Math.PI * 2.0);
        final float pulse = 1f + 0.035f * (float) Math.sin(cycle * 1.15f);
        final float brightness = 0.76f + 0.24f * (float) Math.sin(cycle + 0.7f);

        final float radiusX = mBaseRadiusX * pulse;
        final float radiusY = mBaseRadiusY * pulse;
        mAnimatedRingBounds.set(
                mCenterX - radiusX,
                mCenterY - radiusY,
                mCenterX + radiusX,
                mCenterY + radiusY);

        mGradientMatrix.setRotate(360f * mProgress, mCenterX, mCenterY);
        mRingGradient.setLocalMatrix(mGradientMatrix);

        drawEnergyRing(canvas, brightness);
        drawMovingHighlights(canvas, brightness);
        drawElectricBolts(canvas, pulse);
        drawOrbitingParticles(canvas, pulse);
    }

    private void drawEnergyRing(Canvas canvas, float brightness) {
        mOuterGlowPaint.setAlpha(Math.round(45f + 42f * brightness));
        mMiddleGlowPaint.setAlpha(Math.round(128f + 72f * brightness));
        mCorePaint.setAlpha(Math.round(220f + 35f * brightness));

        canvas.drawOval(mAnimatedRingBounds, mOuterGlowPaint);
        canvas.drawOval(mAnimatedRingBounds, mMiddleGlowPaint);
        canvas.drawOval(mAnimatedRingBounds, mCorePaint);
    }

    private void drawMovingHighlights(Canvas canvas, float brightness) {
        final float start = (mProgress * 360f) - 28f;

        mSweepPaint.setColor(COLOR_WHITE);
        mSweepPaint.setAlpha(Math.round(165f + 82f * brightness));
        mSweepPaint.setStrokeWidth(dp(2.5f));
        canvas.drawArc(mAnimatedRingBounds, start, 74f, false, mSweepPaint);

        mSweepPaint.setColor(COLOR_CYAN);
        mSweepPaint.setAlpha(Math.round(100f + 75f * brightness));
        mSweepPaint.setStrokeWidth(dp(1.45f));
        canvas.drawArc(mAnimatedRingBounds, start + 174f, 48f, false, mSweepPaint);
    }

    private void drawElectricBolts(Canvas canvas, float pulse) {
        final double fullTurn = Math.PI * 2.0;

        for (int i = 0; i < BOLT_COUNT; i++) {
            final float flicker = 0.5f + 0.5f * (float) Math.sin(
                    fullTurn * (mProgress * 2.65f + i * 0.173f));
            if (flicker < 0.24f) {
                continue;
            }

            final double angle = fullTurn * (
                    i / (double) BOLT_COUNT
                    + mProgress * 0.32
                    + 0.016 * Math.sin(fullTurn * (mProgress + i * 0.11)));

            final float cos = (float) Math.cos(angle);
            final float sin = (float) Math.sin(angle);
            final float tangentX = -sin;
            final float tangentY = cos;

            final float startX = mCenterX + mBaseRadiusX * pulse * cos;
            final float startY = mCenterY + mBaseRadiusY * pulse * sin;
            final float length = dp(3.5f + 7.5f * flicker);

            mBoltPath.reset();
            mBoltPath.moveTo(startX, startY);

            for (int segment = 1; segment <= 3; segment++) {
                final float t = segment / 3f;
                final float radialX = startX + cos * length * t;
                final float radialY = startY + sin * length * t;
                final float jitter = dp(1.55f)
                        * (float) Math.sin(
                                i * 2.91f + segment * 1.77f + mProgress * 19.0f)
                        * (1f - t * 0.18f);

                mBoltPath.lineTo(
                        radialX + tangentX * jitter,
                        radialY + tangentY * jitter);
            }

            mBoltGlowPaint.setAlpha(Math.round(48f + 80f * flicker));
            mBoltGlowPaint.setStrokeWidth(dp(3.0f + 0.8f * flicker));
            canvas.drawPath(mBoltPath, mBoltGlowPaint);

            mBoltCorePaint.setColor(flicker > 0.72f ? COLOR_WHITE : COLOR_CYAN);
            mBoltCorePaint.setAlpha(Math.round(145f + 110f * flicker));
            mBoltCorePaint.setStrokeWidth(dp(0.85f + 0.45f * flicker));
            canvas.drawPath(mBoltPath, mBoltCorePaint);
        }
    }

    private void drawOrbitingParticles(Canvas canvas, float pulse) {
        final double fullTurn = Math.PI * 2.0;

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            final float direction = (i & 1) == 0 ? 1f : -0.72f;
            final double angle = fullTurn * (
                    i / (double) PARTICLE_COUNT
                    + mProgress * 0.45f * direction);

            final float cos = (float) Math.cos(angle);
            final float sin = (float) Math.sin(angle);
            final float orbitPadding = dp(1.8f + (i % 3) * 0.65f);

            final float x = mCenterX + (mBaseRadiusX * pulse + orbitPadding) * cos;
            final float y = mCenterY + (mBaseRadiusY * pulse + orbitPadding) * sin;

            final float twinkle = 0.5f + 0.5f * (float) Math.sin(
                    fullTurn * (mProgress * 2.2f + i * 0.21f));

            mParticlePaint.setColor(COLOR_BLUE);
            mParticlePaint.setAlpha(Math.round(32f + 58f * twinkle));
            canvas.drawCircle(x, y, dp(2.8f + 1.2f * twinkle), mParticlePaint);

            mParticlePaint.setColor(COLOR_WHITE);
            mParticlePaint.setAlpha(Math.round(150f + 105f * twinkle));
            canvas.drawCircle(x, y, dp(0.65f + 0.45f * twinkle), mParticlePaint);
        }
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
