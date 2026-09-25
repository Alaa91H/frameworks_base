/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.res.Resources
import android.view.WindowInsets
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.LEFT
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.RIGHT
import androidx.constraintlayout.widget.ConstraintSet.VISIBILITY_MODE_IGNORE
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordancesCombinedViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordancesCombinedViewModelModule.Companion.LOCKSCREEN_INSTANCE
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.tuner.TunerService
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Named
import kotlin.math.roundToInt

class DefaultShortcutsSection
@Inject
constructor(
    @Main private val resources: Resources,
    @Named(LOCKSCREEN_INSTANCE)
    private val keyguardQuickAffordancesCombinedViewModel:
        KeyguardQuickAffordancesCombinedViewModel,
    private val indicationController: KeyguardIndicationController,
    private val keyguardBlueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
    private val keyguardQuickAffordanceViewBinder: KeyguardQuickAffordanceViewBinder,
    private val keyguardInteractor: KeyguardInteractor,
    private val tunerService: TunerService,
) : BaseShortcutSection(), TunerService.Tunable {

    // Amount to increase the bottom margin by to avoid colliding with inset
    private var safeInsetBottom = 0

    private var shortcutLayout: ConstraintLayout? = null
    private var shortcutScalePercent = DEFAULT_SCALE_PERCENT
    private var verticalOffsetDp = 0
    private var horizontalInsetDp = 0
    private var backgroundOpacityPercent = DEFAULT_BACKGROUND_OPACITY
    private var tunerRegistered = false

    override fun addViews(constraintLayout: ConstraintLayout) {
        shortcutLayout = constraintLayout
        addLeftShortcut(constraintLayout)
        addRightShortcut(constraintLayout)

        if (!tunerRegistered) {
            tunerService.addTunable(
                this,
                SHORTCUT_SCALE_KEY,
                SHORTCUT_VERTICAL_OFFSET_KEY,
                SHORTCUT_HORIZONTAL_INSET_KEY,
                SHORTCUT_BACKGROUND_OPACITY_KEY,
            )
            tunerRegistered = true
        }
        applyShortcutTuning()

        constraintLayout
            .requireViewById<LaunchableImageView>(R.id.start_button)
            .setOnApplyWindowInsetsListener { _, windowInsets ->
                val tempSafeInset = windowInsets?.displayCutout?.safeInsetBottom ?: 0
                if (safeInsetBottom != tempSafeInset) {
                    safeInsetBottom = tempSafeInset
                    keyguardBlueprintInteractor
                        .get()
                        .refreshBlueprint(IntraBlueprintTransition.Type.DefaultTransition)
                    applyShortcutTuning()
                }
                WindowInsets.CONSUMED
            }
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        leftShortcutHandle?.destroy()
        leftShortcutHandle =
            keyguardQuickAffordanceViewBinder.bind(
                constraintLayout.requireViewById(R.id.start_button),
                keyguardQuickAffordancesCombinedViewModel.startButton,
                keyguardQuickAffordancesCombinedViewModel.transitionAlpha,
            ) {
                indicationController.showTransientIndication(it)
            }
        rightShortcutHandle?.destroy()
        rightShortcutHandle =
            keyguardQuickAffordanceViewBinder.bind(
                constraintLayout.requireViewById(R.id.end_button),
                keyguardQuickAffordancesCombinedViewModel.endButton,
                keyguardQuickAffordancesCombinedViewModel.transitionAlpha,
            ) {
                indicationController.showTransientIndication(it)
            }

        applyShortcutTuning()
    }

    override fun onTuningChanged(key: String?, newValue: String?) {
        when (key) {
            SHORTCUT_SCALE_KEY -> {
                shortcutScalePercent =
                    TunerService.parseInteger(newValue, DEFAULT_SCALE_PERCENT)
                        .coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT)
            }
            SHORTCUT_VERTICAL_OFFSET_KEY -> {
                verticalOffsetDp =
                    TunerService.parseInteger(newValue, 0)
                        .coerceIn(MIN_VERTICAL_OFFSET_DP, MAX_VERTICAL_OFFSET_DP)
            }
            SHORTCUT_HORIZONTAL_INSET_KEY -> {
                horizontalInsetDp =
                    TunerService.parseInteger(newValue, 0)
                        .coerceIn(MIN_HORIZONTAL_INSET_DP, MAX_HORIZONTAL_INSET_DP)
            }
            SHORTCUT_BACKGROUND_OPACITY_KEY -> {
                backgroundOpacityPercent =
                    TunerService.parseInteger(newValue, DEFAULT_BACKGROUND_OPACITY)
                        .coerceIn(0, 100)
            }
        }
        applyShortcutTuning()
    }

    private fun applyShortcutTuning() {
        val layout = shortcutLayout ?: return
        val startButton = layout.findViewById<LaunchableImageView?>(R.id.start_button) ?: return
        val endButton = layout.findViewById<LaunchableImageView?>(R.id.end_button) ?: return

        val density = resources.displayMetrics.density
        val scale = shortcutScalePercent / 100f
        val verticalTranslation = -(verticalOffsetDp * density)
        val horizontalTranslation = horizontalInsetDp * density
        val backgroundAlpha =
            (backgroundOpacityPercent / 100f * 255f).roundToInt().coerceIn(0, 255)

        startButton.scaleX = scale
        startButton.scaleY = scale
        endButton.scaleX = scale
        endButton.scaleY = scale

        startButton.translationX = horizontalTranslation
        endButton.translationX = -horizontalTranslation
        startButton.translationY = verticalTranslation
        endButton.translationY = verticalTranslation

        startButton.background?.alpha = backgroundAlpha
        endButton.background?.alpha = backgroundAlpha

        updateShortcutAbsoluteTop(scale, verticalTranslation)
    }

    private fun updateShortcutAbsoluteTop(scale: Float, verticalTranslation: Float) {
        val height = resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height)
        val verticalOffsetMargin =
            resources.getDimensionPixelSize(R.dimen.keyguard_affordance_vertical_offset)
        val baselineTop =
            resources.displayMetrics.heightPixels -
                (verticalOffsetMargin + safeInsetBottom) -
                height
        val scaleAdjustment = (height - (height * scale)) / 2f
        keyguardInteractor.setShortcutAbsoluteTop(
            baselineTop + scaleAdjustment + verticalTranslation
        )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val width = resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_width)
        val height = resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height)
        val horizontalOffsetMargin =
            resources.getDimensionPixelSize(R.dimen.keyguard_affordance_horizontal_offset)
        val verticalOffsetMargin =
            resources.getDimensionPixelSize(R.dimen.keyguard_affordance_vertical_offset)

        constraintSet.apply {
            constrainWidth(R.id.start_button, width)
            constrainHeight(R.id.start_button, height)
            connect(R.id.start_button, LEFT, PARENT_ID, LEFT, horizontalOffsetMargin)
            connect(
                R.id.start_button,
                BOTTOM,
                PARENT_ID,
                BOTTOM,
                verticalOffsetMargin + safeInsetBottom,
            )

            constrainWidth(R.id.end_button, width)
            constrainHeight(R.id.end_button, height)
            connect(R.id.end_button, RIGHT, PARENT_ID, RIGHT, horizontalOffsetMargin)
            connect(
                R.id.end_button,
                BOTTOM,
                PARENT_ID,
                BOTTOM,
                verticalOffsetMargin + safeInsetBottom,
            )

            // The constraint set visibility for start and end button are default visible, set to
            // ignore so the view's own initial visibility (invisible) is used
            setVisibilityMode(R.id.start_button, VISIBILITY_MODE_IGNORE)
            setVisibilityMode(R.id.end_button, VISIBILITY_MODE_IGNORE)
        }

        val shortcutAbsoluteTopInScreen =
            (resources.displayMetrics.heightPixels -
                    (verticalOffsetMargin + safeInsetBottom) -
                    height)
                .toFloat()

        keyguardInteractor.setShortcutAbsoluteTop(shortcutAbsoluteTopInScreen)
        applyShortcutTuning()
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (tunerRegistered) {
            tunerService.removeTunable(this)
            tunerRegistered = false
        }
        shortcutLayout = null
        super.removeViews(constraintLayout)
    }

    companion object {
        private const val SHORTCUT_SCALE_KEY = "lockscreen_shortcut_scale"
        private const val SHORTCUT_VERTICAL_OFFSET_KEY = "lockscreen_shortcut_vertical_offset"
        private const val SHORTCUT_HORIZONTAL_INSET_KEY = "lockscreen_shortcut_horizontal_inset"
        private const val SHORTCUT_BACKGROUND_OPACITY_KEY = "lockscreen_shortcut_background_opacity"

        private const val DEFAULT_SCALE_PERCENT = 100
        private const val MIN_SCALE_PERCENT = 70
        private const val MAX_SCALE_PERCENT = 150
        private const val DEFAULT_BACKGROUND_OPACITY = 100

        private const val MIN_VERTICAL_OFFSET_DP = -100
        private const val MAX_VERTICAL_OFFSET_DP = 200
        private const val MIN_HORIZONTAL_INSET_DP = -80
        private const val MAX_HORIZONTAL_INSET_DP = 160
    }
}
