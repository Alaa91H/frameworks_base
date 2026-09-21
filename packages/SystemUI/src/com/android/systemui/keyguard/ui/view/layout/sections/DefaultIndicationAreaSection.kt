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

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardIndicationAreaBinder
import com.android.systemui.keyguard.ui.view.KeyguardIndicationArea
import com.android.systemui.keyguard.ui.viewmodel.KeyguardIndicationAreaViewModel
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.tuner.TunerService
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

class DefaultIndicationAreaSection
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val keyguardIndicationAreaViewModel: KeyguardIndicationAreaViewModel,
    private val indicationController: KeyguardIndicationController,
    private val keyguardBlueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
    private val tunerService: TunerService,
) : KeyguardSection(), TunerService.Tunable {
    private val indicationAreaViewId = R.id.keyguard_indication_area
    private var indicationAreaHandle: DisposableHandle? = null
    private var tunerRegistered = false

    override fun addViews(constraintLayout: ConstraintLayout) {
        val view = KeyguardIndicationArea(context, null)
        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!tunerRegistered) {
            tunerService.addTunable(
                this,
                INDICATION_VERTICAL_OFFSET_KEY,
                INDICATION_HORIZONTAL_INSET_KEY,
            )
            tunerRegistered = true
        }

        indicationAreaHandle =
            KeyguardIndicationAreaBinder.bind(
                constraintLayout.requireViewById(R.id.keyguard_indication_area),
                keyguardIndicationAreaViewModel,
                indicationController,
            )
    }

    override fun onTuningChanged(key: String?, newValue: String?) {
        if (key == INDICATION_VERTICAL_OFFSET_KEY || key == INDICATION_HORIZONTAL_INSET_KEY) {
            keyguardBlueprintInteractor
                .get()
                .refreshBlueprint(IntraBlueprintTransition.Type.DefaultTransition)
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val density = context.resources.displayMetrics.density
        val verticalOffsetDp =
            Settings.Secure.getIntForUser(
                context.contentResolver,
                INDICATION_VERTICAL_OFFSET_KEY,
                0,
                UserHandle.USER_CURRENT,
            ).coerceIn(MIN_VERTICAL_OFFSET_DP, MAX_VERTICAL_OFFSET_DP)
        val horizontalInsetDp =
            Settings.Secure.getIntForUser(
                context.contentResolver,
                INDICATION_HORIZONTAL_INSET_KEY,
                0,
                UserHandle.USER_CURRENT,
            ).coerceIn(0, MAX_HORIZONTAL_INSET_DP)
        val baseBottomMargin =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_indication_margin_bottom)
        val resolvedBottomMargin =
            (baseBottomMargin + (verticalOffsetDp * density).toInt()).coerceAtLeast(0)
        val horizontalInset = (horizontalInsetDp * density).toInt()

        constraintSet.apply {
            constrainWidth(indicationAreaViewId, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(indicationAreaViewId, ViewGroup.LayoutParams.WRAP_CONTENT)
            connect(
                indicationAreaViewId,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM,
                resolvedBottomMargin
            )
            connect(
                indicationAreaViewId,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                horizontalInset
            )
            connect(
                indicationAreaViewId,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
                horizontalInset
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (tunerRegistered) {
            tunerService.removeTunable(this)
            tunerRegistered = false
        }
        indicationAreaHandle?.dispose()
        indicationAreaHandle = null
        constraintLayout.removeView(indicationAreaViewId)
    }

    companion object {
        private const val INDICATION_VERTICAL_OFFSET_KEY =
            "lockscreen_indication_vertical_offset"
        private const val INDICATION_HORIZONTAL_INSET_KEY =
            "lockscreen_indication_horizontal_inset"

        private const val MIN_VERTICAL_OFFSET_DP = -80
        private const val MAX_VERTICAL_OFFSET_DP = 200
        private const val MAX_HORIZONTAL_INSET_DP = 120
    }
}
