package com.mdmesh.core.sync

import android.util.Log

/** Where enrollment logs what the person holding the device doesn't need to read (never secrets). */
fun interface EnrollmentLog {
    fun warn(message: String, cause: Throwable?)
}

/** The platform log (tag EnrollmentManager). */
internal object AndroidEnrollmentLog : EnrollmentLog {
    override fun warn(message: String, cause: Throwable?) {
        Log.w("EnrollmentManager", message, cause)
    }
}
