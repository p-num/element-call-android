/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.analytics

import io.element.android.call.api.analytics.ElementCallAnalyticsFailureCause
import io.element.android.call.api.analytics.ElementCallAnalyticsFailureStage
import io.element.android.call.api.analytics.ElementCallAnalyticsFailureStep
import io.element.android.call.api.rtc.MatrixRtcConnectionLossReason
import io.element.android.call.api.rtc.MatrixRtcJoinException

/**
 * What a failure is, for analytics (feature-hq 044 C7): said by the site that fails, never recovered from its message.
 * [stage] and [step] are reported only for a failure while joining; [errcode] only for [ElementCallAnalyticsFailureCause.JOIN_REFUSED].
 */
internal data class FailureReport(
    val cause: ElementCallAnalyticsFailureCause,
    val stage: ElementCallAnalyticsFailureStage,
    val step: ElementCallAnalyticsFailureStep,
    val errcode: String? = null,
) {
    companion object {
        /** The room did not open, its seeding timeout included. */
        val ROOM_NOT_OPENED = homeserver(ElementCallAnalyticsFailureCause.OTHER)

        /** The media session did not attach; the core says only `Transport(text)`. */
        val MEDIA_NOT_ATTACHED = media(ElementCallAnalyticsFailureCause.OTHER)

        /** Our microphone did not publish, to the media server we are connected to (R16, C6). */
        val MICROPHONE_NOT_PUBLISHED = FailureReport(
            ElementCallAnalyticsFailureCause.MICROPHONE_FAILED,
            ElementCallAnalyticsFailureStage.MEDIA,
            ElementCallAnalyticsFailureStep.MEDIA_CONNECTION,
        )

        /**
         * A join the core gave up on. A timeout or a refusal comes from transport discovery or the membership send, and
         * nothing says which (R23); a closed slot is checked as the membership is sent (R16).
         */
        fun joinFailed(error: Throwable): FailureReport = when (error) {
            is MatrixRtcJoinException.TimedOut -> homeserver(ElementCallAnalyticsFailureCause.JOIN_TIMED_OUT)
            is MatrixRtcJoinException.Refused -> homeserver(ElementCallAnalyticsFailureCause.JOIN_REFUSED).copy(errcode = error.errcode)
            is MatrixRtcJoinException.SlotClosed -> homeserver(ElementCallAnalyticsFailureCause.SLOT_CLOSED, ElementCallAnalyticsFailureStep.MEMBERSHIP)
            is MatrixRtcJoinException.NoLiveKitTransport ->
                homeserver(ElementCallAnalyticsFailureCause.NO_MEDIA_SERVER, ElementCallAnalyticsFailureStep.TRANSPORT_DISCOVERY)
            is MatrixRtcJoinException.TokenEndpointUnsupported -> TOKEN_ENDPOINT_UNSUPPORTED
            else -> homeserver(ElementCallAnalyticsFailureCause.OTHER)
        }

        /** Our media server given up. Nothing says whether the token or the connection failed, so the step is unknown (R23). */
        fun connectionClosed(reason: MatrixRtcConnectionLossReason) = when (reason) {
            MatrixRtcConnectionLossReason.NEVER_CONNECTED -> media(ElementCallAnalyticsFailureCause.CONNECTION_LOST_NEVER_CONNECTED)
            MatrixRtcConnectionLossReason.REFUSED -> media(ElementCallAnalyticsFailureCause.CONNECTION_LOST_REFUSED)
            MatrixRtcConnectionLossReason.BUDGET -> media(ElementCallAnalyticsFailureCause.CONNECTION_LOST_BUDGET)
            MatrixRtcConnectionLossReason.TOKEN_ENDPOINT_UNSUPPORTED -> TOKEN_ENDPOINT_UNSUPPORTED
        }

        val TOKEN_ENDPOINT_UNSUPPORTED = FailureReport(
            ElementCallAnalyticsFailureCause.TOKEN_ENDPOINT_UNSUPPORTED,
            ElementCallAnalyticsFailureStage.MEDIA,
            ElementCallAnalyticsFailureStep.MEDIA_TOKEN,
        )

        private fun homeserver(cause: ElementCallAnalyticsFailureCause, step: ElementCallAnalyticsFailureStep = ElementCallAnalyticsFailureStep.UNKNOWN) =
            FailureReport(cause, ElementCallAnalyticsFailureStage.HOMESERVER, step)

        private fun media(cause: ElementCallAnalyticsFailureCause) =
            FailureReport(cause, ElementCallAnalyticsFailureStage.MEDIA, ElementCallAnalyticsFailureStep.UNKNOWN)
    }
}

private val MATRIX_ERRCODE = Regex("^[A-Z][A-Z0-9_.]{0,63}$")

/** The code if it has the shape of a Matrix error code, else null, so nothing else rides in that field (R21, C9). */
internal fun String?.asReportableErrcode(): String? = this?.takeIf { MATRIX_ERRCODE.matches(it) }
