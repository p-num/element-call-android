/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import io.element.android.call.api.ElementCallEndReason
import io.element.android.call.api.rtc.MatrixRtcCallEnd

/** The core's end as the host is told it, one to one. */
internal fun MatrixRtcCallEnd.toEndReason(): ElementCallEndReason = when (this) {
    MatrixRtcCallEnd.Left -> ElementCallEndReason.LEFT
    MatrixRtcCallEnd.Declined -> ElementCallEndReason.DECLINED
    MatrixRtcCallEnd.Unanswered -> ElementCallEndReason.UNANSWERED
    MatrixRtcCallEnd.RingNotSent -> ElementCallEndReason.RING_NOT_SENT
    MatrixRtcCallEnd.OthersLeft -> ElementCallEndReason.OTHERS_LEFT
    MatrixRtcCallEnd.Empty -> ElementCallEndReason.EMPTY
    MatrixRtcCallEnd.SlotClosed -> ElementCallEndReason.SLOT_CLOSED
    is MatrixRtcCallEnd.ConnectionClosed -> ElementCallEndReason.CONNECTION_CLOSED
}
