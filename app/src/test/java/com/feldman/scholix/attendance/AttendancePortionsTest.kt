package com.feldman.scholix.attendance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendancePortionsTest {

    private val defaultConfig = PortionsConfig()

    @Test
    fun testW1Thresholds() {
        val w = 1
        // 1 hour -> Bonus (+2)
        val bonus = AttendancePortionsCalculator.calculate("ספרות", 1, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.BONUS, bonus.status)
        assertEquals(2, bonus.pointsDelta)
        assertFalse(bonus.isFailing)

        // 2 hours -> Neutral (0)
        val neutral = AttendancePortionsCalculator.calculate("ספרות", 2, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.NEUTRAL, neutral.status)
        assertEquals(0, neutral.pointsDelta)
        assertFalse(neutral.isFailing)

        // 3 hours -> -5 points
        val minus5 = AttendancePortionsCalculator.calculate("ספרות", 3, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.DEDUCTION, minus5.status)
        assertEquals(-5, minus5.pointsDelta)
        assertFalse(minus5.isFailing)

        // 4 hours -> -7 points
        val minus7 = AttendancePortionsCalculator.calculate("ספרות", 4, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.DEDUCTION, minus7.status)
        assertEquals(-7, minus7.pointsDelta)

        // 5 hours -> -9 points
        val minus9 = AttendancePortionsCalculator.calculate("ספרות", 5, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.DEDUCTION, minus9.status)
        assertEquals(-9, minus9.pointsDelta)

        // 6 hours -> Failing grade
        val failing = AttendancePortionsCalculator.calculate("ספרות", 6, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.FAILING, failing.status)
        assertTrue(failing.isFailing)
    }

    @Test
    fun testW2Thresholds() {
        val w = 2
        // 2 hours -> Bonus
        val bonus = AttendancePortionsCalculator.calculate("תנך", 2, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.BONUS, bonus.status)
        assertEquals(2, bonus.pointsDelta)

        // 3 hours -> Neutral
        val neutral = AttendancePortionsCalculator.calculate("תנך", 3, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.NEUTRAL, neutral.status)
        assertEquals(0, neutral.pointsDelta)

        // 4 hours -> -5
        val minus5 = AttendancePortionsCalculator.calculate("תנך", 4, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.DEDUCTION, minus5.status)
        assertEquals(-5, minus5.pointsDelta)

        // 6 hours -> -7
        val minus7 = AttendancePortionsCalculator.calculate("תנך", 6, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.DEDUCTION, minus7.status)
        assertEquals(-7, minus7.pointsDelta)

        // 10 hours -> Failing (5 portions)
        val failing = AttendancePortionsCalculator.calculate("תנך", 10, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.FAILING, failing.status)
        assertTrue(failing.isFailing)
    }

    @Test
    fun testW5Thresholds() {
        val w = 5
        // 0..5 hours -> Bonus
        val b0 = AttendancePortionsCalculator.calculate("מתמטיקה", 0, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.BONUS, b0.status)
        assertEquals(2, b0.pointsDelta)

        val b5 = AttendancePortionsCalculator.calculate("מתמטיקה", 5, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.BONUS, b5.status)
        assertEquals(2, b5.pointsDelta)

        // 6..9 hours -> Neutral (0)
        val n6 = AttendancePortionsCalculator.calculate("מתמטיקה", 6, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.NEUTRAL, n6.status)
        assertEquals(0, n6.pointsDelta)

        val n9 = AttendancePortionsCalculator.calculate("מתמטיקה", 9, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.NEUTRAL, n9.status)
        assertEquals(0, n9.pointsDelta)

        // 10..14 hours -> -5
        val m10 = AttendancePortionsCalculator.calculate("מתמטיקה", 10, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.DEDUCTION, m10.status)
        assertEquals(-5, m10.pointsDelta)

        val m14 = AttendancePortionsCalculator.calculate("מתמטיקה", 14, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.DEDUCTION, m14.status)
        assertEquals(-5, m14.pointsDelta)

        // 15 hours -> -7
        val m15 = AttendancePortionsCalculator.calculate("מתמטיקה", 15, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.DEDUCTION, m15.status)
        assertEquals(-7, m15.pointsDelta)

        // 20 hours -> -9
        val m20 = AttendancePortionsCalculator.calculate("מתמטיקה", 20, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.DEDUCTION, m20.status)
        assertEquals(-9, m20.pointsDelta)

        // 25 hours -> Failing
        val f25 = AttendancePortionsCalculator.calculate("מתמטיקה", 25, 0, 0, w, defaultConfig)
        assertEquals(GradeImpactStatus.FAILING, f25.status)
        assertTrue(f25.isFailing)
    }

    @Test
    fun testBonusWithExcessJustifiedAbsences() {
        // W=5: 2 unjustified absences (<= 1 portion), but 15 justified absences (3 portions > 2 max allowed)
        val res = AttendancePortionsCalculator.calculate("אנגלית", 2, 15, 0, 5, defaultConfig)
        // Bonus should be disqualified because justified portions > 2.0
        assertEquals(GradeImpactStatus.NEUTRAL, res.status)
        assertEquals(0, res.pointsDelta)
    }

    @Test
    fun testCustomBonusPoints() {
        // User config with 3 bonus points
        val config3 = defaultConfig.copy(bonusPoints = 3)
        val res = AttendancePortionsCalculator.calculate("פיזיקה", 1, 0, 0, 5, config3)
        assertEquals(GradeImpactStatus.BONUS, res.status)
        assertEquals(3, res.pointsDelta)
    }

    @Test
    fun testSchoolReferenceTable() {
        val table = AttendancePortionsCalculator.buildSchoolReferenceTable()
        assertEquals(9, table.size)

        // Verify W=1
        assertEquals("1", table[0].bonusHoursText)
        assertEquals("2", table[0].neutralHoursText)
        assertEquals("3", table[0].minus5HoursText)
        assertEquals("6 ומעלה", table[0].failingHoursText)

        // Verify W=5
        assertEquals("5", table[4].bonusHoursText)
        assertEquals("6-9", table[4].neutralHoursText)
        assertEquals("10", table[4].minus5HoursText)
        assertEquals("15", table[4].minus7HoursText)
        assertEquals("20", table[4].minus9HoursText)
        assertEquals("25 ומעלה", table[4].failingHoursText)
    }
}
