/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcMembershipIdentityKind
import org.junit.Test
import org.matrix.rtc.FfiMembershipIdentityKind
import org.matrix.rtc.JoinedMembership

class MembershipGenerationTest {
    @Test
    fun `the adapter preserves generation and does not infer an unavailable identity`() {
        val mappings = mapOf(
            FfiMembershipIdentityKind.PER_JOIN_MEMBER_ID to MatrixRtcMembershipIdentityKind.PER_JOIN_MEMBER_ID,
            FfiMembershipIdentityKind.LEGACY_MEMBERSHIP_TIMESTAMP to MatrixRtcMembershipIdentityKind.LEGACY_MEMBERSHIP_TIMESTAMP,
            FfiMembershipIdentityKind.UNAVAILABLE to MatrixRtcMembershipIdentityKind.UNAVAILABLE,
        )
        for ((source, expected) in mappings) {
            val membership = JoinedMembership(
                roomId = "!fixture:example.org",
                slotId = "m.call#room",
                sender = "@alice:example.org",
                senderDeviceId = null,
                stickyKey = "fixture",
                memberId = "reused-member",
                membershipEventId = "\$refresh",
                membershipTs = 42uL,
                identityKind = source,
                application = "m.call",
                transports = emptyList(),
                canSubscribe = emptyList(),
            ).map()
            assertThat(membership.identityKind).isEqualTo(expected)
            assertThat(membership.membershipTs).isEqualTo(42uL)
        }
    }

    @Test
    fun `missing generation stays missing`() {
        val membership = JoinedMembership(
            roomId = "!fixture:example.org",
            slotId = "m.call#room",
            sender = "@alice:example.org",
            senderDeviceId = null,
            stickyKey = "fixture",
            memberId = "reused-member",
            membershipEventId = null,
            membershipTs = null,
            identityKind = FfiMembershipIdentityKind.UNAVAILABLE,
            application = null,
            transports = emptyList(),
            canSubscribe = emptyList(),
        ).map()
        assertThat(membership.identityKind).isEqualTo(MatrixRtcMembershipIdentityKind.UNAVAILABLE)
        assertThat(membership.membershipTs).isNull()
    }
}
