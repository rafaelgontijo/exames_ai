package br.com.exames.coreui

import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Loads every bundled weight through the platform; no network is involved (resources are in the APK). */
class FigtreeLoadTest {
    @Test fun allFiveWeightsLoadAsLocalResources() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        for (id in listOf(R.font.figtree_regular, R.font.figtree_medium, R.font.figtree_semibold, R.font.figtree_bold, R.font.figtree_extrabold)) {
            val tf = androidx.core.content.res.ResourcesCompat.getFont(ctx, id)
            assertNotNull("font $id did not load", tf)
            assertNotEquals(Typeface.DEFAULT, tf)
        }
    }
    @Test fun noInternetPermissionIsHeldByTheTestedPackage() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(pi.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
    }
}
