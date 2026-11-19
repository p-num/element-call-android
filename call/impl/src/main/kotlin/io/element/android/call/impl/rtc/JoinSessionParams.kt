/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import io.element.android.call.api.MembershipRequestId
import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.api.rtc.MatrixRtcCallKind
import io.element.android.call.api.rtc.MatrixRtcGroupNotify
import io.element.android.call.api.rtc.MatrixRtcRing
import org.matrix.rtc.FfiCallIntent
import org.matrix.rtc.FfiCallKind
import org.matrix.rtc.FfiGroupNotify
import org.matrix.rtc.FfiJoinSessionParams
import org.matrix.rtc.FfiJoinTransport
import org.matrix.rtc.FfiRingConfig

/** How long the core waits before considering a silent member gone. */
private const val KEEP_ALIVE_TIMEOUT_MS = 20_000uL

internal fun MatrixRtcCallKind.toJoinSessionParams(
    applicationSlotId: String?,
    requestedIntent: MatrixRtcCallIntent?,
    membershipRequestId: MembershipRequestId?,
) = FfiJoinSessionParams(
    requestScopeId = membershipRequestId?.value,
    applicationSlotId = applicationSlotId,
    // The core picks from the backend's `rtcTransports`, and fails the join when there is none.
    transport = FfiJoinTransport.Advertised,
    keepAliveTimeoutMs = KEEP_ALIVE_TIMEOUT_MS,
    // Nulls defer to the core and to the slot.
    stickyDurationMs = null,
    degradedLifetimeMs = null,
    encryptionConfig = null,
    kind = toFfi(),
    callIntent = null,
    reactions = null,
    intent = requestedIntent?.toFfi(),
)

private fun MatrixRtcCallKind.toFfi(): FfiCallKind = when (this) {
    is MatrixRtcCallKind.Group -> FfiCallKind.Group(notify?.toFfi())
    is MatrixRtcCallKind.Direct -> FfiCallKind.Direct(ring?.toFfi())
}

private fun MatrixRtcGroupNotify.toFfi() = FfiGroupNotify(
    // Lowercase because it goes out verbatim as `m.call.intent`.
    intent = intent?.toWire(),
    lifetimeMs = lifetimeMs,
    mentionUserIds = mentionUserIds.map { it.value },
    mentionRoom = mentionRoom,
)

private fun MatrixRtcRing.toFfi() = FfiRingConfig(lifetimeMs = lifetimeMs, intent = intent?.toWire())

private fun MatrixRtcCallIntent.toWire(): String = when (this) {
    MatrixRtcCallIntent.AUDIO -> "audio"
    MatrixRtcCallIntent.VIDEO -> "video"
}

private fun MatrixRtcCallIntent.toFfi(): FfiCallIntent = when (this) {
    MatrixRtcCallIntent.AUDIO -> FfiCallIntent.AUDIO
    MatrixRtcCallIntent.VIDEO -> FfiCallIntent.VIDEO
}
