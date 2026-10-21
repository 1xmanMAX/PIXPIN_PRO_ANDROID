package com.forge.pixpin.data

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
    @Test fun sinMemoriaEnCacheNoSePone() {
        assertFalse(CrashLog.merecePonerse(ApplicationExitInfo.REASON_LOW_MEMORY, 400))
        assertFalse(
            CrashLog.merecePonerse(
                ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_GONE,
            ),
        )
    }

    @Test fun sinMemoriaUsandoseSiSePone() {
        assertTrue(
            CrashLog.merecePonerse(
                ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            ),
        )
        assertTrue(
            CrashLog.merecePonerse(
                ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_SERVICE,
            ),
        )
    }

    @Test fun losFallosSiempre() {
        assertTrue(CrashLog.merecePonerse(ApplicationExitInfo.REASON_CRASH, 400))
        assertTrue(CrashLog.merecePonerse(ApplicationExitInfo.REASON_CRASH_NATIVE, 400))
        assertTrue(CrashLog.merecePonerse(ApplicationExitInfo.REASON_ANR, 100))
        assertFalse(CrashLog.merecePonerse(ApplicationExitInfo.REASON_USER_REQUESTED, 100))
    }
}
