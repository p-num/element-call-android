/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.api.rtc.MatrixRtcCallKind
import io.element.android.call.api.rtc.MatrixRtcRing
import io.element.android.call.api.rtc.id.RoomId
import org.junit.Test
import org.matrix.rtc.FfiCallIntent
import org.matrix.rtc.FfiCallKind

class JoinSessionParamsTest {
    @Test
    fun `local request identity reaches the native join field without changing call choices`() {
        val request = ElementCallData(RoomId("!room:example.org"), isAudioCall = true, isStartingCall = false)
        val params = MatrixRtcCallKind.Group(notify = null).toJoinSessionParams(null, null, request.membershipRequestId)
        assertThat(params.requestScopeId).isEqualTo(request.membershipRequestId.value)
        assertThat(params.applicationSlotId).isNull()
        assertThat(params.intent).isNull()
    }

    @Test
    fun `explicit audio and video choices reach the typed core field`() {
        val mappings = mapOf(
            MatrixRtcCallIntent.AUDIO to FfiCallIntent.AUDIO,
            MatrixRtcCallIntent.VIDEO to FfiCallIntent.VIDEO,
        )
        for ((requested, expected) in mappings) {
            val params = MatrixRtcCallKind.Group(notify = null).toJoinSessionParams(null, requested, null)

            assertThat(params.intent).isEqualTo(expected)
            assertThat(params.callIntent).isNull()
        }
    }

    @Test
    fun `generic quiet joins leave the core free to infer the call intent`() {
        val kinds = listOf(MatrixRtcCallKind.Group(notify = null), MatrixRtcCallKind.Direct(ring = null))
        for (kind in kinds) {
            val params = kind.toJoinSessionParams(null, null, null)

            assertThat(params.intent).isNull()
            assertThat(params.callIntent).isNull()
        }
    }

    @Test
    fun `the requested media choice is independent of the ringing intent`() {
        val kind = MatrixRtcCallKind.Direct(MatrixRtcRing(MatrixRtcCallIntent.VIDEO, lifetimeMs = null))
        val params = kind.toJoinSessionParams(null, MatrixRtcCallIntent.AUDIO, null)

        assertThat(params.intent).isEqualTo(FfiCallIntent.AUDIO)
        assertThat(params.callIntent).isNull()
        assertThat((params.kind as FfiCallKind.Direct).ring?.intent).isEqualTo("video")
    }
}
