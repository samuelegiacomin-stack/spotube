/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
import UIKit
import ComposeApp

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        guard identifier == BackgroundDownloadEngine.identifier else { completionHandler(); return }
        BackgroundDownloadEngine.shared.handleBackgroundEvents(completionHandler)
        IosApplicationBootstrap.shared.start()
    }
}
