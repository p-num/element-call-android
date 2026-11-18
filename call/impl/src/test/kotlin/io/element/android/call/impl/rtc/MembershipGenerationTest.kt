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
    private val legacyJoinId = "0123456789abcdef0123456789abcdef"

    @Test
    fun `the adapter preserves generation and does not infer an unavailable identity`() {
        val mappings = mapOf(
            FfiMembershipIdentityKind.PER_JOIN_MEMBER_ID to MatrixRtcMembershipIdentityKind.PER_JOIN_MEMBER_ID,
            FfiMembershipIdentityKind.LEGACY_JOIN_ID to MatrixRtcMembershipIdentityKind.LEGACY_JOIN_ID,
            FfiMembershipIdentityKind.LEGACY_MEMBERSHIP_TIMESTAMP to MatrixRtcMembershipIdentityKind.LEGACY_MEMBERSHIP_TIMESTAMP,
            FfiMembershipIdentityKind.UNAVAILABLE to MatrixRtcMembershipIdentityKind.UNAVAILABLE,
        )
        for ((source, expected) in mappings) {
            val joinId = legacyJoinId.takeIf { source == FfiMembershipIdentityKind.LEGACY_JOIN_ID }
            val membership = JoinedMembership(
                roomId = "!fixture:example.org",
                slotId = "m.call#room",
                sender = "@alice:example.org",
                senderDeviceId = null,
                stickyKey = "fixture",
                memberId = "reused-member",
                membershipEventId = "\$refresh",
                membershipTs = 42uL,
                legacyJoinId = joinId,
                identityKind = source,
                application = "m.call",
                transports = emptyList(),
                canSubscribe = emptyList(),
            ).map()
            assertThat(membership.identityKind).isEqualTo(expected)
            assertThat(membership.membershipTs).isEqualTo(42uL)
            assertThat(membership.legacyJoinId).isEqualTo(joinId)
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
            legacyJoinId = null,
            identityKind = FfiMembershipIdentityKind.UNAVAILABLE,
            application = null,
            transports = emptyList(),
            canSubscribe = emptyList(),
        ).map()
        assertThat(membership.identityKind).isEqualTo(MatrixRtcMembershipIdentityKind.UNAVAILABLE)
        assertThat(membership.membershipTs).isNull()
        assertThat(membership.legacyJoinId).isNull()
    }
}
