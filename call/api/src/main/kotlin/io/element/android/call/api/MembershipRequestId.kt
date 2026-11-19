/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import java.util.UUID

/** Process-local request identity; its text is only for the native bridge, never event content or telemetry. */
class MembershipRequestId private constructor(val value: String) {
    companion object {
        internal fun fresh() = MembershipRequestId(UUID.randomUUID().toString())
    }
}
