import XCTest

final class FullscreenTests: XCTestCase {
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
}
