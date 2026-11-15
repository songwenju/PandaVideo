import XCTest
import UIKit

final class FullscreenTests: XCTestCase {
    /// Exercises catalog selection and AVPlayer progress for the classic cartoon subject.
    func testDiscoverClassicTomAndJerryPlayback() {
        verifyDiscoverPlayback(title: "猫和老鼠", category: "动画")
    }

    /// Exercises catalog selection and AVPlayer progress for the original 1986 series.
    func testDiscoverClassicJourneyToTheWestPlayback() {
        verifyDiscoverPlayback(title: "西游记", category: "高分剧集")
    }

    private func verifyDiscoverPlayback(title: String, category: String) {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let discover = app.buttons.matching(NSPredicate(format: "label == %@", "发现")).firstMatch
        XCTAssertTrue(discover.waitForExistence(timeout: 20), app.debugDescription)
        discover.tap()
        let categoryButton = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", category)).firstMatch
        XCTAssertTrue(categoryButton.waitForExistence(timeout: 15), app.debugDescription)
        categoryButton.tap()
        let score = app.staticTexts.matching(NSPredicate(
            format: "label MATCHES %@", "^[0-9]+([.][0-9]+)? 分$")).firstMatch
        XCTAssertTrue(score.waitForExistence(timeout: 60), app.debugDescription)
        let poster = app.buttons.matching(NSPredicate(format: "label == %@", title)).firstMatch
        // Lazy catalog cards appear in AX only near the viewport. Search while scrolling
        // instead of waiting for an offscreen card, and exclude the bottom navigation area.
        var located = false
        for _ in 0..<30 {
            if poster.exists {
                let frame = poster.frame
                if frame.width > 0 && frame.midY > app.frame.height * 0.22 &&
                    frame.midY < app.frame.height * 0.86 && poster.isHittable {
                    located = true
                    break
                }
            }
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.78))
                .press(forDuration: 0.5, thenDragTo:
                    app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.38)))
        }
        XCTAssertTrue(located, "Discover card not located: \(title)\n\(app.debugDescription)")
        // A tap immediately after a fling can stop scrolling without opening the card.
        // Confirm navigation and repeat the normal tap only if the catalog remains visible.
        poster.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let fullscreen = app.buttons["全屏"].firstMatch
        if !fullscreen.waitForExistence(timeout: 5) && poster.exists {
            poster.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
        XCTAssertTrue(fullscreen.waitForExistence(timeout: 60), app.debugDescription)
        XCTAssertTrue(app.staticTexts["选集"].firstMatch.waitForExistence(timeout: 60),
            app.debugDescription)
        func timeValues() -> Set<Int> {
            // Read one immutable snapshot: live indexed queries become invalid when
            // playback controls disappear or optional metadata arrives during iteration.
            guard let snapshot = try? app.snapshot() else { return [] }
            var values = Set<Int>()
            func visit(_ element: XCUIElementSnapshot) {
                if element.elementType == .staticText &&
                    element.frame.midY < snapshot.frame.height * 0.34 &&
                    element.label.range(of: "^[0-9]{1,2}:[0-9]{2}$",
                        options: .regularExpression) != nil {
                    let parts = element.label.split(separator: ":").compactMap { Int($0) }
                    if parts.count == 2 { values.insert(parts[0] * 60 + parts[1]) }
                }
                element.children.forEach { visit($0) }
            }
            visit(snapshot)
            return values
        }
        var initial = Set<Int>()
        // A continuously advancing elapsed value distinguishes native playback from
        // a loaded episode list or a duration that appears once during buffering.
        let progress = NSPredicate { _, _ in
            let current = timeValues()
            if current.isEmpty {
                // Reveal the auto-hiding transport controls without pressing pause.
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.18)).tap()
                return false
            }
            if initial.isEmpty {
                initial = current
                return false
            }
            return current.contains { value in
                initial.contains { previous in value >= previous + 3 && value <= previous + 15 }
            }
        }
        XCTAssertEqual(XCTWaiter.wait(for: [
            XCTNSPredicateExpectation(predicate: progress, object: app)
        ], timeout: 45), .completed, "Native playback did not advance: \(app.debugDescription)")
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "discover-\(title)-native-playback"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// Keeps the removed login entry absent while local profile editing remains available.
    func testProfileHasNoDoubanLoginEntry() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let profile = app.buttons.matching(NSPredicate(format: "label == %@", "我的")).firstMatch
        XCTAssertTrue(profile.waitForExistence(timeout: 20), app.debugDescription)
        profile.tap()
        let edit = app.buttons["编辑资料"].firstMatch
        XCTAssertTrue(edit.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertFalse(app.buttons["连接豆瓣账号"].exists)
        XCTAssertFalse(app.buttons["豆瓣账号已连接"].exists)
        XCTAssertFalse(app.webViews.firstMatch.exists)
        edit.tap()
        XCTAssertTrue(app.staticTexts["编辑资料"].firstMatch.waitForExistence(timeout: 10),
            app.debugDescription)
        XCTAssertFalse(app.staticTexts["昵称来自豆瓣账号"].exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "local-profile-without-douban-login"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.buttons["取消"].firstMatch.tap()
        XCTAssertTrue(edit.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// Verifies the full Douban JSON catalog reaches the filter screen.
    func testDoubanFilterCatalogLoads() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let tab = app.buttons.matching(NSPredicate(format: "label == %@", "分类")).firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 20), app.debugDescription)
        tab.tap()
        let comedy = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "喜剧")).firstMatch
        XCTAssertTrue(comedy.waitForExistence(timeout: 10), app.debugDescription)
        comedy.tap()
        let rating = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "豆瓣评分")).firstMatch
        // The live popular-order request can fail before selecting the saved rating
        // snapshot. Dismiss its normal retry dialog so the sort row is accessible.
        if !rating.waitForExistence(timeout: 3) {
            let cancel = app.buttons["取消"].firstMatch
            if cancel.waitForExistence(timeout: 5) { cancel.tap() }
        }
        XCTAssertTrue(rating.waitForExistence(timeout: 10), app.debugDescription)
        rating.tap()
        // A previous page heading can still exist during the sorting transition. Wait for
        // real catalog metadata so the evidence cannot capture a replacement skeleton.
        let score = app.staticTexts.matching(NSPredicate(format: "label MATCHES %@", "^[0-9]+([.][0-9]+)? 分$")).firstMatch
        XCTAssertTrue(score.waitForExistence(timeout: 40), app.debugDescription)
        XCTAssertEqual(app.state, .runningForeground)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "douban-filter-catalog"
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// Exercises the real source embed, public API and AVPlayer MP4 transport together.
    func testBilibiliSketchNativePlaybackAndSeek() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let hot = app.buttons.matching(NSPredicate(format: "label == %@", "最近热播")).firstMatch
        XCTAssertTrue(hot.waitForExistence(timeout: 20), app.debugDescription)
        hot.tap()
        let sketch = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "小品")).firstMatch
        for _ in 0..<5 {
            // Compose exposes offscreen horizontal tabs in the accessibility tree; querying
            // their hittability can itself fail. Scroll until the frame is inside the window.
            if sketch.exists && sketch.frame.width > 0 &&
                app.windows.firstMatch.frame.contains(sketch.frame) { break }
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.175))
                .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.175)))
        }
        XCTAssertTrue(sketch.waitForExistence(timeout: 10), app.debugDescription)
        sketch.tap()
        let title = app.buttons
            .matching(NSPredicate(format: "label CONTAINS %@", "又来了")).firstMatch
        // This verified sample is the provider's first row. Do not scroll a skeleton:
        // network completion during a fling can jump past the card before AX observes it.
        XCTAssertTrue(title.waitForExistence(timeout: 45), app.debugDescription)
        title.tap()
        XCTAssertTrue(app.staticTexts["选集"].waitForExistence(timeout: 40), app.debugDescription)
        let advanced = NSPredicate { _, _ in
            app.staticTexts.allElementsBoundByIndex.contains {
                $0.label.range(of: "^0:[0-5][1-9]$", options: .regularExpression) != nil
            }
        }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: advanced, object: app)],
                                     timeout: 40), .completed, app.debugDescription)
        XCTAssertGreaterThan(capture(app, name: "bilibili-sketch-playing"), 1.0)
        let fullscreen = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "全屏")).firstMatch
        if !fullscreen.exists { app.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.15)).tap() }
        XCTAssertTrue(fullscreen.waitForExistence(timeout: 5), app.debugDescription)
        fullscreen.tap()
        let landscape = NSPredicate { _, _ in
            app.windows.firstMatch.frame.width > app.windows.firstMatch.frame.height
        }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: landscape, object: app)],
                                     timeout: 10), .completed)
        func elapsedSeconds() -> Int {
            let values = app.staticTexts.allElementsBoundByIndex.compactMap { element -> Int? in
                let parts = element.label.split(separator: ":")
                guard parts.count == 2, let minutes = Int(parts[0]), let seconds = Int(parts[1]),
                      seconds < 60 else { return nil }
                return minutes * 60 + seconds
            }
            return values.min() ?? -1
        }
        let seek = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label BEGINSWITH %@", "快进 ")).firstMatch
        if !seek.exists { app.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.5)).tap() }
        XCTAssertTrue(seek.waitForExistence(timeout: 5), app.debugDescription)
        let before = elapsedSeconds()
        XCTAssertGreaterThanOrEqual(before, 0)
        let step = seek.label.contains("10") ? 10 : 5
        seek.tap()
        let sought = NSPredicate { _, _ in elapsedSeconds() >= before + step - 1 }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: sought, object: app)],
                                     timeout: 15), .completed, app.debugDescription)
        print("SKETCH_SEEK before=\(before) after=\(elapsedSeconds()) step=\(step)")
        // Pause to exclude normal playback advancement from edge double-tap measurements.
        let pause = app.buttons.matching(NSPredicate(format: "label == %@", "暂停")).firstMatch
        XCTAssertTrue(pause.waitForExistence(timeout: 5), app.debugDescription)
        pause.tap()
        let play = app.buttons.matching(NSPredicate(format: "label == %@", "播放")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 5), app.debugDescription)
        let pausedAt = elapsedSeconds()
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.82, dy: 0.5)).doubleTap()
        let forwarded = NSPredicate { _, _ in abs(elapsedSeconds() - pausedAt - 10) <= 1 }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: forwarded, object: app)],
                                     timeout: 15), .completed, app.debugDescription)
        let afterForward = elapsedSeconds()
        XCTAssertTrue(play.exists, "Edge seek must preserve paused playback")
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.18, dy: 0.5)).doubleTap()
        let rewound = NSPredicate { _, _ in abs(elapsedSeconds() - max(0, afterForward - 10)) <= 1 }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: rewound, object: app)],
                                     timeout: 15), .completed, app.debugDescription)
        XCTAssertTrue(play.exists, "Edge seek must preserve paused playback")
        print("FULLSCREEN_DOUBLE_TAP before=\(pausedAt) forward=\(afterForward) rewind=\(elapsedSeconds())")
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "bilibili-sketch-after-seek"
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// Reports mean luma of a normalized area so black video output is detectable in logs.
    private func meanLuma(_ screenshot: XCUIScreenshot, normalized: CGRect) -> Double {
        guard let image = screenshot.image.cgImage else { return -1 }
        let rect = CGRect(x: normalized.minX * CGFloat(image.width),
                          y: normalized.minY * CGFloat(image.height),
                          width: normalized.width * CGFloat(image.width),
                          height: normalized.height * CGFloat(image.height)).integral
        guard let crop = image.cropping(to: rect) else { return -1 }
        let width = 16, height = 8
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        return pixels.withUnsafeMutableBytes { buffer -> Double in
            guard let context = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return -1 }
            context.interpolationQuality = .low
            context.draw(crop, in: CGRect(x: 0, y: 0, width: width, height: height))
            var sum = 0.0
            for index in stride(from: 0, to: width * height * 4, by: 4) {
                sum += 0.2126 * Double(buffer[index])
                    + 0.7152 * Double(buffer[index + 1])
                    + 0.0722 * Double(buffer[index + 2])
            }
            return sum / Double(width * height)
        }
    }

    @discardableResult
    private func capture(_ app: XCUIApplication, name: String) -> Double {
        let screenshot = app.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        // Portrait video region sits below the status bar and above the details list.
        let luma = meanLuma(screenshot, normalized: CGRect(x: 0.2, y: 0.14, width: 0.6, height: 0.10))
        print("VIDEO_LUMA \(name)=\(luma)")
        return luma
    }

    private func tapEpisode(_ app: XCUIApplication, _ label: String) {
        let button = app.buttons.matching(NSPredicate(format: "label == %@", label)).firstMatch
        for _ in 0..<8 {
            if button.exists && button.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(button.isHittable, "Episode \(label) is not hittable: \(app.debugDescription)")
        button.tap()
    }

    /// Renders cached/catalog content and switches tabs to catch first-frame Kotlin failures.
    func testStartupAndTabNavigation() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let search = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "搜索影视名称")).firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 20), app.debugDescription)
        for label in ["发现", "分类", "我的", "最近热播"] {
            let tab = app.buttons.matching(NSPredicate(format: "label == %@", label)).firstMatch
            XCTAssertTrue(tab.waitForExistence(timeout: 15), app.debugDescription)
            tab.tap()
            XCTAssertEqual(app.state, .runningForeground, "App exited after opening \(label)")
        }
        XCTAssertTrue(search.waitForExistence(timeout: 20), app.debugDescription)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "startup-tab-navigation"
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// Verifies that sleep choices respond before a player exists and expose a live countdown.
    func testSleepTimerCanBeEnabledChangedAndCancelledBeforePlayback() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let profile = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "我的")).firstMatch
        XCTAssertTrue(profile.waitForExistence(timeout: 15), app.debugDescription)
        profile.tap()
        let settings = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "播放设置")).firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 10), app.debugDescription)
        settings.tap()
        let fifteen = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "15 分钟")).firstMatch
        for _ in 0..<4 {
            if fifteen.exists && fifteen.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(fifteen.isHittable, app.debugDescription)
        fifteen.tap()
        let countdown = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "剩余 ")).firstMatch
        XCTAssertTrue(countdown.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(countdown.label.contains("14 分") || countdown.label.contains("15 分"), countdown.label)
        let thirty = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "30 分钟")).firstMatch
        thirty.tap()
        let changed = NSPredicate { _, _ in
            countdown.exists && (countdown.label.contains("29 分") || countdown.label.contains("30 分"))
        }
        XCTAssertEqual(XCTWaiter.wait(for: [
            XCTNSPredicateExpectation(predicate: changed, object: app)
        ], timeout: 5), .completed)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "sleep-timer-active"
        shot.lifetime = .keepAlways
        add(shot)
        app.buttons.matching(NSPredicate(format: "label == %@", "取消")).firstMatch.tap()
        XCTAssertTrue(app.staticTexts["未开启"].waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(countdown.exists)
    }

    /// Exercises the live public Pomo source independently of Douban catalog availability.
    func testPomoSearchPlayback() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let searchEntry = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "搜索影视名称")).firstMatch
        XCTAssertTrue(searchEntry.waitForExistence(timeout: 15), app.debugDescription)
        searchEntry.tap()
        let field = app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)
        field.tap()
        field.typeText("春夏秋冬又一春")
        let search = app.buttons.matching(NSPredicate(format: "label == %@", "搜索")).firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5), app.debugDescription)
        search.tap()
        let result = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "春夏秋冬又一春")).firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 45), app.debugDescription)
        result.tap()
        let episodes = app.staticTexts["选集"].firstMatch
        XCTAssertTrue(episodes.waitForExistence(timeout: 45), app.debugDescription)
        // A nonzero elapsed time is stronger evidence than an autoplay/pause icon.
        let elapsed = NSPredicate { _, _ in
            app.staticTexts.allElementsBoundByIndex.contains { element in
                element.label.range(of: "^0:[0-5][1-9]$", options: .regularExpression) != nil
            }
        }
        XCTAssertEqual(XCTWaiter.wait(for: [
            XCTNSPredicateExpectation(predicate: elapsed, object: app)
        ], timeout: 30), .completed, "Playback time did not advance: \(app.debugDescription)")
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "pomo-native-playback"
        shot.lifetime = .keepAlways
        add(shot)
    }

    func testFullscreenRotatesAndRestoresPortrait() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let poster = app.images.firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 45), app.debugDescription)
        poster.tap()
        let fullscreen = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "全屏")).firstMatch
        // Controls hide during playback; tap the video only if the button has disappeared.
        func showControls() {
            if !fullscreen.exists && !app.staticTexts["锁定"].exists {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.15)).tap()
            }
        }
        XCTAssertTrue(fullscreen.waitForExistence(timeout: 45), app.debugDescription)
        for cycle in 1...3 {
            showControls()
            XCTAssertTrue(fullscreen.waitForExistence(timeout: 5), app.debugDescription)
            fullscreen.tap()
            let landscape = NSPredicate { _, _ in
                let frame = app.windows.firstMatch.frame
                return frame.width > frame.height
            }
            XCTAssertEqual(XCTWaiter.wait(for: [
                XCTNSPredicateExpectation(predicate: landscape, object: app)
            ], timeout: 10), .completed, "Fullscreen did not rotate: \(app.debugDescription)")
            let shot = XCTAttachment(screenshot: app.screenshot())
            shot.name = "fullscreen-landscape-\(cycle)"
            shot.lifetime = .keepAlways
            add(shot)
            let exit = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label == %@", "退出全屏")).firstMatch
            if !exit.exists {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.5)).tap()
            }
            XCTAssertTrue(exit.waitForExistence(timeout: 5), app.debugDescription)
            exit.tap()
            let portrait = NSPredicate { _, _ in
                let frame = app.windows.firstMatch.frame
                return frame.height > frame.width
            }
            XCTAssertEqual(XCTWaiter.wait(for: [
                XCTNSPredicateExpectation(predicate: portrait, object: app)
            ], timeout: 10), .completed, "Exit did not restore portrait")
        }
    }

    /// Guards the preloaded-next-episode takeover: episode 2 must paint video, not only audio.
    func testNextEpisodePreloadKeepsVideo() {
        continueAfterFailure = true
        let app = XCUIApplication()
        app.launch()
        let searchEntry = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "搜索影视")).firstMatch
        XCTAssertTrue(searchEntry.waitForExistence(timeout: 20), app.debugDescription)
        searchEntry.tap()
        let field = app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)
        field.tap()
        field.typeText("大明王朝1566")
        // Submit through ImeAction.Search. The compose action button is sometimes laid out
        // outside the visible bounds while the software keyboard is presented.
        field.typeText("\n")
        let result = app.buttons.matching(NSPredicate(
            format: "label CONTAINS %@", "大明王朝1566"
        )).firstMatch
        if !result.waitForExistence(timeout: 20) {
            let buttons = app.buttons.matching(NSPredicate(format: "label == %@", "搜索"))
            for button in buttons.allElementsBoundByIndex where button.isHittable {
                button.tap()
                break
            }
        }
        XCTAssertTrue(result.waitForExistence(timeout: 45), app.debugDescription)
        result.tap()
        let episodes = app.staticTexts["选集"].firstMatch
        XCTAssertTrue(episodes.waitForExistence(timeout: 45), app.debugDescription)
        let elapsed = NSPredicate { _, _ in
            app.staticTexts.allElementsBoundByIndex.contains { element in
                element.label.range(of: "^0:[0-5][1-9]$", options: .regularExpression) != nil
            }
        }
        XCTAssertEqual(XCTWaiter.wait(for: [
            XCTNSPredicateExpectation(predicate: elapsed, object: app)
        ], timeout: 45), .completed, "Episode 1 did not start: \(app.debugDescription)")
        print("EPISODE_TIMELINE episode-1 playing at \(Date())")
        // Let the speculative next-episode preload resolve, preroll and become ready.
        Thread.sleep(forTimeInterval: 20)
        XCTAssertGreaterThan(capture(app, name: "episode-1"), 1.0,
                             "Episode 1 rendered no video")

        print("EPISODE_TIMELINE tapping episode 2 at \(Date())")
        tapEpisode(app, "第2集")
        Thread.sleep(forTimeInterval: 12)
        print("EPISODE_TIMELINE capturing episode 2 at \(Date())")
        // A preloaded takeover used to keep only audio while the video layer stayed black.
        XCTAssertGreaterThan(capture(app, name: "episode-2"), 1.0,
                             "Episode 2 played audio only after preloaded takeover")

        print("EPISODE_TIMELINE tapping episode 3 at \(Date())")
        tapEpisode(app, "第3集")
        Thread.sleep(forTimeInterval: 12)
        print("EPISODE_TIMELINE capturing episode 3 at \(Date())")
        XCTAssertGreaterThan(capture(app, name: "episode-3"), 1.0,
                             "Episode 3 rendered no video")
    }
}
