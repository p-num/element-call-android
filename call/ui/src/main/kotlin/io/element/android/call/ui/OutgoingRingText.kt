/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcRingPhase

/** The host's localized subtitle for the outgoing ring on this snapshot. Null uses the standard calling text. */
val LocalOutgoingRingText = staticCompositionLocalOf<@Composable (ElementCallController, ElementCallSnapshot) -> String?> { { _, _ -> null } }

@Composable
internal fun ElementCallSnapshot.outgoingRingText(controller: ElementCallController): String? {
    val calling = connection as? ElementCallConnection.Calling ?: return null
    if (!callData.isStartingCall || calling.callee == null || (calling.ring as? MatrixRtcRingPhase.Ringing)?.eventId == null) return null
    return LocalOutgoingRingText.current(controller, this)
}

internal fun Modifier.announceOutgoingRing(text: String?): Modifier =
    if (text == null) this else semantics { liveRegion = LiveRegionMode.Polite }
