import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import core.database.OverlayType
import core.database.RFDao
import core.helpers.getScreenSizeInDp
import dorkbox.systemTray.MenuItem
import dorkbox.systemTray.SystemTray
import kotlinx.coroutines.*
import ui.OverlayWindow
import ui.overlay.*
import java.awt.Image
import java.awt.Toolkit
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.system.exitProcess

fun main() = application {

  val appState = AppState

  // wait for database and state to become available
  runBlocking {
    AppState.config = RFDao.loadConfig()
    AppState.windowStates = RFDao.loadWindowStates()
    appState.isEverythingResizable.value = AppState.config.overlayResizingEnabled
    appState.isAboutOverlayVisible.value = AppState.config.firstLaunch
    CombatInteractor.selectedPath = AppState.config.defaultLogPath
    CombatInteractor.shouldSearchEverywhere = AppState.config.searchEverywhere
    AppState.config.firstLaunch = false
    RFDao.saveConfig(AppState.config)
  }

  // copies the tesseract training data from resources to the disk
  try {
    Files.createTempDirectory("tessdata").let { tempDir ->
      AppState.tessTempDirectory = tempDir
      val trainedDataTempPath = tempDir.resolve("eng.traineddata")
      javaClass.getResourceAsStream("/eng.traineddata").use { inputStream ->
        if (inputStream != null) {
          Files.copy(inputStream, trainedDataTempPath, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    }
  } catch (e: Exception) {
    println("Failed to create tessdata directory: ${e.message}")
  }


  val iconImage = painterResource("raidframer.ico").toAwtImage(
    density = Density(1f),
    layoutDirection = LayoutDirection.Ltr,
    size = Size(32f, 32f)
  )

  /*
   * Starts the application by attaching the interactors and listeners.
   */
  CombatInteractor.start()
  OverlayInteractor.start()

  // determine screen size in dp
  val screenSize = getScreenSizeInDp()
  fun loadImageFromDisk(path: String): Image {
    val bufferedImage = ImageIO.read(File(path))
    return bufferedImage.getScaledInstance(-1, -1, Image.SCALE_SMOOTH)
  }

  // *puts system tray entry*
  val tray = SystemTray.get()

  // menu title with icon
  val titleMenuItem = MenuItem(".: Raid Framer :.")
  titleMenuItem.setImage(iconImage)
  tray.menu.add(titleMenuItem)

  // settings and exit buttons
  tray.menu.add(MenuItem("Settings") {
    AppState.isSettingsOverlayVisible.value = true
  })
  tray.menu.add(MenuItem("About") {
    AppState.isAboutOverlayVisible.value = true
  })
  tray.menu.add(MenuItem("Exit") {
    exitApplication()
  })
//
  tray.setImage(iconImage)

  /*
   * Performs tear-down and exits the application.
   */
  fun exitApplication() {
    CombatInteractor.stop()
    OverlayInteractor.stop()
    tray.shutdown()
    tray.remove()
    exitProcess(0)
  }

  fun scaleDpForScreenResolution(dp: Float): Float {
    val screenSize = Toolkit.getDefaultToolkit().screenSize
    val userScreenWidth = screenSize.getWidth()
    val userScreenHeight = screenSize.getHeight()

    val baseScreenWidth = 2560.0
    val baseScreenHeight = 1440.0

    val widthScalingFactor = userScreenWidth / baseScreenWidth
    val heightScalingFactor = userScreenHeight / baseScreenHeight

    val scalingFactor = minOf(widthScalingFactor, heightScalingFactor)

    return dp * scalingFactor.toFloat()
  }

  /* Shows combat-related statistics for the raid. */
  OverlayWindow(
    ".: Raid Framer Combat Overlay :.",
    initialPosition = WindowPosition(
      x = Dp(AppState.windowStates.combatState?.lastPositionXDp ?: scaleDpForScreenResolution(5f)),
      y = Dp(AppState.windowStates.combatState?.lastPositionYDp ?: scaleDpForScreenResolution(850f))
    ),
    initialSize = DpSize(
      width = Dp(AppState.windowStates.combatState?.lastWidthDp ?: scaleDpForScreenResolution(470f)),
      height = Dp(AppState.windowStates.combatState?.lastHeightDp ?: scaleDpForScreenResolution(270f))
    ),
    overlayType = OverlayType.COMBAT,
    isObstructing = AppState.isCombatObstructing,
    isVisible = AppState.isCombatOverlayVisible,
    isEverythingVisible = AppState.isEverythingVisible,
    isResizable = AppState.isEverythingResizable,
    isFocusable = false,
    ::exitApplication
  ) {
    CombatOverlay()
  }

  /* Super Tracker Overlay */
    OverlayWindow(
      ".: Raid Framer Tracker Overlay :.",
      initialPosition = WindowPosition(
        x = Dp(AppState.windowStates.trackerState?.lastPositionXDp ?: scaleDpForScreenResolution(550f)),
        y = Dp(AppState.windowStates.trackerState?.lastPositionYDp ?: scaleDpForScreenResolution(16f))
      ),
      initialSize = DpSize(
        width = Dp(AppState.windowStates.trackerState?.lastWidthDp ?: scaleDpForScreenResolution(470f)),
        height = Dp(AppState.windowStates.trackerState?.lastHeightDp ?: scaleDpForScreenResolution(160f))
      ),
      overlayType = OverlayType.TRACKER,
      isObstructing = AppState.isTrackerObstructing,
      isVisible = AppState.isTrackerOverlayVisible,
      isEverythingVisible = AppState.isEverythingVisible,
      isResizable = AppState.isEverythingResizable,
      isFocusable = false,
      ::exitApplication
    ) {
      TrackerOverlay()
    }

  /* Filters Window */
  OverlayWindow(
    ".: Raid Framer Filters :.",
    initialPosition = WindowPosition(
      x = Dp(AppState.windowStates.aboutState?.lastPositionXDp ?: scaleDpForScreenResolution(512f)),
      y = Dp(AppState.windowStates.aboutState?.lastPositionYDp ?: scaleDpForScreenResolution(512f))
    ),
    initialSize = DpSize(
      width = Dp(AppState.windowStates.aboutState?.lastWidthDp ?: scaleDpForScreenResolution(256f)),
      height = Dp(AppState.windowStates.aboutState?.lastHeightDp ?: scaleDpForScreenResolution(512f))
    ),
    overlayType = OverlayType.FILTERS,
    isObstructing = mutableStateOf(false),
    isVisible = AppState.isFiltersOverlayVisible,
    isEverythingVisible = mutableStateOf(true),
    isResizable = AppState.isEverythingResizable,
    isFocusable = true,
    ::exitApplication
  ) {
    FiltersOverlay()
  }

  /* About Window */
  OverlayWindow(
    ".: Raid Framer About :.",
    initialPosition = WindowPosition(
      x = Dp(AppState.windowStates.aboutState?.lastPositionXDp ?: scaleDpForScreenResolution(860f)),
      y = Dp(AppState.windowStates.aboutState?.lastPositionYDp ?: scaleDpForScreenResolution(350f))
    ),
    initialSize = DpSize(
      width = Dp(AppState.windowStates.aboutState?.lastWidthDp ?: scaleDpForScreenResolution(600f)),
      height = Dp(AppState.windowStates.aboutState?.lastHeightDp ?: scaleDpForScreenResolution(750f))
    ),
    overlayType = OverlayType.ABOUT,
    isObstructing = mutableStateOf(false), // Always show opaque windows
    isVisible = AppState.isAboutOverlayVisible,
    isEverythingVisible = mutableStateOf(true),
    isResizable = AppState.isEverythingResizable,
    isFocusable = false,
    ::exitApplication
  ) {
    AboutOverlay()
  }

  /* Settings Window */
  OverlayWindow(
    ".: Raid Framer Settings :.",
    initialPosition = WindowPosition(
      x = Dp(AppState.windowStates.settingsState?.lastPositionXDp ?: scaleDpForScreenResolution(800f)),
      y = Dp(AppState.windowStates.settingsState?.lastPositionYDp ?: scaleDpForScreenResolution(420f))
    ),
    initialSize = DpSize(
      width = Dp(AppState.windowStates.settingsState?.lastWidthDp ?: scaleDpForScreenResolution(450f)),
      height = Dp(AppState.windowStates.settingsState?.lastHeightDp ?: scaleDpForScreenResolution(740f))
    ),
    overlayType = OverlayType.SETTINGS,
    isObstructing = mutableStateOf(false), // Always show opaque windows
    isVisible = AppState.isSettingsOverlayVisible,
    isEverythingVisible = mutableStateOf(true), // always visible because it's a settings window
    isResizable = AppState.isEverythingResizable,
    isFocusable = true,
    ::exitApplication
  ) {
    SettingsOverlay()
  }
}