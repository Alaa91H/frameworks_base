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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.res.R
import com.android.systemui.shade.LargeScreenHeaderHelper
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.SharedNotificationContainerBinder
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import com.android.systemui.tuner.TunerService
import dagger.Lazy
import javax.inject.Inject

/** Single column format for notifications (default for phones) */
class DefaultNotificationStackScrollLayoutSection
@Inject
constructor(
    @ShadeDisplayAware context: Context,
    notificationPanelView: NotificationPanelView,
    sharedNotificationContainer: SharedNotificationContainer,
    sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    sharedNotificationContainerBinder: SharedNotificationContainerBinder,
    private val largeScreenHeaderHelperLazy: Lazy<LargeScreenHeaderHelper>,
    private val keyguardBlueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
    private val tunerService: TunerService,
) :
    NotificationStackScrollLayoutSection(
        context,
        notificationPanelView,
        sharedNotificationContainer,
        sharedNotificationContainerViewModel,
        sharedNotificationContainerBinder,
    ), TunerService.Tunable {

    private var tunerRegistered = false
    private var registeringTunables = false

    override fun bindData(constraintLayout: ConstraintLayout) {
        super.bindData(constraintLayout)
        if (!tunerRegistered) {
            tunerRegistered = true
            registeringTunables = true
            tunerService.addTunable(
                this,
                NOTIFICATION_TOP_SPACING_KEY,
                NOTIFICATION_HORIZONTAL_INSET_KEY,
            )
            registeringTunables = false
        }
    }

    override fun onTuningChanged(key: String?, newValue: String?) {
        if (!registeringTunables &&
            (key == NOTIFICATION_TOP_SPACING_KEY || key == NOTIFICATION_HORIZONTAL_INSET_KEY)
        ) {
            keyguardBlueprintInteractor
                .get()
                .refreshBlueprint(IntraBlueprintTransition.Type.DefaultTransition)
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            val bottomMargin =
                context.resources.getDimensionPixelSize(R.dimen.keyguard_status_view_bottom_margin)
            val useLargeScreenHeader =
                context.resources.getBoolean(R.bool.config_use_large_screen_shade_header)
            val marginTopLargeScreen =
                largeScreenHeaderHelperLazy.get().getLargeScreenHeaderHeight()
            val topSpacingDp =
                Settings.Secure.getIntForUser(
                    context.contentResolver,
                    NOTIFICATION_TOP_SPACING_KEY,
                    0,
                    UserHandle.USER_CURRENT,
                ).coerceIn(MIN_TOP_SPACING_DP, MAX_TOP_SPACING_DP)
            val horizontalInsetDp =
                Settings.Secure.getIntForUser(
                    context.contentResolver,
                    NOTIFICATION_HORIZONTAL_INSET_KEY,
                    0,
                    UserHandle.USER_CURRENT,
                ).coerceIn(0, MAX_HORIZONTAL_INSET_DP)
            val density = context.resources.displayMetrics.density
            val customTopSpacing = (topSpacingDp * density).toInt()
            val horizontalInset = (horizontalInsetDp * density).toInt()
            val baseTopMargin =
                bottomMargin +
                    if (useLargeScreenHeader) {
                        marginTopLargeScreen
                    } else {
                        0
                    }
            val resolvedTopMargin = (baseTopMargin + customTopSpacing).coerceAtLeast(0)

            connect(
                R.id.nssl_placeholder,
                TOP,
                R.id.smart_space_barrier_bottom,
                BOTTOM,
                resolvedTopMargin,
            )
            connect(R.id.nssl_placeholder, START, PARENT_ID, START, horizontalInset)
            connect(R.id.nssl_placeholder, END, PARENT_ID, END, horizontalInset)

            addNotificationPlaceholderBarrier(this)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (tunerRegistered) {
            tunerService.removeTunable(this)
            tunerRegistered = false
        }
        super.removeViews(constraintLayout)
    }

    companion object {
        private const val NOTIFICATION_TOP_SPACING_KEY =
            "lockscreen_notifications_top_spacing"
        private const val NOTIFICATION_HORIZONTAL_INSET_KEY =
            "lockscreen_notifications_horizontal_inset"

        private const val MIN_TOP_SPACING_DP = -80
        private const val MAX_TOP_SPACING_DP = 200
        private const val MAX_HORIZONTAL_INSET_DP = 120
    }
}
