package dev.thorreader.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thorreader.app.importer.ImportContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On the device: the import permission really is signature-level, the API service and the folder
 * activity really require it, and an app with the same signature can bind. (The shell, which is
 * NOT signed with our key, is refused - checked from adb in step 25's verification.)
 */
@RunWith(AndroidJUnit4::class)
class ImportPermissionTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun permissionIsSignatureLevel() {
        val info = ctx.packageManager.getPermissionInfo(ImportContract.PERMISSION_IMPORT, 0)
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, info.protection)
        assertEquals(ctx.packageName, info.packageName)
    }

    @Test
    fun serviceAndFolderActivityRequireIt() {
        val pm = ctx.packageManager
        val svc = pm.resolveService(Intent(ImportContract.ACTION_IMPORT_BOOKS).setPackage(ctx.packageName), 0)
        assertNotNull(svc)
        assertTrue(svc!!.serviceInfo.exported)
        assertEquals(ImportContract.PERMISSION_IMPORT, svc.serviceInfo.permission)
        val act = pm.resolveActivity(Intent(ImportContract.ACTION_CHOOSE_FOLDER).setPackage(ctx.packageName), PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull(act)
        assertEquals(ImportContract.PERMISSION_IMPORT, act!!.activityInfo.permission)
    }

    @Test
    fun sameSignatureCanBind() {
        val bound = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = bound.countDown()
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val ok = ctx.bindService(Intent(ImportContract.ACTION_IMPORT_BOOKS).setPackage(ctx.packageName), conn, Context.BIND_AUTO_CREATE)
        try {
            assertTrue(ok)
            assertTrue(bound.await(10, TimeUnit.SECONDS))
        } finally {
            ctx.unbindService(conn)
        }
    }
}
