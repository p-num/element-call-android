/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallOptions
import io.element.android.call.api.ElementCallRequestLifecycle
import io.element.android.call.api.ElementCallRoomContext
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.NoOpElementCallRequestLifecycle
import io.element.android.call.api.analytics.NoOpElementCallAnalytics
import io.element.android.call.api.rtc.MatrixRtcCall
import io.element.android.call.api.rtc.MatrixRtcClient
import io.element.android.call.api.rtc.MatrixRtcMediaSession
import io.element.android.call.api.rtc.MatrixRtcRoom
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.FakeElementCallLifecycleListener
import io.element.android.call.test.FakeElementCallPreferencesStore
import io.element.android.call.test.FakeElementCallRequestLifecycle
import io.element.android.call.test.FakeElementCallRoomContextProvider
import io.element.android.call.test.FakeMatrixRtcCall
import io.element.android.call.test.FakeMatrixRtcClient
import io.element.android.call.test.FakeMatrixRtcMediaSession
import io.element.android.call.test.FakeMatrixRtcRoom
import io.element.android.call.test.audio.FakeAudioFocus
import io.element.android.call.test.audio.FakeCallAudioDeviceController
import io.element.android.call.tests.testutils.WarmUpRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultElementCallControllerOwnershipTest {
    @get:Rule val warmUpRule = WarmUpRule()

    @Test
    fun `copy keeps call choices equal but creates a new opaque membership request`() {
        val original = request()
        val copied = original.copy()
        assertThat(copied).isEqualTo(original)
        assertThat(copied.hashCode()).isEqualTo(original.hashCode())
        assertThat(copied.membershipRequestId).isNotSameInstanceAs(original.membershipRequestId)
        assertThat(copied.membershipRequestId.value).isNotEqualTo(original.membershipRequestId.value)
        assertThat(original.toString()).doesNotContain(original.membershipRequestId.value)
    }

    @Test
    fun `a slow state subscriber receives an equal successor request after missing null`() = runTest {
        val controller = controller()
        val original = request()
        controller.startCall(original)
        runCurrent()
        val paused = CompletableDeferred<Unit>()
        val frames = mutableListOf<ElementCallSnapshot>()
        backgroundScope.launch {
            controller.state.filterNotNull().collect { snapshot ->
                frames += snapshot
                if (frames.size == 1) paused.await()
            }
        }
        runCurrent()
        assertThat(frames).hasSize(1)
        assertThat(controller.hangUpAndWait(original)).isTrue()
        val newer = original.copy()
        controller.startCall(newer)
        runCurrent()
        assertThat(controller.state.value?.callData).isSameInstanceAs(newer)
        paused.complete(Unit)
        runCurrent()
        assertThat(frames.last().callData).isSameInstanceAs(newer)
    }

    @Test
    fun `a revoked queued request cannot publish startup`() = runTest {
        val listener = FakeElementCallLifecycleListener()
        val policy = FakeElementCallRequestLifecycle(permitsEntryResult = { false })
        val controller = controller(listener = listener, policy = policy)
        val original = request()
        controller.startCall(original)
        runCurrent()
        assertThat(controller.state.value).isNull()
        assertThat(listener.startedCalls).isEmpty()
    }

    @Test
    fun `retirement observes the original owner before null state is published`() = runTest {
        lateinit var controller: DefaultElementCallController
        var stillOwned: Boolean? = null
        val policy = FakeElementCallRequestLifecycle(retireResult = { stillOwned = controller.state.value?.callData === it })
        controller = controller(policy = policy)
        val original = request()
        controller.startCall(original)
        runCurrent()
        assertThat(controller.hangUpAndWait(original)).isTrue()
        assertThat(policy.retired).hasSize(1)
        assertThat(policy.retired.single()).isSameInstanceAs(original)
        assertThat(stillOwned).isTrue()
        assertThat(controller.state.value).isNull()
    }

    @Test
    fun `revocation before permission completion never opens a room`() = runTest {
        var permitted = true
        val policy = FakeElementCallRequestLifecycle(permitsEntryResult = { permitted })
        val client = FakeMatrixRtcClient()
        val controller = controller(client, policy = policy)
        val original = request()
        controller.startCall(original)
        runCurrent()
        permitted = false
        controller.setMicrophonePermissionGranted(true, original)
        runCurrent()
        assertThat(client.roomCount).isEqualTo(0)
        assertThat(controller.state.value).isNull()
    }

    @Test
    fun `ignoring another request retires only it while repeating the active request is harmless`() = runTest {
        val policy = FakeElementCallRequestLifecycle()
        val controller = controller(policy = policy)
        val original = request()
        controller.startCall(original)
        runCurrent()
        controller.startCall(original)
        val newer = original.copy()
        controller.startCall(newer)
        runCurrent()
        assertThat(controller.state.value?.callData).isSameInstanceAs(original)
        assertThat(policy.retired).hasSize(1)
        assertThat(policy.retired.single()).isSameInstanceAs(newer)
    }

    @Test
    fun `the exact request identity reaches the room join`() = runTest {
        val client = FakeMatrixRtcClient()
        val controller = controller(client)
        val original = request()
        start(controller, original)
        assertThat(client.lastRoom?.lastMembershipRequestId).isSameInstanceAs(original.membershipRequestId)
    }

    @Test
    fun `late room success closes its own room without replacing the successor lease`() = runTest {
        val fixture = lateOpen()
        try {
            fixture.gate.complete(Result.success(fixture.oldRoom))
            runCurrent()
            assertThat(fixture.oldRoom.joinCallCount).isEqualTo(0)
            assertThat(fixture.oldRoom.shutdownCount).isEqualTo(1)
            assertThat(fixture.controller.state.value?.callData).isSameInstanceAs(fixture.newer)
            assertThat(fixture.controller.hangUpAndWait(fixture.newer)).isTrue()
            assertThat(fixture.newRoom.shutdownCount).isEqualTo(1)
        } finally {
            fixture.gate.complete(Result.failure(IllegalStateException("test cleanup")))
            runCurrent()
        }
    }

    @Test
    fun `late room failure cannot fail an equal successor`() = runTest {
        val fixture = lateOpen()
        fixture.gate.complete(Result.failure(IllegalStateException("old room failed")))
        runCurrent()
        assertThat(fixture.controller.state.value?.callData).isSameInstanceAs(fixture.newer)
        assertThat(fixture.controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
    }

    @Test
    fun `late join success leaves and closes only its unpublished call`() = runTest {
        val fixture = lateJoin()
        val stale = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room")
        fixture.gate.complete(Result.success(stale))
        runCurrent()
        assertThat(fixture.listener.joinedCalls).hasSize(1)
        assertThat(fixture.listener.joinedCalls.single()).isSameInstanceAs(fixture.newer)
        assertThat(stale.leaveCount).isEqualTo(1)
        assertThat(stale.closeCount).isEqualTo(1)
        assertThat(fixture.controller.state.value?.callData).isSameInstanceAs(fixture.newer)
    }

    @Test
    fun `late join failure cannot fail an equal successor`() = runTest {
        val fixture = lateJoin()
        fixture.gate.complete(Result.failure(IllegalStateException("old join failed")))
        runCurrent()
        assertThat(fixture.controller.state.value?.callData).isSameInstanceAs(fixture.newer)
        assertThat(fixture.controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
    }

    @Test
    fun `cancellable room lookup cannot publish an old result after a successor`() = runTest {
        val gate = CompletableDeferred<Result<MatrixRtcRoom>>()
        val oldRoom = FakeMatrixRtcRoom(A_ROOM_ID)
        val newRoom = FakeMatrixRtcRoom(A_ROOM_ID)
        var opens = 0
        val client = FakeMatrixRtcClient(roomResult = { if (++opens == 1) gate.await() else Result.success(newRoom) })
        val listener = FakeElementCallLifecycleListener()
        val controller = controller(client, listener)
        val original = request()
        start(controller, original)
        assertThat(controller.hangUpAndWait(original)).isTrue()
        val newer = original.copy()
        start(controller, newer)
        gate.complete(Result.success(oldRoom))
        runCurrent()
        assertThat(controller.state.value?.callData).isSameInstanceAs(newer)
        assertThat(oldRoom.joinCallCount).isEqualTo(0)
        assertThat(listener.joinedCalls).hasSize(1)
        assertThat(listener.joinedCalls.single()).isSameInstanceAs(newer)
    }

    @Test
    fun `late media success closes its own unpublished session and preserves the successor media`() = runTest {
        val fixture = lateMedia()
        val stale = FakeMatrixRtcMediaSession()
        fixture.gate.complete(Result.success(stale))
        runCurrent()
        assertThat(stale.publishMicrophoneCount).isEqualTo(0)
        assertThat(stale.disconnectCount).isEqualTo(1)
        assertThat(stale.closeCount).isEqualTo(1)
        assertThat(fixture.controller.state.value?.callData).isSameInstanceAs(fixture.newer)
        assertThat(fixture.controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(fixture.controller.hangUpAndWait(fixture.newer)).isTrue()
        assertThat(fixture.newRoom.lastCall?.lastMediaSession?.disconnectCount).isEqualTo(1)
    }

    @Test
    fun `late media failure cannot fail an equal successor`() = runTest {
        val fixture = lateMedia()
        fixture.gate.complete(Result.failure(IllegalStateException("old media failed")))
        runCurrent()
        assertThat(fixture.controller.state.value?.callData).isSameInstanceAs(fixture.newer)
        assertThat(fixture.controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
    }

    @Test
    fun `revocation during room lookup closes the returned room without joining`() = runTest {
        val gate = CompletableDeferred<Result<MatrixRtcRoom>>()
        var permitted = true
        val policy = FakeElementCallRequestLifecycle(permitsEntryResult = { permitted })
        val room = FakeMatrixRtcRoom(A_ROOM_ID)
        val controller = controller(FakeMatrixRtcClient(roomResult = { gate.await() }), policy = policy)
        val original = request()
        start(controller, original)
        permitted = false
        gate.complete(Result.success(room))
        runCurrent()
        assertThat(controller.state.value).isNull()
        assertThat(room.joinCallCount).isEqualTo(0)
        assertThat(room.shutdownCount).isEqualTo(1)
        assertThat(policy.retired.single()).isSameInstanceAs(original)
    }

    @Test
    fun `revocation during join leaves its unpublished call without announcing a joined call`() = runTest {
        val gate = CompletableDeferred<Result<MatrixRtcCall>>()
        var permitted = true
        val policy = FakeElementCallRequestLifecycle(permitsEntryResult = { permitted })
        val room = FakeMatrixRtcRoom(A_ROOM_ID, joinResult = { withContext(NonCancellable) { gate.await() } })
        val listener = FakeElementCallLifecycleListener()
        val controller = controller(FakeMatrixRtcClient(roomResult = { Result.success(room) }), listener, policy)
        val original = request()
        start(controller, original)
        permitted = false
        val joined = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room")
        gate.complete(Result.success(joined))
        runCurrent()
        assertThat(controller.state.value).isNull()
        assertThat(listener.joinedCalls).isEmpty()
        assertThat(joined.leaveCount).isEqualTo(1)
        assertThat(joined.closeCount).isEqualTo(1)
        assertThat(room.shutdownCount).isEqualTo(1)
    }

    @Test
    fun `late microphone completion cannot publish old media readiness over a successor`() = runTest {
        val gate = CompletableDeferred<Result<Unit>>()
        val oldMedia = FakeMatrixRtcMediaSession().apply {
            publishMicrophoneResult = { withContext(NonCancellable) { gate.await() } }
        }
        val oldCall = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room").apply { newMediaSession = { oldMedia } }
        val oldRoom = FakeMatrixRtcRoom(A_ROOM_ID, joinResult = { Result.success(oldCall) })
        val newRoom = FakeMatrixRtcRoom(A_ROOM_ID)
        var opens = 0
        val controller = controller(FakeMatrixRtcClient(roomResult = { Result.success(if (++opens == 1) oldRoom else newRoom) }))
        val original = request()
        start(controller, original)
        assertThat(controller.hangUpAndWait(original)).isTrue()
        val newer = original.copy()
        start(controller, newer)
        gate.complete(Result.success(Unit))
        runCurrent()
        assertThat(controller.state.value?.callData).isSameInstanceAs(newer)
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(oldMedia.disconnectCount).isEqualTo(1)
        assertThat(oldMedia.closeCount).isEqualTo(1)
        assertThat(newRoom.lastCall?.lastMediaSession?.disconnectCount).isEqualTo(0)
    }

    private suspend fun TestScope.lateMedia(): MediaFixture {
        val gate = CompletableDeferred<Result<MatrixRtcMediaSession>>()
        val oldCall = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room").apply {
            connectMediaResult = { withContext(NonCancellable) { gate.await() } }
        }
        val oldRoom = FakeMatrixRtcRoom(A_ROOM_ID, joinResult = { Result.success(oldCall) })
        val newRoom = FakeMatrixRtcRoom(A_ROOM_ID)
        var opens = 0
        val controller = controller(FakeMatrixRtcClient(roomResult = { Result.success(if (++opens == 1) oldRoom else newRoom) }))
        val original = request()
        start(controller, original)
        assertThat(controller.hangUpAndWait(original)).isTrue()
        val newer = original.copy()
        start(controller, newer)
        return MediaFixture(controller, newer, newRoom, gate)
    }

    private suspend fun TestScope.lateOpen(): OpenFixture {
        val gate = CompletableDeferred<Result<MatrixRtcRoom>>()
        val oldRoom = FakeMatrixRtcRoom(A_ROOM_ID)
        val newRoom = FakeMatrixRtcRoom(A_ROOM_ID)
        var opens = 0
        val client = FakeMatrixRtcClient(roomResult = {
            if (++opens == 1) withContext(NonCancellable) { gate.await() } else Result.success(newRoom)
        })
        val controller = controller(client)
        val original = request()
        start(controller, original)
        assertThat(controller.hangUpAndWait(original)).isTrue()
        val newer = original.copy()
        start(controller, newer)
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        return OpenFixture(controller, newer, oldRoom, newRoom, gate)
    }

    private suspend fun TestScope.lateJoin(): JoinFixture {
        val gate = CompletableDeferred<Result<MatrixRtcCall>>()
        val oldRoom = FakeMatrixRtcRoom(A_ROOM_ID, joinResult = { withContext(NonCancellable) { gate.await() } }).apply {
            shutdownResult = { error("old shutdown failed") }
        }
        val newRoom = FakeMatrixRtcRoom(A_ROOM_ID)
        var opens = 0
        val client = FakeMatrixRtcClient(roomResult = { Result.success(if (++opens == 1) oldRoom else newRoom) })
        val listener = FakeElementCallLifecycleListener()
        val controller = controller(client, listener)
        val original = request()
        start(controller, original)
        assertThat(controller.hangUpAndWait(original)).isFalse()
        val newer = original.copy()
        start(controller, newer)
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        return JoinFixture(controller, newer, listener, gate)
    }

    private fun TestScope.start(controller: DefaultElementCallController, data: ElementCallData) {
        controller.startCall(data)
        runCurrent()
        controller.setMicrophonePermissionGranted(true, data)
        runCurrent()
    }

    private fun TestScope.controller(
        client: MatrixRtcClient = FakeMatrixRtcClient(),
        listener: FakeElementCallLifecycleListener = FakeElementCallLifecycleListener(),
        policy: ElementCallRequestLifecycle = NoOpElementCallRequestLifecycle,
    ) = DefaultElementCallController(
        scope = backgroundScope,
        platform = FakeElementCallPlatform(),
        rtcClient = client,
        audioDeviceController = FakeCallAudioDeviceController(),
        audioFocus = FakeAudioFocus(requestAudioFocusResult = {}, releaseAudioFocusResult = {}),
        lifecycleListener = listener,
        requestLifecycle = policy,
        roomContextProvider = FakeElementCallRoomContextProvider(ElementCallRoomContext("A room", isDm = false, members = emptyMap())),
        options = ElementCallOptions(),
        preferencesStore = FakeElementCallPreferencesStore(),
        analytics = NoOpElementCallAnalytics,
    )

    private fun request() = ElementCallData(A_ROOM_ID, isAudioCall = true, isStartingCall = false, hasExplicitCallIntent = true)

    private data class MediaFixture(
        val controller: DefaultElementCallController,
        val newer: ElementCallData,
        val newRoom: FakeMatrixRtcRoom,
        val gate: CompletableDeferred<Result<MatrixRtcMediaSession>>,
    )

    private data class OpenFixture(
        val controller: DefaultElementCallController,
        val newer: ElementCallData,
        val oldRoom: FakeMatrixRtcRoom,
        val newRoom: FakeMatrixRtcRoom,
        val gate: CompletableDeferred<Result<MatrixRtcRoom>>,
    )

    private data class JoinFixture(
        val controller: DefaultElementCallController,
        val newer: ElementCallData,
        val listener: FakeElementCallLifecycleListener,
        val gate: CompletableDeferred<Result<MatrixRtcCall>>,
    )
}
