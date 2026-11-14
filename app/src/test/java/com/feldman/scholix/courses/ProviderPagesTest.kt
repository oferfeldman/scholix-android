package com.feldman.scholix.courses

import com.feldman.scholix.AppDest
import com.feldman.scholix.api.platforms.GoogleClassroomPlatform
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.api.platforms.WebtopPlatform
import org.junit.Assert.*
import org.junit.Test

class ProviderPagesTest {
    @Test fun lockerimIsNestedAndStudiesIsNotAMainNavigationPage() {
        assertEquals(AppDest.Locker, AppDest.Lockerim.parent)
        assertFalse(com.feldman.scholix.storage.defaultNavigationOrder.contains("Studies"))
        assertFalse(com.feldman.scholix.storage.defaultNavigationOrder.contains(AppDest.Lockerim.label))
    }

    @Test fun openUniversityCombinesAssignmentsWithGradesWithoutSchoolAttendanceOrSchedule() {
        val pages = supportedProviderPages(OpenAUPlatform())
        assertFalse(AppDest.Homework in pages)
        assertTrue(AppDest.Materials in pages)
        assertTrue(AppDest.Grades in pages)
        assertFalse(CourseSectionKind.ASSIGNMENTS in courseSections(OpenAUPlatform(), org.json.JSONObject()))
        assertFalse(AppDest.Attendance in pages)
        assertFalse(AppDest.Schedule in pages)
        assertFalse(AppDest.Messages in pages)
    }

    @Test fun webtopOffersSchoolPagesWithoutUniversityAssignments() {
        val pages = supportedProviderPages(WebtopPlatform())
        assertTrue(AppDest.Attendance in pages)
        assertTrue(AppDest.Schedule in pages)
        assertTrue(AppDest.Grades in pages)
        assertTrue(AppDest.Messages in pages)
        assertFalse(AppDest.Homework in pages)
        assertFalse(AppDest.Materials in pages)
    }

    @Test fun classroomAndLemidaKeepTheirSupportedWorkPages() {
        assertTrue(AppDest.Homework in supportedProviderPages(GoogleClassroomPlatform()))
        assertFalse(AppDest.Attendance in supportedProviderPages(GoogleClassroomPlatform()))
        assertEquals(listOf(AppDest.Homework, AppDest.Materials), supportedProviderPages(null, lemida = true))
        assertTrue(supportedProviderPages(null).isEmpty())
    }
}
