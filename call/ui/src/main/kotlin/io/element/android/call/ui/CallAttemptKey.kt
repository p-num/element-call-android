/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import io.element.android.call.api.ElementCallData

/** Equal room and media values still represent separate permission requests. */
internal data class CallAttemptKey(private val call: ElementCallData) {
    override fun equals(other: Any?): Boolean = other is CallAttemptKey && call === other.call
    override fun hashCode(): Int = System.identityHashCode(call)
}
