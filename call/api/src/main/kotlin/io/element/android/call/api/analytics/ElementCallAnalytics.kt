/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.analytics

import io.element.android.call.api.ElementCallEndReason
import io.element.android.call.api.ElementCallVersion

/**
 * Where the call's analytics go (feature-hq 044). The component sends nothing itself; the host forwards, or not.
 *
 * Forward each event's [ElementCallAnalyticsEvent.name] and [ElementCallAnalyticsEvent.properties] unchanged, so both
 * platforms land in the same graphs, and only with the user's consent to analytics.
 */
fun interface ElementCallAnalytics {
    /**
     * Called synchronously, in order, before the call is torn down, sometimes while the call holds its own lock.
     * Return quickly and do not call back into the call; a throw is logged and ignored.
     */
    fun track(event: ElementCallAnalyticsEvent)
}

/** The default receiver: drops each event as it is made. */
object NoOpElementCallAnalytics : ElementCallAnalytics {
    override fun track(event: ElementCallAnalyticsEvent) = Unit
}

/**
 * One event of one join attempt. Nothing in it identifies the user, the room, the device or the homeserver: its fields
 * are enums, a random [attemptId], a duration, a count and a Matrix error code.
 */
sealed interface ElementCallAnalyticsEvent {
    /** The event's name, to report unchanged: `CallJoinAttempted`, `CallJoinOutcome`, `CallFailure` or `CallParticipationEnded`. */
    val name: String

    /** Random, made for the attempt, the same on every event of it. */
    val attemptId: String

    /** Fixed when the attempt starts and the same on every event of it. */
    val callKind: ElementCallAnalyticsCallKind

    /** Every property under its wire name, `callImplementation`, `callPlatform`, `callComponentVersion` and `callKind` included. */
    val properties: Map<String, Any>
        get() = buildMap {
            put("callImplementation", "native")
            put("callPlatform", "android")
            put("callComponentVersion", ElementCallVersion.library)
            put("callKind", callKind.wireValue)
            put("attemptId", attemptId)
            putAll(ownProperties())
        }

    /** A join was attempted, before anything was joined. */
    data class CallJoinAttempted(
        override val attemptId: String,
        override val callKind: ElementCallAnalyticsCallKind,
    ) : ElementCallAnalyticsEvent {
        override val name: String get() = "CallJoinAttempted"
    }

    /** How the attempt turned out; exactly one per attempt. */
    data class CallJoinOutcome(
        override val attemptId: String,
        override val callKind: ElementCallAnalyticsCallKind,
        val outcome: ElementCallAnalyticsOutcome,
        /** On [ElementCallAnalyticsOutcome.ABANDONED] only, when the call ended from the other side. */
        val endReason: ElementCallEndReason?,
    ) : ElementCallAnalyticsEvent {
        override val name: String get() = "CallJoinOutcome"
    }

    /** A failure, while joining or in the call. */
    data class CallFailure(
        override val attemptId: String,
        override val callKind: ElementCallAnalyticsCallKind,
        val phase: ElementCallAnalyticsFailurePhase,
        val cause: ElementCallAnalyticsFailureCause,
        /** While joining only. */
        val stage: ElementCallAnalyticsFailureStage?,
        /** While joining only. */
        val step: ElementCallAnalyticsFailureStep?,
        /** On [ElementCallAnalyticsFailureCause.JOIN_REFUSED] only, and only when it has the shape of a Matrix error code. */
        val errcode: String?,
    ) : ElementCallAnalyticsEvent {
        override val name: String get() = "CallFailure"
    }

    /** A connected call ended. [durationMs] is from connected to the end; [participantsMax] counts ourselves. */
    data class CallParticipationEnded(
        override val attemptId: String,
        override val callKind: ElementCallAnalyticsCallKind,
        val endReason: ElementCallEndReason,
        val durationMs: Long,
        val participantsMax: Int,
    ) : ElementCallAnalyticsEvent {
        override val name: String get() = "CallParticipationEnded"
    }
}

private fun ElementCallAnalyticsEvent.ownProperties(): Map<String, Any> = when (this) {
    is ElementCallAnalyticsEvent.CallJoinAttempted -> emptyMap()
    is ElementCallAnalyticsEvent.CallJoinOutcome -> buildMap {
        put("outcome", outcome.wireValue)
        endReason?.let { put("endReason", it.wireValue) }
    }
    is ElementCallAnalyticsEvent.CallFailure -> buildMap {
        put("phase", phase.wireValue)
        put("cause", cause.wireValue)
        stage?.let { put("stage", it.wireValue) }
        step?.let { put("step", it.wireValue) }
        errcode?.let { put("errcode", it) }
    }
    is ElementCallAnalyticsEvent.CallParticipationEnded -> mapOf(
        "endReason" to endReason.wireValue,
        "durationMs" to durationMs,
        "participantsMax" to participantsMax,
    )
}

/** The spec's spelling of each end reason; exhaustive, so a new reason does not compile until it has one. */
private val ElementCallEndReason.wireValue: String
    get() = when (this) {
        ElementCallEndReason.LEFT -> "left"
        ElementCallEndReason.DECLINED -> "declined"
        ElementCallEndReason.UNANSWERED -> "unanswered"
        ElementCallEndReason.RING_NOT_SENT -> "ringNotSent"
        ElementCallEndReason.OTHERS_LEFT -> "othersLeft"
        ElementCallEndReason.EMPTY -> "empty"
        ElementCallEndReason.SLOT_CLOSED -> "slotClosed"
        ElementCallEndReason.CONNECTION_CLOSED -> "connectionLost"
    }

/** Whether the call is in a direct-message room. */
enum class ElementCallAnalyticsCallKind(val wireValue: String) {
    DM("dm"),
    GROUP("group"),
}

enum class ElementCallAnalyticsOutcome(val wireValue: String) {
    CONNECTED("connected"),
    FAILED("failed"),
    ABANDONED("abandoned"),
    PERMISSION_REFUSED("permissionRefused"),
}

/** [JOINING] before our own media first connected, [IN_CALL] after. */
enum class ElementCallAnalyticsFailurePhase(val wireValue: String) {
    JOINING("joining"),
    IN_CALL("inCall"),
}

enum class ElementCallAnalyticsFailureCause(val wireValue: String) {
    JOIN_TIMED_OUT("joinTimedOut"),
    JOIN_REFUSED("joinRefused"),
    SLOT_CLOSED("slotClosed"),
    NO_MEDIA_SERVER("noMediaServer"),
    TOKEN_ENDPOINT_UNSUPPORTED("tokenEndpointUnsupported"),
    CONNECTION_LOST_NEVER_CONNECTED("connectionLostNeverConnected"),
    CONNECTION_LOST_REFUSED("connectionLostRefused"),
    CONNECTION_LOST_BUDGET("connectionLostBudget"),
    MICROPHONE_FAILED("microphoneFailed"),
    OTHER("other"),
}

/** Which server to look at: [HOMESERVER] while setting the call up with it, [MEDIA] while reaching the media server. */
enum class ElementCallAnalyticsFailureStage(val wireValue: String) {
    HOMESERVER("homeserver"),
    MEDIA("media"),
}

/** The step within its stage that failed; [UNKNOWN] until the core says which. */
enum class ElementCallAnalyticsFailureStep(val wireValue: String) {
    TRANSPORT_DISCOVERY("transportDiscovery"),
    MEMBERSHIP("membership"),
    MEDIA_TOKEN("mediaToken"),
    MEDIA_CONNECTION("mediaConnection"),
    UNKNOWN("unknown"),
}
