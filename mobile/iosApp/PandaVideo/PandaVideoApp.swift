import SwiftUI
import SuiXinKanShared

@main
struct SuiXinKanApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            SharedView().ignoresSafeArea()
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        PlayerHostController.orientationMask
    }
}

struct SharedView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        PlayerHostController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Owns orientation at the UIKit boundary while keeping the Compose controller alive.
final class PlayerHostController: UIViewController {
    static var orientationMask: UIInterfaceOrientationMask = .portrait
    private lazy var content = IosPlatformKt.MainViewController()
    private var fullscreen = false
    private var observer: NSObjectProtocol?
    private var statusBarObserver: NSObjectProtocol?
    private var lightStatusBarIcons: Bool?

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        Self.orientationMask
    }
    override var shouldAutorotate: Bool { true }
    override var prefersStatusBarHidden: Bool { fullscreen }
    override var preferredStatusBarStyle: UIStatusBarStyle {
        guard let lightStatusBarIcons else { return .default }
        return lightStatusBarIcons ? .lightContent : .darkContent
    }
    override var prefersHomeIndicatorAutoHidden: Bool { fullscreen }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(named: "LaunchBackground") ?? .systemBackground
        observer = NotificationCenter.default.addObserver(
            forName: Notification.Name("SuiXinKanFullscreenChanged"),
            object: nil, queue: .main
        ) { [weak self] notification in
            guard let enabled = notification.object as? NSNumber else { return }
            self?.setFullscreen(enabled.boolValue)
        }
        statusBarObserver = NotificationCenter.default.addObserver(
            forName: Notification.Name("SuiXinKanStatusBarChanged"),
            object: nil, queue: .main
        ) { [weak self] notification in
            guard let enabled = notification.object as? NSNumber else { return }
            self?.lightStatusBarIcons = enabled.boolValue
            self?.setNeedsStatusBarAppearanceUpdate()
            self?.view.window?.rootViewController?.setNeedsStatusBarAppearanceUpdate()
        }
        installContent()
    }

    private func installContent() {
        addChild(content)
        view.addSubview(content.view)
        content.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            content.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            content.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            content.view.topAnchor.constraint(equalTo: view.topAnchor),
            content.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        content.didMove(toParent: self)
    }

    private func setFullscreen(_ enabled: Bool) {
        fullscreen = enabled
        Self.orientationMask = enabled ? .landscapeRight : .portrait
        setNeedsStatusBarAppearanceUpdate()
        setNeedsUpdateOfHomeIndicatorAutoHidden()
        guard let scene = view.window?.windowScene else { return }
        if #available(iOS 16.0, *) {
            // Invalidate both the app root and our container: SwiftUI wraps this controller.
            scene.windows.forEach { $0.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations() }
            setNeedsUpdateOfSupportedInterfaceOrientations()
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: Self.orientationMask)) { error in
                NSLog("SuiXinKan orientation request failed: %@", error.localizedDescription)
                // The request is commonly denied by the portrait orientation lock in Control
                // Center. Tell the shared layer so it can show a hint instead of failing silently.
                if enabled {
                    NotificationCenter.default.post(
                        name: Notification.Name("SuiXinKanFullscreenDenied"), object: nil)
                }
            }
        } else {
            let orientation: UIInterfaceOrientation = enabled ? .landscapeRight : .portrait
            UIDevice.current.setValue(orientation.rawValue, forKey: "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        if let statusBarObserver { NotificationCenter.default.removeObserver(statusBarObserver) }
    }
}
