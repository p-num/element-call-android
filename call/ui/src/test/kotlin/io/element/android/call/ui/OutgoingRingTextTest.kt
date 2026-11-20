/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.call.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcRingPhase
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.test.FakeElementCallController
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class OutgoingRingTextTest : RobolectricTest() {
    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private val callee = UserId("@callee:example.org")
    private val phase = MatrixRtcRingPhase.Ringing(100L, 30_000L, EventId("\$ring"))
    private val calling = ElementCallConnection.Calling(callee, phase)
    private val label = "Waiting for them to answer"

    @Test
    fun `the full screen reads its exact controller and snapshot and announces one persistent subtitle`() = runAndroidComposeUiTest<ComponentActivity> {
        val original = aCallSnapshot(connection = calling, isMaximized = true)
        val controller = FakeElementCallController(original)
        var emptyStage by mutableStateOf(false)
        var hostText by mutableStateOf<String?>(null)
        setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalOutgoingRingText provides { owner, rendered -> hostText.takeIf { owner === controller && rendered === original } },
            ) {
                val state = rememberElementCallScreenState(controller, aNullNavigator())
                ElementCallScreen(if (emptyStage) state.copy(tiles = persistentListOf()) else state)
            }
        }
        onNodeWithText(activity!!.getString(R.string.element_call_calling)).assertIsDisplayed()
        hostText = label
        onNode(polite and hasText(label), useUnmergedTree = true).assertIsDisplayed()
        emptyStage = true
        onAllNodes(polite, useUnmergedTree = true).assertCountEquals(1)
        onNode(polite and hasText(label), useUnmergedTree = true).assertIsDisplayed()
        onAllNodesWithText(activity!!.getString(R.string.element_call_calling)).assertCountEquals(0)
        controller.state.value = original.copy(connection = ElementCallConnection.ConnectingMedia)
        onAllNodesWithText(label).assertCountEquals(0)
        onAllNodes(polite, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the minimized bar reads the rendered ring and clears on pickup or an incoming request`() = runAndroidComposeUiTest<ComponentActivity> {
        val original = aCallSnapshot(connection = calling, isMaximized = false)
        val controller = FakeElementCallController(original)
        setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalOutgoingRingText provides { owner, rendered -> label.takeIf { owner === controller && rendered === controller.state.value } },
            ) {
                ElementCallOverlay(controller) {}
            }
        }
        onNode(polite and hasText(label), useUnmergedTree = true).assertIsDisplayed()
        onAllNodes(polite, useUnmergedTree = true).assertCountEquals(1)
        controller.state.value = original.copy(connection = ElementCallConnection.ConnectingMedia)
        onAllNodesWithText(label).assertCountEquals(0)
        controller.state.value = ElementCallSnapshot(
            callData = original.callData.copy(isStartingCall = false),
            connection = ElementCallConnection.Joining,
            isMaximized = false,
        )
        onNodeWithText(activity!!.getString(R.string.element_call_connecting), useUnmergedTree = true).assertIsDisplayed()
        onAllNodes(polite, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `default and unavailable ring IDs keep the ordinary standalone subtitle`() = runAndroidComposeUiTest<ComponentActivity> {
        val original = aCallSnapshot(connection = calling, isMaximized = false)
        val controller = FakeElementCallController(original)
        var supplyText by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                if (supplyText) {
                    CompositionLocalProvider(LocalOutgoingRingText provides { _, _ -> label }) {
                        ElementCallOverlay(controller) {}
                    }
                } else {
                    ElementCallOverlay(controller) {}
                }
            }
        }
        val ordinary = activity!!.getString(R.string.element_call_calling)
        onNodeWithText(ordinary, useUnmergedTree = true).assertIsDisplayed()
        controller.state.value = original.copy(connection = calling.copy(ring = phase.copy(eventId = null)))
        supplyText = true
        onNodeWithText(ordinary, useUnmergedTree = true).assertIsDisplayed()
        onAllNodes(polite, useUnmergedTree = true).assertCountEquals(0)
    }
}
