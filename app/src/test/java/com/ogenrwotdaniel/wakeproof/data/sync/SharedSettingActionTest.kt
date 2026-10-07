package com.ogenrwotdaniel.wakeproof.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedSettingActionTest {

    @Test
    fun `an empty cloud is seeded by whichever device syncs first`() {
        assertEquals(SharedSettingAction.PUSH, sharedSettingAction(localUpdatedAt = 0, remoteUpdatedAt = null))
        assertEquals(SharedSettingAction.PUSH, sharedSettingAction(localUpdatedAt = 500, remoteUpdatedAt = null))
    }

    @Test
    fun `a goal never changed here adopts the cloud's, so two old installs converge`() {
        assertEquals(SharedSettingAction.PULL, sharedSettingAction(localUpdatedAt = 0, remoteUpdatedAt = 1))
    }

    @Test
    fun `otherwise the most recent change wins`() {
        assertEquals(SharedSettingAction.PULL, sharedSettingAction(localUpdatedAt = 100, remoteUpdatedAt = 200))
        assertEquals(SharedSettingAction.PUSH, sharedSettingAction(localUpdatedAt = 300, remoteUpdatedAt = 200))
        assertEquals(SharedSettingAction.NONE, sharedSettingAction(localUpdatedAt = 200, remoteUpdatedAt = 200))
    }
}
