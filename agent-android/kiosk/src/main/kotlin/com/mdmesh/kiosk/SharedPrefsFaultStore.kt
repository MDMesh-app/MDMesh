/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Ported from hmdm-android CrashLoopProtection.java and changed for MDMesh: the
 * SharedPreferences storage, rewritten in Kotlin and split out behind FaultStore.
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 * Modifications Copyright (C) 2026 the MDMesh contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mdmesh.kiosk

import android.content.Context

/**
 * SharedPreferences-backed [FaultStore]. Uses [CrashLoopGuard.FAULT_PREFERENCE_NAME]
 * in `MODE_PRIVATE` and commits synchronously (`commit()`, not `apply()`) because the
 * process is often mid-crash when a fault is registered.
 */
class SharedPrefsFaultStore(context: Context) : FaultStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(CrashLoopGuard.FAULT_PREFERENCE_NAME, Context.MODE_PRIVATE)

    override val counter: Int
        get() = prefs.getInt(KEY_COUNTER, 0)

    override val lastFaultTime: Long
        get() = prefs.getLong(KEY_LAST_FAULT_TIME, -1L) // -1 == never faulted

    override fun write(counter: Int, lastFaultTime: Long) {
        prefs.edit()
            .putInt(KEY_COUNTER, counter)
            .putLong(KEY_LAST_FAULT_TIME, lastFaultTime)
            .commit()
    }

    private companion object {
        const val KEY_COUNTER = "fault_counter"
        const val KEY_LAST_FAULT_TIME = "last_fault_time"
    }
}
