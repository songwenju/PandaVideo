import XCTest
import UIKit
import Vision

final class FullscreenTests: XCTestCase {
    /// Keeps the existing hot-page grid while rendering the verified documentary source.
    func testJianpianHotKeepsTabsAndPosterGrid() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let hot = app.buttons.matching(NSPredicate(format: "label == %@", "最近热播")).firstMatch
        XCTAssertTrue(hot.waitForExistence(timeout: 20), app.debugDescription)
        hot.tap()
        for label in ["热播剧集", "热门电影", "热门综艺", "热门动漫", "纪录片", "小品"] {
            XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == %@", label))
                .firstMatch.waitForExistence(timeout: 10), app.debugDescription)
        }
        let documentary = app.buttons.matching(NSPredicate(format: "label == %@", "纪录片")).firstMatch
        for _ in 0..<4 {
            if app.frame.contains(documentary.frame) { break }
            let y = (documentary.frame.midY - app.frame.minY) / app.frame.height
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: y))
                .press(forDuration: 0.05, thenDragTo: app.coordinate(
                    withNormalizedOffset: CGVector(dx: 0.2, dy: y)))
        }
        documentary.tap()
        let poster = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "河西走廊")).firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 45), app.debugDescription)
        XCTAssertEqual(poster.frame.width / poster.frame.height, 0.7, accuracy: 0.04)
        XCTAssertFalse(app.staticTexts["高分精选"].exists)
        XCTAssertEqual(app.state, .runningForeground)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "jianpian-hot-original-grid"
        shot.lifetime = .keepAlways
        add(shot)
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = "jianpian-hot-grid-hierarchy"
        tree.lifetime = .keepAlways
        add(tree)
    }

    /// Exercises the source-owned homepage and resource cards on the actual iOS surface.
    func testJianpianHomeAndResourceCards() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let discoverTabs = app.buttons.matching(NSPredicate(format: "label == %@", "精选"))
        XCTAssertTrue(discoverTabs.firstMatch.waitForExistence(timeout: 20), app.debugDescription)
        let discover = discoverTabs.element(boundBy: discoverTabs.count - 1)
        discover.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == %@", "高分剧集"))
            .firstMatch.waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertFalse(app.staticTexts["高分精选"].exists)
        let poster = app.buttons.matching(NSPredicate(
            format: "label CONTAINS %@", "肖申克的救赎")).firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 40), app.debugDescription)
        let home = XCTAttachment(screenshot: app.screenshot())
        home.name = "jianpian-source-home"
        home.lifetime = .keepAlways
        add(home)
        poster.tap()
        XCTAssertTrue(app.staticTexts["播放资源"].firstMatch.waitForExistence(timeout: 45),
                      app.debugDescription)
        XCTAssertTrue(app.staticTexts["选集"].firstMatch.exists, app.debugDescription)
        let measured = app.staticTexts.matching(NSPredicate(format: "label MATCHES %@", ".*[0-9]+ms.*")).firstMatch
        XCTAssertTrue(measured.waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertFalse(app.staticTexts["点击切换"].exists)
        XCTAssertFalse(app.staticTexts["当前线路"].exists)
        let playing = NSPredicate { _, _ in
            let clocks = app.staticTexts.allElementsBoundByIndex.compactMap { element -> Int? in
                guard element.label.range(of: "^[0-9]+:[0-5][0-9]$",
                                          options: .regularExpression) != nil else { return nil }
                let parts = element.label.split(separator: ":").compactMap { Int($0) }
                return parts.count == 2 ? parts[0] * 60 + parts[1] : nil
            }
            return clocks.count >= 2 && (clocks.min() ?? 0) > 0 &&
                self.meanLuma(app.screenshot(),
                              normalized: CGRect(x: 0.25, y: 0.16, width: 0.5, height: 0.035)) > 12
        }
        XCTAssertEqual(XCTWaiter.wait(for: [
            XCTNSPredicateExpectation(predicate: playing, object: app)
        ], timeout: 45), .completed, app.debugDescription)
        let details = XCTAttachment(screenshot: app.screenshot())
        details.name = "jianpian-resource-cards-playing"
        details.lifetime = .keepAlways
        add(details)
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = "jianpian-resource-cards-hierarchy"
        tree.lifetime = .keepAlways
        add(tree)
    }

    /// Records a real catalog click and requires video pixels; the recording defines latency.
    func testCatalogVideoFirstFrame() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let discoverTabs = app.buttons.matching(NSPredicate(format: "label == %@", "精选"))
        XCTAssertTrue(discoverTabs.firstMatch.waitForExistence(timeout: 20), app.debugDescription)
        let discover = discoverTabs.element(boundBy: discoverTabs.count - 1)
        discover.tap()
        let poster = app.buttons.matching(
            NSPredicate(format: "label == %@", "肖申克的救赎")).firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 30), app.debugDescription)
        let before = XCTAttachment(screenshot: app.screenshot())
        before.name = "video-first-frame-before-click"
        before.lifetime = .keepAlways
        add(before)
        print("VIDEO_FIRST_FRAME_CLICK wall=\(Date().timeIntervalSince1970)")
        poster.tap()
        XCTAssertTrue(app.staticTexts["选集"].firstMatch.waitForExistence(timeout: 45),
                      app.debugDescription)
        // Keep the sample above the central controls and away from their white text.
        // A visible pause icon alone does not prove that AVPlayer painted a frame.
        let videoRegion = CGRect(x: 0.25, y: 0.16, width: 0.5, height: 0.035)
        let pixels = NSPredicate { _, _ in
            let times = app.staticTexts.allElementsBoundByIndex.compactMap { element -> Int? in
                guard element.label.range(
                    of: "^[0-9]+:[0-5][0-9]$", options: .regularExpression) != nil else { return nil }
                let parts = element.label.split(separator: ":").compactMap { Int($0) }
                guard parts.count == 2 else { return nil }
                return parts[0] * 60 + parts[1]
            }
            // Both elapsed and duration must exist; the duration alone is nonzero at 0:00.
            let hasProgress = times.count >= 2 && (times.min() ?? 0) > 0
            return hasProgress && self.meanLuma(app.screenshot(), normalized: videoRegion) > 12
        }
        XCTAssertEqual(XCTWaiter.wait(for: [
            XCTNSPredicateExpectation(predicate: pixels, object: app)
        ], timeout: 45), .completed, "No visible video pixels: \(app.debugDescription)")
        let playing = app.screenshot()
        XCTAssertGreaterThan(meanLuma(playing, normalized: videoRegion), 12)
        let frame = XCTAttachment(screenshot: playing)
        frame.name = "video-first-frame-playing"
        frame.lifetime = .keepAlways
        add(frame)
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "video-first-frame-player-hierarchy"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)
    }

    /// Separates device launch metrics from XCTest's slower accessibility synchronization.
    func testCatalogColdLaunchMetrics() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let options = XCTMeasureOptions()
        options.iterationCount = 5
        measure(metrics: [XCTApplicationLaunchMetric(waitUntilResponsive: true)], options: options) {
            app.launch()
            let score = app.staticTexts.matching(NSPredicate(
                format: "label MATCHES %@", "^[0-9]+([.][0-9]+)? 分$")).firstMatch
            XCTAssertTrue(score.waitForExistence(timeout: 30), app.debugDescription)
            app.terminate()
        }
    }

    /// Verifies the rendered cached grids and records automation latency separately from video.
    func testCatalogCachedScreensAndSwitches() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let discoverTabs = app.buttons.matching(NSPredicate(format: "label == %@", "精选"))
        XCTAssertTrue(discoverTabs.firstMatch.waitForExistence(timeout: 20), app.debugDescription)
        let discover = discoverTabs.element(boundBy: discoverTabs.count - 1)
        discover.tap()
        let allRegions = app.buttons.matching(NSPredicate(format: "label == %@", "全部地区")).firstMatch
        XCTAssertTrue(allRegions.waitForExistence(timeout: 10), app.debugDescription)
        allRegions.tap()
        func renderedTabPoint(_ category: String) -> CGPoint? {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.recognitionLanguages = ["zh-Hans", "en-US"]
            let region = CGRect(x: 0, y: 0.78, width: 1, height: 0.09)
            request.regionOfInterest = region
            guard (try? VNImageRequestHandler(data: app.screenshot().pngRepresentation)
                .perform([request])) != nil,
                  let label = request.results?.first(where: {
                      $0.topCandidates(1).first?.string == category
                  }) else { return nil }
            // Vision normalizes observations within the region of interest.
            return CGPoint(
                x: (region.minX + label.boundingBox.midX * region.width) * app.frame.width,
                y: (1 - region.minY - label.boundingBox.midY * region.height) * app.frame.height)
        }
        for (index, category) in ["精选", "高分剧集", "精选", "高分剧集", "精选"].enumerated() {
            let tab = app.buttons.matching(NSPredicate(format: "label == %@", category)).firstMatch
            XCTAssertTrue(tab.waitForExistence(timeout: 10), app.debugDescription)
            // The selected tab scrolls into view and can move the first category offscreen.
            // Reveal that real control before timing a tap; an offscreen AX node is not tappable.
            if !app.frame.contains(tab.frame) {
                let y = tab.frame.midY
                let origin = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
                origin.withOffset(CGVector(dx: app.frame.width * 0.25, dy: y))
                    .press(forDuration: 0.1, thenDragTo:
                        origin.withOffset(CGVector(dx: app.frame.width * 0.85, dy: y)))
            }
            XCTAssertTrue(tab.isHittable, app.debugDescription)
            // AX bounds and XCTest idle detection can lag Compose header rebound.
            // Require the rendered label to stay still before using its screen position.
            var point: CGPoint?
            var stableSince = ProcessInfo.processInfo.systemUptime
            let stable = NSPredicate { _, _ in
                guard let current = renderedTabPoint(category) else { return false }
                let now = ProcessInfo.processInfo.systemUptime
                if let previous = point, abs(previous.x - current.x) < 1,
                   abs(previous.y - current.y) < 1 {
                    point = current
                    return now - stableSince >= 0.6
                }
                point = current
                stableSince = now
                return false
            }
            XCTAssertEqual(XCTWaiter.wait(for: [
                XCTNSPredicateExpectation(predicate: stable, object: app)
            ], timeout: 10), .completed, "Rendered category did not settle: \(category)")
            guard let point else { return }
            let start = ProcessInfo.processInfo.systemUptime
            print("CATALOG_VIDEO_EVENT tap-begin index=\(index) category=\(category) wall=\(Date().timeIntervalSince1970)")
            app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
                .withOffset(CGVector(dx: point.x, dy: point.y)).tap()
            let title = category == "精选" ? "肖申克的救赎" : "大明王朝1566"
            let row = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
            XCTAssertTrue(row.waitForExistence(timeout: 30), app.debugDescription)
            XCTAssertTrue(tab.isSelected, app.debugDescription)
            XCTAssertTrue(allRegions.isSelected, app.debugDescription)
            XCTAssertTrue(row.frame.width > 0 && app.frame.intersects(row.frame), app.debugDescription)
            let elapsed = ProcessInfo.processInfo.systemUptime - start
            print("CATALOG_AUTOMATION_LATENCY index=\(index) category=\(category) seconds=\(elapsed)")
            let shot = XCTAttachment(screenshot: app.screenshot())
            shot.name = "catalog-cache-\(index)-\(category)"
            shot.lifetime = .keepAlways
            add(shot)
        }
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "catalog-cache-final-hierarchy"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)
        app.terminate()
        app.launch()
        let restored = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "肖申克的救赎")).firstMatch
        XCTAssertTrue(restored.waitForExistence(timeout: 30), app.debugDescription)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "catalog-cache-after-process-restart"
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// Country selection belongs to discovery and survives category changes and navigation.
    func testDiscoverCountryFilterKeepsSelection() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        func dismissSourceFailure() {
            // Public regional catalogs can return HTTP 403. Selection must survive
            // that normal retry dialog as well as a successful network response.
            let cancel = app.buttons.matching(NSPredicate(format: "label == %@", "取消")).firstMatch
            if cancel.waitForExistence(timeout: 3) { cancel.tap() }
        }
        let discoverTabs = app.buttons.matching(NSPredicate(format: "label == %@", "精选"))
        XCTAssertTrue(discoverTabs.firstMatch.waitForExistence(timeout: 20), app.debugDescription)
        let discover = discoverTabs.element(boundBy: discoverTabs.count - 1)
        discover.tap()
        let china = app.buttons.matching(NSPredicate(format: "label == %@", "中国大陆")).firstMatch
        XCTAssertTrue(china.waitForExistence(timeout: 10), app.debugDescription)
        china.tap()
        dismissSourceFailure()
        XCTAssertTrue(china.isSelected, app.debugDescription)
        let comedy = app.buttons.matching(NSPredicate(format: "label == %@", "喜剧")).firstMatch
        XCTAssertTrue(comedy.waitForExistence(timeout: 5), app.debugDescription)
        // Compose exposes categories outside the horizontal viewport to accessibility.
        // Reveal the real button before tapping after changes to category names or order.
        for _ in 0..<3 {
            if app.frame.contains(comedy.frame) { break }
            let revealLeft = comedy.frame.minX < app.frame.minX
            let y = (comedy.frame.midY - app.frame.minY) / app.frame.height
            app.coordinate(withNormalizedOffset: CGVector(dx: revealLeft ? 0.25 : 0.8, dy: y))
                .press(forDuration: 0.05, thenDragTo: app.coordinate(
                    withNormalizedOffset: CGVector(dx: revealLeft ? 0.8 : 0.25, dy: y)))
        }
        XCTAssertTrue(app.frame.contains(comedy.frame), app.debugDescription)
        comedy.tap()
        dismissSourceFailure()
        XCTAssertTrue(china.isSelected, app.debugDescription)
        app.buttons.matching(NSPredicate(format: "label == %@", "最近热播")).firstMatch.tap()
        dismissSourceFailure()
        discoverTabs.element(boundBy: discoverTabs.count - 1).tap()
        dismissSourceFailure()
        XCTAssertTrue(china.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(china.isSelected, app.debugDescription)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "discover-country-filter"
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// Requires public network access; verifies source selection and native playback of a public BV.
    func testBilibiliPublicVideoSearchAndPlayback() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let entry = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "搜索影视")).firstMatch
        XCTAssertTrue(entry.waitForExistence(timeout: 20), app.debugDescription)
        entry.tap()
        let source = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "B站")).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 10), app.debugDescription)
        source.tap()
        let field = app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)
        field.tap()
        field.typeText("BV1A6ZMB3EtZ")
        app.buttons.matching(NSPredicate(format: "label == %@", "搜索")).firstMatch.tap()
        let play = app.buttons.matching(NSPredicate(format: "label == %@", "立即观看")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 30), app.debugDescription)
        let results = XCTAttachment(screenshot: app.screenshot())
        results.name = "bilibili-public-video-search"
        results.lifetime = .keepAlways
        add(results)
        play.tap()
        XCTAssertTrue(app.staticTexts["选集"].firstMatch.waitForExistence(timeout: 45), app.debugDescription)
        XCTAssertFalse(app.staticTexts["演员"].exists, "Uploader must not become a film cast member")
        let elapsed = NSPredicate { _, _ in
            app.staticTexts.allElementsBoundByIndex.contains {
                $0.label.range(of: "^0:[0-5][1-9]$", options: .regularExpression) != nil
            }
        }
        XCTAssertEqual(XCTWaiter.wait(for: [
            XCTNSPredicateExpectation(predicate: elapsed, object: app)
        ], timeout: 30), .completed, "Public video playback time did not advance: \(app.debugDescription)")
        XCTAssertGreaterThan(capture(app, name: "bilibili-native-video"), 1.0,
                             "Public video rendered no picture")
    }

    /// Requires a PandaCast receiver on the same LAN; verifies actual iOS Bonjour discovery.
    func testPandaCastBonjourDiscovery() {
        verifyPandaCast(startPaused: false)
    }

    /// A paused local player can hand off a URI, remain connected in the background,
    /// and resume the receiver without ever starting local playback.
    func testPandaCastFromPausedPhoneAndBackgroundReturn() {
        verifyPandaCast(startPaused: true)
    }

    private func verifyPandaCast(startPaused: Bool) {
        continueAfterFailure = false
        let app = XCUIApplication()
        addUIInterruptionMonitor(withDescription: "Local network permission") { alert in
            let allow = alert.buttons["允许"]
            if allow.exists { allow.tap(); return true }
            let english = alert.buttons["Allow"]
            if english.exists { english.tap(); return true }
            return false
        }
        app.launch()
        let poster = app.images.firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 45), app.debugDescription)
        poster.tap()
        let cast = app.buttons["投屏到电视"].firstMatch
        XCTAssertTrue(cast.waitForExistence(timeout: 45), app.debugDescription)
        if startPaused {
            let localPause = app.buttons.matching(NSPredicate(format: "label == %@", "暂停")).firstMatch
            if localPause.waitForExistence(timeout: 5) { localPause.tap() }
            XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == %@", "播放"))
                .firstMatch.waitForExistence(timeout: 10), app.debugDescription)
        }
        cast.tap()
        // Explicitly handle the system's local-network permission alert.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.alerts.buttons["允许"].firstMatch
        if allow.waitForExistence(timeout: 3) { allow.tap() }
        let receiver = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "熊猫投屏 ·")).firstMatch
        XCTAssertTrue(receiver.waitForExistence(timeout: 20), app.debugDescription)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "iphone-pandacast-bonjour-discovery"
        attachment.lifetime = .keepAlways
        add(attachment)
        receiver.tap()
        if app.staticTexts["正在连接…"].waitForExistence(timeout: 2) {
            let connectingAttachment = XCTAttachment(screenshot: app.screenshot())
            connectingAttachment.name = "iphone-pandacast-connecting-ui"
            connectingAttachment.lifetime = .keepAlways
            add(connectingAttachment)
        }
        let remote = app.descendants(matching: .any).matching(
            NSPredicate(format: "label == %@", "投屏遥控器")).firstMatch
        XCTAssertTrue(remote.waitForExistence(timeout: 30), app.debugDescription)
        let pause = app.buttons["暂停电视"].firstMatch
        if startPaused {
            let play = app.buttons["播放电视"].firstMatch
            XCTAssertTrue(play.waitForExistence(timeout: 15), app.debugDescription)
            // Backgrounding the sender is not an explicit disconnect.
            XCUIDevice.shared.press(.home)
            app.activate()
            XCTAssertTrue(remote.waitForExistence(timeout: 15), app.debugDescription)
            XCTAssertTrue(play.waitForExistence(timeout: 10), app.debugDescription)
            play.tap()
        }
        XCTAssertTrue(pause.waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertTrue(app.buttons["快进 10 秒"].exists)
        XCTAssertTrue(app.buttons["快退 10 秒"].exists)
        let remoteAttachment = XCTAttachment(screenshot: app.screenshot())
        remoteAttachment.name = "iphone-pandacast-remote-ui"
        remoteAttachment.lifetime = .keepAlways
        add(remoteAttachment)
        pause.tap()
        let commandAttachment = XCTAttachment(screenshot: app.screenshot())
        commandAttachment.name = "iphone-pandacast-pause-request-ui"
        commandAttachment.lifetime = .keepAlways
        add(commandAttachment)
        XCTAssertTrue(app.buttons["播放电视"].waitForExistence(timeout: 10), app.debugDescription)
        let pausedAttachment = XCTAttachment(screenshot: app.screenshot())
        pausedAttachment.name = "iphone-pandacast-paused-ui"
        pausedAttachment.lifetime = .keepAlways
        add(pausedAttachment)
        let episodes = app.buttons["选集"].firstMatch
        if episodes.exists {
            // Catalog history can reopen episode two. Always pick a different episode.
            let targetLabel = app.staticTexts["第2集"].exists ? "第1集" : "第2集"
            episodes.tap()
            let targetEpisode = app.buttons[targetLabel].firstMatch
            XCTAssertTrue(targetEpisode.waitForExistence(timeout: 10), app.debugDescription)
            let frame = targetEpisode.frame
            XCTAssertTrue(frame.width > 0 && frame.height > 0 && app.frame.contains(frame), app.debugDescription)
            // The Compose scrim spans the AX hit-test region despite being behind the drawer.
            // Tap the visible cell center, then require drawer dismissal and a changed caption.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
                .withOffset(CGVector(dx: frame.midX, dy: frame.midY)).tap()
            let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"),
                object: targetEpisode)
            XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 15), .completed, app.debugDescription)
            XCTAssertTrue(remote.waitForExistence(timeout: 45), app.debugDescription)
            XCTAssertTrue(app.staticTexts[targetLabel].waitForExistence(timeout: 10), app.debugDescription)
            // A paused receiver must stay paused after the new episode is handed off.
            XCTAssertTrue(app.buttons["播放电视"].waitForExistence(timeout: 10), app.debugDescription)
        }
        app.buttons["退出投屏"].tap()
        XCTAssertTrue(app.staticTexts["投屏已结束"].waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertTrue(app.buttons["重新投屏"].exists)
        let endedAttachment = XCTAttachment(screenshot: app.screenshot())
        endedAttachment.name = "iphone-pandacast-ended-ui"
        endedAttachment.lifetime = .keepAlways
        add(endedAttachment)
        app.buttons["返回"].firstMatch.tap()
        XCTAssertTrue(cast.waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertEqual(app.state, .runningForeground)
    }

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
        let discoverTabs = app.buttons.matching(NSPredicate(format: "label == %@", "精选"))
        XCTAssertTrue(discoverTabs.firstMatch.waitForExistence(timeout: 20), app.debugDescription)
        let discover = discoverTabs.element(boundBy: discoverTabs.count - 1)
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

    /// Keeps sketch artwork adjacent to its caption and excludes download promotions.
    func testSketchCatalogPosterLayoutAndDownloadFilter() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let hot = app.buttons.matching(NSPredicate(format: "label == %@", "最近热播")).firstMatch
        XCTAssertTrue(hot.waitForExistence(timeout: 20), app.debugDescription)
        hot.tap()
        let sketch = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "小品")).firstMatch
        for _ in 0..<5 {
            if sketch.exists && sketch.frame.width > 0 &&
                app.windows.firstMatch.frame.contains(sketch.frame) { break }
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.175))
                .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.175)))
        }
        XCTAssertTrue(sketch.waitForExistence(timeout: 10), app.debugDescription)
        sketch.tap()
        let poster = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "又来了")).firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 45), app.debugDescription)
        let caption = app.staticTexts.matching(NSPredicate(format: "label == %@", poster.label)).firstMatch
        XCTAssertTrue(caption.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertEqual(poster.frame.width / poster.frame.height, 0.7, accuracy: 0.04)
        XCTAssertGreaterThanOrEqual(caption.frame.minY, poster.frame.maxY)
        XCTAssertLessThan(caption.frame.minY - poster.frame.maxY, 20)
        XCTAssertFalse(app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "3部高清下载")).firstMatch.exists)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "sketch-catalog-filled-posters"
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
        let search = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "搜索影视")).firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 20), app.debugDescription)
        for label in ["精选", "分类", "我的", "最近热播"] {
            let matchingTabs = app.buttons.matching(NSPredicate(format: "label == %@", label))
            XCTAssertTrue(matchingTabs.firstMatch.waitForExistence(timeout: 15), app.debugDescription)
            let tab = matchingTabs.element(boundBy: matchingTabs.count - 1)
            tab.tap()
            XCTAssertEqual(app.state, .runningForeground, "App exited after opening \(label)")
        }
        XCTAssertTrue(search.waitForExistence(timeout: 20), app.debugDescription)
        for label in ["热门电影", "热播剧集", "热门电影", "热播剧集"] {
            let category = app.buttons.matching(NSPredicate(format: "label == %@", label)).firstMatch
            XCTAssertTrue(category.waitForExistence(timeout: 15), app.debugDescription)
            // Compose includes offscreen tabs in AX. Selecting a tab centers the row,
            // so reveal the next target before asking XCTest to compute its hit point.
            for _ in 0..<3 {
                if app.frame.contains(category.frame) { break }
                let revealLeft = category.frame.minX < app.frame.minX
                let y = (category.frame.midY - app.frame.minY) / app.frame.height
                app.coordinate(withNormalizedOffset: CGVector(dx: revealLeft ? 0.25 : 0.8, dy: y))
                    .press(forDuration: 0.05, thenDragTo: app.coordinate(
                        withNormalizedOffset: CGVector(dx: revealLeft ? 0.8 : 0.25, dy: y)))
            }
            XCTAssertTrue(app.frame.contains(category.frame), "Category is offscreen: \(label)")
            category.tap()
            XCTAssertEqual(app.state, .runningForeground, "App exited after selecting \(label)")
        }
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
            // Read one AX snapshot: enumerating live StaticText indexes races with the
            // transport auto-hide animation and can fail before the predicate returns.
            let snapshot = app.debugDescription
            let text = snapshot as NSString
            let pattern = try! NSRegularExpression(pattern: "StaticText,.*label: '(\\d+:[0-5]\\d)'")
            let clocks = pattern.matches(in: snapshot, range: NSRange(location: 0, length: text.length))
                .compactMap { match -> Int? in
                let parts = text.substring(with: match.range(at: 1)).split(separator: ":").compactMap { Int($0) }
                return parts.count == 2 ? parts[0] * 60 + parts[1] : nil
            }
            return clocks.count >= 2 && (clocks.min() ?? 0) > 0 &&
                snapshot.contains("label: '暂停'")
        }
        // The transport row auto-hides before slow AX snapshots finish. Reveal it before
        // checking the timeline; resumed/intro-skipped playback can also exceed one minute.
        var timelineReady = false
        for _ in 0..<3 {
            if !app.buttons.matching(NSPredicate(format: "label == %@", "暂停")).firstMatch.exists {
                app.windows.firstMatch.coordinate(
                    withNormalizedOffset: CGVector(dx: 0.5, dy: 0.18)).tap()
            }
            if XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: elapsed, object: app)],
                             timeout: 12) == .completed {
                timelineReady = true
                break
            }
        }
        XCTAssertTrue(timelineReady, "Episode 1 did not start: \(app.debugDescription)")
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

        // Returning through the same iOS episode surface exercises the shared URL cache,
        // including restoring the original source after two forward episode selections.
        tapEpisode(app, "第1集")
        Thread.sleep(forTimeInterval: 12)
        XCTAssertGreaterThan(capture(app, name: "episode-1-return"), 1.0,
                             "Returning to the cached first episode rendered no video")
    }
}
