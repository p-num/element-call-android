/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import kotlinx.coroutines.flow.StateFlow

/**
 * Our participation in a call, as the core's `RtcCall`: one `(roomId, slotId)` pair, joined with
 * [MatrixRtcRoom.joinCall].
 *
 * Membership. Media is attached separately with [connectMedia], and what participants are actually
 * publishing belongs to the [MatrixRtcMediaSession] that returns - but [members] is available from the
 * moment we join, before there is any media at all.
 */
interface MatrixRtcCall : AutoCloseable {
    val roomId: RoomId
    val slotId: String

    /** Our own membership id. Correlation also requires the generation metadata in [members]. */
    val memberId: String

    /**
     * Voice or video, as the join settled it: the user's explicit choice, else the one the members already in it
     * agree on, else video. Start the camera for [MatrixRtcCallIntent.VIDEO] only.
     */
    val intent: MatrixRtcCallIntent

    /**
     * Who the RTC core currently considers joined to this slot, ourselves included.
     *
     * The core's membership projection, not the media roster, so it is populated before - and
     * independently of - anything being published.
     */
    val members: StateFlow<List<MatrixRtcMembership>>

    /** How many memberships the core counts in this slot, ourselves included: the size of [members]. */
    val memberCount: StateFlow<Int>

    /** Where the call is, from the join on, with or without media. Ends on [MatrixRtcCallStatus.Ended]. */
    val status: StateFlow<MatrixRtcCallStatus>

    /**
     * Raise our hand on this membership. Our tile shows it at once; suspends until it has landed, however long signalling is
     * away, and succeeds early when a lower supersedes it or the call ends. Fails only on a refusal (feature-hq 034 C12).
     */
    suspend fun raiseHand(): Result<Unit>

    /** Lower our hand, as [raiseHand] raises it. A no-op while it is down. */
    suspend fun lowerHand(): Result<Unit>

    /**
     * Send a reaction on this membership. Fails inside the core's cooldown and when it has not landed within five seconds;
     * either way nothing is shown, ours included, since ours is drawn only when the core reports it (feature-hq 009 R12, R23).
     */
    suspend fun sendReaction(kind: MatrixRtcReactionKind): Result<Unit>

    /**
     * Connect to the media transport the call was joined on. Returns once the engine is up, without waiting for our own media
     * server, which the core connects as it would reconnect a lost one (feature-hq 034 C10).
     *
     * @param isSfuFallbackEnabled see [io.element.android.call.api.ElementCallOptions.isSfuFallbackEnabled].
     * @param videoCodec what the camera and every screen share of this session publish with (feature-hq 043 C2).
     */
    suspend fun connectMedia(
        isSfuFallbackEnabled: Boolean = false,
        videoCodec: MatrixRtcVideoCodec = MatrixRtcVideoCodec.AUTO,
    ): Result<MatrixRtcMediaSession>

    /**
     * Leave the call, publishing a leave membership event. Returns without waiting for it to be sent: one attempt, and if it
     * fails the delayed leave or the membership's lifetime removes us (feature-hq 034 C10).
     */
    suspend fun leave(reason: MatrixRtcLeaveReason? = null): Result<Unit>
}

/**
 * One MSC4143 membership of an RTC session, as the core sees it.
 *
 * [memberId] keys the media roster and frame-encryption reports. Its generation semantics depend on
 * [identityKind]: legacy ids can be reused, and require [membershipTs] to distinguish joins.
 */
data class MatrixRtcMembership(
    val memberId: String,
    val userId: UserId,
    /** Null when the membership was not encrypted, so no device could be attested. */
    val deviceId: String?,
    /** The MSC4143 application type, for instance `m.call`. Null when the membership did not say. */
    val application: String?,
    /** Membership generation, supplied by the core rather than inferred from an id's spelling. */
    val identityKind: MatrixRtcMembershipIdentityKind = MatrixRtcMembershipIdentityKind.UNAVAILABLE,
    /** Legacy membership creation time. Missing means correlation must remain unbound. */
    val membershipTs: ULong? = null,
)

enum class MatrixRtcMembershipIdentityKind {
    PER_JOIN_MEMBER_ID,
    LEGACY_MEMBERSHIP_TIMESTAMP,
    UNAVAILABLE,
}

/**
 * Why we are leaving a call. [code] is the machine-readable MSC4143 reason.
 */
data class MatrixRtcLeaveReason(
    val code: String,
    val reason: String? = null,
)
