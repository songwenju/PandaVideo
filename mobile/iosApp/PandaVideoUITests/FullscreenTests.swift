import XCTest

final class FullscreenTests: XCTestCase {
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
