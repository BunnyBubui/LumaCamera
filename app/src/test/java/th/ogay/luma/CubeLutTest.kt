package th.ogay.luma

import org.junit.Assert.*
import org.junit.Test

class CubeLutTest{
    private val identity="""TITLE "Identity"
        LUT_3D_SIZE 2
        DOMAIN_MIN 0 0 0
        DOMAIN_MAX 1 1 1
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()
    @Test fun parsesRedFastestOrder(){val a=CubeLut.parse(identity);assertEquals(0xffff0000.toInt(),a[1][0][0]);assertEquals(0xff00ff00.toInt(),a[0][1][0]);assertEquals(0xff0000ff.toInt(),a[0][0][1]);assertEquals(0xffffffff.toInt(),a[1][1][1])}
    @Test(expected=IllegalArgumentException::class)fun rejectsMissingSamples(){CubeLut.parse("LUT_3D_SIZE 2\n0 0 0")}
    @Test(expected=IllegalArgumentException::class)fun rejectsUnsupportedDomains(){CubeLut.parse(identity.replace("DOMAIN_MIN 0 0 0","DOMAIN_MIN -1 -1 -1"))}
    @Test(expected=IllegalArgumentException::class)fun rejectsNonFiniteSamples(){CubeLut.parse(identity.replace("1 1 1","NaN 1 1"))}
}
