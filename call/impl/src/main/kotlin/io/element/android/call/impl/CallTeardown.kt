/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import io.element.android.call.api.rtc.MatrixRtcCall
import io.element.android.call.api.rtc.MatrixRtcCallStatus
import io.element.android.call.api.rtc.MatrixRtcLeaveReason
import io.element.android.call.api.rtc.MatrixRtcMediaSession
import io.element.android.call.api.rtc.MatrixRtcRoom
import io.element.android.call.impl.util.runCatchingExceptions
import timber.log.Timber

/** Each resource is released even when another release fails. */
internal class CallTeardown(
    private val room: MatrixRtcRoom?,
    private val call: MatrixRtcCall?,
    private val media: MatrixRtcMediaSession?,
) {
    val reason = (call?.status?.value as? MatrixRtcCallStatus.Ended)?.reason

    suspend fun close(leave: Boolean): Boolean {
        val disconnected = step("disconnect media") { media?.disconnect()?.getOrThrow() }
        val mediaClosed = step("close media") { media?.close() }
        val departed = if (leave && reason == null) {
            step("leave membership") { call?.leave(MatrixRtcLeaveReason(code = DefaultElementCallController.HANGUP_REASON))?.getOrThrow() }
        } else {
            true
        }
        val callClosed = step("close call") { call?.close() }
        val roomClosed = step("shutdown room") { room?.shutdown() }
        return disconnected && mediaClosed && departed && callClosed && roomClosed
    }

    private suspend fun step(name: String, block: suspend () -> Unit): Boolean = runCatchingExceptions { block() }
        .onFailure { Timber.w(it, "ElementCall: $name failed") }
        .isSuccess
}
