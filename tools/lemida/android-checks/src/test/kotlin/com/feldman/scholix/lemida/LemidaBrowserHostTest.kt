package com.feldman.scholix.lemida

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reuse the actual device regressions with controlled WebView callbacks; no live session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
@OptIn(ExperimentalCoroutinesApi::class)
class LemidaBrowserHostTest : LemidaBrowserLifecycleTest() {
    // Controlled methods do not render a real WebView or depend on its event queue.
    @Before fun controlledMainDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun restoreMainDispatcher() { Dispatchers.resetMain() }
}
