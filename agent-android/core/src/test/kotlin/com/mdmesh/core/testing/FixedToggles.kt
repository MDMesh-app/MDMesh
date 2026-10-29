package com.mdmesh.core.testing

import com.mdmesh.policy.ResolvedToggles
import com.mdmesh.policy.TogglePolicy

/** A toggle source that always resolves to [policies] (no Device-Owner flip, no probe errors). */
fun fixedToggles(vararg policies: Pair<String, TogglePolicy>): () -> ResolvedToggles =
    { ResolvedToggles(mapOf(*policies)) }
