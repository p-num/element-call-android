/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.analytics.ElementCallAnalytics
import io.element.android.call.api.analytics.ElementCallAnalyticsCallKind
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent
import io.element.android.call.api.rtc.id.RoomId
import org.junit.Test

class ElementCallAnalyticsTest {
    @Test
    fun `existing functional receivers keep their default behavior`() {
        val events = mutableListOf<ElementCallAnalyticsEvent>()
        val receiver = ElementCallAnalytics { events += it }
        val bound = receiver.forAttempt(ElementCallData(RoomId("!fixture:example.org"), true, true))
        assertThat(bound).isSameInstanceAs(receiver)
        val event = ElementCallAnalyticsEvent.CallJoinAttempted("fixture", ElementCallAnalyticsCallKind.DM)
        bound.track(event)
        assertThat(events).containsExactly(event)
    }
}
