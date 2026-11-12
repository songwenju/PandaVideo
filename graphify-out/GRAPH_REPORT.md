# Graph Report - PandaVideo  (2026-11-12)

## Corpus Check
- 161 files · ~97,040 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 35 file(s) not represented in the graph (top: .xml 13, (none) 5, .css 4)

## Summary
- 2328 nodes · 5237 edges · 150 communities (103 shown, 47 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 257 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `519f070d`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Panda Video App Symbols
- Navigation Config Test Symbols
- test douban Symbols
- Fullscreen Tests Symbols
- Pico Fullscreen Window Symbols
- Android Seek Preview Symbols
- test download workflow Symbols
- Android Fullscreen Device Test Symbols
- test app Symbols
- test app Symbols
- Top Tab Refresh Gesture Test Symbols
- test catalog Symbols
- Playback Test Symbols
- Offline Downloads Symbols
- Content Repository Test Symbols
- App State Test Symbols
- server Symbols
- Android Dlna Device Test Symbols
- Desktop Player Symbols
- Profile Screen Symbols
- Profile Screen Symbols
- Player Seek Gesture Test Symbols
- Modern Player Symbols
- App State Test Symbols
- Modern Player Symbols
- Modern Player Symbols
- Audio Mode Device Test Symbols
- Cache Maintenance Test Symbols
- Offline Downloads Symbols
- app Symbols
- Platform Symbols
- Search Screen Symbols
- Dlna Casting Test Symbols
- movie sources Symbols
- User Store Symbols
- Platform Symbols
- Offline Downloads Test Symbols
- Modern Player Symbols
- Playback Media Session Service Symbols
- App State Test Symbols
- Ios Platform Symbols
- Ios Offline Engine Symbols
- Ios Platform Symbols
- Catalog Database Test Symbols
- test dytt mount Symbols
- Android Platform Symbols
- Dlna Symbols
- Settings Store Symbols
- Jianpian Source Symbols
- Android Offline Engine Symbols
- Catalog Components Symbols
- test player Symbols
- app Symbols
- Picture In Picture Composition Test Symbols
- Navigation Icons Symbols
- Cache Maintenance Storage Test Symbols
- Dlna Casting Symbols
- test movie sources Symbols
- Main Symbols
- Ios Platform Symbols
- Profile Screen Symbols
- Offline Media Device Test Symbols
- posters Symbols
- Android Platform Symbols
- Seek Preview Test Symbols
- Modern Player Symbols
- Android Offline Engine Symbols
- Android Haptics Symbols
- test playback library Symbols
- Desktop Platform Symbols
- Retry Prompts Symbols
- Ios Platform Symbols
- Youth Store Symbols
- Dlna Symbols
- Settings Screen Symbols
- Data Management Test Symbols
- test posters Symbols
- xunlei mcp Symbols
- test media search Symbols
- Haptics Symbols
- test list pagination Symbols
- Ios Haptics Symbols
- Ios Avatar Picker Symbols
- Panda Video App Symbols
- App State Test Symbols
- Player Symbols
- Catalog Availability Symbols
- Ios Offline Engine Symbols
- Search History Store Symbols
- Response Cache Test Symbols
- Video Zoom Test Symbols
- Android Seek Preview Device Test Symbols
- Android Platform Symbols
- Player Symbols
- Dlna Test Symbols
- test app Symbols
- Html Node Symbols
- catalog Symbols
- Seek Preview Symbols
- site Symbols
- player Symbols
- douban Symbols
- Dlna Casting Symbols
- Shared Transitions Symbols
- Android Platform Symbols
- Top Tab Swipe Symbols
- Android Avatar Picker Symbols
- generate app icons Symbols
- Dlna Symbols
- Downloads Screen Symbols
- app Symbols
- player edge Symbols
- Panda Video App Symbols
- service Symbols
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
- README Symbols
- test app Symbols
- Picture In Picture Composition Test Symbols
- Bundled Posters Symbols
- real stream Symbols
- package Symbols
- autoplay dplayer Symbols
- player flow Symbols
- README Symbols
- External References Symbols
- External References Symbols
- External References Symbols

## God Nodes (most connected - your core abstractions)
1. `PlaybackSession` - 60 edges
2. `PlayerRequest` - 59 edges
3. `TestPlatform` - 56 edges
4. `mockEngine()` - 54 edges
5. `PlatformServices` - 48 edges
6. `CatalogItem` - 46 edges
7. `AppStateTest` - 43 edges
8. `PlayerController` - 38 edges
9. `ContentRepositoryTest` - 36 edges
10. `FakePlayer` - 35 edges

## Surprising Connections (you probably didn't know these)
- `PlaybackSession` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonMain/kotlin/dev/pandavideo/core/player/Player.kt → AGENTS.md
- `OfflineDownloads` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/repository/OfflineDownloads.kt → AGENTS.md
- `DlnaCasting` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonMain/kotlin/dev/pandavideo/ui/player/DlnaCasting.kt → AGENTS.md
- `BrowseFilters` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonMain/kotlin/dev/pandavideo/core/model/Models.kt → AGENTS.md
- `CatalogPlaybackMatchTest` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonTest/kotlin/dev/pandavideo/CatalogPlaybackMatchTest.kt → AGENTS.md

## Import Cycles
- None detected.

## Communities (150 total, 47 thin omitted)

### Community 0 - "Panda Video App Symbols"
Cohesion: 0.08
Nodes (23): AppDelegate, PlayerHostController, SharedView, SuiXinKanApp, Bool, .preferredStatusBarStyle, .prefersHomeIndicatorAutoHidden, .prefersStatusBarHidden (+15 more)

### Community 1 - "Navigation Config Test Symbols"
Cohesion: 0.11
Nodes (18): NavigationConfigTest, browseYearOptions(), annualTvFilters(), BrowseFilterPanel(), FilterChoice(), ChoiceRow(), Modifier, bottomtabs (+10 more)

### Community 10 - "test douban Symbols"
Cohesion: 0.09
Nodes (13): DoubanLists, Top250Parser, DoubanTest, list_page_size(), source_rating(), subject_url(), valid_rating(), validate_browse() (+5 more)

### Community 101 - "Fullscreen Tests Symbols"
Cohesion: 0.25
Nodes (3): FullscreenTests, XCTest, XCTestCase

### Community 102 - "Pico Fullscreen Window Symbols"
Cohesion: 0.27
Nodes (6): PicoFullscreenWindow, WindowSize, Activity, context, log, method

### Community 103 - "Android Seek Preview Symbols"
Cohesion: 0.19
Nodes (9): AndroidSeekPreview, Request, FrameExtractor, bitmap, bytearrayoutputstream, contextcompat, mimetypes, presentation (+1 more)

### Community 108 - "Android Fullscreen Device Test Symbols"
Cohesion: 0.32
Nodes (6): FullscreenTestActivity, Activity, Bundle, asserttrue, systemclock, textview

### Community 12 - "Playback Test Symbols"
Cohesion: 0.09
Nodes (20): Listener, Episode, PlaybackInfo, PlayerRequest, PlaybackSession, PlaybackState, PlayerCapabilities, PlayerState (+12 more)

### Community 120 - "Offline Downloads Symbols"
Cohesion: 0.33
Nodes (6): DownloadStatus, Completed, Downloading, Failed, Paused, Queued

### Community 13 - "Content Repository Test Symbols"
Cohesion: 0.07
Nodes (13): BrowseFilters, CatalogItem, CatalogPlaybackMatchTest, ContentRepositoryTest, JianpianSourceTest, jpDetail(), mockEngine(), pomoMedia() (+5 more)

### Community 14 - "Desktop Player Symbols"
Cohesion: 0.16
Nodes (11): DesktopPlayer, PlayerSurface(), JFXPanel, MediaPlayer, MediaView, Modifier, animationtimer, duration (+3 more)

### Community 141 - "Profile Screen Symbols"
Cohesion: 0.50
Nodes (4): ClearTarget, Downloads, Favorites, History

### Community 142 - "Profile Screen Symbols"
Cohesion: 0.50
Nodes (4): ProfileLibrary, Downloads, Favorites, History

### Community 144 - "Modern Player Symbols"
Cohesion: 0.50
Nodes (4): TapZone, Center, Left, Right

### Community 148 - "Modern Player Symbols"
Cohesion: 0.67
Nodes (3): AdjustKind, Brightness, Volume

### Community 149 - "Modern Player Symbols"
Cohesion: 0.67
Nodes (3): TapFeedbackKind, Forward, Rewind

### Community 15 - "Audio Mode Device Test Symbols"
Cohesion: 0.15
Nodes (21): AudioModeDeviceTest, CatalogPlaybackDeviceTest, androidjunit4, assertequals, assertis, assertnotnull, assertnull, assertsame (+13 more)

### Community 16 - "Cache Maintenance Test Symbols"
Cohesion: 0.22
Nodes (5): ContentCacheUsage, CacheMaintenanceTest, cacheUsage(), completabledeferred, formatcachebytes

### Community 19 - "Offline Downloads Symbols"
Cohesion: 0.13
Nodes (7): OfflineAsset, OfflineDownload, OfflineDownloads, OfflineEngine, UnsupportedOfflineEngine, Job, StateFlow

### Community 2 - "app Symbols"
Cohesion: 0.10
Nodes (45): AppError, Config, DyttDetailParser, EightysgodDetailParser, RequestHandler, ResultRegistry, clean_text(), encode_thunder_uri() (+37 more)

### Community 20 - "Platform Symbols"
Cohesion: 0.08
Nodes (12): PlatformServices, avatarUri(), PlayerSurface(), rememberAvatarPicker(), rememberAvatarPicker(), Color, dev, Flow (+4 more)

### Community 21 - "Search Screen Symbols"
Cohesion: 0.12
Nodes (24): NestedScrollConnection, HighlightedTitle(), SearchPage(), SearchResults(), SearchRow(), SearchStatusMessage(), AppState, ImageVector (+16 more)

### Community 23 - "Dlna Casting Test Symbols"
Cohesion: 0.13
Nodes (3): DlnaCastingTest, TestPlatform, TestPlayer

### Community 24 - "movie sources Symbols"
Cohesion: 0.11
Nodes (18): SearchLinks, SearchOutcome, SiteRedirects, Source, SourceError, YangguangSearchParser, category_type(), catalog() (+10 more)

### Community 25 - "User Store Symbols"
Cohesion: 0.08
Nodes (11): EpisodeProgress, HistoryTitle, SavedTitle, UserBackup, UserProfile, UserStore, UserStoreBehaviorTest, currentEpochMillis() (+3 more)

### Community 26 - "Platform Symbols"
Cohesion: 0.14
Nodes (10): KeyValueStorage, UpdateNotice, YouthPreferences, asstateflow, decodefromstring, encodetostring, mutablestateflow, random (+2 more)

### Community 27 - "Offline Downloads Test Symbols"
Cohesion: 0.19
Nodes (4): Engine, OfflineDownloadsTest, OfflineEngine, OfflineEngine

### Community 28 - "Modern Player Symbols"
Cohesion: 0.09
Nodes (24): TapFeedback, progressFraction(), DetailChip(), EpisodeGrid(), PlayerDetails(), PlayerRecommendation(), AppState, dev (+16 more)

### Community 29 - "Playback Media Session Service Symbols"
Cohesion: 0.14
Nodes (15): PlaybackMediaSessionService, SessionCallback, Callback, Intent, ListenableFuture, MediaSession, MediaSessionService, Bundle (+7 more)

### Community 30 - "Ios Platform Symbols"
Cohesion: 0.10
Nodes (6): IosPlatform, dev, StateFlow, NSSearchPathDirectory, SharedFlow, UISlider

### Community 31 - "Ios Offline Engine Symbols"
Cohesion: 0.24
Nodes (11): Delegate, AVAssetDownloadDelegateProtocol, AVAssetDownloadTask, CMTimeRange, CValue, NSError, NSURL, NSURLSession (+3 more)

### Community 32 - "Ios Platform Symbols"
Cohesion: 0.14
Nodes (5): IosPlayer, AVAssetImageGenerator, AVPlayerItem, Job, MPMediaItemArtwork

### Community 33 - "Catalog Database Test Symbols"
Cohesion: 0.19
Nodes (6): CatalogDatabase, StoredCatalog, StoredResponse, CatalogDatabaseTest, execute(), T

### Community 34 - "test dytt mount Symbols"
Cohesion: 0.10
Nodes (4): CopyLinkTest, DyttTest, MountTest, post_api()

### Community 35 - "Android Platform Symbols"
Cohesion: 0.10
Nodes (4): AndroidFullscreenDeviceTest, AndroidPlatform, dev, StateFlow

### Community 36 - "Dlna Symbols"
Cohesion: 0.12
Nodes (21): DlnaException, discoverAndroidDlna(), ssdpLocation(), discoverIosDlna(), Context, Exception, async, connectivitymanager (+13 more)

### Community 37 - "Settings Store Symbols"
Cohesion: 0.20
Nodes (3): AppSettings, SettingsStore, SettingsStoreTest

### Community 38 - "Jianpian Source Symbols"
Cohesion: 0.10
Nodes (17): SearchItem, SearchResult, SourceCount, ApiError, JianpianSource, Server, PomoSource, PomoSourceTest (+9 more)

### Community 39 - "Android Offline Engine Symbols"
Cohesion: 0.26
Nodes (5): AndroidOfflineEngine, CacheDataSource, Downloader, OfflineEngine, SimpleCache

### Community 4 - "Catalog Components Symbols"
Cohesion: 0.11
Nodes (26): CatalogState, CatalogContent(), CatalogSkeleton(), ErrorText(), rememberSkeletonAlpha(), SkeletonBox(), SearchSkeleton(), AppState (+18 more)

### Community 41 - "test player Symbols"
Cohesion: 0.18
Nodes (6): EpisodeParser, PlayerTests, media_type(), resolve_online(), play(), HTMLParser

### Community 42 - "app Symbols"
Cohesion: 0.15
Nodes (3): DyttSearchParser, EightysgodSearchParser, HTMLParser

### Community 43 - "Picture In Picture Composition Test Symbols"
Cohesion: 0.20
Nodes (4): EmptyApplier, PictureInPictureCompositionTest, PersistentVideoContent(), AbstractApplier

### Community 44 - "Navigation Icons Symbols"
Cohesion: 0.14
Nodes (12): NavigationIcons, PlayerTransportIcons, load_dotenv(), BottomNavigationTabs(), ImageVector, Path, color, imagevector (+4 more)

### Community 45 - "Cache Maintenance Storage Test Symbols"
Cohesion: 0.08
Nodes (27): CacheMaintenanceStorageTest, PosterCacheTest, defaultPosterRequest(), posterImageLoader(), posterRequest(), prefetchPosters(), coil3, ImageLoader (+19 more)

### Community 46 - "Dlna Casting Symbols"
Cohesion: 0.26
Nodes (4): DlnaCasting, DlnaRemoteFrame(), Job, Modifier

### Community 47 - "test movie sources Symbols"
Cohesion: 0.20
Nodes (3): MovieSourceTests, search_page(), source()

### Community 48 - "Main Symbols"
Cohesion: 0.15
Nodes (10): DesktopWindowController, DesktopWindowController, desktopApplication(), main(), DesktopWindowController, application, painterresource, rememberwindowstate (+2 more)

### Community 49 - "Ios Platform Symbols"
Cohesion: 0.11
Nodes (14): IosStorage, PlayerSurface(), Color, Modifier, NSObjectProtocol, avroutepickerview, bodyasbytes, catransaction (+6 more)

### Community 5 - "Profile Screen Symbols"
Cohesion: 0.08
Nodes (44): ProfileTitle, Poster(), AudioModeBackdrop(), MiniPlayerBar(), DownloadPosterCard(), EmptyLibraryHint(), ProfileAvatar(), ProfileScreen() (+36 more)

### Community 50 - "Offline Media Device Test Symbols"
Cohesion: 0.10
Nodes (17): OfflineMediaDeviceTest, Listener, Listener, Context, datasourceinputstream, dataspec, defaulthttpdatasource, defaultmediasourcefactory (+9 more)

### Community 53 - "posters Symbols"
Cohesion: 0.22
Nodes (9): PosterCache, PosterError, PosterRedirects, approved_url(), fetch_image(), image_type(), Exception, HTTPRedirectHandler (+1 more)

### Community 54 - "Android Platform Symbols"
Cohesion: 0.17
Nodes (3): AndroidPlayerConnectionDeviceTest, AndroidPlayer, Player

### Community 55 - "Seek Preview Test Symbols"
Cohesion: 0.16
Nodes (3): PreviewFrame, Controller, SeekPreviewTest

### Community 57 - "Modern Player Symbols"
Cohesion: 0.23
Nodes (11): Adjustment, FormattingTest, playbackTime(), ControlTextPill(), PlayerGestureZone(), PlayerLevelIndicator(), ScrubFeedback(), SeekHint() (+3 more)

### Community 58 - "Android Offline Engine Symbols"
Cohesion: 0.18
Nodes (6): OfflineMediaSourceFactory, MediaSource, androidx, Factory, MediaItem, IntArray

### Community 59 - "Android Haptics Symbols"
Cohesion: 0.23
Nodes (7): AndroidHaptics, LongArray, IntArray, VibrationEffect, Vibrator, build, vibratormanager

### Community 6 - "test playback library Symbols"
Cohesion: 0.08
Nodes (23): PlaybackLibrary, PlayerError, PlaybackLibraryTests, validate_item(), page_url(), fetch(), main(), download() (+15 more)

### Community 60 - "Desktop Platform Symbols"
Cohesion: 0.08
Nodes (10): DesktopPlatform, PreferencesStorage, LiveSourceProbeTest, isWebUrl(), HttpClient, StateFlow, desktop, okhttp (+2 more)

### Community 61 - "Retry Prompts Symbols"
Cohesion: 0.23
Nodes (3): RetryPrompt, RetryPrompts, RetryPromptsTest

### Community 62 - "Ios Platform Symbols"
Cohesion: 0.23
Nodes (6): PipDelegate, PlayerContainerView, AVPictureInPictureController, AVPictureInPictureControllerDelegateProtocol, NSError, UIView

### Community 64 - "Dlna Symbols"
Cohesion: 0.28
Nodes (4): DlnaClient, DlnaDevice, DlnaPosition, xmlEscape()

### Community 65 - "Settings Screen Symbols"
Cohesion: 0.09
Nodes (35): ThemeMode, EditProfileDialog(), ProfileEditorCard(), ProfileFieldHeading(), ThemePickerDialog(), CacheUsageRow(), SettingsCaption(), SettingsCard() (+27 more)

### Community 69 - "Data Management Test Symbols"
Cohesion: 0.13
Nodes (6): ParserRules, ParserRulesStore, UpdateStore, DataManagementTest, FakeStorage, contentparsers

### Community 72 - "xunlei mcp Symbols"
Cohesion: 0.24
Nodes (8): XunleiMcpClient, XunleiMcpError, submit_xunlei_mcp_download(), Any, Exception, Minimal legacy SSE client for Xunlei's MCP download service., queue, typing

### Community 74 - "Haptics Symbols"
Cohesion: 0.14
Nodes (6): Haptics, SilentHaptics, Flow, assharedflow, mutablesharedflow, stateflow

### Community 75 - "test list pagination Symbols"
Cohesion: 0.20
Nodes (6): ListPagination, assert, { ListPagination, PAGE_SIZES }, test, frontend_static_list_pagination_page_sizes, ref_node_test

### Community 77 - "Ios Haptics Symbols"
Cohesion: 0.20
Nodes (5): IosHaptics, uiimpactfeedbackgenerator, uiimpactfeedbackstyle, uinotificationfeedbackgenerator, uinotificationfeedbacktype

### Community 78 - "Ios Avatar Picker Symbols"
Cohesion: 0.15
Nodes (12): NSObject, rememberAvatarPicker(), PHPickerViewController, PHPickerViewControllerDelegateProtocol, cancellablecontinuation, cgrectmake, cgsizemake, readvalue (+4 more)

### Community 79 - "Panda Video App Symbols"
Cohesion: 0.15
Nodes (14): MainActivity, Failed, Ready, StartupState, PandaVideoApp(), StartupFailureScreen(), MainViewController(), ComponentActivity (+6 more)

### Community 80 - "App State Test Symbols"
Cohesion: 0.17
Nodes (3): OfflineEngine, dev, OfflineEngine

### Community 83 - "Player Symbols"
Cohesion: 0.20
Nodes (10): PlayerPhase, Buffering, Ended, Error, Idle, Paused, Playing, Preparing (+2 more)

### Community 84 - "Catalog Availability Symbols"
Cohesion: 0.06
Nodes (31): CatalogPage, MatchResult, CacheMaintenanceState, CacheUsage, ResourceStatus, CatalogAvailabilityTest, ModelsTest, appendCatalog() (+23 more)

### Community 86 - "Ios Offline Engine Symbols"
Cohesion: 0.42
Nodes (3): IosOfflineEngine, Pending, OfflineEngine

### Community 97 - "Dlna Test Symbols"
Cohesion: 0.27
Nodes (3): DlnaXml, DlnaTest, parseDlnaDevice()

### Community 100 - "Html Node Symbols"
Cohesion: 0.40
Nodes (5): attr(), descendants(), hasClass(), text(), HtmlNode

### Community 104 - "catalog Symbols"
Cohesion: 0.70
Nodes (4): load(), renderItem(), resetPage(), syncFilters()

### Community 107 - "Seek Preview Symbols"
Cohesion: 0.40
Nodes (4): previewPosition(), currentcoroutinecontext, ensureactive, withtimeoutornull

### Community 11 - "player Symbols"
Cohesion: 0.08
Nodes (36): clearBuffering(), destroyMedia(), exitRotation(), failure(), fullscreenChanged(), isPlayerFullscreen(), loadEpisode(), renderEpisodes() (+28 more)

### Community 22 - "Shared Transitions Symbols"
Cohesion: 0.10
Nodes (18): posterSharedTransition(), Modifier, alertdialog, animatedvisibilityscope, button, buttondefaults, composable, compositionlocalof (+10 more)

### Community 40 - "Android Platform Symbols"
Cohesion: 0.11
Nodes (18): PlayerSurface(), Modifier, activity, activityinfo, androidview, audiomanager, componentname, mediacontroller (+10 more)

### Community 56 - "Top Tab Swipe Symbols"
Cohesion: 0.17
Nodes (14): topTabSwipe(), videoPinch(), Modifier, Modifier, abs, awaiteachgesture, awaitfirstdown, calculatezoom (+6 more)

### Community 66 - "Android Avatar Picker Symbols"
Cohesion: 0.15
Nodes (12): rememberAvatarPicker(), activityresultcontracts, bitmapfactory, exifinterface, localcontext, matrix, max, pickvisualmediarequest (+4 more)

### Community 67 - "generate app icons Symbols"
Cohesion: 0.18
Nodes (10): main(), render(), vector_paths(), Image, Generate native app, launch and web icons from the shared panda SVG. Requires…, Build macOS icons from the shared logo's existing 1024px iOS rendering.…, io, pil (+2 more)

### Community 68 - "Dlna Symbols"
Cohesion: 0.10
Nodes (17): dlnaIpv4(), dlnaTime(), parseDlnaTime(), ssdpRequest(), ssdpRequests(), xmlUnescape(), ByteArray, bodyastext (+9 more)

### Community 7 - "Downloads Screen Symbols"
Cohesion: 0.14
Nodes (24): DownloadAction(), DownloadsScreen(), SearchSuggestions(), Dp, AppState, ImageVector, AppState, Modifier (+16 more)

### Community 8 - "app Symbols"
Cohesion: 0.08
Nodes (36): appendBrowserDownload(), copyDownloadLink(), formatSize(), hasPlayback(), hideMagnetNotice(), hideNotice(), loadStatus(), metadataItem() (+28 more)

### Community 85 - "player edge Symbols"
Cohesion: 0.22
Nodes (5): assert, { chromium }, { fixturePath, outputPath, launchOptions }, fs, results

### Community 9 - "Panda Video App Symbols"
Cohesion: 0.09
Nodes (24): PandaVideoContent(), SearchEntry(), HomeCategories(), RetryDialog(), AppState, currentbackstackentryasstate, darkcolors, entertransition (+16 more)

### Community 90 - "service Symbols"
Cohesion: 0.29
Nodes (7): main(), running_pid(), Native DSM launcher; no pip dependencies., os, signal, subprocess, sys

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

### Community 17 - "README Symbols"
Cohesion: 0.07
Nodes (25): NAS 原生部署, 局域网域名, 数据及升级, macOS 后台服务, 功能与来源, 在线播放与下载, 测试与相关文档, 熊猫视频 · PandaVideo 网页版 (+17 more)

### Community 18 - "test app Symbols"
Cohesion: 0.27
Nodes (16): App, base64, concurrent_futures, contextlib, douban, email_message, http_server, json (+8 more)

### Community 51 - "Picture In Picture Composition Test Symbols"
Cohesion: 0.13
Nodes (21): broadcastframeclock, canvas, canvaslayerscomposescene, composescenepointer, composition, disposableeffect, experimentalcomposeuiapi, experimentalcoroutinesapi (+13 more)

### Community 52 - "Bundled Posters Symbols"
Cohesion: 0.27
Nodes (8): buffer, experimentalresourceapi, filesystem, mutex, res, sqliteconnection, topath, withlock

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

### Community 91 - "README Symbols"
Cohesion: 0.18
Nodes (8): PandaVideo agent guide, Project boundaries, Refresh and verify, PandaVideo mobile, 主要功能, 开始使用, 支持平台, 熊猫视频 · PandaVideo

## Knowledge Gaps
- **155 isolated node(s):** `Request`, `Config`, `UpdateNotice`, `Failed`, `CacheMaintenanceState` (+150 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 758 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **47 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PlatformServices` connect `Platform Symbols` to `Settings Screen Symbols`, `Android Avatar Picker Symbols`, `Android Platform Symbols`, `App State Test Symbols`, `Profile Screen Symbols`, `Downloads Screen Symbols`, `Panda Video App Symbols`, `Haptics Symbols`, `Ios Avatar Picker Symbols`, `Panda Video App Symbols`, `Desktop Platform Symbols`, `Dlna Casting Symbols`, `Catalog Availability Symbols`, `Dlna Casting Test Symbols`, `Modern Player Symbols`, `Platform Symbols`, `Modern Player Symbols`, `Ios Platform Symbols`?**
  _High betweenness centrality (0.082) - this node is a cross-community bridge._
- **Why does `CatalogItem` connect `Content Repository Test Symbols` to `Catalog Database Test Symbols`, `App State Test Symbols`, `Catalog Components Symbols`, `Jianpian Source Symbols`, `Haptics Symbols`, `Playback Test Symbols`, `Cache Maintenance Storage Test Symbols`, `Modern Player Symbols`, `Audio Mode Device Test Symbols`, `Catalog Availability Symbols`, `Bundled Posters Symbols`, `Desktop Platform Symbols`?**
  _High betweenness centrality (0.059) - this node is a cross-community bridge._
- **Why does `PlayerRequest` connect `Playback Test Symbols` to `App State Test Symbols`, `Jianpian Source Symbols`, `Panda Video App Symbols`, `Content Repository Test Symbols`, `Cache Maintenance Storage Test Symbols`, `Audio Mode Device Test Symbols`, `Offline Downloads Symbols`, `Catalog Availability Symbols`, `Response Cache Test Symbols`, `Offline Downloads Test Symbols`, `Desktop Platform Symbols`?**
  _High betweenness centrality (0.050) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `PlayerRequest` (e.g. with `.completedEpisodeResolvesLocallyWithoutNetworkAndOmitsUncachedNextEpisode()` and `.lostLocalFilesBecomeRetryableInsteadOfReturningFakeOfflineMedia()`) actually correct?**
  _`PlayerRequest` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 53 inferred relationships involving `mockEngine()` (e.g. with `.animationAndDocumentaryCatalogsCanPlaySeriesLikeSearch()` and `.yearlessCatalogDoesNotPreferBareTitleOverAnotherRemake()`) actually correct?**
  _`mockEngine()` has 53 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Request`, `Config`, `UpdateNotice` to the rest of the system?**
  _155 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Panda Video App Symbols` be split into smaller, more focused modules?**
  _Cohesion score 0.08387096774193549 - nodes in this community are weakly interconnected._
## Coverage and Static Analysis Limitations

- Code-only extraction; documentation, images, binaries, and unsupported XML/plist formats are not indexed.
- Community names are derived from their dominant source file.
- Imported/unresolved nodes can lack source locations. INFERRED edges are heuristics; missing edges do not establish absence of a runtime relationship.
- The graph is undirected; structural paths are not ordered execution traces.
- Git commit identity excludes uncommitted edits. Check source SHA-256 hashes in coverage.json for snapshot freshness.
- graphify-out/ was written into the working tree; portable outputs can be shared, while AST caches and machine-local sidecars are ignored.

Parser warnings: 11 files may be only partially extracted.

- `frontend/tests/browser/fixtures/dplayer-test0.ts`: first parser error at L1.
- `frontend/tests/browser/fixtures/dplayer-test1.ts`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/app/AppState.kt`: first parser error at L43.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/core/player/SeekPreview.kt`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/html/HtmlNode.kt`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/repository/BundledPosters.kt`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/repository/CacheMaintenance.kt`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/repository/CatalogAvailability.kt`: first parser error at L1.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/repository/ContentRepository.kt`: first parser error at L42.
- `mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/repository/JianpianSource.kt`: first parser error at L1.
- `mobile/tools/feature-verification.init.gradle`: first parser error at L7.

Integrity diagnostics: 0 dangling edges, 0 missing-endpoint edges, 61 self-loops, 142 same-endpoint edge collisions in the raw extraction. Collisions can combine distinct static relationships in the exported graph; inspect source for full detail.
