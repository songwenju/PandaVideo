# PandaVideo 能力契约

2026-11-22 后台补源缓存及广告隔离补充（`persistent-fastest-playback-lines`）：`BackgroundPlaybackCacheTest.verifiedMirrorListsSurviveRestartAndResolveTheirOriginalEpisodePaths` 守护列表重启恢复与原站集身份解析；`LineAdStoreTest.supplementaryMirrorReportsUseOriginalProviderAndLineIdentity` 守护聚合展示编号不改变广告标记的原站身份。匹配保留年份、演员冲突与电影/剧集边界检查。既有契约签名差异继续保留待对账。

2026-11-22 《大明王朝1566》多线路修订（`jianpian-home-source-identity`）：为豆瓣2210001补充简片13003，核验同名、2007年、豆瓣ID与29条HTTP HLS/MP4候选线路。目录优先简片，保留Pomo暴风6186映射，已有旧身份库按原流程升级；实际可用性仍由测速决定，手动线路优先保持，历史显式旧播放页不在本次升级范围。`CatalogDatabaseTest.damingDynastyUpgradesLegacySingleSourceWithoutLosingSeriesIdentity` 守护旧Pomo目录映射升级、剧集身份与重启持久化；通用映射守护按每条记录的isSeries验证。既有并行契约签名差异保持待对账。

高清分段同时更新 `player-next-episode-address-preload`、`ios-standby-preroll`、`playback-source-recovery` 的代码锚点：分段描述透传给预加载，iOS 仍只拥有一个绑定视频层/PiP 的 AVPlayer 实例（实际为 AVQueuePlayer），普通单地址结束后保留可回退的当前项；分段原生预加载不复用单地址候选。高清故障排除整档 MP4，保留同 PID 的完整 HLS 恢复与原进度，不改变其他来源的失败身份排除规则。原有能力守护保留。

2026-11-22 TV 启动焦点修订（`android-tv-direct-playback`）：初次打开时进入热播，焦点直接在第二行“热播剧集”。只在目录未初始化时请求该类型焦点，详情和搜索返回仍恢复保存位置；热播左右切换和分类确认筛选规则保持。此修订替代顶部热播的默认焦点要求，设备回归从热播剧集直接右移验证。既有并行契约差异保持待对账。

2026-11-22 央视公开高清分段修订（`cctv-public-vod-playback`）：Android/iOS 本地优先完整 `chapters4` 再 `chapters3` MP4，逐段校验官方 HTTPS 主机、独立非空 URL、正有限时长与整期总长；缺段或失败档位回退，不推导缺失地址。整期共享总时长、自动续播、跨段快进快退、历史/片头定位与跨段预览；只有最后一段结束才完成节目。完整 `hls_url` 保留给普通播放回退、下载及单 URL 投屏，离线任务不保存在线分段、离线回放清空分段。实现补充 `SegmentedTimeline.kt`、`Player.kt`、`AndroidPlaybackPreloader.kt`、`PlaybackMediaSessionService.kt`、`AndroidSeekPreview.kt`、`IosPlatform.kt`、`OfflineDownloads.kt`。守护补充 `CctvSourceTest` 完整档位优先级及缺段/保护地址回退、`SegmentedTimelineTest` 边界和双向映射、`PlaybackTest` 保留 PID/进度的失败回退。既有其他窗口签名差异继续保留，未执行重新签名。

2026-11-22 TV 分类确认修订（`android-tv-direct-playback`）：分类五行筛选改为确认或点击后才生效，移动焦点不修改筛选；仅热播类型启用获取焦点即切换。筛选横向列表移除focusGroup，允许上下移到其他行而无需遍历隐藏选项。设备守护检查年份未确认时仍为全部、下键到排序、上键返回、确认后应用年份及其余状态保留。此修订替代下文分类焦点即筛选要求。既有并行契约签名差异保持待对账。

2026-11-22 TV 筛选展开修订（`android-tv-direct-playback`）：主导航合为一行，搜索最左，记录与投屏最右，默认焦点在热播。热播类型直接横向显示，获取焦点即切换；分类参照手机展开类型、题材、地区、年份、排序五行，长列表横向滚动，各选项获取焦点即生效且保留其他筛选。分类海报改为八列，精选/热播六列、搜索三列。此修订替代下文热播/分类下拉和分类六列要求，精选及央视下拉保持。设备回归检查无需确认切换、长年份可达、跨题材与Tab保留年份、精选下拉取消。已同步实现与守护；修改前已有并行契约签名差异，保留签名待对账。

2026-11-22 TV 快进快退反馈修订（`android-tv-direct-playback`）：短按左右键跳转10秒并显示短提示；超过600ms进入长按预览，此后每120ms累计10秒，松开只提交一次跳转。播控可见且播放按钮聚焦时，原时间轴跟随预览目标并显示滑块时间弹窗；播控隐藏时显示目标时间/总时长及进度条小卡片。手势不主动打开播控。返回关闭预览后同一手势不再显示，但继续累计并在松开时提交。手势期间暂停自动隐藏，服务端原生播放器处理跳转且就绪后重启5秒倒计时；媒体变化、退出和后台取消手势。操作按钮和选集仍左右导航。`TvRemoteTest` 守护600/120ms阈值、累积、一次提交、关闭、取消、反向和边界。此修订替代旧220ms合并及长按展开播控要求。修改前契约已有未对账并行签名差异，保留原签名。

2026-11-22 TV 主内容Tab修订（`android-tv-direct-playback`、`cctv-public-vod-playback`）：热播、精选、央视点播、分类获得遥控器焦点即切换，同时保留点击。搜索、记录和投屏仍需点击或确认。四个主Tab使用固定在同一Compose位置的共享焦点组，央视页只替换下方内容，不抢走顶部焦点。各页筛选、缓存、滚动和输入保持，主动切Tab取消待执行的详情焦点恢复，央视返回恢复精选内容焦点。`TvTabNavigationTest` 守护立即切换、页面状态保留、工具按钮边界及旧焦点恢复取消。

2026-11-22 TV 详情播放区域焦点修订（`android-tv-direct-playback`）：预览获焦点显示3dp品牌橙色边框，失焦或全屏时移除。边框位于首帧占位之上，加载时也可辨认焦点，保留画面和点击/确认全屏操作。

2026-11-22 TV 全屏遥控与居中修订（`android-tv-direct-playback`）：移除后退/前进10秒按钮，保留左右键十秒目标预览、220ms合并及松键提交。播放/暂停使用 TV Material `IconButton`，48dp圆形内22dp图标居中。`mobile/tools/test_tv_play_controls.py` 在实际UI树中守护入口移除及两种状态中心误差不超过1px；`TvRemoteTest` 继续守护十秒定位与确认键行为。

2026-11-22 TV 加载占位修订（`android-tv-direct-playback`）：详情加载和等待首帧的预览/全屏占位统一使用橙色转圈及“正在加载”，不显示Logo。复用 `VideoFrameVisibility` 和现有守护测试，已出画面的缓冲或暂停仍保留视频帧，错误提示保持。

2026-11-22 TV 投屏交接修订（`android-tv-direct-playback`）：接收端创建播放器前，通过 `PlaybackMediaSessionService.releaseForCasting` 在主线程同步释放本地播放器、预加载、系统静音观察器与媒体会话，再停止服务。暂停、关闭控制器和异步停止服务不能代替解码资源释放；直接进入接收端及重复投屏均执行交接。手机后台播放生命周期保持。`ReceiverHandoffProbe` 在准备并暂停本地视频后，通过正式 AVTransport 启动投屏，检查接收端可播放、本地媒体会话清除且本地播放器释放。

2026-11-22 TV 播放控件修订（`android-tv-direct-playback`）：预览画面移除中央“全屏播放”按钮，点击预览画面或右侧“全屏”按钮进入全屏，复用原会话和视频表面。预览画面保留可聚焦的全屏点击语义；纯图标按钮内部水平居中。隐藏控制栏时，确认键只显示控制栏并保留播放/暂停状态，父级完整消费此次按下、重复及松开，防止焦点移至播放按钮后松键误触。控制栏已显示时，确认键正常点击聚焦按钮。此修订替代旧的隐藏控制时确认键切换播放行为；十秒快进/快退保持。`TvRemoteTest` 守护播放及暂停两种状态、焦点变化后的松键、长按重复、正常按钮点击及不相关松键。

2026-11-22 全屏选集完整展示：网格随面板宽度和系统字体大小调整列数，长名称占整行并换行；线路与详情复用 `PlaybackLineButton`，统一真实名称、来源回退、测速、广告标记与选中样式。渲染守护覆盖窄横屏、1.5 倍字体、长短集名完整布局、线路排序、切线保留面板及长按广告标记。

2026-11-22 TV 卡片标题：鼠标悬停或遥控器聚焦800ms后，超出单行宽度的标题完整循环滚动；移开恢复省略号。`TvPoster` 用同一 `MutableInteractionSource` 收集 hover/focus，标题不新增遥控器停靠点，边框仍在卡片内部。Android 编译、TV lint 和现有遥控器/搜索守护测试验证兼容性。

广告偏好更新还会通过 `PlaybackSession.invalidateLinePreload` 清除已经解析的下一集候选，不重新加载当前媒体；下一集按最新标记重新解析。守护 `PlaybackTest.newAdvertisingReportInvalidatesNextEpisodePreloadWithoutReloadingCurrentMedia`。

2026-11-22 步长决定：用户明确要求统一为 10 秒。`fullscreen-ten-second-seek` 替代旧 5 秒契约；全屏按钮行为、图标数字、无障碍文案和边界测试均采用 10 秒，与双击手势、TV 和投屏遥控器一致。下文旧的“签名未对账”说明记录各项登记时的状态，不能作为恢复 5 秒行为的依据。

2026-11-22 签名对账：已核对 10 秒步长、搜索相关性优先排序及广告线路守护更新；显式接受当前契约后，42 项能力检查通过。历史登记说明中的未签名状态已由本次对账解决。

`manual-playback-line-ad-reports`：手机详情、全屏和投屏选集通过同一个线路长按动作确认“有广告”或取消标记，标签立即更新，长按不切线、不关闭选集。`LineAdStore` 独立偏好长期保存，无测速TTL，重启和清目录缓存保留；具名线路按来源主机、Pomo上游site与线路名隔离，跨集和同来源影片沿用。空名称及纯编号仅当前影片沿用，未标记表示未知。自动进入、换集和故障恢复先优先未标记的兼容线路，再比较原测速耗时；保留手动选择、对应集和进度及暂停意图，标记不禁止播放，仅有广告线路可用时仍可播放。此规则扩展 `persistent-fastest-playback-lines` 的自动选路优先级，原测速展示、缓存及有效期不变。守护：`LineAdStoreTest`、`LineAdSelectionTest`、`PlaybackTest.recoveryPrefersUnmarkedMeasuredLineAndKeepsCurrentEpisodeAndProgress`、`LineAdMarkUiTest`。登记前已有其他窗口的签名、手机全屏5/10秒及搜索评分守护差异，保留签名待对账，不覆盖既有差异。

搜索排序修订：共享搜索先按精确、前缀、包含、其他匹配排序，同等级再按真实评分及年份降序；补分不能让高分续作超越无评分的精确片名。TV 复用 `TvTitleIndex.ranks` 识别中文、全拼、首字母及已知年份佐证的国语年份别名，对全部在线结果排序，不套用本地60条上限；`tvSearchOrder` 统一本地与在线结果，网格和焦点恢复共用身份顺序，在线精确匹配优先于本地部分匹配。片源别名继续保留。守护 `SearchPresentationTest.exactTitlesTakePriorityOverRatingAndYearWhileInvalidRatingsRemainUnknown`、`unratedExactTitleAndVerifiedReleaseAliasesPrecedeHighlyRatedPartialMatches`、`TvSearchTest.gridRanksOnlineExactMatchesAheadOfLocalPartialMatchesAndPreservesIdentities`、`verifiedReleaseAliasesMatchChinesePinyinAndInitialsExactly`、`onlineRankingDoesNotApplyTheSixtyTitleLocalSearchLimit` 和 `TvTitleIndexTest.exactThenPrefixThenContainsMatchesPrecedeProviderOrder`；既有未对账签名保留。

2026-11-22 TV 搜索独立页头（`android-tv-direct-playback`）：搜索页隐藏热播、精选、央视点播、分类、记录和投屏导航，仅保留返回、标题、类型筛选、键盘及结果。进入时聚焦键盘，详情返回恢复结果焦点；页头返回和遥控器返回均回到进入搜索前的页面，保留查询、类型及两页位置。`TvSearchTest.searchBackReturnsToOriginWithoutLosingQueryFiltersOrResultPosition` 与 `closingSearchOutsideSearchDoesNotNavigateOrRequestFocusRestoration` 守护导航状态。既有签名和手机5/10秒差异未对账，保留签名。

2026-11-22 TV 搜索修订（`android-tv-direct-playback`）：默认“全部”，保留电影、电视剧、动漫、纪录片、综艺筛选；题材搜索映射至接口支持的范围，再按明确的片源类型展示，未知类型保留。500ms 后先启动在线搜索，再建立可选本地拼音索引；片源支持 HZW 等首字母时，未收录内容也能展示，不承诺全网拼音覆盖。本地失败不阻断在线搜索，提供加载及无结果提示。普通文字按钮16dp留白保留，六列字母键改为左右4dp，避免 W 裁剪。新增 `TvSearch.kt`、`TvSearchTest` 及 `TvTitleIndexTest.allScopeFindsOnePieceByChineseTitleInitialsAndFullPinyin` 守护。修改前已有签名与手机5/10秒差异，不重新签名覆盖未对账修改。

央视点播导航：唯一入口位于主内容导航的“分类”前，顺序为“热播、精选、央视点播、分类”；点击“央视点播”继续进入原央视节目库，移除工具栏的原快捷入口。共享频道、年月、关键词与返回焦点状态不变。

电视系统启动窗口：主 Activity 使用 `TvLaunchTheme`，初始化期间显示与首页一致的深色渐变、居中的同一熊猫 Logo 和底部“影视 · 随心看”slogan，直接进入首页。复用手机深色 slogan 图；Android 12+ 显式配置系统启动图和 branding image 并保留遮罩留白；接收端继承原主题。实现 `mobile/tv/src/main/res/` 的启动背景、Logo、slogan 与版本主题及主 Activity 清单主题。编译及 lint 检查资源打包，冷启动帧验证依赖电视 ADB 可连接。新增 slogan 锚点，既有签名及手机全屏5/10秒冲突仍待对账，未覆盖签名。

自动择线的手动优先贯穿解析阶段：`PlayerRequest.allowLineFallback=false` 禁止切到其他线路或供应商，当前线路再次点击同样锁定，手动重试沿用这一选择。守护：`LineProbeTest.manualRouteResolutionFailureNeverFallsBackToAnotherLineOrProvider`。

`persistent-fastest-playback-lines` 自动择线补充（2026-11-22 最新授权）：先复用已有源信息及成功媒体缓存开播，不等待全线路测速；随后后台搜索荐片及 Pomo 的同片同年镜像，追加原站剧集身份并补测，不加载媒体、不切线、不改变进度与暂停意图。无映射时先严格匹配一个来源。换集和退出取消过期发现，手选优先，镜像列表缓存供重启恢复。不同年份及电影/剧集不匹配的结果拒绝追加。同影片切集和预加载复用源信息，新集仅解析目标线路，失败才回退同集。后台补测仍最多3条并发，每条10秒预算与一次异常重试，成功2分钟、失败30秒展示有效期。原生错误或连续15秒缓冲时保留原有恢复规则。守护：`PlaybackTest.cachedPlaybackStartsBeforeDiscoveryAndAppendPreservesPausedManualLine`、`staleDiscoveryCannotPublishAfterEpisodeChangeOrClose`、`JianpianSourceTest.backgroundMirrorsRetainProviderPathsAndRejectRemakeYears`、`LineProbePersistenceTest`、`AutoLineHistoryTest`。契约签名在此次修改前已不匹配，保留待对账，不重新签名覆盖。

`episode-playback-source-cache`：切集、切线和下一集预加载复用当前影片线路、集数和元数据，最多48个源，每源128个成功媒体结果。影片页面、剧集路径和原始地址共同隔离缓存；媒体5分钟有效，命中不续期，失败或被拒绝地址不能复用。重试、主动刷新、故障恢复及距片尾60秒且预加载超过45秒的刷新绕过媒体缓存；新鲜预加载结果忽略缓存策略字段匹配切集请求。正文变化替换源缓存，清目录缓存同步清理。守护：`PlaybackSourceCacheTest`、`PlaybackTest.episodeNavigationReusesCurrentSourceButRetryResolvesFreshMedia`、`PlaybackPreloadTest.nearEndRefreshesOldAddressAndPreloadsNewData`。

该缓存规则跨 Android 手机、iOS、Android 电视和 DLNA 投屏遥控统一。手机/iOS 内嵌、全屏及投屏的 `ModernPlayer`，电视详情 `TvDetail` 和全屏 `AndroidTvApp` 均调用 `AppState` 所持共享会话的 `select`/`selectLine`，使用同一播放器、源信息、媒体缓存及失败策略。共享端到端守护为 `PlaybackSourceCacheTest.sharedAppSessionReusesEpisodeCacheForLocalTvAndCastingNavigation`；iOS 设备流程 `testNextEpisodePreloadKeepsVideo` 覆盖连续换集后回到已缓存首集仍有视频画面。

`cctv-public-vod-playback`：TV 首页一个“央视点播”入口，接入24个已核验官方整期栏目：今日说法、开讲啦、新闻联播、焦点访谈、百家讲坛、经济半小时、探索·发现、地理·中国、动物世界、人与自然、健康之路、新闻调查、面对面、新闻1+1、天网、一线、道德观察、第一时间、星光大道、越战越勇、读书、走近科学、生财有道、消费主张。按用户要求以官方频道归属替换题材分类，先选CCTV-1综合、2财经、3综艺、10科教、12社会与法、13新闻，再选栏目；已核验双频道的焦点访谈保留两处归属，同频道不重复。栏目栏支持遥控器横向滚动、离屏焦点恢复；官方 d 年/月筛选按栏目/年月/分页隔离缓存，返回保留筛选和焦点，失败支持重试/取消。关键词、日期、拼音与首字母搜索每批最多10页，显示覆盖期数，可继续检索更早节目。卡片和详情标题显示本期主题，完整原标题保留用于搜索。详情展示原始播出日、时长、四行简介、完整简介弹窗及当前范围同栏目节目；每期推荐按各自 PID 打开。部分为历史回看，不保证持续更新。按稳定 GUID 每次请求正式 HTTPS 点播接口，只采用公开完整视频 `hls_url`；复用详情、历史、Media3 和遥控操作。拒绝无效/试看节目、音频或加密备用字段、伪造地址及已失败媒体，不承诺直播和完整目录。实现 `CctvSource.kt`、`ContentRepository.kt`、`TvLibrary.kt`、`TvCctvLibrary.kt`、`TvDetail.kt`；守护 `CctvSourceTest` 的整期过滤、年月/元数据、频道覆盖和双频道归属、分页、所有注册栏目的独立节目解析、地址刷新、稳定身份、非视频/保护字段与伪造引用测试，`TvCctvSearchTest` 验证关键词/拼音/日期、主题标题和推荐身份，`CctvCacheTest` 验证重启后栏目/年月/分页缓存隔离。登记时签名及手机全屏 5/10 秒锚点已有其他窗口差异，保留既有签名待对账，未覆盖签名。

电视触屏支持：`TvAction` 和 `TvPoster` 在 Android TV 原有焦点/确认键处理外补充触屏手势；点击全屏画面显示或隐藏播控栏。所有筛选、搜索键盘、选集、播放按钮复用同一点击回调，滚动取消点击，回调随重组更新，不增加第二套遥控器焦点/按键处理。

电视焦点边缘完整性：`TvAction` 和 `TvPoster` 的焦点态保持原布局尺寸，按钮白色高亮、海报3dp白色描边向内2dp绘制，防止默认1.1倍缩放和外描边被滚动视口裁切。复用组件统一覆盖首页、央视栏目/节目、详情线路/选集/推荐及播放器弹层；保留焦点导航和点击回调。静态锚点记录尺寸与描边配置，电视边缘焦点截图复测；既有签名及手机全屏5/10秒冲突待对账，未覆盖签名。

`android-tv-halfway-pagination`：电视首页、热播、分类浏览至当前已加载条目的一半时自动追加下一页，追加后重新计算阈值，直到没有下一页。保留筛选、滚动及海报焦点，来源偏移去重；加载中或失败时停止自动请求，切换筛选禁止使用旧结果分页。全部过滤的中间页仍推进来源偏移。手机原有近底部阈值不变。实现 `TvLibrary.kt`、`CatalogComponents.kt`；守护 `CatalogPaginationTest` 的半程阈值、追加后阈值、终页、加载/错误/非激活状态及空页用例。既有签名及其他窗口来源/全屏锚点差异待对账，未覆盖签名。

`android-tv-direct-playback`：独立包 `dev.pandavideo.tv`，Android 6/API 23 起，电视桌面入口及可选触屏。精选、热播、分类、国家筛选、搜索与播放记录复用 `AppState` 直连片源。首页复用手机 `HomeCategoryOptions`，热播复用 `HotTypeOptions`，各自保存选择；分类保留类型、题材、地区、年份、排序。三个内容页面统一六列竖向海报网格。按用户最新要求，热播类型、精选内容/地区、分类五项及央视频道/年月共用单列可滚动下拉菜单，显示名称、当前值、矢量箭头，在按钮附近展开；上下移动、确认生效收起、返回取消，关闭恢复原按钮焦点，长年份列表滚到当前值，左右不跳出菜单。分类筛选合并成一行，保留全部选项和状态。文字按钮左右16dp留白，桌面和内部使用同一熊猫Logo。详情左预览、右信息与全屏/收藏，下方线路和每20集分段的横向选集。详情解析可取消，成功后打开预览；预览和全屏共用会话及视频表面，返回预览保留播放状态。全屏顶部标题、底部时间轴和每24集分段的八列选集面板，支持倍速。方向键与确认键导航，隐藏控制时左右十秒目标预览，220ms合并或松键提交；详情退出及后台暂停并保存进度，错误可重试/取消。六列遥控器键盘支持已收录目录全拼/首字母搜索，未收录内容按完整片名在线搜索，不承诺全网拼音覆盖；返回恢复海报焦点和滚动。投屏入口进入前停止普通会话，内置接收端提供前台SSD​​P/Bonjour发现及有界AVTransport服务，支持播放、暂停、定位、停止，退后台关闭服务并释放解码器。普通播放和投屏均使用系统媒体音量，零音量或系统静音时强制解码器静音，正音量保留系统曲线。实现 `mobile/tv/`、`AndroidTvApp.kt`、`TvLibrary.kt`、`TvDetail.kt`、`TvTitleIndex.kt`、`TvRemote.kt`、`AndroidSystemMediaMute.kt`；守护 `TvRemoteTest`、`TvTitleIndexTest`、`SystemMediaMuteTest`、接收端协议与关闭测试、`AppStateTest` 详情生命周期用例，及 `mobile/tools/test_tv_filter_dropdown.py` 的选择、取消、长列表焦点恢复设备回归。

电视左右键修复（2026-11-22）：播控条显示且播放按钮聚焦时，也支持每次十秒的快进/快退和长按累加；下方操作按钮与选集面板仍左右导航。seek 更新交互计时，沿用 `TvRemoteTest` 的十秒、边界与累加守护，并补充暂停时显示播控条的实机回归。既有契约签名冲突待对账，未覆盖签名。

该电视能力还包含 Android 6 的 `AndroidSourceTls`：仅补入官方 ISRG Root X1，固定 SHA-256 指纹，保留系统根证书、PKIX、有效期和默认主机名校验，统一目录、海报和原生视频信任。`TvNetworkProbe` 在实机验证正常 HTTPS 与错误主机名、过期、自签证书拒绝。

`persistent-fastest-playback-lines`：按用户新增要求补测全部待测线路，同集优先，缺少对应集时沿用手动切线的序号/首集规则，按实际被测剧集缓存。最多3条并发，每条尝试10秒预算，异常重试一次后仍失败暂时隐藏；明确无效媒体直接隐藏，完成一条立即更新、按耗时排序，旧未知结果不能阻止补测。保留当前成功线路，切集/退出取消未完成任务且不误判失效。取消原16条及共享6秒限制。测速持久化到SQLite，成功2分钟、失败30秒后下次测量可重测，目录网络失败仍不判无资源。手动切线即播、进度及投屏暂停规则保持。守护：`LineProbeTest` 的完整补测、逐条更新、重试隐藏、恢复和取消测试；`LineProbePersistenceTest`、`PlaybackTest.staleLineMeasurementsCannotReplaceNewEpisodeState`、`FullscreenEpisodeDrawerTest`。既有签名与全屏5/10秒冲突仍未覆盖。

电视端沿用 `persistent-fastest-playback-lines`：`AndroidTvApp` 在播放会话加载完成后调用同一个 `AppState.probeLines`，逐条发布并经 `updateLineProbes` 拒绝旧结果；预览/全屏切换不重启测速。详情页与全屏共用 `tvPlaybackLineLabel` 展示名称及 `Nms/待测速`，按用户要求移除线路标签的集数；共用 `visiblePlaybackLines` 排序并隐藏失败线路。详情左上预览放大，预留区域与实际视频共用尺寸计算；右侧简介最多两行，线路及每20集分组的选集排列在简介下方。TV 入口显示“精选”和“热播”。守护：`TvLineProbeTest.bothTvPickersShowPendingOrMeasuredLatencyWithoutEpisodeCount`、`tvUsesPhoneOrderingAndHidesFailedRoutesAsResultsArrive`。沿用既有未对账签名，不重新签名覆盖。

`jianpian-hot-tabs-source`：最近热播保留顶部 Tab、网格、横滑和下拉刷新，隐藏“已加载全部N部”。剧集/电影/动漫/综艺/纪录片优先荐片热度目录，再补回改版前豆瓣 `sort=U`、0至10分列表。荐片每逻辑24槽翻一页，原列表每20槽翻一页；真实荐片即使只有15条仍继续翻页至空页。首屏、缓存、刷新、翻页均荐片优先去重，保留重拍年份、类型和季数差异；来源失败保留工作来源及缓存，列表不变时复用实例。发现页8分下限及小品来源保持独立。守护：`HotCatalogTest`、`JianpianSourceTest.hotCategoriesKeepProviderOrderUnratedRowsAndRawPagination`、`AppStateTest.hotCatalogPagesAndTypeSwitchRemainIndependentOfHomeAndBrowse`、`addedHotCategoriesUseTheirOwnTagsAndResetPaging`、`confirmedMissingIsRemovedFromAllCatalogsAndCursorIsPreserved`。既有签名与全屏5/10秒冲突未覆盖。

`jianpian-home-source-identity`：按用户最终修正，精选恢复原有分类、网格、目录分页与8分下限，热播独立用荐片。数据库预装已验证详情地址并持久保存新增身份映射，清普通缓存不删映射；详情资源和选集排在演员推荐之前，线路卡片仅显示名称、真实耗时/待测速，移除集数；选集区保留总集数，确认失效线路隐藏，选中背景高亮。其他线路后台测速，成功探测短期复用。并发检查与播放复用详情，网络失败不误删。守护包括 `JianpianSourceTest.lineMeasurementsUseRealPrefixesAndReuseSelectedProbe`、`CatalogDatabaseTest.verifiedDetailIdentitySurvivesRestartAndSkipsTitleSearch`，保留原有来源身份、评分和无资源测试。既有签名及全屏5/10秒冲突尚未对账，未覆盖签名。

精选 Top 250 补入荐片：批量导入以荐片详情中的豆瓣 ID、上映年份和直接 HLS/MP4 线路确认同一版本，支持已核实别名；只保存稳定详情 ID，播放时检查媒体可达性。确认映射写入内置 SQLite，已有非空身份表同样升级并优先使用荐片，保留原 Pomo 资料和未涉及的运行时映射；未确认不判定无资源。守护为 `CatalogDatabaseTest.shippedJianpianSourcesUpgradeNonemptyDatabaseAndPersistOffline` 和 `persistedJianpianPinBypassesTitleSearch`；导入工具为 `mobile/tools/import_jianpian_sources.py`。沿用未对账签名及全屏 5/10 秒锚点基线，未重新签名。

按新增要求，精选普通分类列表本身也合并荐片评分目录，保留原豆瓣分类及8分下限；来源详情补齐年份、地区、题材后过滤，实际15条的来源页继续翻页到原始空页，20槽精选分页和24槽来源分页独立。搜索并行汇总荐片和Pomo，原始候选用于版本验证，荐片直连优先；普通精选翻页按版本去重。旧缓存先展示，合并缓存避免被单源快照覆盖，部分来源失败标记待校验。Top250原榜单保持250部。守护：`HomeSourceCatalogTest`、`JianpianSourceTest.catalogPrefersVerifiedJianpianAndRetainsEveryDirectLine`、`CatalogDatabaseTest.legacySingleSourceSearchRefreshesToMergedSourcesButSurvivesOffline`；真实联通验证为 `LiveSourceProbeTest.selectedCatalogAndSearchIncludeRealJianpianSources`。

`discover-country-filter`：发现页分类标签下提供国家地区筛选，复用 `BrowseCountryOptions`。国家选择由 `rememberSaveable` 保留，分类横滑、刷新、分页及离开再返回均传递当前国家；当前分类直接订阅活跃列表，邻页按国家隔离预览。推荐继续使用当前筛选快照。守护：`AppStateTest.discoverRetainsCountryForCategoryRefreshAndPagingButResetsOnRegionChange`、`FullscreenTests.testDiscoverCountryFilterKeepsSelection`。2026-11-19 登记时仍存在原有签名未接受及全屏5/10秒锚点冲突，未覆盖签名。

热播补齐缓存守护：`HotCatalogCacheTest.partialCachedHotListRevalidatesMissingOriginalRowsAfterRestart` 验证部分来源失败的缓存会在重进页面后继续补齐，完整合并结果则可离线重启复用。

`player-session-reuse` 补充：听音频返回列表后，从目录、搜索、首页续播或历史重新进入同一影片和集数，复用会话并保留进度、倍速及暂停意图。仅按目录或播放页身份匹配；换片、显式换集、离线播放及错误/完播重开仍执行加载。守护：`AppStateTest.audioModeReopensFromCatalogSearchAndHistoryWithoutReloading`、`audioModeReentryPreservesPauseButOtherEpisodesAndTitlesReplaceSession`。

全屏双击补充：`fullscreen-edge-double-tap-seek` 保护内嵌与全屏共用的左侧快退 10 秒、右侧快进 10 秒、中间播放/暂停。实现位于 `ModernPlayer.kt`，守护项为 `PlaybackTest` 的分区与步长测试、`FullscreenTests.swift` 的暂停状态下双向双击实测。

`capabilities.json` 是本仓库所有受保护能力的单一来源；本文件是同一份清单的人工阅读镜像。
任何窗口修改代码后，都必须运行检查，确认已有能力没有被静默改变。

```sh
python3 graphify-out/check_capabilities.py          # 默认检查，失败返回 1
python3 graphify-out/check_capabilities.py --list   # 列出能力
python3 graphify-out/check_capabilities.py --json   # 机器可读结果
python3 graphify-out/check_capabilities.py --accept # 显式接受契约变更
```

检查内容包括：

- 每个能力的实现文件仍然存在；
- 每个代码锚点（常量、函数、关键条件）仍然存在于指定文件；
- 每个守护测试仍然存在；
- `capabilities.json` 的签名与 `capabilities.sig` 一致，即契约没有被其他窗口静默改写。

## 能力目录

| ID | 能力 | 实现 | 守护测试 |
| --- | --- | --- | --- |
| `douban-native-login-session` | 已移除豆瓣登录入口及弹窗；本地昵称展示与编辑；保留底层会话隔离 | `DoubanSession.kt`、`DoubanConnection.kt`、资料 UI、`ContentRepository.kt`、原生平台适配 | `DoubanSessionTest`、iOS 无登录入口与资料编辑 UI 测试 |
| `ios-pending-seek-timeline` | iOS seek 松手后保持目标进度，旧采样及旧完成事件不回弹，保留暂停意图 | `PendingSeek.kt`、`IosPlatform.kt` | `PendingSeekTest` |
| `immediate-next-episode-playback` | 完播或跳过片尾后直接播放同线路下一集，移除提示页及倒计时；末集停止、睡眠优先、预加载复用 | `Player.kt`、`ModernPlayer.kt`、`DesktopVideoFrame.kt`、`MiniPlayer.kt` | `PlaybackTest`、`PlaybackPreloadTest` |
| `series-intro-outro-skip` | 播放器“更多”开关按地区回退（美剧 30 秒、其余 90 秒），按剧保存片头片尾秒数、续播与回看保护、片尾直接续播下一集、定时优先及完播记录 | `SkipDurations.kt`、`SkipDurationsStore.kt`、`Player.kt`、`AppState.kt`、`SkipDurationsDialog.kt`、`ModernPlayer.kt` | `SkipPlaybackTest` |
| `database-history-episode-continuation` | 历史、每集进度和剧集顺序存独立 SQLite；退出/切集/完播时保存，无定时保存；默认入口自动恢复集数和进度，无续播弹窗；显式选集优先，重试保持集数；完播后历史重进下一集，末集重播；旧记录迁移，清缓存不删记录 | `PlaybackDatabase.kt`、`UserStore.kt`、`Models.kt`、`Player.kt`、`AppState.kt`、`PandaVideoApp.kt` | `PlaybackDatabaseTest`、`AppStateTest`、`PlaybackTest` |
| `sketch-catalog-browser-playback` | 小品仅保留碟音网，旧优酷来源缓存不得恢复；卡片隐藏年份、评分和来源说明，封面与电影卡片等高（宽高比 0.7）；B 站完整 MP4 每次重新解析并使用内部播放器，未支持嵌入保留原站播放 | `SketchSource.kt`、`SketchPlayback.kt`、`BilibiliMedia.kt`、`ContentRepository.kt`、`AppState.kt`、`PandaVideoApp.kt`、`CatalogComponents.kt`、`HomeHistory.kt`、`AndroidOfflineEngine.kt`、`IosPlatform.kt` | `SketchSourceTest`、`SketchPlaybackTest`、`AppStateTest`、`SketchCatalogCacheTest` |
| `saved-first-tab-startup` | 冷启动打开保存顺序的第一项，拖动排序不重建导航图 | `PandaVideoApp.kt`、`SettingsStore.kt` | `SettingsStoreTest`、`BottomTabGestureTest` |
| `home-first-last-played` | 底部第一项首页首位置顶最后播放内容，与首批目录同步绘制；缓存立即显示，刷新保留内容，空目录或失败仍可续播；持久保存年份与评分并补齐旧记录，随交换迁移并恢复记忆集数与进度 | `HomeHistory.kt`、`CatalogComponents.kt`、`PandaVideoApp.kt`、`AppState.kt`、`UserStore.kt` | `HomeHistoryTest`、`AppStateTest`、`UserStoreBehaviorTest`、`PlaybackDatabaseTest` |
| `player-next-episode-address-preload` | 下一集播放地址预解析与片尾刷新 | `core/player/Player.kt` | `PlaybackPreloadTest`、`PlaybackTest` |
| `android-native-data-preload` | Android 原生视频数据预加载 | `AndroidPlaybackPreloader.kt`、`PlaybackMediaSessionService.kt`、`AndroidPlatform.kt` | `PlaybackPreloadDeviceTest` |
| `ios-standby-preroll` | iOS 单播放器预加载与 item 接管 | `IosPlatform.kt` | `FullscreenTests.testNextEpisodePreloadKeepsVideo`（模拟器 XCUITest） |
| `playback-startup-telemetry` | 启播遥测区分 Ready 与首帧；视频遮罩等待当前请求的真实首帧，换会话首个 Composition 不沿用旧首帧状态；已显示视频缓冲时保留最后画面 | `PlaybackTelemetry.kt`、`Player.kt`、`VideoFrameVisibility.kt`、`ModernPlayer.kt` | `PlaybackPreloadTest`、`VideoFrameVisibilityTest`、`PlaybackStateSubscriptionTest` |
| `player-session-reuse` | 单播放会话与播放器复用 | `Player.kt`、`AppState.kt` | `PlaybackTest`、`AndroidPlayerConnectionDeviceTest` |
| `player-pinch-zoom` | 双指缩放 50%～400%，居中显示与百分比反馈；默认完整画面，进入全屏恢复原始比例 | `VideoZoom.kt`、`ModernPlayer.kt`、`AndroidPlatform.kt`、`IosPlatform.kt` | `VideoZoomTest`（原生画面与手势需设备验收） |
| `fullscreen-ten-second-seek` | 全屏播控快进/快退 10 秒；按钮数字与无障碍文案一致，与双击及电视/投屏遥控器统一步长 | `ModernPlayer.kt`、`PlayerTransportIcons.kt` | `PlaybackTest.fullscreenTenSecondSeekClampsToMediaBounds` |
| `fullscreen-episode-drawer` | 全屏右侧滑入选集；返回内嵌播放时按窗口恢复切换布局，避免横屏详情闪现 | `FullscreenEpisodeDrawer.kt`、`FullscreenLayoutState.kt`、`ModernPlayer.kt`、`PlayerOverlayBackHandler` 平台适配 | `FullscreenLayoutTest`、`PlaybackTest`、`FullscreenEpisodeDrawerTest`（系统返回需设备验收） |
| `playback-source-recovery` | 播放失败换源与重试 | `Player.kt` | `PlaybackTest` |
| `pomo-preferred-playback-source` | 精选普通分类合并荐片评分目录和原豆瓣列表，保持8分及地区/题材筛选、独立翻页；Top250保持榜单。搜索并行汇总两源，匹配优先验证荐片直连，失败回退Pomo；旧单源缓存刷新，部分失败不误删 | `ContentRepository.kt`、`JianpianSource.kt` | `HomeSourceCatalogTest`、`JianpianSourceTest`、`CatalogDatabaseTest` |
| `audio-only-hidden-playback` | 后台音频模式与隐藏暂停 | `Player.kt` | `PlaybackTest`、`AudioModeDeviceTest` |
| `audio-only-fullscreen` | 听音频保留当前窗口 | `ModernPlayer.kt`、`MiniPlayer.kt` | `PlaybackTest`、`PictureInPictureCompositionTest`（播放状态与宿主保留；布局需界面验收） |
| `audio-entry-and-sleep-timer` | 听音频直接入口与统一定时关闭抽屉 | `PlayerAudioControls.kt`、`AudioModeBackdrop.kt`、`PlayerSleepTimerSheet.kt`、`ModernPlayer.kt`、`MiniPlayer.kt`、`AppState.kt`、`Player.kt` | `PlaybackTest`、`AppStateTest`、`PlayerSleepTimerUiTest` |
| `home-high-rating-filter` | 首页保留 8 分以上内容；剧集排除明确赛事、演唱会、课程及已知非剧情节目，网络与离线缓存执行同一规则，不改上游分页 | `Models.kt`、`ContentRepository.kt` | `ContentRepositoryTest`、`CatalogDatabaseTest` |
| `douban-json-categories` | 完整豆瓣 JSON 分类、地区分页与网站 JSON 离线快照 | `ContentRepository.kt`、`CatalogDatabase.kt`、`catalog-categories.json`、`export_website_catalog.py` | `ContentRepositoryTest`、`CatalogDatabaseTest` |
| `offline-downloads` | 单集/多集离线缓存、本地播放、非 Wi-Fi 确认与断点续传 | `OfflineDownloads.kt`、`AppState.kt`、`ModernPlayer.kt`、`EpisodeCacheSheet.kt`、`AndroidOfflineEngine.kt`、`IosOfflineEngine.kt`、`AndroidPlatform.kt`、`IosPlatform.kt` | `OfflineDownloadsTest`、`OfflineMediaDeviceTest` |
| `actor-search-and-filmography` | 演员搜索与参演作品；首页、缓存目录和完整详情演员名单补齐已确认作品，配音版本按作品去重，计数与可显示作品一致，逐项显示检查结果 | `Actors.kt`、`AppState.kt`、`CatalogDatabase.kt`、`ActorBrowser.kt`、`ActorSource.kt`、`ActorUi.kt`、`ActorHalfSheet.kt`、`ModernPlayer.kt` | `ActorSourceTest`、`ActorCacheTest`、`AppStateTest`、`ActorNavigationDeviceTest`、`ActorHalfSheetTest` |
| `catalog-tab-swipe-animation` | 首页分类跟手滑动与切换动效 | `TopTabSwipe.kt`、`PandaVideoApp.kt` | `TopTabRefreshGestureTest` |
| `catalog-failure-details` | 列表与搜索显示脱敏异常；播放自动恢复结束后仅显示一个弹窗，区分来源异常、版本歧义和无匹配资源，保留影片身份与重试操作 | `RetryPrompts.kt`、`AppState.kt`、`ContentRepository.kt`、`RetryDialog.kt` | `RetryPromptsTest`、`AppStateTest`、`CatalogPlaybackMatchTest` |
| `search-release-rating` | 按已知版本合并来源重复项，完全片名、前缀、包含匹配及其他别名依次排序，同级评分降序、无分靠后，再按年份；未评分正篇先于高分续集。后台限时补分不阻断搜索与播放，原始匹配候选保留 | `SearchPresentation.kt`、`ContentRepository.kt`、`CatalogDatabase.kt`、`SearchScreen.kt`、`AppState.kt` | `SearchPresentationTest`、`PomoSourceTest` |

搜索补分对缺年份的内置目录先通过已有豆瓣条目核对详情年份，不能直接把目录评分套给同名版本；同名版本共用一次建议查询，成功建议和评分持久缓存，空建议不覆盖已有数据。`ResponseCacheTest` 覆盖内置 TV 类型恢复、年份核对及离线重启评分复用。

完整的需求说明、文件路径、锚点和守护测试见 [capabilities.json](capabilities.json)。

纪录片和动画的分集属性不等于来源的电视剧分类；匹配搜索覆盖所有分类，继续校验片名、年份和分集，避免《非洲》这类纪录片因 TV 筛选漏掉资源。`CatalogPlaybackMatchTest` 覆盖此行为。此次登记保留既有未对账签名及 5 秒/10 秒步长冲突，不重新签名。

`douban-native-login-session` 于 2026-11-15 按用户“还是不行，删掉这个入口吧”移除登录入口及弹窗，不再自动验证或展示豆瓣昵称；资料页与编辑页使用本地昵称，昵称可编辑。底层 Android WebView CookieManager / iOS WKHTTPCookieStore 会话隔离及测试保留；移除入口不清除资料、收藏、历史或浏览器会话。iOS 守护改为检查入口不存在、资料编辑可用。既有签名不匹配及 5 秒快进锚点冲突未对账，未覆盖原签名。

`douban-json-categories` 按用户最新要求恢复豆瓣 `j/new_search_subjects` 完整分类接口，精选保留 Top 250，其他分类继续上游分页并保留首页 8.0 分下限及地区题材交集。网站导出 JSON 包含来源、抓取时间及覆盖情况，可用于新旧安装缺失键的离线兜底；已有 SQLite 数据优先，刷新绕过快照，失败保留并标记 stale。全局快照不用于指定地区，接口失败不伪造后续页，移动端运行不依赖本地网站。替代此前 Top 250 分类限定。沿用此前签名不匹配、快进锚点及小品测试锚点冲突的待对账基线，未覆盖签名。

启动缓存补充：热播、发现、筛选及邻页先显示本地快照，过期仅触发后台更新，不阻塞列表；高分、类别和已确认无资源过滤仍适用。主动下拉刷新跳过快照回调但保留现有内容，分页不被第一页缓存覆盖。`ResponseCacheTest` 验证过期持久缓存重启后先于网络出现、新鲜缓存不联网、主动刷新及旧分类过滤；`AppStateTest.cachedHotListEndsLoadingWhileRevalidationIsPending` 验证慢网络期间列表已显示且结束初始 loading。仍保留待对账签名。

`series-intro-outro-skip` 按用户要求默认开启，无配置时美剧片头片尾各 30 秒，其他地区各 90 秒；播放快照与设置开关共用地区规则。显式关闭保存零值，重新打开或换集保留关闭，自定义时长保持不变。守护测试覆盖默认跳过的执行、地域回退和关闭持久化。沿用已有签名待对账。

片尾回看保护只对向后定位到片尾或重开已完播集生效；向前快进到片尾继续触发自动续播，暂停时等待用户恢复播放。未知时长时定位不关闭后续片尾跳过。守护：`SkipPlaybackTest.forwardSeekIntoCreditsAdvancesButPausedSeekWaitsForPlay`、`seekingBeforeDurationArrivesDoesNotDisableLaterOutro`。

`player-regional-high-rating-recommendations`：播放页推荐当前影片同制作地区、同类型、豆瓣至少 8 分的内容，排除当前影片。中国大陆、香港、台湾分别匹配，未知地区不回退全局推荐；已知动画电影与剧集分别匹配。继续约束到打开影片时的筛选列表，区域补全不引入范围外影片。实现：`PlayerRecommendations.kt`、`ContentRepository.kt`、`AppState.kt`、`ModernPlayer.kt`；守护：`PlayerRecommendationsTest` 的七项地区、类型、评分、范围及元数据测试。沿用已有未对账签名。

2026-11-22 电视详情补接：`TvDetail.kt` 在在线搜索与播放历史详情元数据就绪后复用区域推荐加载器，逐批显示相关推荐；返回或切换影片取消旧请求，全屏保留加载。点击推荐继续保留自动加载状态，已有目录筛选列表和央视同栏目列表仍沿用原数据。守护：`TvDetailRecommendationsTest` 覆盖搜索/历史区域请求、高分结果、继续点击推荐、来源列表/央视保持、未知地区和取消。登记前已有其他窗口的签名及电视播控锚点差异，保留原签名。

同次实机检查补充：电视详情观察 `AppState.playingCatalog` 的异步元数据补全，片源缺地区时使用可信目录地区重新加载推荐；新增 `verifiedCatalogMetadataEnablesHistoryRecommendationsWhenProviderRegionIsMissing` 守护，未知地区仍不回退全局列表。

`offline-downloads` 的唯一缓存入口位于播放详情年份后面，打开选集面板，默认本集并支持当前线路全选；宽屏支持面板使用选集标题旁的唯一入口。已有缓存、等待和进行中的集不可重复选择，暂停/失败集可继续/重试。批量路径先完整校验，再按来源顺序加入串行队列，各集开始时重新解析媒体地址，不切换当前播放。守护测试覆盖批量顺序、独立解析、本地多集、去重、无效批量无副作用和暂停恢复。保留其他窗口尚未对账的基线签名。

缓存队列统一检查有效网络：非 Wi-Fi 须确认，批量只提醒一次；网络变化使旧确认失效。断网或失去 Wi-Fi 保留进度等待，Wi-Fi 恢复仅继续网络等待任务，手动暂停保持暂停。Android 使用 Media3 已缓存字节续传；iOS HLS 暂停并恢复系统任务，MP4 私有保存并消费系统续传数据。源站拒绝范围请求或旧链接失效时允许重新下载。新增队列守护与 Android 原生字节范围测试；原有签名冲突未解决前不执行 `--accept`。

`actor-search-and-filmography` 按用户要求改用爱奇艺公开人物搜索及人物作品页，不依赖 Wikidata。人物资料保留全部来源字段标识、名称、顺序及原始值，“关于TA”展示全部非空资料；简介完整展示，有效生日格式化，无效日期保留原值。导航携带完整资料，详情按来源 ID 精确匹配并独立缓存，兼容旧生日缓存；守护测试覆盖字段对齐、同名人物身份、旧数据和离线缓存。播放页点击演员改为原页面内从右侧滑入的面板，竖屏顶部对齐实际视频区域下沿并填满下方区域，横屏在右侧显示并填满可用高度；复用资料与作品，不导航或影响播放，视频控件保持可交互；关闭、标题栏下拉或右滑收起保留播放状态。守护测试验证滑入方向、竖屏及横屏边界、视频交互和播放会话保持。搜索页仍进入独立演员页。头像复用应用磁盘缓存，缓存键隔离来源；仅显示明确出演/配音的电影和电视剧，出演名单包含该人物时保留兼任导演等角色的作品，无出演证据的纯幕后作品仍排除。豆瓣目录映射保持媒体类型一致和唯一匹配，优先标准化同名同年，兼容空白标点、电视剧第一季/第一部/尾缀1差异及最多一年年份偏差，不合并续集或多位数字片名。映射后使用目录片名和年份查找资源；豆瓣资料缺失时独立验证播放源，确认的详情页可展示并直接播放，不重新搜索，也不把官网或媒体URL当详情页。当前播放页名单确认的参演作品补入片单，片名、年份和类型相同才合并，来源不可用时保留已确认作品。首页、缓存目录和完整缓存详情的明确演员名单也补齐作品，仍要求唯一人物ID；本地目录修正唯一匹配的最多一年首映偏差，普通话等显式配音后缀统一查询片名，同一已验证目录URL只展示和计数一次。标题计数与可显示作品一致，逐项发布结果，部分失败可重试。新版v3映射缓存立即刷新旧遗漏，旧缓存仅用于限时离线回退；未匹配作品和检查异常不写入长期映射缓存，空列表提供重试。守护测试覆盖马大帅、续集防串、年份歧义、豆瓣空响应、已验证详情页直达、网络失败保留、参演姓名精确匹配及缓存迁移。作品快照不代表完整履历。基线签名已不匹配，且5秒/10秒锚点尚未对账，暂不覆盖其他窗口未对账的契约签名。

`audio-only-fullscreen` 按用户要求改为听音频保留当前窗口，播放页与底部小窗都不自动展开。保留已有能力 ID；登记时已有契约签名不匹配，须先对账此前契约修改，再显式接受。

`audio-entry-and-sleep-timer` 同样保留原有签名待对账。「定时关闭」到点暂停，保留播放进度；「播完当前」在本集结束时阻止自动下一集，时长定时与本集结束定时互相替换。

`fullscreen-episode-drawer` 同样在签名不匹配的基线下登记，暂不重新签名。全屏选集保留播放器并从右侧滑入；选集后关闭、切线路保持打开；退出全屏或进入 PiP 后隐藏。

`catalog-tab-swipe-animation` 覆盖相邻真实列表在松手前跟手进入视口、独立预加载与结果复用、松手切换、短距离及首尾回弹、点击分类入场，并保留垂直刷新与斜向手势仲裁。相邻页不得改变当前筛选、触发自动分页或弹出失败提示。发现、分类和热播在切到仍未完成的预加载时接续同一请求，立即沿用其缓存列表；退出或改变地区后旧任务不得覆盖新筛选。`AppStateTest` 覆盖请求计数、原预加载完成时间、过期缓存先显示和地区隔离。沿用已有签名待对账。

`home-first-last-played` 使用当前保存的底部第一项，仅该首页的第一个顶部分类展示带“继续播放”标签的首位历史卡片，切换到其他分类恢复普通目录。适配发现、分类、最近热播与已有“我的”播放记录。个人卡片不受来源目录评分或分类限制；来源目录保持高分筛选与原始分页，明确匹配的重复影片只在展示层去除。点击使用保存播放地址与记录集数，保留进度，不自动替换成下一集。沿用已有签名待对账。

演员作品海报优先使用豆瓣等独立图片来源，不回退到爱奇艺受保护作品图。播放来源详情缺图时保留已验证搜索候选的海报；已确认播放地址的补图必须匹配同一地址、片名、年份和类型。v4 映射缓存中空海报或受保护海报立即尝试补图，补图失败保留已验证身份，离线旧缓存剔除受保护图片。`ActorCacheTest` 覆盖空图缓存补齐、搜索海报保留和同名不同年份防串图。

演员作品的播放入口为整张海报，点击即打开已验证作品；移除海报上的“播放”按钮浮层，点击反馈按海报 8dp 圆角裁切，无障碍操作名称包含作品标题。

演员页面先发布内存中的已确认参演作品，再补充本地目录和限时缓存；过期但未超过离线保留期的资料与片单在网络刷新前展示。已有作品刷新时保留海报，用进度条提示更新；作品资源检查独立于后续作品映射并限制为四个并发请求，退出页面取消相关任务。`ActorCacheTest` 与 `AppStateTest` 覆盖慢请求期间先显示、取消保留缓存及超过保留期不显示。

## 修改流程

`bilibili-public-video-source`：搜索页通过“影视 / B站”选择来源，支持公开用户视频的关键词、BV号、官方HTTPS视频链接及链接中的分集。B站结果保持源排序，不关联影视评分、豆瓣详情或演员身份。每次播放刷新完整单段MP4地址，复用原生播放器和缓存；稳定BV/CID用于分集恢复。匿名访客Cookie只在内存中使用，禁止跨源转发；登录、付费、无效身份、多段或DASH均明确失败，不替换为同名影视。守护为 `BilibiliSourceTest`、`BilibiliCacheTest`（重启可读元数据且不保存播放令牌），显式联网验证为 `BilibiliLiveProbeTest`，iOS 流程为 `testBilibiliPublicVideoSearchAndPlayback`。既有小品嵌入委托相同解析器并由 `SketchPlaybackTest` 保护。沿用已有未对账签名。

演员参演作品页只展示已映射的影视条目，并按映射后的目录 URL 隐藏确认无播放资源的作品；不展示抓取来源说明和未映射条目的搜索入口。网络错误与超时不作为无资源证据。

### 只想改实现，不想改能力

1. 修改前先运行一次检查，确认基线通过。
2. 修改代码。
3. 再运行一次检查。若通过，说明能力锚点与守护测试仍在。
4. 按 `mobile/AGENTS.md` 的要求运行对应 Host/Android/iOS 验证。

### 有意识地修改能力

1. 同步修改实现、守护测试、`capabilities.json` 中的 `requirement`/`anchors`/`guards`。
2. 更新本文件的目录或说明。
3. 运行检查；确认失败项与本次有意变更一致。
4. 显式接受契约变更：

```sh
python3 graphify-out/check_capabilities.py --accept
```

`--accept` 只会在所有锚点和守护测试都存在时重新签名，不会掩盖缺失的代码。

### 多窗口并行改代码

- 提交或合并前运行检查。
- 如果失败项指向一个你没有改过的能力，说明另一个窗口改了它：先对账，不要删除锚点、不要改守护测试、不要用 `--accept` 把失败压下去。
- 如果失败项是签名不匹配，说明 `capabilities.json` 被改过但没有重新签名；先审查契约差异，再决定是恢复还是接受。
- 锁文件、格式化、重命名等批量修改也不能顺手改掉受保护锚点；若确实需要重命名，按“有意识地修改能力”的流程处理。

## 设计限制

Tab 切换保留已加载的过期缓存列表并重新校验，不先替换成空列表或骨架屏。顶部 Tab 点击入场从首个绘制帧就使用正确偏移，不能先显示静止内容再突然侧移；横滑提交继续直接重定位页面，不重复入场。活动与预览数据源切换时，订阅首帧立即读取新源的快照，避免沿用另一个分类的数据；列表与海报组合保持不变。守护：`AppStateTest.staleTabsKeepTheirRowsWhileRevalidatingAfterASelectionChange`、`TopTabRefreshGestureTest.categoryTapNeverDrawsSettledContentBeforeItsArrivalAnimation`、`TopTabRefreshGestureTest.changingCatalogSubscriptionKeepsEachPagesOwnRowsOnItsFirstFrame`。保留已有未对账签名。

### dlna-video-cast-handoff — BDDLNA 视频投屏与真实状态

Android/iOS 使用共用 SSDP + AVTransport，保留进度与暂停意图。有视频地址即可投屏，不要求手机首帧或持续播放；暂停交接只设置地址和进度，不发送 Play。成功后停止手机本地媒体，接收端暂停与手机切后台保留连接；接收端结束后恢复手机媒体、进度和倍速，但不自动播放。切集/退出停止旧会话，缓冲状态维持播放意图，电视播放失败向手机反馈。实现：`Dlna.kt`、`DlnaCasting.kt`。守护：`DlnaTest`、`DlnaCastingTest`（首帧前交接、暂停轮询、结束识别、停止本地媒体与恢复）、显式启用的 `PandaCastReceiverInteropTest` 实机联调。接收端独立目录：`/Users/bytedance/Documents/develop/vibe/PandaCastReceiver`。

iOS 使用 `IosDlna.kt` 中的系统 Bonjour 浏览器发现 `_pandacast._tcp` 接收端，`Info.plist` 声明对应服务与本地网络用途，保留 SSDP 与定向 IP 搜索。取消或结束搜索后停止浏览和解析。设备守护：`FullscreenTests.testPandaCastBonjourDiscovery`、`testPandaCastFromPausedPhoneAndBackgroundReturn`，要求同一局域网运行接收端；使用协议模拟服务时仅证明 UI 与控制链路，不证明接收端解码。

投屏列表仅保留接收端选择，已移除 AirPlay 原生路由入口与对应平台接口；本地网络权限帮助仍保留。

连接后由 `DlnaRemoteScreen.kt` 展示参考设计的紧凑遥控器，提供播放暂停、10 秒快进快退、进度条与选集。连接中可取消，成功退出后显示结束状态与重新投屏入口。选集后停止旧会话并重新连接原接收端；切线路时携带电视进度和播放意图。守护：`remoteEpisodeHandoffKeepsRemotePlayIntentAndPositionWithPhonePaused`、`cancellingConnectionStopsPendingHandoffBeforeReconnecting`；iOS Bonjour UI 用例覆盖进入遥控器、暂停与结束投屏，保留遥控器和结束页截图。

登记时原有签名及全屏 5/10 秒锚点冲突未对账，保留签名，不执行 `--accept` 掩盖既有失败。

- 这是一个变更检测工具，不是安全边界。它不能阻止有意绕过，只能让绕过留下明显痕迹。
- 新增能力不会被自动发现；必须人工登记到 `capabilities.json`，否则检查无法保护它。
- 锚点是文本匹配，不是行为证明。锚点还在，不代表运行时行为一定正确；守护测试和平台验证仍然必须运行。
- `ios-standby-preroll` 的 ID 沿用旧的备用播放器 preroll 契约，实现已改为“单 AVPlayer + 预热 AVPlayerItem 接管”。备用 player 会导致接管集只有声音无画面，item 跨 player 复用会直接抛异常，因此不再创建备用 AVPlayer。

### hls-playlist-first-segment-reuse — 未加密 HLS 播放列表与首片段复用

Android/iOS 对下一集未加密 VOD TS 媒体播放列表缓存播放列表与完整首片段。正式播放等待同一个下载任务，命中后读取缓存入口，后续片段仍直接访问源站。相对地址以最终重定向地址解析，不合并播放列表查询参数。完整 URL 为键，缓存有效期两分钟，首片段上限 16MiB，下载任务超时 12 秒。失败、过期、不支持或取消候选时回退；保留暂停、起播进度、倍速、单播放器和 PiP。Android 本地文件保留至原生读者释放，服务退出后清理；iOS 仅监听 127.0.0.1，支持 GET、HEAD、单字节范围请求。

守护：`HlsBootstrapCacheTest`；显式提供计数测试源运行 `HlsBootstrapDeviceTest` 与 `IosHlsBootstrapTest`。这是一份会话缓存，不承诺跨进程持久化。登记前原签名与全屏 5/10 秒冲突未解决，保留原签名待对账。

### cast-receiver-black-background-remote-sizing — 投屏背景、左右键与画面尺寸

TV 内置接收端和独立熊猫投屏播放背景及 shutter 为纯黑。左右键直接快退／快进 10 秒，限制在有效时间轴内并保留暂停意图；等待页及不可 seek 媒体不跳转。保留进度条与设置的原生导航；播控区上键选择画面尺寸，画面尺寸按钮的下键回到播放暂停。控制条显示时提供“充满屏幕／适应屏幕”，按等比例裁切或完整画面切换，不重建播放器、不改变进度，按钮随控制条隐藏。守护：TV `ReceiverRemoteProbe` 和独立接收端 `ReceiverPlaybackLifecycleTest.remoteArrowSeekAndScreenFillPreservePausedPlayback`，使用可控 30 秒视频进行实机验证。沿用既有未对账签名，不覆盖手机全屏 5/10 秒冲突。
