/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallRequestLifecycle

class FakeElementCallRequestLifecycle(
    var permitsEntryResult: (ElementCallData) -> Boolean = { true },
    var retireResult: (ElementCallData) -> Unit = {},
) : ElementCallRequestLifecycle {
    val retired = mutableListOf<ElementCallData>()
    override fun permitsEntry(request: ElementCallData): Boolean = permitsEntryResult(request)
    override fun retire(request: ElementCallData) {
        retired += request
        retireResult(request)
    }
}
