/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.analytics

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallEndReason
import io.element.android.call.api.ElementCallVersion
import io.element.android.call.api.analytics.ElementCallAnalyticsCallKind
import io.element.android.call.api.analytics.ElementCallAnalyticsCallKind.DM
import io.element.android.call.api.analytics.ElementCallAnalyticsCallKind.GROUP
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent.CallFailure
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent.CallJoinAttempted
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent.CallJoinOutcome
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent.CallParticipationEnded
import io.element.android.call.api.analytics.ElementCallAnalyticsFailureCause
import io.element.android.call.api.analytics.ElementCallAnalyticsFailurePhase
import io.element.android.call.api.analytics.ElementCallAnalyticsFailureStage
import io.element.android.call.api.analytics.ElementCallAnalyticsFailureStep
import io.element.android.call.api.analytics.ElementCallAnalyticsOutcome
import io.element.android.call.api.rtc.MatrixRtcCallEnd
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcCallStatus
import io.element.android.call.api.rtc.MatrixRtcConnectionLossReason
import io.element.android.call.api.rtc.MatrixRtcMediaPhase
import io.element.android.call.api.rtc.MatrixRtcRingPhase
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.test.FakeElementCallAnalytics
import org.junit.Test

/** The state machine of one attempt (feature-hq 044 C2), alone, with a clock the test moves. */
class ElementCallAnalyticsReporterTest {
    private var nowMs = 1_000L
    private val analytics = FakeElementCallAnalytics()
    private var roomKind = GROUP
    private val reporter = ElementCallAnalyticsReporter(analytics, elapsedRealtimeMs = { nowMs }, undecidedCallKind = { roomKind }, id = AN_ID)

    private val joinRefused = FailureReport(
        ElementCallAnalyticsFailureCause.JOIN_REFUSED,
        ElementCallAnalyticsFailureStage.HOMESERVER,
        ElementCallAnalyticsFailureStep.UNKNOWN,
        errcode = "M_FORBIDDEN",
    )

    private fun connect(kind: ElementCallAnalyticsCallKind = GROUP, participants: Int = 1) {
        reporter.attempted(kind)
        reporter.ownMediaConnected(participants)
        reporter.microphonePublished(participants)
    }

    private fun outcome(outcome: ElementCallAnalyticsOutcome, kind: ElementCallAnalyticsCallKind = GROUP, endReason: ElementCallEndReason? = null) =
        CallJoinOutcome(AN_ID, kind, outcome, endReason)

    @Test
    fun `attempted twice sends one event`() {
        reporter.attempted(GROUP)
        reporter.attempted(DM)
        assertThat(analytics.events).containsExactly(CallJoinAttempted(AN_ID, GROUP))
    }

    @Test
    fun `an outcome before the join decided goes out at once, with what the room is known to be then`() {
        roomKind = DM
        reporter.permissionRefused()
        assertThat(analytics.events).containsExactly(
            CallJoinAttempted(AN_ID, DM),
            outcome(ElementCallAnalyticsOutcome.PERMISSION_REFUSED, DM),
        ).inOrder()

        reporter.attemptOver()
        reporter.attempted(GROUP)
        assertThat(analytics.events).hasSize(2)
    }

    @Test
    fun `the room's kind is read when the outcome is reached, not when the attempt starts`() {
        roomKind = GROUP
        val other = FakeElementCallAnalytics()
        val late = ElementCallAnalyticsReporter(other, { 0 }, { roomKind }, AN_ID)
        roomKind = DM
        late.hungUp()
        assertThat(other.events).containsExactly(CallJoinAttempted(AN_ID, DM), outcome(ElementCallAnalyticsOutcome.ABANDONED, DM)).inOrder()
    }

    @Test
    fun `a failure before the join decided is the attempt, the outcome, then the failure`() {
        reporter.failed(
            FailureReport(ElementCallAnalyticsFailureCause.OTHER, ElementCallAnalyticsFailureStage.HOMESERVER, ElementCallAnalyticsFailureStep.UNKNOWN)
        )
        assertThat(analytics.events).containsExactly(
            CallJoinAttempted(AN_ID, GROUP),
            outcome(ElementCallAnalyticsOutcome.FAILED),
            CallFailure(
                AN_ID,
                GROUP,
                ElementCallAnalyticsFailurePhase.JOINING,
                ElementCallAnalyticsFailureCause.OTHER,
                ElementCallAnalyticsFailureStage.HOMESERVER,
                ElementCallAnalyticsFailureStep.UNKNOWN,
                null,
            ),
        ).inOrder()
    }

    @Test
    fun `a hang-up before the join decided is abandoned without an end reason`() {
        reporter.hungUp()
        assertThat(analytics.events).containsExactly(CallJoinAttempted(AN_ID, GROUP), outcome(ElementCallAnalyticsOutcome.ABANDONED)).inOrder()
    }

    @Test
    fun `the join's decision wins over the room's kind once it exists`() {
        roomKind = DM
        reporter.attempted(GROUP)
        reporter.hungUp()
        assertThat(analytics.events.map { it.callKind }).containsExactly(GROUP, GROUP)
    }

    @Test
    fun `the first outcome wins`() {
        reporter.permissionRefused()
        reporter.hungUp()
        reporter.failed(joinRefused)
        reporter.attemptOver()
        assertThat(analytics.names).containsExactly("CallJoinAttempted", "CallJoinOutcome").inOrder()
        assertThat((analytics.events[1] as CallJoinOutcome).outcome).isEqualTo(ElementCallAnalyticsOutcome.PERMISSION_REFUSED)
    }

    @Test
    fun `connected only once both our media and the microphone are in, in either order, stamped at the second`() {
        reporter.attempted(GROUP)
        nowMs = 2_000
        reporter.ownMediaConnected(participants = 1)
        assertThat(analytics.events).hasSize(1)
        nowMs = 3_000
        reporter.microphonePublished(participants = 1)
        assertThat(analytics.events.last()).isEqualTo(outcome(ElementCallAnalyticsOutcome.CONNECTED))

        nowMs = 10_000
        reporter.hungUp()
        assertThat(analytics.events.last()).isEqualTo(CallParticipationEnded(AN_ID, GROUP, ElementCallEndReason.LEFT, durationMs = 7_000, participantsMax = 1))
    }

    @Test
    fun `the microphone first, then our media, is connected too`() {
        reporter.attempted(GROUP)
        reporter.microphonePublished(participants = 1)
        assertThat(analytics.events).hasSize(1)
        reporter.ownMediaConnected(participants = 1)
        reporter.ownMediaConnected(participants = 1)
        assertThat(analytics.events).containsExactly(CallJoinAttempted(AN_ID, GROUP), outcome(ElementCallAnalyticsOutcome.CONNECTED)).inOrder()
    }

    @Test
    fun `the duration is from connected, not from the attempt`() {
        reporter.attempted(GROUP)
        nowMs = 5_000
        reporter.ownMediaConnected(1)
        reporter.microphonePublished(1)
        nowMs = 6_500
        reporter.ended(ElementCallEndReason.UNANSWERED)
        assertThat((analytics.events.last() as CallParticipationEnded).durationMs).isEqualTo(1_500)
    }

    @Test
    fun `participantsMax is the largest of the seed and every count, never below 1, ignored before connected`() {
        reporter.attempted(GROUP)
        reporter.participants(9)
        reporter.ownMediaConnected(participants = 0)
        reporter.microphonePublished(participants = 0)
        reporter.participants(3)
        reporter.participants(2)
        reporter.hungUp()
        assertThat((analytics.events.last() as CallParticipationEnded).participantsMax).isEqualTo(3)
    }

    @Test
    fun `participantsMax is seeded from the count at connected`() {
        connect(participants = 5)
        reporter.participants(4)
        reporter.hungUp()
        assertThat((analytics.events.last() as CallParticipationEnded).participantsMax).isEqualTo(5)
    }

    @Test
    fun `a zero seed is floored at 1`() {
        connect(participants = 0)
        reporter.hungUp()
        assertThat((analytics.events.last() as CallParticipationEnded).participantsMax).isEqualTo(1)
    }

    @Test
    fun `a hang-up while joining is abandoned with no end reason`() {
        reporter.attempted(GROUP)
        reporter.ownMediaConnected(1)
        reporter.hungUp()
        assertThat(analytics.events.last()).isEqualTo(outcome(ElementCallAnalyticsOutcome.ABANDONED))
    }

    @Test
    fun `the core's ends while joining are abandoned with their end reason, and a plain leave with none`() {
        val otherSide = listOf(
            ElementCallEndReason.DECLINED,
            ElementCallEndReason.UNANSWERED,
            ElementCallEndReason.RING_NOT_SENT,
            ElementCallEndReason.OTHERS_LEFT,
            ElementCallEndReason.EMPTY,
            ElementCallEndReason.SLOT_CLOSED,
        )
        otherSide.forEach { reason ->
            val analytics = FakeElementCallAnalytics()
            ElementCallAnalyticsReporter(analytics, { 0 }, { GROUP }, AN_ID).apply {
                attempted(GROUP)
                ended(reason)
                hungUp()
                attemptOver()
            }
            assertThat(
                analytics.events
            ).containsExactly(CallJoinAttempted(AN_ID, GROUP), outcome(ElementCallAnalyticsOutcome.ABANDONED, endReason = reason)).inOrder()
        }
        reporter.attempted(GROUP)
        reporter.ended(ElementCallEndReason.LEFT)
        assertThat(analytics.events.last()).isEqualTo(outcome(ElementCallAnalyticsOutcome.ABANDONED, endReason = null))
    }

    @Test
    fun `a join failure is the outcome, then the failure, and nothing after`() {
        reporter.attempted(GROUP)
        reporter.failed(joinRefused)
        reporter.ownMediaConnected(1)
        reporter.microphonePublished(1)
        reporter.ended(ElementCallEndReason.CONNECTION_CLOSED)
        reporter.hungUp()
        reporter.attemptOver()
        assertThat(analytics.events).containsExactly(
            CallJoinAttempted(AN_ID, GROUP),
            outcome(ElementCallAnalyticsOutcome.FAILED),
            CallFailure(
                AN_ID,
                GROUP,
                ElementCallAnalyticsFailurePhase.JOINING,
                ElementCallAnalyticsFailureCause.JOIN_REFUSED,
                ElementCallAnalyticsFailureStage.HOMESERVER,
                ElementCallAnalyticsFailureStep.UNKNOWN,
                "M_FORBIDDEN"
            ),
        ).inOrder()
    }

    @Test
    fun `a failure after connected is in the call, with no stage or step, then the end`() {
        connect()
        reporter.failed(FailureReport.connectionClosed(MatrixRtcConnectionLossReason.BUDGET))
        reporter.ended(ElementCallEndReason.CONNECTION_CLOSED)
        reporter.failed(FailureReport.connectionClosed(MatrixRtcConnectionLossReason.REFUSED))
        reporter.hungUp()
        assertThat(analytics.events.drop(2)).containsExactly(
            CallFailure(AN_ID, GROUP, ElementCallAnalyticsFailurePhase.IN_CALL, ElementCallAnalyticsFailureCause.CONNECTION_LOST_BUDGET, null, null, null),
            CallParticipationEnded(AN_ID, GROUP, ElementCallEndReason.CONNECTION_CLOSED, durationMs = 0, participantsMax = 1),
        ).inOrder()
    }

    @Test
    fun `a slot closed after connected is an end and nothing else`() {
        connect()
        reporter.ended(ElementCallEndReason.SLOT_CLOSED)
        reporter.hungUp()
        assertThat(analytics.events.drop(2)).containsExactly(
            CallParticipationEnded(AN_ID, GROUP, ElementCallEndReason.SLOT_CLOSED, durationMs = 0, participantsMax = 1),
        )
    }

    @Test
    fun `a refused permission after the join decided goes out at once`() {
        reporter.attempted(DM)
        reporter.permissionRefused()
        assertThat(analytics.events.last()).isEqualTo(outcome(ElementCallAnalyticsOutcome.PERMISSION_REFUSED, DM))
    }

    @Test
    fun `illegal transitions are silent`() {
        reporter.ownMediaConnected(1)
        reporter.microphonePublished(1)
        reporter.participants(4)
        assertThat(analytics.events).isEmpty()

        connect()
        reporter.attempted(DM)
        reporter.permissionRefused()
        reporter.ownMediaConnected(1)
        reporter.microphonePublished(1)
        assertThat(analytics.events).hasSize(2)

        reporter.hungUp()
        reporter.hungUp()
        reporter.ended(ElementCallEndReason.DECLINED)
        reporter.failed(joinRefused)
        reporter.attemptOver()
        assertThat(analytics.events).hasSize(3)
    }

    @Test
    fun `every event of an attempt carries its id and kind, and two attempts have different ids`() {
        val first = ElementCallAnalyticsReporter(analytics, { nowMs }, { GROUP })
        val second = ElementCallAnalyticsReporter(analytics, { nowMs }, { GROUP })
        assertThat(first.id).isNotEqualTo(second.id)
        first.attempted(DM)
        first.ownMediaConnected(1)
        first.microphonePublished(1)
        first.failed(FailureReport.connectionClosed(MatrixRtcConnectionLossReason.BUDGET))
        first.ended(ElementCallEndReason.CONNECTION_CLOSED)
        assertThat(analytics.events.map { it.attemptId }.toSet()).containsExactly(first.id)
        assertThat(analytics.events.map { it.properties["attemptId"] }.toSet()).containsExactly(first.id)
        assertThat(analytics.events.map { it.callKind }.toSet()).containsExactly(DM)
    }

    @Test
    fun `properties are exactly the spec's set per event, with absent values left out rather than empty`() {
        val common = setOf("callImplementation", "callPlatform", "callComponentVersion", "callKind", "attemptId")
        reporter.attempted(DM)
        reporter.failed(joinRefused.copy(errcode = "not a code"))
        connect()
        val events = analytics.events
        assertThat(events[0].properties.keys).isEqualTo(common)
        assertThat(events[1].properties.keys).isEqualTo(common + "outcome")
        assertThat(events[2].properties.keys).isEqualTo(common + setOf("phase", "cause", "stage", "step"))

        val connected = FakeElementCallAnalytics()
        ElementCallAnalyticsReporter(connected, { 0 }, { GROUP }, AN_ID).apply {
            attempted(GROUP)
            ended(ElementCallEndReason.DECLINED)
        }
        assertThat(connected.events[1].properties).containsExactlyEntriesIn(
            mapOf(
                "callImplementation" to "native",
                "callPlatform" to "android",
                "callComponentVersion" to ElementCallVersion.library,
                "callKind" to "group",
                "attemptId" to AN_ID,
                "outcome" to "abandoned",
                "endReason" to "declined",
            )
        )
        assertThat(events[2].properties).containsAtLeastEntriesIn(
            mapOf("phase" to "joining", "cause" to "joinRefused", "stage" to "homeserver", "step" to "unknown", "callKind" to "dm"),
        )

        val ended = ElementCallAnalyticsEvent.CallParticipationEnded(AN_ID, GROUP, ElementCallEndReason.CONNECTION_CLOSED, 42, 3)
        assertThat(ended.properties - common).containsExactly("endReason", "connectionLost", "durationMs", 42L, "participantsMax", 3)
        val inCall = CallFailure(AN_ID, GROUP, ElementCallAnalyticsFailurePhase.IN_CALL, ElementCallAnalyticsFailureCause.MICROPHONE_FAILED, null, null, null)
        assertThat(inCall.properties - common).containsExactly("phase", "inCall", "cause", "microphoneFailed")
    }

    @Test
    fun `every end reason has its wire name`() {
        val names = ElementCallEndReason.entries.map {
            ElementCallAnalyticsEvent.CallParticipationEnded(AN_ID, GROUP, it, 0, 1).properties["endReason"]
        }
        assertThat(names).containsExactly("left", "declined", "unanswered", "ringNotSent", "othersLeft", "empty", "slotClosed", "connectionLost").inOrder()
    }

    @Test
    fun `errcode is reported only in the shape of a Matrix error code`() {
        listOf("M_FORBIDDEN", "M_LIMIT_EXCEEDED", "ORG.EXAMPLE.CODE", "A" + "B".repeat(63)).forEach {
            assertThat(it.asReportableErrcode()).isEqualTo(it)
        }
        listOf(null, "", "you are banned from !room:example.org", "m_forbidden", "M_forbidden", "1_CODE", "_M", "A" + "B".repeat(64), "M FORBIDDEN").forEach {
            assertThat(it.asReportableErrcode()).isNull()
        }
    }

    @Test
    fun `a malformed errcode is dropped from the event, and errcode rides only on a refused join`() {
        reporter.attempted(GROUP)
        reporter.failed(joinRefused.copy(errcode = "@alice:example.org"))
        assertThat((analytics.events.last() as CallFailure).errcode).isNull()

        val other = FakeElementCallAnalytics()
        ElementCallAnalyticsReporter(other, { 0 }, { GROUP }, AN_ID).apply {
            attempted(GROUP)
            failed(joinRefused.copy(cause = ElementCallAnalyticsFailureCause.JOIN_TIMED_OUT))
        }
        assertThat((other.events.last() as CallFailure).errcode).isNull()
    }

    @Test
    fun `a throwing receiver does not throw, and the next event still goes to it`() {
        var calls = 0
        val throwing = ElementCallAnalyticsReporter({
            calls++
            error("the host broke")
        }, { 0 }, { GROUP }, AN_ID)
        throwing.attempted(GROUP)
        throwing.hungUp()
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `a connection closed report names its cause, the token endpoint at the media token step`() {
        assertThat(FailureReport.connectionClosed(MatrixRtcConnectionLossReason.NEVER_CONNECTED).cause)
            .isEqualTo(ElementCallAnalyticsFailureCause.CONNECTION_LOST_NEVER_CONNECTED)
        assertThat(FailureReport.connectionClosed(MatrixRtcConnectionLossReason.REFUSED))
            .isEqualTo(
                FailureReport(
                    ElementCallAnalyticsFailureCause.CONNECTION_LOST_REFUSED,
                    ElementCallAnalyticsFailureStage.MEDIA,
                    ElementCallAnalyticsFailureStep.UNKNOWN
                )
            )
        assertThat(FailureReport.connectionClosed(MatrixRtcConnectionLossReason.TOKEN_ENDPOINT_UNSUPPORTED))
            .isEqualTo(
                FailureReport(
                    ElementCallAnalyticsFailureCause.TOKEN_ENDPOINT_UNSUPPORTED,
                    ElementCallAnalyticsFailureStage.MEDIA,
                    ElementCallAnalyticsFailureStep.MEDIA_TOKEN
                )
            )
    }

    @Test
    fun `a status with our media up counts, ringing or not, and a plain connecting one does not`() {
        reporter.attempted(DM)
        reporter.microphonePublished(1)
        reporter.status(MatrixRtcCallStatus.Connecting, participants = 1)
        reporter.status(MatrixRtcCallStatus.Calling(A_CALLEE, MatrixRtcRingPhase.Sending, MatrixRtcMediaPhase.Connecting), participants = 1)
        assertThat(analytics.events).hasSize(1)

        reporter.status(MatrixRtcCallStatus.Calling(A_CALLEE, MatrixRtcRingPhase.Sending, MatrixRtcMediaPhase.Connected(degraded = false)), participants = 1)
        assertThat(analytics.events.last()).isEqualTo(outcome(ElementCallAnalyticsOutcome.CONNECTED, DM))
    }

    @Test
    fun `the core's lost connection is its failure, then its end`() {
        connect()
        reporter.status(MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.ConnectionClosed(MatrixRtcConnectionLossReason.NEVER_CONNECTED, "text")), participants = 1)
        assertThat(analytics.names.drop(2)).containsExactly("CallFailure", "CallParticipationEnded").inOrder()
        assertThat((analytics.events[2] as CallFailure).cause).isEqualTo(ElementCallAnalyticsFailureCause.CONNECTION_LOST_NEVER_CONNECTED)
    }

    @Test
    fun `the call over with no core end is a hang-up, and with one is the core's`() {
        connect()
        reporter.callEvent(MatrixRtcCallEvent.ParticipantJoined("m", UserId("@bob:example.org"), participantCount = 4))
        reporter.callOver(coreEnd = null)
        assertThat(analytics.events.last()).isEqualTo(CallParticipationEnded(AN_ID, GROUP, ElementCallEndReason.LEFT, durationMs = 0, participantsMax = 4))

        val other = FakeElementCallAnalytics()
        ElementCallAnalyticsReporter(other, { 0 }, { GROUP }, AN_ID).apply {
            attempted(DM)
            callOver(coreEnd = MatrixRtcCallEnd.Declined)
        }
        assertThat(other.events.last()).isEqualTo(outcome(ElementCallAnalyticsOutcome.ABANDONED, DM, ElementCallEndReason.DECLINED))
    }

    private companion object {
        val A_CALLEE = UserId("@callee:example.org")
        const val AN_ID = "an-attempt-id"
    }
}
