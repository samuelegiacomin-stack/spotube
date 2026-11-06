/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
import Foundation
import UIKit
import ComposeApp

/// Owns the single background session for this installation. All mutable state is main-queue
/// confined, including URLSession delegate callbacks. Audio transfers never depend on a coroutine.
final class BackgroundDownloadEngine: NSObject, NativeDownloadEngine, URLSessionDownloadDelegate {
    static let shared = BackgroundDownloadEngine()
    static let identifier = "org.spotube.audio-downloads.v1"

    private struct Record: Codable {
        let destination: String // Relative to the sandbox: container UUIDs can change on upgrade.
        let taskIdentifier: Int
        var outcome: String? // nil = transferring, "complete" or a persisted failure message
    }

    private var records: [String: Record] = [:]
    private var listeners: [String: NativeDownloadListener] = [:]
    private var suspended: [String: Bool] = [:]
    private var completionHandler: (() -> Void)?
    private let wakeGate = NativeDownloadWakeGate()
    private var wakeListener: NativeDownloadWakeListener?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var wakeDeadline: DispatchWorkItem?
    // Input corruption is fail-closed. Transient write errors must never poison future retries.
    private var journalReadError: Error?
    private let files = FileManager.default
    private let journal: URL
    private var session: URLSession!

    private override init() {
        journal = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Spotube/native-downloads-v1.json")
        super.init()
        do {
            if files.fileExists(atPath: journal.path) {
                records = try JSONDecoder().decode([String: Record].self, from: Data(contentsOf: journal))
                records.filter { $0.value.outcome != nil }.keys.forEach { wakeGate.resultPersisted(id: $0) }
            }
        } catch { journalReadError = error } // Do not erase an unreadable task mapping.
        let config = URLSessionConfiguration.background(withIdentifier: Self.identifier)
        config.sessionSendsLaunchEvents = true
        config.isDiscretionary = false
        config.waitsForConnectivity = true
        config.httpMaximumConnectionsPerHost = 3
        config.timeoutIntervalForResource = 7 * 24 * 60 * 60
        session = URLSession(configuration: config, delegate: self, delegateQueue: .main)
        session.getAllTasks { tasks in
            DispatchQueue.main.async {
                for task in tasks {
                    guard let id = task.taskDescription, let record = self.records[id],
                          record.taskIdentifier == task.taskIdentifier else {
                        if self.journalReadError == nil { task.cancel() }
                        continue
                    }
                    // Kotlin reattaches and applies current playback priority before resuming.
                    if self.suspended[id] ?? true { task.suspend() }
                }
            }
        }
    }

    private func persist() throws {
        if let error = journalReadError { throw error }
        try files.createDirectory(at: journal.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(records).write(to: journal, options: .atomic)
    }

    private func relativePath(_ path: String) throws -> String {
        let root = NSHomeDirectory() + "/"
        if path.hasPrefix(root) { return String(path.dropFirst(root.count)) }
        // The common journal may contain a path from the previous app container.
        if path.contains("/Containers/Data/Application/") {
            for marker in ["/Documents/Spotube/", "/Library/Application Support/Spotube/"] {
                if let range = path.range(of: marker) { return String(path[range.lowerBound...].dropFirst()) }
            }
        }
        throw NSError(domain: "SpotubeDownloads", code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "Download destination is outside the app sandbox"])
    }

    private func destination(_ record: Record) -> URL {
        URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true).appendingPathComponent(record.destination)
    }

    private func validFile(_ url: URL) -> Bool {
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]) else { return false }
        return values.isRegularFile == true && (values.fileSize ?? 0) > 0
    }

    func start(id: String, url: String, destination: String, listener: NativeDownloadListener) {
        DispatchQueue.main.async {
            self.listeners[id] = listener
            self.session.getAllTasks { tasks in
                DispatchQueue.main.async {
                    // A cancel/remove may have arrived while querying the session.
                    guard self.listeners[id] === listener else { return }
                    // Even terminal replay/failure releases the manager's handoff barrier.
                    defer { listener.onAttached() }
                    do {
                        if let error = self.journalReadError { throw error }
                        if let record = self.records[id] {
                            if record.outcome == "complete", self.validFile(self.destination(record)) {
                                listener.onComplete()
                                return
                            }
                            if let outcome = record.outcome, outcome != "complete" {
                                listener.onFailure(message: outcome)
                                return
                            }
                            // Recovery after an atomic move but before the outcome write.
                            if self.validFile(self.destination(record)) {
                                self.records[id]?.outcome = "complete"
                                try self.persist()
                                self.wakeGate.resultPersisted(id: id)
                                listener.onComplete()
                                return
                            }
                            if let task = tasks.first(where: { $0.taskIdentifier == record.taskIdentifier &&
                                $0.taskDescription == id && $0.state != .canceling && $0.state != .completed }) {
                                task.priority = URLSessionTask.lowPriority
                                if self.suspended[id] ?? true { task.suspend() } else { task.resume() }
                                return
                            }
                        }
                        guard let source = URL(string: url), ["https", "http"].contains(source.scheme?.lowercased() ?? "") else {
                            throw URLError(.badURL)
                        }
                        let relativeDestination = try self.relativePath(destination)
                        let task = self.session.downloadTask(with: source)
                        task.taskDescription = id
                        task.priority = URLSessionTask.lowPriority
                        self.records[id] = Record(destination: relativeDestination,
                                                  taskIdentifier: task.taskIdentifier, outcome: nil)
                        do { try self.persist() } catch { task.cancel(); throw error }
                        if !(self.suspended[id] ?? true) { task.resume() }
                    } catch { listener.onFailure(message: error.localizedDescription) }
                }
            }
        }
    }

    func cancel(id: String) {
        DispatchQueue.main.async { self.cancelOnMain(id) }
    }

    private func cancelOnMain(_ id: String) {
        let taskID = records[id]?.taskIdentifier
        listeners.removeValue(forKey: id)
        suspended.removeValue(forKey: id)
        records.removeValue(forKey: id)
        wakeGate.acknowledge(id: id)
        do { try persist() } catch { NSLog("Spotube download journal write failed: %@", error.localizedDescription) }
        finishEventsIfPossible()
        session.getAllTasks { tasks in
            tasks.filter { $0.taskIdentifier == taskID && $0.taskDescription == id }.forEach { $0.cancel() }
        }
    }

    func setSuspended(id: String, suspended: Bool) {
        DispatchQueue.main.async {
            self.suspended[id] = suspended
            guard let taskID = self.records[id]?.taskIdentifier else { return }
            self.session.getAllTasks { tasks in
                DispatchQueue.main.async {
                    for task in tasks where task.taskIdentifier == taskID && task.taskDescription == id {
                        if self.suspended[id] ?? true { task.suspend() } else { task.resume() }
                    }
                }
            }
        }
    }

    func reconcile(knownIds: [String]) {
        DispatchQueue.main.async {
            let known = Set(knownIds)
            // Common remove()/clear history may have committed just before process termination.
            // Terminal files are retained; only the orphaned task/result mapping is discarded.
            let orphaned = self.records.keys.filter { !known.contains($0) }
            orphaned.forEach { self.cancelOnMain($0) }
        }
    }

    func acknowledge(id: String) {
        DispatchQueue.main.async {
            // Only terminal results may be forgotten. Progress survives listener replacement.
            guard self.records[id]?.outcome != nil else {
                if self.records[id] == nil {
                    self.listeners.removeValue(forKey: id)
                    self.suspended.removeValue(forKey: id)
                }
                return
            }
            self.wakeGate.acknowledge(id: id)
            self.records.removeValue(forKey: id)
            self.listeners.removeValue(forKey: id)
            self.suspended.removeValue(forKey: id)
            do { try self.persist() } catch { NSLog("Spotube download journal write failed: %@", error.localizedDescription) }
            self.finishEventsIfPossible()
        }
    }

    func setWakeListener(listener: NativeDownloadWakeListener) {
        DispatchQueue.main.async {
            self.wakeListener = listener
            if self.completionHandler != nil { listener.onWake() }
        }
    }

    func queueReady() {
        DispatchQueue.main.async {
            self.wakeGate.queueReady()
            self.finishEventsIfPossible()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription,
              records[id]?.taskIdentifier == downloadTask.taskIdentifier else { return }
        listeners[id]?.onProgress(downloaded: totalBytesWritten, total: max(0, totalBytesExpectedToWrite))
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = downloadTask.taskDescription, let record = records[id],
              record.taskIdentifier == downloadTask.taskIdentifier else { return }
        do {
            guard let response = downloadTask.response as? HTTPURLResponse, response.statusCode == 200 else {
                throw URLError(.badServerResponse)
            }
            let type = (response.mimeType ?? "").lowercased()
            guard !type.hasPrefix("text/"), !type.hasPrefix("video/"), !type.contains("json"),
                  !type.contains("mpegurl"), !type.contains("dash+xml"), validFile(location) else {
                throw URLError(.cannotDecodeContentData)
            }
            let size = (try files.attributesOfItem(atPath: location.path)[.size] as? NSNumber)?.int64Value ?? 0
            // Content-Length is only comparable when URLSession has not decoded an encoding.
            if response.value(forHTTPHeaderField: "Content-Encoding") == nil,
               response.expectedContentLength > 0, size != response.expectedContentLength {
                throw URLError(.networkConnectionLost)
            }
            let output = destination(record)
            try files.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
            // Move out of URLSession's ephemeral delegate URL before returning. Stage on the
            // destination filesystem, then atomically publish; never expose a partial audio file.
            let staging = output.deletingLastPathComponent().appendingPathComponent(".\(id).native-part")
            if files.fileExists(atPath: staging.path) { try files.removeItem(at: staging) }
            try files.moveItem(at: location, to: staging)
            if files.fileExists(atPath: output.path) {
                _ = try files.replaceItemAt(output, withItemAt: staging)
            } else { try files.moveItem(at: staging, to: output) }
            records[id]?.outcome = "complete"
            try persist()
            wakeGate.resultPersisted(id: id)
            listeners[id]?.onComplete()
        } catch { fail(id, error) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error = error, let id = task.taskDescription,
              records[id]?.taskIdentifier == task.taskIdentifier, records[id]?.outcome == nil else { return }
        fail(id, error)
    }

    private func fail(_ id: String, _ error: Error) {
        wakeGate.resultPersisted(id: id)
        records[id]?.outcome = error.localizedDescription
        do { try persist() } catch { NSLog("Spotube download journal write failed: %@", error.localizedDescription) }
        listeners[id]?.onFailure(message: error.localizedDescription)
    }

    /// Hold the wake through Kotlin's durable completion AND replacement-task handoff. The
    /// bounded background task is a fallback: on expiration keep both journals for next launch.
    func handleBackgroundEvents(_ handler: @escaping () -> Void) {
        DispatchQueue.main.async {
            self.completionHandler = handler
            self.wakeGate.beginWake()
            if self.backgroundTask == .invalid {
                self.backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Spotube download completion") {
                    self.finishEventsIfPossible(force: true)
                }
            }
            let deadline = DispatchWorkItem { self.finishEventsIfPossible(force: true) }
            self.wakeDeadline?.cancel()
            self.wakeDeadline = deadline
            DispatchQueue.main.asyncAfter(deadline: .now() + 25, execute: deadline)
            self.wakeListener?.onWake()
            self.finishEventsIfPossible()
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        wakeGate.didDeliverEvents()
        finishEventsIfPossible()
    }

    private func finishEventsIfPossible(force: Bool = false) {
        guard let handler = completionHandler else { return }
        guard force || wakeGate.canFinish else { return }
        completionHandler = nil
        wakeGate.finishWake()
        wakeDeadline?.cancel()
        wakeDeadline = nil
        handler() // Delegate queue and this method are on main, as UIKit requires.
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }
}
