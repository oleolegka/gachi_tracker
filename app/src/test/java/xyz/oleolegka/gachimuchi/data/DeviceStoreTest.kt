package xyz.oleolegka.gachimuchi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.isUid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the device id is minted once and then never changes`() {
        val first = DeviceStore(context).deviceId
        assertTrue(isUid(first))

        // a second store over the same preferences is what a second launch looks like
        assertEquals(first, DeviceStore(context).deviceId)
        assertEquals(first, DeviceStore(context).deviceId)
    }
}
