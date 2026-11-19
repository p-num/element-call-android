/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import android.content.Context
import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.MembershipRequestId
import io.element.android.call.api.rtc.MatrixRtcCall
import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.api.rtc.MatrixRtcCallKind
import io.element.android.call.api.rtc.MatrixRtcJoinException
import io.element.android.call.api.rtc.MatrixRtcRoom
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.util.childScope
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rtc.FfiJoinSessionParams
import org.matrix.rtc.MatrixRtcFfiException
import org.matrix.rtc.RtcRoom
import timber.log.Timber

/** One room open in the core. */
internal class RustMatrixRtcRoom(
    private val rtcRoom: RtcRoom,
    override val roomId: RoomId,
    private val dispatchers: ElementCallDispatchers,
    private val ffiDispatcher: CoroutineDispatcher,
    private val context: Context,
    private val sessionCoroutineScope: CoroutineScope,
) : MatrixRtcRoom {
    private val mutex = Mutex()
    private var call: RustMatrixRtcCall? = null
    private var isShutdown = false

    override suspend fun joinCall(
        kind: MatrixRtcCallKind,
        applicationSlotId: String?,
        requestedIntent: MatrixRtcCallIntent?,
        membershipRequestId: MembershipRequestId?,
    ): Result<MatrixRtcCall> = runCatchingExceptions {
        mutex.withLock {
            check(!isShutdown) { "Room $roomId is shut down" }
            val rtcCall = withContext(ffiDispatcher) {
                joinCallTyped(kind.toJoinSessionParams(applicationSlotId, requestedIntent, membershipRequestId))
            }
            Timber.d(
                "MatrixRTC: joined $roomId/${rtcCall.slotId()}, member id ${rtcCall.memberId()}" +
                    ", ${kind.describe()}"
            )
            RustMatrixRtcCall(
                rtcCall = rtcCall,
                roomId = roomId,
                callScope = sessionCoroutineScope.childScope(dispatchers.io, "MatrixRtcCall-$roomId"),
                dispatchers = dispatchers,
                ffiDispatcher = ffiDispatcher,
                context = context,
            ).also {
                call = it
                it.start()
            }
        }
    }

    override suspend fun shutdown() {
        val joined = mutex.withLock {
            if (isShutdown) return
            isShutdown = true
            call.also { call = null }
        }
        // The core leaves whatever is still joined.
        withContext(ffiDispatcher) { rtcRoom.shutdown() }
        joined?.close()
        rtcRoom.close()
        Timber.i("MatrixRTC: $roomId shut down")
    }

    private suspend fun joinCallTyped(params: FfiJoinSessionParams) = try {
        rtcRoom.joinCall(params)
    } catch (e: MatrixRtcFfiException.JoinTimedOut) {
        throw MatrixRtcJoinException.TimedOut()
    } catch (e: MatrixRtcFfiException.JoinRefused) {
        throw MatrixRtcJoinException.Refused(errcode = e.errcode, reason = e.reason)
    } catch (e: MatrixRtcFfiException.NoLiveKitTransport) {
        throw MatrixRtcJoinException.NoLiveKitTransport(transportType = e.transportType)
    } catch (_: MatrixRtcFfiException.TokenEndpointUnsupported) {
        throw MatrixRtcJoinException.TokenEndpointUnsupported()
    } catch (_: MatrixRtcFfiException.SlotClosed) {
        throw MatrixRtcJoinException.SlotClosed()
    }

    private fun MatrixRtcCallKind.describe(): String = when (this) {
        is MatrixRtcCallKind.Group -> "group, notify ${notify?.intent ?: "none"}"
        is MatrixRtcCallKind.Direct -> "direct, ring ${ring?.let { "${it.intent} for ${it.lifetimeMs ?: "default"} ms" } ?: "none"}"
    }
}
