/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcCallStatus
import io.element.android.call.api.rtc.MatrixRtcMediaPhase
import io.element.android.call.api.rtc.MatrixRtcRingPhase
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.UserId
import org.junit.Test
import org.matrix.rtc.FfiCallEvent
import org.matrix.rtc.FfiCallStatus
import org.matrix.rtc.FfiMediaPhase
import org.matrix.rtc.FfiRingPhase

class CallEventMapperTest {
    @Test
    fun `own publishing health crosses independently of aggregate health`() {
        listOf(true, false).forEach { degraded ->
            assertThat(FfiCallEvent.OwnMediaConnectionState(degraded).map()).isEqualTo(MatrixRtcCallEvent.OwnMediaConnectionState(degraded))
        }
    }

    @Test
    fun `a join carries the count after it`() {
        val mapped = FfiCallEvent.ParticipantJoined(memberId = "bob", userId = "@bob:example.org", participantCount = 3u).map()

        assertThat(mapped).isEqualTo(MatrixRtcCallEvent.ParticipantJoined("bob", UserId("@bob:example.org"), participantCount = 3))
    }

    @Test
    fun `a leave carries the count after it`() {
        val mapped = FfiCallEvent.ParticipantLeft(memberId = "bob", participantCount = 2u).map()

        assertThat(mapped).isEqualTo(MatrixRtcCallEvent.ParticipantLeft("bob", participantCount = 2))
    }

    @Test
    fun `a hand going up or down crosses with its member`() {
        assertThat(FfiCallEvent.HandRaised(memberId = "bob", raisedAtMs = 1_000uL).map()).isEqualTo(MatrixRtcCallEvent.HandRaised("bob"))
        assertThat(FfiCallEvent.HandLowered(memberId = "bob").map()).isEqualTo(MatrixRtcCallEvent.HandLowered("bob"))
    }

    @Test
    fun `distinct native ring IDs cross with their callee and timing`() {
        val callee = "@bob:example.org"
        val firstId = "\$first-ring-from-server"
        val secondId = "\$second-ring-from-server"
        val first = FfiCallStatus.Calling(callee, FfiRingPhase.Ringing(eventId = firstId, sentAtMs = 2_000uL, lifetimeMs = 90_000uL), FfiMediaPhase.Connecting)
        val second = first.copy(ring = FfiRingPhase.Ringing(eventId = secondId, sentAtMs = 3_000uL, lifetimeMs = 90_000uL))

        assertThat(first.map()).isEqualTo(
            MatrixRtcCallStatus.Calling(
                UserId(callee),
                MatrixRtcRingPhase.Ringing(sentAtMs = 2_000L, lifetimeMs = 90_000L, eventId = EventId(firstId)),
                MatrixRtcMediaPhase.Connecting,
            )
        )
        assertThat(second.map()).isEqualTo(
            MatrixRtcCallStatus.Calling(
                UserId(callee),
                MatrixRtcRingPhase.Ringing(sentAtMs = 3_000L, lifetimeMs = 90_000L, eventId = EventId(secondId)),
                MatrixRtcMediaPhase.Connecting,
            )
        )
    }

    @Test
    fun `a malformed native ring ID preserves ringing and its timing`() {
        val callee = "@bob:example.org"
        val expected = MatrixRtcCallStatus.Calling(
            UserId(callee),
            MatrixRtcRingPhase.Ringing(sentAtMs = 2_000L, lifetimeMs = 90_000L, eventId = null),
            MatrixRtcMediaPhase.Connecting,
        )

        for (eventId in listOf("", "not-an-event-id", "\$contains a space")) {
            val ring = FfiRingPhase.Ringing(eventId = eventId, sentAtMs = 2_000uL, lifetimeMs = 90_000uL)

            assertThat(FfiCallStatus.Calling(callee, ring, FfiMediaPhase.Connecting).map()).isEqualTo(expected)
        }
    }
}
