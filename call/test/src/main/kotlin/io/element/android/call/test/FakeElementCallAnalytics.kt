/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.analytics.ElementCallAnalytics
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent

/**
 * Records every event the call reports, in order. [onTrack] runs as each one arrives, so a test can see what has and
 * has not happened yet at that moment.
 */
class FakeElementCallAnalytics(
    private val onTrack: (ElementCallAnalyticsEvent) -> Unit = {},
) : ElementCallAnalytics {
    val events = mutableListOf<ElementCallAnalyticsEvent>()

    val names: List<String> get() = events.map { it.name }

    override fun track(event: ElementCallAnalyticsEvent) {
        events += event
        onTrack(event)
    }
}
