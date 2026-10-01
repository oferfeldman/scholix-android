@file:Suppress("PROPERTY_WONT_BE_SERIALIZED")

package com.feldman.scholix

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.feldman.motion.MotionDest
import com.feldman.motion.MotionNavigator
import com.feldman.motion.MotionBottomSheetBackdropScope
import com.feldman.motion.MotionBottomSheetEffects
import com.feldman.motion.MotionPaneType
import com.feldman.motion.MotionScaffold
import com.feldman.scholix.pages.AppearanceSettingsPage
import com.feldman.scholix.pages.CrashLogsPage
import com.feldman.scholix.pages.NavigationSettingsPage
import com.feldman.scholix.pages.AddPlatformSheet
import com.feldman.scholix.pages.AttendancePage
import com.feldman.scholix.pages.EditProviderSheet
import com.feldman.scholix.pages.GradesScreen
import com.feldman.scholix.pages.LockerApp
import com.feldman.scholix.pages.LoginPage
import com.feldman.scholix.pages.MessagesScreen
import com.feldman.scholix.pages.MessageDetailPage
import com.feldman.scholix.pages.MessageComposePage
import com.feldman.scholix.pages.MessagePeoplePage
import com.feldman.scholix.pages.MessageOptionsPage
import com.feldman.scholix.pages.MessageToolsPage
import com.feldman.scholix.pages.MessageFoldersPage
import com.feldman.scholix.pages.MessageSignaturePage
import com.feldman.scholix.pages.NotificationsPage
import com.feldman.scholix.pages.PlatformsPage
import com.feldman.scholix.pages.SchedulePage
import com.feldman.scholix.pages.SettingsPage
import com.feldman.scholix.pages.TiktekBookPage
import com.feldman.scholix.pages.TiktekBooksPage
import com.feldman.scholix.pages.TiktekSolutionPage
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
@Parcelize
sealed class AppDest : MotionDest {

    @Serializable
    @Parcelize
    data object More : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "More"
        @IgnoredOnParcel
        @Transient
        override val pane = MotionPaneType.BOTTOM_SHEET
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            val overflowPages = LocalAppState.current.overflowPages
            MotionScaffold(
                fitContentHeight = true
            ) {
                Title("More")
                Section {
                    overflowPages.forEach { destination ->
                        PageItem(
                            key = destination.label,
                            title = destination.label,
                            icon = painterResource(destination.filledIcon),
                            onClick = { onBack(); onNavigate(destination) }
                        )
                    }
                    PageItem(
                        title = "Customize navigation",
                        icon = painterResource(R.drawable.ic_menu),
                        onClick = { onBack(); onNavigate(NavigationSettings) }
                    )
                }
            }
        }
    }

    @Serializable
    @Parcelize
    data object Grades : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Grades"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_docs
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = R.drawable.ic_docs_outline

        @Composable
        override fun Content(
            onNavigate: MotionNavigator,
            onBack: () -> Unit,
            searchQuery: String,
            onFabAction: ((() -> Unit) -> Unit) -> Unit
        ) {
            val state = LocalAppState.current
            GradesScreen(
                preloadedCourses = state.preloadedCourses,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    @Serializable
    @Parcelize
    data object Schedule : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Schedule"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_schedule
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = R.drawable.ic_schedule_outline

        @Composable
        override fun Content(
            onNavigate: MotionNavigator,
            onBack: () -> Unit,
            searchQuery: String,
            onFabAction: ((() -> Unit) -> Unit) -> Unit
        ) {
            SchedulePage(
                platforms = LocalAppState.current.platforms,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    @Serializable
    @Parcelize
    data object Attendance : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Attendance"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_alarm
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = R.drawable.ic_alarm_outline

        @Composable
        override fun Content(
            onNavigate: MotionNavigator,
            onBack: () -> Unit,
            searchQuery: String,
            onFabAction: ((() -> Unit) -> Unit) -> Unit
        ) {
            AttendancePage(modifier = Modifier.fillMaxSize())
        }
    }

    @Serializable
    @Parcelize
    data object Messages : AppDest() {
        @IgnoredOnParcel @Transient override val pane = MotionPaneType.LIST
        @IgnoredOnParcel
        @Transient
        override val label = "Messages"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_message
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = R.drawable.ic_message_outline
        @IgnoredOnParcel
        @Transient
        override val showNavigation = true

        @Composable
        override fun Content(
            onNavigate: MotionNavigator,
            onBack: () -> Unit,
            searchQuery: String,
            onFabAction: ((() -> Unit) -> Unit) -> Unit
        ) {
            MessagesScreen(onNavigate, onBack)
        }
    }

    @Serializable
    @Parcelize
    data class MessageDetail(val providerId: String, val messageId: String, val folder: String = "INBOX", val fromInbox: Int = 1) : AppDest() {
        @IgnoredOnParcel @Transient override val label = "Message"
        @IgnoredOnParcel @Transient override val parent: MotionDest = Messages
        @IgnoredOnParcel @Transient override val pane = MotionPaneType.DETAIL
        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            MessageDetailPage(this, onNavigate, onBack)
        }
    }

    @Serializable
    @Parcelize
    data class ComposeMessage(val providerId: String, val draftKey: String, val source: MessageDetail? = null) : AppDest() {
        @IgnoredOnParcel @Transient override val label = "Compose message"
        @IgnoredOnParcel @Transient override val parent: MotionDest = source ?: Messages
        @IgnoredOnParcel @Transient override val pane = if (source == null) MotionPaneType.DETAIL else MotionPaneType.EXTRA
        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            MessageComposePage(this, onNavigate, onBack)
        }
    }

    @Serializable
    @Parcelize
    data class MessagePeople(val composer: ComposeMessage) : AppDest() {
        @IgnoredOnParcel @Transient override val label = "Choose recipients"
        @IgnoredOnParcel @Transient override val parent: MotionDest = composer
        @IgnoredOnParcel @Transient override val pane = MotionPaneType.BOTTOM_SHEET
        @IgnoredOnParcel @Transient override val showNavigation = false
        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            MessagePeoplePage(composer, onBack)
        }
    }

    @Serializable
    @Parcelize
    data class MessageOptions(val composer: ComposeMessage) : AppDest() {
        @IgnoredOnParcel @Transient override val label = "Message options"
        @IgnoredOnParcel @Transient override val parent: MotionDest = composer
        @IgnoredOnParcel @Transient override val pane = MotionPaneType.BOTTOM_SHEET
        @IgnoredOnParcel @Transient override val showNavigation = false
        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            MessageOptionsPage(composer, onNavigate, onBack)
        }
    }

    @Serializable
    @Parcelize
    data class MessageTools(val providerId: String) : AppDest() {
        @IgnoredOnParcel @Transient override val label = "Mailbox options"
        @IgnoredOnParcel @Transient override val parent: MotionDest = Messages
        @IgnoredOnParcel @Transient override val pane = MotionPaneType.BOTTOM_SHEET
        @IgnoredOnParcel @Transient override val showNavigation = false
        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            MessageToolsPage(providerId, onNavigate, onBack)
        }
    }

    @Serializable
    @Parcelize
    data class MessageFolders(val providerId: String, val folder: String, val moveIds: List<String> = emptyList()) : AppDest() {
        @IgnoredOnParcel @Transient override val label = "Message folders"
        @IgnoredOnParcel @Transient override val parent: MotionDest = Messages
        @IgnoredOnParcel @Transient override val pane = MotionPaneType.DETAIL
        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            MessageFoldersPage(this, onBack)
        }
    }

    @Serializable
    @Parcelize
    data class MessageSignature(val providerId: String) : AppDest() {
        @IgnoredOnParcel @Transient override val label = "Personal signature"
        @IgnoredOnParcel @Transient override val parent: MotionDest = Messages
        @IgnoredOnParcel @Transient override val pane = MotionPaneType.DETAIL
        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            MessageSignaturePage(providerId, onBack)
        }
    }

    @Serializable
    @Parcelize
    data class Notifications(val providerId: String) : AppDest() {
        @IgnoredOnParcel @Transient override val label = "Notifications"
        @IgnoredOnParcel @Transient override val parent: MotionDest = Messages
        @IgnoredOnParcel @Transient override val pane = MotionPaneType.DETAIL
        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            NotificationsPage(providerId, onNavigate, onBack)
        }
    }

    @Serializable
    @Parcelize
    data object Locker : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Locker"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_lock
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = R.drawable.ic_lock_outline
        @IgnoredOnParcel
        @Transient
        override val showNavigation = true

        @Composable
        override fun Content(
            onNavigate: MotionNavigator,
            onBack: () -> Unit,
            searchQuery: String,
            onFabAction: ((() -> Unit) -> Unit) -> Unit
        ) {
            val state = LocalAppState.current
            val vm = state.lockerViewModel ?: androidx.lifecycle.viewmodel.compose.viewModel(
                factory = com.feldman.scholix.pages.LockerViewModelFactory(state.repository)
            )
            LockerApp(
                modifier = Modifier.fillMaxSize(),
                viewModel = vm,
                platforms = state.platforms
            )
        }
    }

    @Serializable
    @Parcelize
    data object Tiktek : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Tiktek"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_live_help
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = R.drawable.ic_live_help_outline

        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            TiktekBooksPage(onOpenBook = { id, title, subjectId ->
                onNavigate(TiktekBook(id, title, subjectId))
            })
        }
    }

    @Serializable
    @Parcelize
    data class TiktekBook(val bookId: String, val bookName: String, val subjectId: String) : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Book"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_docs
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = R.drawable.ic_docs_outline
        @IgnoredOnParcel
        @Transient
        override val parent: MotionDest = Tiktek
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            TiktekBookPage(bookId, bookName, subjectId, onBack) { imageUrl ->
                onNavigate(TiktekSolution(imageUrl))
            }
        }
    }

    @Serializable
    @Parcelize
    data class TiktekSolution(val imageUrl: String) : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Solution"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_docs
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = R.drawable.ic_docs_outline
        @IgnoredOnParcel
        @Transient
        override val parent: MotionDest = Tiktek
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            TiktekSolutionPage(imageUrl, onBack)
        }
    }

    @Serializable
    @Parcelize
    data object Settings : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Settings"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_settings
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = R.drawable.ic_settings_outline

        @Composable
        override fun Content(
            onNavigate: MotionNavigator,
            onBack: () -> Unit,
            searchQuery: String,
            onFabAction: ((() -> Unit) -> Unit) -> Unit
        ) {
            SettingsPage(
                onOpenPlatforms = { onNavigate(Platforms) },
                onOpenAppearance = { onNavigate(Appearance) },
                onOpenNavigation = { onNavigate(NavigationSettings) },
                onOpenCrashLogs = { onNavigate(CrashLogs) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    @Serializable
    @Parcelize
    data object NavigationSettings : AppDest() {
        @IgnoredOnParcel @Transient override val label = "Navigation"
        @IgnoredOnParcel @Transient override val parent: MotionDest = Settings
        @IgnoredOnParcel @Transient override val showNavigation = false

        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            NavigationSettingsPage(onBack)
        }
    }

    @Serializable
    @Parcelize
    data object CrashLogs : AppDest() {
        @IgnoredOnParcel @Transient override val label = "Crash logs"
        @IgnoredOnParcel @Transient override val parent: MotionDest = Settings
        @IgnoredOnParcel @Transient override val showNavigation = false

        @Composable
        override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            CrashLogsPage(onBack)
        }
    }

    @Serializable
    @Parcelize
    data object Appearance : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Appearance"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_settings
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = R.drawable.ic_settings_outline
        @IgnoredOnParcel
        @Transient
        override val parent: MotionDest = Settings
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @Composable
        override fun Content(
            onNavigate: MotionNavigator,
            onBack: () -> Unit,
            searchQuery: String,
            onFabAction: ((() -> Unit) -> Unit) -> Unit
        ) {
            AppearanceSettingsPage(onBack = onBack)
        }
    }

    @Serializable
    @Parcelize
    data object Platforms : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Providers"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_account
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = R.drawable.ic_account_outline
        @IgnoredOnParcel
        @Transient
        override val parent: MotionDest = Settings
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @Composable
        override fun Content(
            onNavigate: MotionNavigator,
            onBack: () -> Unit,
            searchQuery: String,
            onFabAction: ((() -> Unit) -> Unit) -> Unit
        ) {
            val state = LocalAppState.current
            PlatformsPage(
                modifier = Modifier.fillMaxSize(),
                onPlatformsChanged = state.onPlatformsChanged,
                onLogout = state.onLogout,
                onAddPlatform = { onNavigate(AddPlatform) },
                onEditProvider = { providerId -> onNavigate(EditProvider(providerId)) },
                platforms = state.platforms,
                onBack = onBack
            )
        }
    }

    @Serializable
    @Parcelize
    data object AddPlatform : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Add provider"
        @IgnoredOnParcel
        @Transient
        override val parent: MotionDest = Platforms
        @IgnoredOnParcel
        @Transient
        override val pane: MotionPaneType = MotionPaneType.BOTTOM_SHEET
        @IgnoredOnParcel
        @Transient
        override val bottomSheetEffects = MotionBottomSheetEffects(
            blurBackground = true,
            darkenBackground = true,
            backdropScope = MotionBottomSheetBackdropScope.PANE
        )
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @Composable
        override fun Content(
            onNavigate: MotionNavigator,
            onBack: () -> Unit,
            searchQuery: String,
            onFabAction: ((() -> Unit) -> Unit) -> Unit
        ) {
            AddPlatformSheet(
                onAdded = LocalAppState.current.onPlatformsChanged,
                onClose = onBack
            )
        }
    }

    @Serializable
    @Parcelize
    data class EditProvider(val providerId: String) : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Edit provider"
        @IgnoredOnParcel
        @Transient
        override val parent: MotionDest = Platforms
        @IgnoredOnParcel
        @Transient
        override val pane: MotionPaneType = MotionPaneType.BOTTOM_SHEET
        @IgnoredOnParcel
        @Transient
        override val bottomSheetEffects = MotionBottomSheetEffects(
            blurBackground = true,
            darkenBackground = true,
            backdropScope = MotionBottomSheetBackdropScope.PANE
        )
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @Composable
        override fun Content(
            onNavigate: MotionNavigator,
            onBack: () -> Unit,
            searchQuery: String,
            onFabAction: ((() -> Unit) -> Unit) -> Unit
        ) {
            val state = LocalAppState.current
            val provider = state.platforms.firstOrNull { it.id == providerId }
            if (provider != null) {
                EditProviderSheet(
                    provider = provider,
                    onChanged = state.onPlatformsChanged,
                    onClose = onBack
                )
            } else {
                LaunchedEffect(providerId) { onBack() }
            }
        }
    }

    @Serializable
    @Parcelize
    data object Login : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Login"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_login
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @Composable
        override fun Content(
            onNavigate: MotionNavigator,
            onBack: () -> Unit,
            searchQuery: String,
            onFabAction: ((() -> Unit) -> Unit) -> Unit
        ) {
            val state = LocalAppState.current
            LoginPage(
                onLoginSuccess = {
                    state.onLoginSuccess()
                    onNavigate(Grades, resetStack = true)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
