# Graph Report - PandaVideo  (2026-11-12)

## Corpus Check
- 158 files · ~95,659 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 35 file(s) not represented in the graph (top: .xml 13, (none) 5, .css 4)

## Summary
- 2298 nodes · 5155 edges · 146 communities (98 shown, 48 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 246 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `ab319cb8`
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
- app Symbols
- test app Symbols
- test app Symbols
- Top Tab Refresh Gesture Test Symbols
- test catalog Symbols
- Playback Test Symbols
- Offline Downloads Symbols
- Content Repository Test Symbols
- Pomo Source Test Symbols
- server Symbols
- Content Repository Test Symbols
- Profile Screen Symbols
- Profile Screen Symbols
- Player Seek Gesture Test Symbols
- Android Fullscreen Device Test Symbols
- Cache Maintenance Test Symbols
- Offline Downloads Symbols
- app Symbols
- Platform Symbols
- Search Screen Symbols
- Jianpian Source Test Symbols
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
- Android Dlna Device Test Symbols
- Settings Store Symbols
- Jianpian Source Symbols
- Android Offline Engine Symbols
- Catalog Components Symbols
- Offline Downloads Symbols
- app Symbols
- Picture In Picture Composition Test Symbols
- Navigation Icons Symbols
- Panda Video App Symbols
- Dlna Casting Symbols
- test movie sources Symbols
- User Store Behavior Test Symbols
- Ios Platform Symbols
- Catalog Database Symbols
- posters Symbols
- Android Platform Symbols
- Seek Preview Test Symbols
- Pomo Source Symbols
- Player Symbols
- Android Haptics Symbols
- test playback library Symbols
- Desktop Platform Symbols
- Android Offline Engine Symbols
- Youth Store Symbols
- Dlna Symbols
- Data Management Test Symbols
- test posters Symbols
- xunlei mcp Symbols
- test media search Symbols
- Haptics Symbols
- test list pagination Symbols
- Ios Haptics Symbols
- Ios Avatar Picker Symbols
- Main Activity Symbols
- Content Repository Symbols
- Player Symbols
- Catalog Availability Symbols
- Catalog Availability Test Symbols
- Search History Store Symbols
- Response Cache Test Symbols
- Video Zoom Test Symbols
- Android Seek Preview Device Test Symbols
- Android Platform Symbols
- Profile Screen Symbols
- Dlna Test Symbols
- test app Symbols
- Html Node Symbols
- catalog Symbols
- site Symbols
- player Symbols
- douban Symbols
- Ios Platform Symbols
- Android Platform Symbols
- Shared Transitions Symbols
- Android Offline Engine Symbols
- Top Tab Swipe Symbols
- import catalog Symbols
- Settings Screen Symbols
- Android Avatar Picker Symbols
- generate app icons Symbols
- Dlna Symbols
- Downloads Screen Symbols
- app Symbols
- player edge Symbols
- service Symbols
- player stability Symbols
- web sync Symbols
- Android Seek Preview Symbols
- build.gradle Symbols
- gradlew Symbols
- run app Symbols
- install launch agent Symbols
- build shared Symbols
- run Symbols
- service Symbols
- build.gradle Symbols
- README Symbols
- AGENTS Symbols
- README Symbols
- xunlei mcp Symbols
- Picture In Picture Composition Test Symbols
- real stream Symbols
- package Symbols
- autoplay dplayer Symbols
- player flow Symbols
- Dlna Casting Test Symbols
- README Symbols
- External References Symbols
- External References Symbols
- External References Symbols

## God Nodes (most connected - your core abstractions)
1. `PlaybackSession` - 60 edges
2. `PlayerRequest` - 59 edges
3. `mockEngine()` - 54 edges
4. `TestPlatform` - 53 edges
5. `PlatformServices` - 48 edges
6. `CatalogItem` - 46 edges
7. `AppStateTest` - 40 edges
8. `PlayerController` - 38 edges
9. `ContentRepositoryTest` - 36 edges
10. `FakePlayer` - 35 edges

## Surprising Connections (you probably didn't know these)
- `PlaybackSession` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonMain/kotlin/dev/pandavideo/core/player/Player.kt → AGENTS.md
- `BrowseFilters` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonMain/kotlin/dev/pandavideo/core/model/Models.kt → AGENTS.md
- `CatalogPlaybackMatchTest` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonTest/kotlin/dev/pandavideo/CatalogPlaybackMatchTest.kt → AGENTS.md
- `OfflineDownloads` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonMain/kotlin/dev/pandavideo/data/repository/OfflineDownloads.kt → AGENTS.md
- `DlnaCasting` --references--> `Graphify query entry`  [INFERRED]
  mobile/shared/src/commonMain/kotlin/dev/pandavideo/ui/player/DlnaCasting.kt → AGENTS.md

## Import Cycles
- None detected.

## Communities (146 total, 48 thin omitted)

### Community 0 - "Panda Video App Symbols"
Cohesion: 0.06
Nodes (34): AppDelegate, PlayerHostController, SharedView, SuiXinKanApp, DesktopPlayer, PlayerSurface(), Bool, JFXPanel (+26 more)

### Community 1 - "Navigation Config Test Symbols"
Cohesion: 0.05
Nodes (44): NavigationConfigTest, browseYearOptions(), PandaVideoContent(), SearchEntry(), annualTvFilters(), BrowseFilterPanel(), FilterChoice(), HomeCategories() (+36 more)

### Community 10 - "test douban Symbols"
Cohesion: 0.10
Nodes (12): DoubanLists, Top250Parser, DoubanTest, list_page_size(), source_rating(), subject_url(), valid_rating(), validate_browse() (+4 more)

### Community 101 - "Fullscreen Tests Symbols"
Cohesion: 0.25
Nodes (3): FullscreenTests, XCTest, XCTestCase

### Community 102 - "Pico Fullscreen Window Symbols"
Cohesion: 0.27
Nodes (6): PicoFullscreenWindow, WindowSize, Activity, context, log, method

### Community 103 - "Android Seek Preview Symbols"
Cohesion: 0.43
Nodes (3): AndroidSeekPreview, Request, FrameExtractor

### Community 12 - "Playback Test Symbols"
Cohesion: 0.08
Nodes (22): Listener, Episode, PlaybackInfo, PlayerRequest, PlaybackSession, PlayerCapabilities, PlayerController, PlayerState (+14 more)

### Community 120 - "Offline Downloads Symbols"
Cohesion: 0.33
Nodes (6): DownloadStatus, Completed, Downloading, Failed, Paused, Queued

### Community 13 - "Content Repository Test Symbols"
Cohesion: 0.12
Nodes (3): BrowseFilters, ContentRepositoryTest, mockEngine()

### Community 14 - "Content Repository Test Symbols"
Cohesion: 0.16
Nodes (8): CatalogItem, CatalogPlaybackMatchTest, ModelsTest, appendCatalog(), pomoMedia(), pomoRow(), pomoSearch(), JsonObject

### Community 141 - "Profile Screen Symbols"
Cohesion: 0.50
Nodes (4): ClearTarget, Downloads, Favorites, History

### Community 142 - "Profile Screen Symbols"
Cohesion: 0.50
Nodes (4): ProfileLibrary, Downloads, Favorites, History

### Community 15 - "Android Fullscreen Device Test Symbols"
Cohesion: 0.11
Nodes (29): FullscreenTestActivity, AudioModeDeviceTest, CatalogPlaybackDeviceTest, Activity, Bundle, androidjunit4, assertequals, assertfalse (+21 more)

### Community 16 - "Cache Maintenance Test Symbols"
Cohesion: 0.23
Nodes (5): ContentCacheUsage, CacheMaintenanceTest, cacheUsage(), completabledeferred, formatcachebytes

### Community 19 - "Offline Downloads Symbols"
Cohesion: 0.30
Nodes (4): OfflineDownload, OfflineDownloads, Job, StateFlow

### Community 2 - "app Symbols"
Cohesion: 0.11
Nodes (46): AppError, Config, DyttDetailParser, EightysgodDetailParser, ResultRegistry, SearchOutcome, SourceError, clean_text() (+38 more)

### Community 20 - "Platform Symbols"
Cohesion: 0.07
Nodes (14): PlatformServices, avatarUri(), rememberAvatarPicker(), EditProfileDialog(), ProfileEditorCard(), ProfileFieldHeading(), rememberAvatarPicker(), Color (+6 more)

### Community 21 - "Search Screen Symbols"
Cohesion: 0.10
Nodes (30): SearchState, NestedScrollConnection, rememberSkeletonAlpha(), SkeletonBox(), HighlightedTitle(), SearchPage(), SearchResults(), SearchRow() (+22 more)

### Community 22 - "Jianpian Source Test Symbols"
Cohesion: 0.17
Nodes (3): JianpianSourceTest, jpDetail(), encodeutf8

### Community 23 - "Dlna Casting Test Symbols"
Cohesion: 0.12
Nodes (3): DlnaCastingTest, TestPlatform, TestPlayer

### Community 24 - "movie sources Symbols"
Cohesion: 0.11
Nodes (14): SearchLinks, SiteRedirects, Source, YangguangSearchParser, category_type(), catalog(), fetch_html(), parse_search() (+6 more)

### Community 25 - "User Store Symbols"
Cohesion: 0.12
Nodes (15): EpisodeProgress, HistoryTitle, SavedTitle, UserBackup, UserStore, currentEpochMillis(), canonicalProgress(), progressFraction() (+7 more)

### Community 26 - "Platform Symbols"
Cohesion: 0.19
Nodes (7): KeyValueStorage, UpdateNotice, asstateflow, encodetostring, mutablestateflow, random, serializable

### Community 27 - "Offline Downloads Test Symbols"
Cohesion: 0.30
Nodes (3): Engine, OfflineDownloadsTest, OfflineEngine

### Community 28 - "Modern Player Symbols"
Cohesion: 0.07
Nodes (43): PlaybackState, AdjustKind, Adjustment, TapFeedback, TapFeedbackKind, FormattingTest, PlayerSurface(), previewPosition() (+35 more)

### Community 29 - "Playback Media Session Service Symbols"
Cohesion: 0.13
Nodes (14): PlaybackMediaSessionService, SessionCallback, Callback, Intent, ListenableFuture, MediaSession, MediaSessionService, Bundle (+6 more)

### Community 3 - "App State Test Symbols"
Cohesion: 0.07
Nodes (6): AppStateTest, OfflineEngine, TestPlatform, TestPlayer, dev, OfflineEngine

### Community 30 - "Ios Platform Symbols"
Cohesion: 0.10
Nodes (8): IosPlatform, MainViewController(), dev, StateFlow, UIViewController, NSSearchPathDirectory, SharedFlow, UISlider

### Community 31 - "Ios Offline Engine Symbols"
Cohesion: 0.16
Nodes (14): Delegate, IosOfflineEngine, Pending, AVAssetDownloadDelegateProtocol, AVAssetDownloadTask, CMTimeRange, CValue, NSError (+6 more)

### Community 32 - "Ios Platform Symbols"
Cohesion: 0.14
Nodes (6): IosPlayer, AVAssetImageGenerator, AVPlayerItem, Job, NSObjectProtocol, MPMediaItemArtwork

### Community 34 - "test dytt mount Symbols"
Cohesion: 0.10
Nodes (4): CopyLinkTest, DyttTest, MountTest, post_api()

### Community 35 - "Android Platform Symbols"
Cohesion: 0.10
Nodes (4): AndroidFullscreenDeviceTest, AndroidPlatform, dev, StateFlow

### Community 36 - "Android Dlna Device Test Symbols"
Cohesion: 0.10
Nodes (22): AndroidDlnaDeviceTest, discoverAndroidDlna(), ssdpLocation(), ssdpRequest(), ssdpRequests(), ByteArray, Context, async (+14 more)

### Community 37 - "Settings Store Symbols"
Cohesion: 0.20
Nodes (3): AppSettings, SettingsStore, SettingsStoreTest

### Community 38 - "Jianpian Source Symbols"
Cohesion: 0.21
Nodes (9): SearchItem, SearchResult, SourceCount, JianpianSource, Server, detail(), search(), signedHeaders() (+1 more)

### Community 39 - "Android Offline Engine Symbols"
Cohesion: 0.36
Nodes (3): AndroidOfflineEngine, Downloader, OfflineEngine

### Community 4 - "Catalog Components Symbols"
Cohesion: 0.09
Nodes (48): CatalogState, CatalogContent(), CatalogSkeleton(), ErrorText(), AppState, Modifier, alertdialog, animatefloat (+40 more)

### Community 41 - "Offline Downloads Symbols"
Cohesion: 0.13
Nodes (7): OfflineAsset, OfflineEngine, UnsupportedOfflineEngine, Flow, assharedflow, mutablesharedflow, stateflow

### Community 42 - "app Symbols"
Cohesion: 0.15
Nodes (3): DyttSearchParser, EightysgodSearchParser, HTMLParser

### Community 43 - "Picture In Picture Composition Test Symbols"
Cohesion: 0.20
Nodes (4): EmptyApplier, PictureInPictureCompositionTest, PersistentVideoContent(), AbstractApplier

### Community 44 - "Navigation Icons Symbols"
Cohesion: 0.12
Nodes (16): NavigationIcons, PlayerTransportIcons, load_dotenv(), Dp, ImageVector, Path, color, compositionlocalof (+8 more)

### Community 45 - "Panda Video App Symbols"
Cohesion: 0.12
Nodes (22): Failed, Ready, StartupState, PosterCacheTest, PandaVideoApp(), StartupFailureScreen(), defaultPosterRequest(), posterImageLoader() (+14 more)

### Community 46 - "Dlna Casting Symbols"
Cohesion: 0.23
Nodes (5): DlnaCasting, DlnaDevicePicker(), DlnaRemoteFrame(), Job, Modifier

### Community 47 - "test movie sources Symbols"
Cohesion: 0.20
Nodes (3): MovieSourceTests, search_page(), source()

### Community 49 - "Ios Platform Symbols"
Cohesion: 0.09
Nodes (19): IosStorage, PipDelegate, PlayerContainerView, PlayerSurface(), AVPictureInPictureController, AVPictureInPictureControllerDelegateProtocol, Color, Modifier (+11 more)

### Community 52 - "Catalog Database Symbols"
Cohesion: 0.13
Nodes (12): StoredCatalog, StoredResponse, execute(), T, buffer, experimentalresourceapi, filesystem, mutex (+4 more)

### Community 53 - "posters Symbols"
Cohesion: 0.22
Nodes (9): PosterCache, PosterError, PosterRedirects, approved_url(), fetch_image(), image_type(), Exception, HTTPRedirectHandler (+1 more)

### Community 54 - "Android Platform Symbols"
Cohesion: 0.21
Nodes (3): AndroidPlayerConnectionDeviceTest, AndroidPlayer, Player

### Community 55 - "Seek Preview Test Symbols"
Cohesion: 0.16
Nodes (3): PreviewFrame, Controller, SeekPreviewTest

### Community 57 - "Pomo Source Symbols"
Cohesion: 0.22
Nodes (6): ApiError, PomoSource, configuration(), fetch(), Exception, JsonElement

### Community 59 - "Android Haptics Symbols"
Cohesion: 0.23
Nodes (7): AndroidHaptics, LongArray, IntArray, VibrationEffect, Vibrator, build, vibratormanager

### Community 6 - "test playback library Symbols"
Cohesion: 0.07
Nodes (17): PlaybackLibrary, EpisodeParser, PlayerError, PlaybackLibraryTests, PlayerTests, validate_item(), media_type(), page_url() (+9 more)

### Community 60 - "Desktop Platform Symbols"
Cohesion: 0.06
Nodes (19): DesktopWindowController, DesktopPlatform, DesktopWindowController, PreferencesStorage, LiveSourceProbeTest, desktopApplication(), main(), DesktopWindowController (+11 more)

### Community 61 - "Android Offline Engine Symbols"
Cohesion: 0.16
Nodes (8): OfflineMediaDeviceTest, Listener, OfflineMediaSourceFactory, androidx, Factory, Listener, Context, IntArray

### Community 64 - "Dlna Symbols"
Cohesion: 0.24
Nodes (6): DlnaClient, DlnaDevice, DlnaException, DlnaPosition, xmlEscape(), Exception

### Community 69 - "Data Management Test Symbols"
Cohesion: 0.14
Nodes (6): ParserRules, ParserRulesStore, UpdateStore, DataManagementTest, FakeStorage, contentparsers

### Community 72 - "xunlei mcp Symbols"
Cohesion: 0.38
Nodes (4): XunleiMcpClient, XunleiMcpError, Any, Exception

### Community 75 - "test list pagination Symbols"
Cohesion: 0.20
Nodes (6): ListPagination, assert, { ListPagination, PAGE_SIZES }, test, frontend_static_list_pagination_page_sizes, ref_node_test

### Community 77 - "Ios Haptics Symbols"
Cohesion: 0.20
Nodes (5): IosHaptics, uiimpactfeedbackgenerator, uiimpactfeedbackstyle, uinotificationfeedbackgenerator, uinotificationfeedbacktype

### Community 78 - "Ios Avatar Picker Symbols"
Cohesion: 0.25
Nodes (7): NSObject, rememberAvatarPicker(), PHPickerViewController, PHPickerViewControllerDelegateProtocol, cgrectmake, cgsizemake, usecontents

### Community 79 - "Main Activity Symbols"
Cohesion: 0.27
Nodes (7): MainActivity, ComponentActivity, Configuration, Bundle, enableedgetoedge, setcontent, systembarstyle

### Community 80 - "Content Repository Symbols"
Cohesion: 0.14
Nodes (9): MatchResult, formatMinutesSeconds(), clearCache(), inspectCatalog(), isWebUrl(), browsecountries, channel, homecatalogfilters (+1 more)

### Community 83 - "Player Symbols"
Cohesion: 0.20
Nodes (10): PlayerPhase, Buffering, Ended, Error, Idle, Paused, Playing, Preparing (+2 more)

### Community 84 - "Catalog Availability Symbols"
Cohesion: 0.12
Nodes (17): CacheMaintenanceState, CacheUsage, ResourceStatus, measure(), cached(), key(), visible(), Available (+9 more)

### Community 86 - "Catalog Availability Test Symbols"
Cohesion: 0.17
Nodes (4): CatalogPage, CatalogAvailabilityTest, CacheMaintenanceStorageTest, visibleCatalog()

### Community 96 - "Profile Screen Symbols"
Cohesion: 0.13
Nodes (15): ThemeMode, ProfileTitle, DownloadPosterCard(), EmptyLibraryHint(), ProfileAvatar(), ProfileScreen(), ProfileToolbarButton(), SavedPosterCard() (+7 more)

### Community 97 - "Dlna Test Symbols"
Cohesion: 0.27
Nodes (3): DlnaXml, DlnaTest, parseDlnaDevice()

### Community 100 - "Html Node Symbols"
Cohesion: 0.40
Nodes (5): attr(), descendants(), hasClass(), text(), HtmlNode

### Community 104 - "catalog Symbols"
Cohesion: 0.70
Nodes (4): load(), renderItem(), resetPage(), syncFilters()

### Community 11 - "player Symbols"
Cohesion: 0.08
Nodes (36): clearBuffering(), destroyMedia(), exitRotation(), failure(), fullscreenChanged(), isPlayerFullscreen(), loadEpisode(), renderEpisodes() (+28 more)

### Community 40 - "Android Platform Symbols"
Cohesion: 0.11
Nodes (18): PlayerSurface(), Modifier, activity, activityinfo, androidview, audiomanager, componentname, mediacontroller (+10 more)

### Community 5 - "Shared Transitions Symbols"
Cohesion: 0.10
Nodes (21): posterSharedTransition(), SearchSuggestions(), Modifier, AppState, Modifier, alpha, animatedvisibilityscope, asyncimage (+13 more)

### Community 50 - "Android Offline Engine Symbols"
Cohesion: 0.12
Nodes (15): CacheDataSource, SimpleCache, datasourceinputstream, dataspec, defaulthttpdatasource, defaultmediasourcefactory, executors, hlsdownloader (+7 more)

### Community 56 - "Top Tab Swipe Symbols"
Cohesion: 0.17
Nodes (14): topTabSwipe(), videoPinch(), Modifier, Modifier, abs, awaiteachgesture, awaitfirstdown, calculatezoom (+6 more)

### Community 62 - "import catalog Symbols"
Cohesion: 0.21
Nodes (12): fetch(), main(), download(), top_page(), tv_page(), Film/series metadata and partial-title keyword normalization., Refresh the verified Douban homepage SQLite snapshot and its original poster…, argparse (+4 more)

### Community 65 - "Settings Screen Symbols"
Cohesion: 0.10
Nodes (30): CacheUsageRow(), SettingsCaption(), SettingsCard(), SettingsChoice(), SettingsDivider(), SettingsHeading(), SettingsScreen(), SettingsToggle() (+22 more)

### Community 66 - "Android Avatar Picker Symbols"
Cohesion: 0.15
Nodes (12): rememberAvatarPicker(), activityresultcontracts, bitmapfactory, exifinterface, localcontext, matrix, max, pickvisualmediarequest (+4 more)

### Community 67 - "generate app icons Symbols"
Cohesion: 0.18
Nodes (10): main(), render(), vector_paths(), Image, Generate native app, launch and web icons from the shared panda SVG. Requires…, Build macOS icons from the shared logo's existing 1024px iOS rendering.…, io, pil (+2 more)

### Community 68 - "Dlna Symbols"
Cohesion: 0.14
Nodes (13): dlnaIpv4(), dlnaTime(), parseDlnaTime(), xmlUnescape(), bodyastext, get, header, httptimeout (+5 more)

### Community 7 - "Downloads Screen Symbols"
Cohesion: 0.13
Nodes (21): Poster(), AudioModeBackdrop(), DownloadAction(), DownloadsScreen(), Modifier, AppState, ImageVector, accent (+13 more)

### Community 8 - "app Symbols"
Cohesion: 0.08
Nodes (36): appendBrowserDownload(), copyDownloadLink(), formatSize(), hasPlayback(), hideMagnetNotice(), hideNotice(), loadStatus(), metadataItem() (+28 more)

### Community 85 - "player edge Symbols"
Cohesion: 0.22
Nodes (5): assert, { chromium }, { fixturePath, outputPath, launchOptions }, fs, results

### Community 90 - "service Symbols"
Cohesion: 0.29
Nodes (7): main(), running_pid(), Native DSM launcher; no pip dependencies., os, signal, subprocess, sys

### Community 98 - "player stability Symbols"
Cohesion: 0.33
Nodes (3): assert, { chromium }, { fixturePath, outputPath, launchOptions }

### Community 105 - "web sync Symbols"
Cohesion: 0.40
Nodes (4): assert, { chromium }, { fixturePath, launchOptions, outputPath }, frontend_tests_browser_test_support_outputpath

### Community 107 - "Android Seek Preview Symbols"
Cohesion: 0.12
Nodes (15): bitmap, bytearrayoutputstream, cancellablecontinuation, contextcompat, currentcoroutinecontext, ensureactive, mimetypes, presentation (+7 more)

### Community 111 - "build.gradle Symbols"
Cohesion: 0.50
Nodes (3): javaversion, jvmtarget, targetformat

### Community 112 - "gradlew Symbols"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 139 - "AGENTS Symbols"
Cohesion: 0.50
Nodes (4): Graphify query entry, PandaVideo agent guide, Project boundaries, Refresh and verify

### Community 17 - "README Symbols"
Cohesion: 0.07
Nodes (25): NAS 原生部署, 局域网域名, 数据及升级, macOS 后台服务, 功能与来源, 在线播放与下载, 测试与相关文档, 熊猫视频 · PandaVideo 网页版 (+17 more)

### Community 18 - "xunlei mcp Symbols"
Cohesion: 0.19
Nodes (21): App, Minimal legacy SSE client for Xunlei's MCP download service., Deterministic device QA API; never used by the production app., base64, concurrent_futures, contextlib, douban, email_message (+13 more)

### Community 51 - "Picture In Picture Composition Test Symbols"
Cohesion: 0.13
Nodes (23): broadcastframeclock, canvas, canvaslayerscomposescene, composescenepointer, composition, disposableeffect, experimentalcomposeuiapi, experimentalcoroutinesapi (+15 more)

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

### Community 9 - "Dlna Casting Test Symbols"
Cohesion: 0.24
Nodes (7): bundledsqlitedriver, file, httpclient, httpstatuscode, respond, standardtestdispatcher, testscope

### Community 91 - "README Symbols"
Cohesion: 0.25
Nodes (5): PandaVideo mobile, 主要功能, 开始使用, 支持平台, 熊猫视频 · PandaVideo

## Knowledge Gaps
- **155 isolated node(s):** `Request`, `Config`, `UpdateNotice`, `Failed`, `CacheMaintenanceState` (+150 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 760 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **48 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PlayerRequest` connect `Playback Test Symbols` to `Navigation Config Test Symbols`, `App State Test Symbols`, `Pomo Source Test Symbols`, `Dlna Casting Test Symbols`, `Content Repository Test Symbols`, `Android Fullscreen Device Test Symbols`, `Content Repository Symbols`, `Offline Downloads Symbols`, `Jianpian Source Test Symbols`, `Response Cache Test Symbols`, `Pomo Source Symbols`, `Offline Downloads Test Symbols`, `Desktop Platform Symbols`?**
  _High betweenness centrality (0.060) - this node is a cross-community bridge._
- **Why does `PlatformServices` connect `Platform Symbols` to `Navigation Config Test Symbols`, `App State Test Symbols`, `Catalog Components Symbols`, `Downloads Screen Symbols`, `Playback Test Symbols`, `Dlna Casting Test Symbols`, `Platform Symbols`, `Modern Player Symbols`, `Ios Platform Symbols`, `Android Platform Symbols`, `Offline Downloads Symbols`, `Panda Video App Symbols`, `Dlna Casting Symbols`, `Desktop Platform Symbols`, `Settings Screen Symbols`, `Android Avatar Picker Symbols`, `Haptics Symbols`, `Ios Avatar Picker Symbols`, `Content Repository Symbols`, `Profile Screen Symbols`?**
  _High betweenness centrality (0.058) - this node is a cross-community bridge._
- **Why does `CatalogItem` connect `Content Repository Test Symbols` to `Catalog Database Test Symbols`, `App State Test Symbols`, `Catalog Components Symbols`, `Pomo Source Test Symbols`, `Offline Downloads Symbols`, `Dlna Casting Test Symbols`, `Playback Test Symbols`, `Modern Player Symbols`, `Panda Video App Symbols`, `Android Fullscreen Device Test Symbols`, `Content Repository Symbols`, `Catalog Availability Symbols`, `Catalog Database Symbols`, `Catalog Availability Test Symbols`, `Jianpian Source Test Symbols`, `Desktop Platform Symbols`?**
  _High betweenness centrality (0.056) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `PlayerRequest` (e.g. with `.completedEpisodeResolvesLocallyWithoutNetworkAndOmitsUncachedNextEpisode()` and `.lostLocalFilesBecomeRetryableInsteadOfReturningFakeOfflineMedia()`) actually correct?**
  _`PlayerRequest` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 53 inferred relationships involving `mockEngine()` (e.g. with `.animationAndDocumentaryCatalogsCanPlaySeriesLikeSearch()` and `.yearlessCatalogDoesNotPreferBareTitleOverAnotherRemake()`) actually correct?**
  _`mockEngine()` has 53 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Request`, `Config`, `UpdateNotice` to the rest of the system?**
  _155 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Panda Video App Symbols` be split into smaller, more focused modules?**
  _Cohesion score 0.05660377358490566 - nodes in this community are weakly interconnected._
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

Integrity diagnostics: 0 dangling edges, 0 missing-endpoint edges, 62 self-loops, 141 same-endpoint edge collisions in the raw extraction. Collisions can combine distinct static relationships in the exported graph; inspect source for full detail.
