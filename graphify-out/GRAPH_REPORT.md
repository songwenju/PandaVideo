# Graph Report - PandaVideo  (2026-11-15)

## Corpus Check
- Large corpus: 257 files · ~623,606 words. Semantic extraction will be expensive (many Claude tokens). Consider running on a subfolder.

## Summary
- 3040 nodes · 7662 edges · 161 communities (107 shown, 54 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 564 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `6dfda3c5`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Panda Video App Symbols
- Content Repository Test Symbols
- test douban Symbols
- Offline Downloads Symbols
- Fullscreen Tests Symbols
- Playback Database Symbols
- Screen Wake Lock Test Symbols
- test download workflow Symbols
- Android Offline Engine Symbols
- Player Symbols
- Desktop Platform Symbols
- Ios Avatar Picker Symbols
- Playback Test Symbols
- Android Offline Engine Symbols
- Poster Symbols
- Data Management Test Symbols
- Bottom Tab Gesture Test Symbols
- Offline Downloads Symbols
- Android Dlna Device Test Symbols
- Android Seek Preview Symbols
- test app Symbols
- Catalog Playback Device Test Symbols
- App State Test Symbols
- Ios Haptics Symbols
- Catalog Availability Symbols
- test catalog Symbols
- Profile Screen Symbols
- Profile Screen Symbols
- test app Symbols
- Navigation Inset Transition Test Symbols
- Douban Connection Symbols
- Playback Telemetry Symbols
- Skip Playback Test Symbols
- app Symbols
- Platform Symbols
- Android Platform Symbols
- User Store Symbols
- Dlna Casting Test Symbols
- movie sources Symbols
- Models Symbols
- test movie sources Symbols
- Panda Video App Symbols
- Offline Downloads Test Symbols
- App State Test Symbols
- Ios Platform Symbols
- Ios Platform Symbols
- Catalog Database Symbols
- test dytt mount Symbols
- Android Platform Symbols
- Player Symbols
- Dlna Casting Test Symbols
- Dlna Test Symbols
- Playback Media Session Service Symbols
- Settings Store Test Symbols
- Navigation Config Test Symbols
- Jianpian Source Test Symbols
- Catalog Database Test Symbols
- Navigation Icons Symbols
- Playback Preload Test Symbols
- Cache Maintenance Test Symbols
- Actor Source Test Symbols
- Pico Fullscreen Window Symbols
- Home History Test Symbols
- posters Symbols
- Android Fullscreen Device Test Symbols
- Seek Preview Test Symbols
- Android Playback Preloader Symbols
- Picture In Picture Composition Test Symbols
- User Store Behavior Test Symbols
- test playback library Symbols
- test app Symbols
- Douban Session Symbols
- Pending Seek Symbols
- Dlna Symbols
- Ios Douban Session Symbols
- Actor Half Sheet Test Symbols
- Android Platform Symbols
- Retry Prompts Symbols
- Modern Player Symbols
- Video Zoom Test Symbols
- xunlei mcp Symbols
- test media search Symbols
- Haptics Symbols
- test list pagination Symbols
- Settings Store Symbols
- Actor Navigation Device Test Symbols
- Response Cache Test Symbols
- Jianpian Source Symbols
- User Store Symbols
- Sketch Source Test Symbols
- Android Haptics Symbols
- Ios Platform Symbols
- Main Activity Symbols
- Search Screen Symbols
- test posters Symbols
- Search History Store Symbols
- Ios Startup Playback Test Symbols
- App State Test Symbols
- Ios Offline Engine Symbols
- Douban Session Test Symbols
- catalog Symbols
- import catalog Symbols
- site Symbols
- player Symbols
- douban Symbols
- Android Avatar Picker Symbols
- Html Node Symbols
- export website catalog Symbols
- Android Platform Symbols
- Player Audio Controls Symbols
- Settings Screen Symbols
- Content Repository Symbols
- Top Tab Swipe Symbols
- Dlna Symbols
- app Symbols
- player edge Symbols
- Player Overlay Back Handler.android Symbols
- player stability Symbols
- web sync Symbols
- build.gradle Symbols
- gradlew Symbols
- run app Symbols
- install launch agent Symbols
- build shared Symbols
- run Symbols
- service Symbols
- build.gradle Symbols
- README Symbols
- Cache Maintenance Storage Test Symbols
- AGENTS Symbols
- generate launch slogan Symbols
- README Symbols
- Actor Half Sheet Test Symbols
- real stream Symbols
- package Symbols
- autoplay dplayer Symbols
- player flow Symbols
- External References Symbols
- External References Symbols
- External References Symbols
- External References Symbols
- External References Symbols
- External References Symbols
- External References Symbols
- External References Symbols
- External References Symbols
- External References Symbols
- External References Symbols

## God Nodes (most connected - your core abstractions)
1. `PlayerRequest` - 120 edges
2. `PlaybackSession` - 113 edges
3. `mockEngine()` - 108 edges
4. `AppState` - 93 edges
5. `CatalogItem` - 91 edges
6. `TestPlatform` - 78 edges
7. `AppStateTest` - 65 edges
8. `CatalogDatabase` - 61 edges
9. `FakeStorage` - 53 edges
10. `MediaSource` - 51 edges

## Surprising Connections (you probably didn't know these)
- `BrowseFilters` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonMain/kotlin/dev/pandavideo/core/model/Models.kt → AGENTS.md
- `DlnaCasting` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonMain/kotlin/dev/pandavideo/ui/player/DlnaCasting.kt → AGENTS.md
- `OfflineDownloads` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/repository/OfflineDownloads.kt → AGENTS.md
- `PlaybackSession` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonMain/kotlin/dev/pandavideo/core/player/Player.kt → AGENTS.md
- `CatalogPlaybackMatchTest` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonTest/kotlin/dev/pandavideo/CatalogPlaybackMatchTest.kt → AGENTS.md

## Import Cycles
- None detected.

## Communities (161 total, 54 thin omitted)

### Community 0 - "Panda Video App Symbols"
Cohesion: 0.06
Nodes (35): AppDelegate, PlayerHostController, SharedView, SuiXinKanApp, DesktopPlayer, PlayerSurface(), Bool, JFXPanel (+27 more)

### Community 1 - "Content Repository Test Symbols"
Cohesion: 0.07
Nodes (4): BrowseFilters, ContentRepositoryTest, PomoSourceTest, mockEngine()

### Community 10 - "test douban Symbols"
Cohesion: 0.10
Nodes (12): DoubanLists, Top250Parser, DoubanTest, list_page_size(), source_rating(), subject_url(), valid_rating(), validate_browse() (+4 more)

### Community 100 - "Offline Downloads Symbols"
Cohesion: 0.13
Nodes (3): OfflineDownload, UnsupportedOfflineEngine, CatalogAvailabilityTest

### Community 101 - "Fullscreen Tests Symbols"
Cohesion: 0.12
Nodes (9): FullscreenTests, CGRect, Double, String, UIKit, XCTest, XCTestCase, XCUIApplication (+1 more)

### Community 108 - "Android Offline Engine Symbols"
Cohesion: 0.09
Nodes (21): AndroidOfflineEngine, BilibiliMedia, CacheDataSource, Downloader, MediaItem, OfflineEngine, SimpleCache, datasourceinputstream (+13 more)

### Community 113 - "Player Symbols"
Cohesion: 0.20
Nodes (10): PlayerPhase, Buffering, Ended, Error, Idle, Paused, Playing, Preparing (+2 more)

### Community 114 - "Desktop Platform Symbols"
Cohesion: 0.06
Nodes (19): DesktopWindowController, DesktopPlatform, DesktopWindowController, PreferencesStorage, LiveSourceProbeTest, desktopApplication(), main(), DesktopWindowController (+11 more)

### Community 115 - "Ios Avatar Picker Symbols"
Cohesion: 0.22
Nodes (8): NSObject, rememberAvatarPicker(), NSObject, PHPickerViewController, PHPickerViewControllerDelegateProtocol, cgrectmake, cgsizemake, usecontents

### Community 12 - "Playback Test Symbols"
Cohesion: 0.15
Nodes (5): PlayerRequest, FakePlayer, PlaybackTest, adjustedLevel(), quickSeekPosition()

### Community 120 - "Android Offline Engine Symbols"
Cohesion: 0.16
Nodes (8): OfflineMediaDeviceTest, Listener, OfflineMediaSourceFactory, androidx, Factory, Listener, Context, IntArray

### Community 13 - "Poster Symbols"
Cohesion: 0.09
Nodes (28): PosterCacheTest, CatalogPosterCard(), defaultPosterRequest(), Poster(), posterImageLoader(), posterRequest(), prefetchPosters(), posterSharedTransition() (+20 more)

### Community 137 - "Data Management Test Symbols"
Cohesion: 0.11
Nodes (6): ParserRules, ParserRulesStore, UpdateStore, YouthPreferences, YouthStore, DataManagementTest

### Community 139 - "Offline Downloads Symbols"
Cohesion: 0.33
Nodes (6): DownloadStatus, Completed, Downloading, Failed, Paused, Queued

### Community 14 - "Android Dlna Device Test Symbols"
Cohesion: 0.15
Nodes (14): AndroidDlnaDeviceTest, discoverAndroidDlna(), Context, connectivitymanager, datagrampacket, datagramsocket, inet4address, inetaddress (+6 more)

### Community 142 - "Android Seek Preview Symbols"
Cohesion: 0.23
Nodes (5): AndroidSeekPreviewDeviceTest, AndroidSeekPreview, Request, FrameExtractor, Request

### Community 157 - "Ios Haptics Symbols"
Cohesion: 0.20
Nodes (5): IosHaptics, uiimpactfeedbackgenerator, uiimpactfeedbackstyle, uinotificationfeedbackgenerator, uinotificationfeedbacktype

### Community 158 - "Catalog Availability Symbols"
Cohesion: 0.12
Nodes (17): CacheMaintenanceState, CacheUsage, ResourceStatus, measure(), cached(), key(), visible(), Available (+9 more)

### Community 16 - "Profile Screen Symbols"
Cohesion: 0.06
Nodes (66): ProfileLibrary, ProfileTitle, CatalogSkeleton(), rememberSkeletonAlpha(), SkeletonBox(), DownloadLibraryPage(), DownloadPosterCard(), EmptyLibraryHint() (+58 more)

### Community 160 - "Profile Screen Symbols"
Cohesion: 0.50
Nodes (4): ClearTarget, Downloads, Favorites, History

### Community 169 - "Douban Connection Symbols"
Cohesion: 0.20
Nodes (3): DoubanConnectionState, DoubanSession, verify()

### Community 173 - "Playback Telemetry Symbols"
Cohesion: 0.13
Nodes (11): PlaybackStartupSample, PlaybackTelemetry, StartupOutcome, PlayerState, VideoFrameVisibility, VideoFrameVisibilityTest, AudioOnly, Cancelled (+3 more)

### Community 19 - "Skip Playback Test Symbols"
Cohesion: 0.17
Nodes (5): SkipDurations, SkipDurationsStore, Host, SkipPlaybackTest, SkipDurationsDialog()

### Community 2 - "app Symbols"
Cohesion: 0.07
Nodes (48): AppError, Config, DyttDetailParser, DyttSearchParser, EightysgodDetailParser, EightysgodSearchParser, RequestHandler, ResultRegistry (+40 more)

### Community 20 - "Platform Symbols"
Cohesion: 0.08
Nodes (12): PlatformServices, avatarUri(), PlayerSurface(), rememberAvatarPicker(), rememberAvatarPicker(), Color, dev, Flow (+4 more)

### Community 21 - "Android Platform Symbols"
Cohesion: 0.14
Nodes (6): AndroidPlayerConnectionDeviceTest, BufferingVideoDeviceTest, PlaybackPreloadDeviceTest, AndroidPlayer, MediaSource, Player

### Community 22 - "User Store Symbols"
Cohesion: 0.26
Nodes (7): EpisodeProgress, SavedTitle, UserBackup, canonicalProgress(), takeKeysByRecency(), titlePage(), decodefromstring

### Community 23 - "Dlna Casting Test Symbols"
Cohesion: 0.15
Nodes (4): DlnaCasting, DlnaCastingTest, TestPlayer, Job

### Community 24 - "movie sources Symbols"
Cohesion: 0.10
Nodes (21): SearchLinks, SearchOutcome, SiteRedirects, Source, SourceError, YangguangSearchParser, category_type(), catalog() (+13 more)

### Community 25 - "Models Symbols"
Cohesion: 0.14
Nodes (8): MatchResult, ModelsTest, appendCatalog(), formatMinutesSeconds(), isTelevisionDrama(), isWebUrl(), channel, serialname

### Community 26 - "test movie sources Symbols"
Cohesion: 0.20
Nodes (3): MovieSourceTests, search_page(), source()

### Community 27 - "Panda Video App Symbols"
Cohesion: 0.08
Nodes (29): Failed, Ready, StartupState, NavigationDestinationContent(), PandaVideoApp(), PandaVideoContent(), SearchEntry(), StartupFailureScreen() (+21 more)

### Community 28 - "Offline Downloads Test Symbols"
Cohesion: 0.17
Nodes (7): OfflineDownloads, Engine, OfflineDownloadsTest, OfflineEngine, Job, StateFlow, OfflineEngine

### Community 3 - "App State Test Symbols"
Cohesion: 0.11
Nodes (3): AppState, AppStateTest, TestPlatform

### Community 30 - "Ios Platform Symbols"
Cohesion: 0.09
Nodes (8): IosPlatform, MainViewController(), dev, StateFlow, UIViewController, NSSearchPathDirectory, SharedFlow, UISlider

### Community 32 - "Ios Platform Symbols"
Cohesion: 0.11
Nodes (10): IosPlayer, Prepared, discoverIosDlna(), AVAssetImageGenerator, AVPlayer, AVPlayerItem, AVPlayerLayer, Job (+2 more)

### Community 33 - "Catalog Database Symbols"
Cohesion: 0.14
Nodes (14): BundledCatalogEntry, BundledCatalogSnapshot, BundledPlaybackIdentities, BundledPlaybackIdentity, StoredResponse, SQLiteConnection, buffer, bundledsqlitedriver (+6 more)

### Community 34 - "test dytt mount Symbols"
Cohesion: 0.10
Nodes (4): CopyLinkTest, DyttTest, MountTest, post_api()

### Community 35 - "Android Platform Symbols"
Cohesion: 0.10
Nodes (4): AndroidFullscreenDeviceTest, AndroidPlatform, dev, StateFlow

### Community 37 - "Player Symbols"
Cohesion: 0.07
Nodes (22): Episode, PlaybackInfo, PlaybackSession, PlaybackState, PlayerCapabilities, PlayerController, Preload, TapZone (+14 more)

### Community 4 - "Dlna Test Symbols"
Cohesion: 0.24
Nodes (3): DlnaXml, DlnaTest, parseDlnaDevice()

### Community 40 - "Playback Media Session Service Symbols"
Cohesion: 0.12
Nodes (17): PlaybackMediaSessionService, SessionCallback, Callback, Intent, ListenableFuture, MediaSession, MediaSessionService, Bundle (+9 more)

### Community 41 - "Settings Store Test Symbols"
Cohesion: 0.19
Nodes (3): SettingsStore, FakeStorage, SettingsStoreTest

### Community 42 - "Navigation Config Test Symbols"
Cohesion: 0.12
Nodes (17): NavigationConfigTest, browseYearOptions(), annualTvFilters(), BrowseFilterPanel(), FilterChoice(), ChoiceRow(), Modifier, bottomtabs (+9 more)

### Community 43 - "Jianpian Source Test Symbols"
Cohesion: 0.12
Nodes (8): CatalogItem, CatalogPlaybackMatchTest, JianpianSourceTest, jpDetail(), pomoMedia(), pomoRow(), pomoSearch(), JsonObject

### Community 44 - "Catalog Database Test Symbols"
Cohesion: 0.17
Nodes (5): CatalogDatabase, StoredCatalog, CatalogDatabaseTest, execute(), T

### Community 45 - "Navigation Icons Symbols"
Cohesion: 0.08
Nodes (28): NavigationIcons, PlayerTransportIcons, BottomNavigationTabs(), DownloadAction(), DownloadsScreen(), ActorAvatar(), ActorRail(), ImageVector (+20 more)

### Community 48 - "Cache Maintenance Test Symbols"
Cohesion: 0.24
Nodes (4): ContentCacheUsage, CacheMaintenanceTest, completabledeferred, formatcachebytes

### Community 49 - "Actor Source Test Symbols"
Cohesion: 0.08
Nodes (13): ActorSearchState, ActorFilmography, ActorProfileField, ActorRef, ActorSuggestions, ActorWork, ActorSource, ActorSourceTest (+5 more)

### Community 50 - "Pico Fullscreen Window Symbols"
Cohesion: 0.27
Nodes (6): PicoFullscreenWindow, WindowSize, Activity, context, log, method

### Community 52 - "Home History Test Symbols"
Cohesion: 0.25
Nodes (8): CatalogState, HistoryTitle, HomeHistoryTest, CatalogContent(), catalogWithoutHistory(), historyCatalogMatch(), historyCatalogPoster(), homeHistoryFor()

### Community 53 - "posters Symbols"
Cohesion: 0.22
Nodes (9): PosterCache, PosterError, PosterRedirects, approved_url(), fetch_image(), image_type(), Exception, HTTPRedirectHandler (+1 more)

### Community 54 - "Android Fullscreen Device Test Symbols"
Cohesion: 0.16
Nodes (19): FullscreenTestActivity, AudioModeDeviceTest, CatalogPlaybackDeviceTest, Activity, Bundle, androidjunit4, asserttrue, assumetrue (+11 more)

### Community 55 - "Seek Preview Test Symbols"
Cohesion: 0.15
Nodes (3): PreviewFrame, Controller, SeekPreviewTest

### Community 56 - "Android Playback Preloader Symbols"
Cohesion: 0.18
Nodes (12): AndroidPlaybackPreloader, onCompleted(), onPlaybackStateChanged(), onTimelineChanged(), startupPosition(), ExoPlayer, MediaItem, Timeline (+4 more)

### Community 57 - "Picture In Picture Composition Test Symbols"
Cohesion: 0.21
Nodes (4): EmptyApplier, PictureInPictureCompositionTest, PersistentVideoContent(), AbstractApplier

### Community 6 - "test playback library Symbols"
Cohesion: 0.07
Nodes (17): PlaybackLibrary, EpisodeParser, PlayerError, PlaybackLibraryTests, PlayerTests, validate_item(), media_type(), page_url() (+9 more)

### Community 60 - "test app Symbols"
Cohesion: 0.13
Nodes (3): XunleiDownloadTest, Handler, SimpleHTTPRequestHandler

### Community 62 - "Douban Session Symbols"
Cohesion: 0.13
Nodes (12): AndroidDoubanSession, UnsupportedDoubanSession, DoubanLoginView(), isDoubanAccountLanding(), isDoubanLoginNavigation(), isDoubanResponseCookie(), isDoubanSessionUrl(), Modifier (+4 more)

### Community 63 - "Pending Seek Symbols"
Cohesion: 0.21
Nodes (3): PendingSeek, Request, PendingSeekTest

### Community 64 - "Dlna Symbols"
Cohesion: 0.24
Nodes (6): DlnaClient, DlnaDevice, DlnaException, DlnaPosition, xmlEscape(), Exception

### Community 65 - "Ios Douban Session Symbols"
Cohesion: 0.17
Nodes (11): NSObject, IosDoubanSession, DoubanLoginView(), currentEpochMillis(), Modifier, NSObject, NSHTTPCookie, WKNavigationDelegateProtocol (+3 more)

### Community 66 - "Actor Half Sheet Test Symbols"
Cohesion: 0.10
Nodes (9): ActorHalfSheetTest, PlatformContext, FixturePlayer, PlayerSleepTimerUiTest, SceneHarness, TopTabRefreshGestureTest, AutoCloseable, PlatformContext (+1 more)

### Community 67 - "Android Platform Symbols"
Cohesion: 0.11
Nodes (10): SharedPrefsStorage, KeyValueStorage, UpdateNotice, asstateflow, encodetostring, mutablestateflow, random, serializable (+2 more)

### Community 69 - "Retry Prompts Symbols"
Cohesion: 0.19
Nodes (4): RetryPrompt, RetryPrompts, RetryPromptsTest, failureDetails()

### Community 7 - "Modern Player Symbols"
Cohesion: 0.07
Nodes (41): AdjustKind, Adjustment, TapFeedback, TapFeedbackKind, FormattingTest, PlayerSeekGestureTest, previewPosition(), progressFraction() (+33 more)

### Community 71 - "Video Zoom Test Symbols"
Cohesion: 0.20
Nodes (8): VideoZoomTest, videoPinch(), videoZoom(), Modifier, awaiteachgesture, awaitfirstdown, calculatezoom, pointereventpass

### Community 72 - "xunlei mcp Symbols"
Cohesion: 0.35
Nodes (5): XunleiMcpClient, XunleiMcpError, submit_xunlei_mcp_download(), Any, Exception

### Community 74 - "Haptics Symbols"
Cohesion: 0.12
Nodes (7): SearchState, Haptics, SilentHaptics, Flow, assharedflow, mutablesharedflow, stateflow

### Community 75 - "test list pagination Symbols"
Cohesion: 0.20
Nodes (6): ListPagination, assert, { ListPagination, PAGE_SIZES }, test, frontend_static_list_pagination_page_sizes, ref_node_test

### Community 77 - "Settings Store Symbols"
Cohesion: 0.29
Nodes (5): AppSettings, ThemeMode, Dark, Light, System

### Community 79 - "Response Cache Test Symbols"
Cohesion: 0.12
Nodes (5): CatalogPage, Top250Categories, Top250CategoriesTest, CacheMaintenanceStorageTest, ResponseCacheTest

### Community 80 - "Jianpian Source Symbols"
Cohesion: 0.05
Nodes (23): SearchItem, SearchResult, SourceCount, ApiError, JianpianSource, Server, PomoSource, SearchPresentation (+15 more)

### Community 83 - "User Store Symbols"
Cohesion: 0.14
Nodes (3): UserStore, PlaybackDatabaseTest, currentEpochMillis()

### Community 84 - "Sketch Source Test Symbols"
Cohesion: 0.13
Nodes (9): SketchSource, SketchSourceTest, SketchCatalogCacheTest, descendants(), dieyinSketchFixture(), sketchFixture(), HtmlNode, encodeurlparameter (+1 more)

### Community 86 - "Android Haptics Symbols"
Cohesion: 0.23
Nodes (7): AndroidHaptics, LongArray, IntArray, VibrationEffect, Vibrator, build, vibratormanager

### Community 87 - "Ios Platform Symbols"
Cohesion: 0.10
Nodes (18): IosStorage, PipDelegate, PlayerContainerView, PlayerSurface(), AVPictureInPictureController, AVPictureInPictureControllerDelegateProtocol, Color, Modifier (+10 more)

### Community 89 - "Main Activity Symbols"
Cohesion: 0.27
Nodes (7): MainActivity, ComponentActivity, Configuration, Bundle, enableedgetoedge, setcontent, systembarstyle

### Community 9 - "Search Screen Symbols"
Cohesion: 0.11
Nodes (26): NestedScrollConnection, HighlightedTitle(), SearchField(), SearchPage(), SearchResults(), SearchRow(), SearchSkeleton(), SearchStatusMessage() (+18 more)

### Community 96 - "App State Test Symbols"
Cohesion: 0.17
Nodes (3): OfflineEngine, dev, OfflineEngine

### Community 97 - "Ios Offline Engine Symbols"
Cohesion: 0.06
Nodes (30): WebViewClient, OfflineAsset, OfflineEngine, Delegate, IosOfflineEngine, Pending, DoubanLoginView(), AVAssetDownloadDelegateProtocol (+22 more)

### Community 104 - "catalog Symbols"
Cohesion: 0.70
Nodes (4): load(), renderItem(), resetPage(), syncFilters()

### Community 107 - "import catalog Symbols"
Cohesion: 0.08
Nodes (28): main(), running_pid(), main(), render(), vector_paths(), export_top250_categories(), fetch(), main() (+20 more)

### Community 11 - "player Symbols"
Cohesion: 0.08
Nodes (36): clearBuffering(), destroyMedia(), exitRotation(), failure(), fullscreenChanged(), isPlayerFullscreen(), loadEpisode(), renderEpisodes() (+28 more)

### Community 152 - "Android Avatar Picker Symbols"
Cohesion: 0.07
Nodes (34): rememberAvatarPicker(), activityresultcontracts, assertis, assertnotnull, assertnull, assertsame, bitmap, bitmapfactory (+26 more)

### Community 170 - "Html Node Symbols"
Cohesion: 0.67
Nodes (3): attr(), hasClass(), text()

### Community 18 - "export website catalog Symbols"
Cohesion: 0.17
Nodes (24): capture(), main(), source_key(), App, Minimal legacy SSE client for Xunlei's MCP download service., Deterministic device QA API; never used by the production app., Preserve available Douban category pages from a local PandaVideo website as…, argparse (+16 more)

### Community 29 - "Android Platform Symbols"
Cohesion: 0.10
Nodes (20): createAndroidPlayerView(), PlayerSurface(), Context, Modifier, PlayerView, activity, activityinfo, aspectratioframelayout (+12 more)

### Community 36 - "Player Audio Controls Symbols"
Cohesion: 0.06
Nodes (58): DesktopVideoFrame(), DlnaRemoteFrame(), EpisodeCacheSheet(), MiniPlayerBar(), AudioActionButton(), AudioReturnButton(), PlayerAudioButton(), PlayerSleepTimerButton() (+50 more)

### Community 39 - "Settings Screen Symbols"
Cohesion: 0.11
Nodes (32): PlayerSleepTimerSheet(), SleepTimerOption(), SleepTimerOptionsContent(), EditProfileDialog(), ProfileEditorCard(), ProfileFieldHeading(), CacheUsageRow(), SettingsCaption() (+24 more)

### Community 46 - "Content Repository Symbols"
Cohesion: 0.11
Nodes (27): actorCatalogWork(), actorProfile(), actorWorks(), searchActors(), dev, assertfailswith, assertfalse, async (+19 more)

### Community 5 - "Top Tab Swipe Symbols"
Cohesion: 0.10
Nodes (23): PlayerOverlayBackHandler(), TopTabPager(), topTabSwipe(), ActorHalfSheet(), Modifier, Modifier, abs, animate (+15 more)

### Community 68 - "Dlna Symbols"
Cohesion: 0.11
Nodes (17): dlnaIpv4(), dlnaTime(), parseDlnaTime(), ssdpLocation(), ssdpRequest(), ssdpRequests(), xmlUnescape(), ByteArray (+9 more)

### Community 8 - "app Symbols"
Cohesion: 0.08
Nodes (36): appendBrowserDownload(), copyDownloadLink(), formatSize(), hasPlayback(), hideMagnetNotice(), hideNotice(), loadStatus(), metadataItem() (+28 more)

### Community 85 - "player edge Symbols"
Cohesion: 0.22
Nodes (5): assert, { chromium }, { fixturePath, outputPath, launchOptions }, fs, results

### Community 98 - "player stability Symbols"
Cohesion: 0.33
Nodes (3): assert, { chromium }, { fixturePath, outputPath, launchOptions }

### Community 105 - "web sync Symbols"
Cohesion: 0.40
Nodes (4): assert, { chromium }, { fixturePath, launchOptions, outputPath }, frontend_tests_browser_test_support_outputpath

### Community 111 - "build.gradle Symbols"
Cohesion: 0.50
Nodes (3): javaversion, jvmtarget, targetformat

### Community 112 - "gradlew Symbols"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 147 - "Cache Maintenance Storage Test Symbols"
Cohesion: 0.21
Nodes (9): cachepolicy, datasource, errorresult, files, headersof, ktornetworkfetcherfactory, mockengine, platformcontext (+1 more)

### Community 15 - "AGENTS Symbols"
Cohesion: 0.15
Nodes (10): Capability contract, Graphify query entry, PandaVideo agent guide, Project boundaries, Refresh and verify, PandaVideo mobile, 主要功能, 开始使用 (+2 more)

### Community 17 - "README Symbols"
Cohesion: 0.07
Nodes (25): NAS 原生部署, 局域网域名, 数据及升级, macOS 后台服务, 功能与来源, 在线播放与下载, 测试与相关文档, 熊猫视频 · PandaVideo 网页版 (+17 more)

### Community 51 - "Actor Half Sheet Test Symbols"
Cohesion: 0.11
Nodes (35): advancetimeby, animatedcontent, assertequals, asskiabitmap, broadcastframeclock, canvas, canvaslayerscomposescene, composescenepointer (+27 more)

### Community 70 - "real stream Symbols"
Cohesion: 0.18
Nodes (10): assert, { chromium }, fs, { outputPath, launchOptions }, fs, os, path, ref_node_fs (+2 more)

### Community 76 - "package Symbols"
Cohesion: 0.18
Nodes (10): dependencies, playwright, engines, node, name, private, scripts, test (+2 more)

### Community 81 - "autoplay dplayer Symbols"
Cohesion: 0.22
Nodes (8): assert, { chromium }, { fixturePath, outputPath, launchOptions }, media, { chromium }, { launchOptions }, playwright, frontend_tests_browser_test_support_launchoptions

### Community 82 - "player flow Symbols"
Cohesion: 0.22
Nodes (8): assert, { chromium }, { fixturePath, launchOptions }, assert, { chromium }, { fixturePath, launchOptions }, frontend_tests_browser_test_support_fixturepath, ref_node_assert

## Knowledge Gaps
- **167 isolated node(s):** `Request`, `CacheMaintenanceState`, `Config`, `Failed`, `BundledCatalogEntry` (+162 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 860 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **54 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PlatformServices` connect `Platform Symbols` to `Android Platform Symbols`, `Player Audio Controls Symbols`, `App State Test Symbols`, `Dlna Casting Test Symbols`, `Modern Player Symbols`, `Settings Screen Symbols`, `Douban Connection Symbols`, `Haptics Symbols`, `Player Symbols`, `Android Platform Symbols`, `Profile Screen Symbols`, `Desktop Platform Symbols`, `Ios Avatar Picker Symbols`, `Android Avatar Picker Symbols`, `Models Symbols`, `Panda Video App Symbols`, `Ios Platform Symbols`?**
  _High betweenness centrality (0.093) - this node is a cross-community bridge._
- **Why does `PlayerRequest` connect `Playback Test Symbols` to `Content Repository Test Symbols`, `App State Test Symbols`, `Skip Playback Test Symbols`, `Android Avatar Picker Symbols`, `Catalog Playback Device Test Symbols`, `Models Symbols`, `Panda Video App Symbols`, `App State Test Symbols`, `Offline Downloads Test Symbols`, `Player Symbols`, `Jianpian Source Test Symbols`, `Content Repository Symbols`, `Playback Preload Test Symbols`, `Actor Source Test Symbols`, `Actor Half Sheet Test Symbols`, `Android Fullscreen Device Test Symbols`, `Actor Half Sheet Test Symbols`, `Android Platform Symbols`, `Haptics Symbols`, `Response Cache Test Symbols`, `Jianpian Source Symbols`, `Offline Downloads Symbols`, `Desktop Platform Symbols`?**
  _High betweenness centrality (0.070) - this node is a cross-community bridge._
- **Why does `PlaybackSession` connect `Player Symbols` to `Actor Half Sheet Test Symbols`, `Player Audio Controls Symbols`, `Modern Player Symbols`, `Playback Test Symbols`, `Playback Telemetry Symbols`, `Playback Preload Test Symbols`, `AGENTS Symbols`, `Skip Playback Test Symbols`, `Android Fullscreen Device Test Symbols`, `Catalog Playback Device Test Symbols`, `Models Symbols`?**
  _High betweenness centrality (0.050) - this node is a cross-community bridge._
- **Are the 16 inferred relationships involving `PlayerRequest` (e.g. with `.batchSelectionResolvesEachEpisodeInSourceOrderAndKeepsPlaybackSnapshot()` and `.cachePickerKeepsSingleFilmWithoutAnEpisodeListDownloadable()`) actually correct?**
  _`PlayerRequest` has 16 INFERRED edges - model-reasoned connections that need verification._
- **Are the 21 inferred relationships involving `PlaybackSession` (e.g. with `SkipDurations` and `PlaybackTelemetry`) actually correct?**
  _`PlaybackSession` has 21 INFERRED edges - model-reasoned connections that need verification._
- **Are the 107 inferred relationships involving `mockEngine()` (e.g. with `.africaDocumentarySeriesDoesNotUseTheTelevisionOnlySearchFilter()` and `.animationAndDocumentaryCatalogsCanPlaySeriesLikeSearch()`) actually correct?**
  _`mockEngine()` has 107 INFERRED edges - model-reasoned connections that need verification._
- **Are the 2 inferred relationships involving `CatalogItem` (e.g. with `.truncatedArtworkCacheRefreshesToFullCastWithoutWaitingForExpiry()` and `.verifiedPlaybackPageIsRecheckedWithoutSearchingOrTreatingOfflineAsMissing()`) actually correct?**
  _`CatalogItem` has 2 INFERRED edges - model-reasoned connections that need verification._
## Coverage and Static Analysis Limitations

- Code-only extraction; documentation, images, binaries, and unsupported XML/plist formats are not indexed.
- Community names are derived from their dominant source file.
- Imported/unresolved nodes can lack source locations. INFERRED edges are heuristics; missing edges do not establish absence of a runtime relationship.
- The graph is undirected; structural paths are not ordered execution traces.
- Git commit identity excludes uncommitted edits. Check source SHA-256 hashes in coverage.json for snapshot freshness.
- graphify-out/ was written into the working tree; portable outputs can be shared, while AST caches and machine-local sidecars are ignored.

Parser warnings: 14 files may be only partially extracted.

- `frontend/tests/browser/fixtures/dplayer-test0.ts`: first parser error at L1.
- `frontend/tests/browser/fixtures/dplayer-test1.ts`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/app/ActorBrowser.kt`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/app/AppState.kt`: first parser error at L53.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/app/DoubanConnection.kt`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/core/player/SeekPreview.kt`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/html/HtmlNode.kt`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/repository/BundledPosters.kt`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/repository/CacheMaintenance.kt`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/repository/CatalogAvailability.kt`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/repository/ContentRepository.kt`: first parser error at L50.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/repository/JianpianSource.kt`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/ui/search/ActorUi.kt`: first parser error at L1.
- `mobile/tools/feature-verification.init.gradle`: first parser error at L7.

Integrity diagnostics: 0 dangling edges, 0 missing-endpoint edges, 62 self-loops, 172 same-endpoint edge collisions in the raw extraction. Collisions can combine distinct static relationships in the exported graph; inspect source for full detail.
