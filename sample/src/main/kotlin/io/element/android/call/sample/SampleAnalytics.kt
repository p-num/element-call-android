/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import io.element.android.call.api.analytics.ElementCallAnalytics
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent
import timber.log.Timber

/**
 * The sample's analytics receiver (feature-hq 044): where a host would forward each event, it logs it, and keeps the
 * last [KEPT] for the instrumented test.
 */
object SampleAnalytics : ElementCallAnalytics {
    private const val KEPT = 20

    private val recent = ArrayDeque<ElementCallAnalyticsEvent>()

    val events: List<ElementCallAnalyticsEvent> get() = synchronized(recent) { recent.toList() }

    fun clear() = synchronized(recent) { recent.clear() }

    override fun track(event: ElementCallAnalyticsEvent) {
        Timber.i("Analytics: ${event.name} ${event.properties}")
        synchronized(recent) {
            recent.addLast(event)
            while (recent.size > KEPT) recent.removeFirst()
        }
    }
}
