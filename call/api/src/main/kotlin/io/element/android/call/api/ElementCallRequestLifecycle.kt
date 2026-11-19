/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

/** These synchronous hooks run under the controller mutex; implementations must not block, throw or reenter it. */
interface ElementCallRequestLifecycle {
    /** Checks whether the request still owns entry before startup, permission handling and adopting results. */
    fun permitsEntry(request: ElementCallData): Boolean

    /** Revoke the exact request on rejected startup or before clearing an active call and starting cleanup. */
    fun retire(request: ElementCallData)
}

object NoOpElementCallRequestLifecycle : ElementCallRequestLifecycle {
    override fun permitsEntry(request: ElementCallData): Boolean = true
    override fun retire(request: ElementCallData) = Unit
}
