package com.feldman.scholix.lemida

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Offline regression: search and both filter rows must never occupy the same space. */
class LemidaHomeworkLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun filtersHaveSeparateRows() = checkLayout(1f)
    @Test fun largeTextOnNarrowScreenStillHasSeparateRows() = checkLayout(2f)

    private fun checkLayout(fontScale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        LemidaHomeworkFilters(19, 0L,
                            listOf(LemidaCourse(1, "מתמטיקה בדידה")),
                            "", {}, null, {}, "all", {})
                    }
                }
            }
        }
        val search = compose.onNodeWithTag("homework-search").fetchSemanticsNode().boundsInRoot
        val courses = compose.onNodeWithTag("homework-course-filters").fetchSemanticsNode().boundsInRoot
        val types = compose.onNodeWithTag("homework-type-filters").fetchSemanticsNode().boundsInRoot
        assertTrue("Course filters overlap search: $search / $courses", search.bottom <= courses.top)
        assertTrue("Type filters overlap courses: $courses / $types", courses.bottom <= types.top)
    }
}
