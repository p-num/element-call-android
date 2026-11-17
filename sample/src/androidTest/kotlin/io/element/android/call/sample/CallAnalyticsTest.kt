/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.Stage
import io.element.android.call.api.ElementCallEndReason
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent
import io.element.android.call.api.analytics.ElementCallAnalyticsOutcome
import io.element.android.call.ui.ElementCallActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Feature-hq 044 R14 on a device: rotation, picture-in-picture and backgrounding recreate the call screen and re-run
 * its permission answer, and none of it reports a second event for the attempt. The rules are the unit tests'.
 */
@RunWith(AndroidJUnit4::class)
class CallAnalyticsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun grantPermissions() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
    }

    @Test
    fun rotationPictureInPictureAndBackgroundingReportNothingTwice() {
        // The launch ends the previous call, whose end may land after this; this attempt is the one attempted from here.
        SampleAnalytics.clear()
        launchSample(SampleRingingFixture.PICKUP, composeRule).use { scenario ->
            waitUntil("the call connects") { SampleAnalytics.events.any { it is ElementCallAnalyticsEvent.CallJoinOutcome } }

            onCallScreen { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitActivity<ElementCallActivity>(Stage.RESUMED)
            onCallScreen { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            awaitActivity<ElementCallActivity>(Stage.RESUMED)

            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            waitUntil("the window opens") { scenario.controller().isInPictureInPicture.value }
            ElementCallActivity.start(context)
            waitUntil("the window closes") { !scenario.controller().isInPictureInPicture.value }
            awaitActivity<ElementCallActivity>(Stage.RESUMED)

            scenario.controller().hangUp()
            waitUntil("the end is reported") { SampleAnalytics.events.any { it is ElementCallAnalyticsEvent.CallParticipationEnded } }

            val attempted = SampleAnalytics.events.filterIsInstance<ElementCallAnalyticsEvent.CallJoinAttempted>()
            check(attempted.size == 1) { "Attempted $attempted" }
            val events = SampleAnalytics.events.filter { it.attemptId == attempted.single().attemptId }
            check(events.map { it.name } == listOf("CallJoinAttempted", "CallJoinOutcome", "CallParticipationEnded")) { "Reported $events" }
            check((events[1] as ElementCallAnalyticsEvent.CallJoinOutcome).outcome == ElementCallAnalyticsOutcome.CONNECTED) { "Reported $events" }
            check((events[2] as ElementCallAnalyticsEvent.CallParticipationEnded).endReason == ElementCallEndReason.LEFT) { "Reported $events" }
        }
    }
}
