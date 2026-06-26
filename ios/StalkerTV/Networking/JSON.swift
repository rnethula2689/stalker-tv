import Foundation

/// A tiny, forgiving JSON accessor that mirrors the semantics of Android's
/// org.json (`optString` / `optInt` / `optJSONObject` / `optJSONArray`), so the
/// portal port reads almost line-for-line like the original Kotlin.
struct JSON {
    let raw: Any?

    init(_ raw: Any?) { self.raw = raw }

    /// Parse a response body string into a JSON wrapper.
    static func parse(_ body: String) -> JSON {
        guard let data = body.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        else { return JSON(nil) }
        return JSON(obj)
    }

    subscript(_ key: String) -> JSON {
        if let dict = raw as? [String: Any] { return JSON(dict[key]) }
        return JSON(nil)
    }

    subscript(_ index: Int) -> JSON {
        if let arr = raw as? [Any], index >= 0, index < arr.count { return JSON(arr[index]) }
        return JSON(nil)
    }

    var array: [JSON]? {
        guard let arr = raw as? [Any] else { return nil }
        return arr.map { JSON($0) }
    }

    var count: Int { (raw as? [Any])?.count ?? 0 }

    func string(_ key: String, _ fallback: String = "") -> String { self[key].asString(fallback) }
    func int(_ key: String, _ fallback: Int = 0) -> Int { self[key].asInt(fallback) }
    func long(_ key: String, _ fallback: Int64 = 0) -> Int64 { self[key].asLong(fallback) }

    func asString(_ fallback: String = "") -> String {
        if let s = raw as? String { return s }
        if let n = raw as? NSNumber { return n.stringValue }
        return fallback
    }
    func asInt(_ fallback: Int = 0) -> Int {
        if let n = raw as? NSNumber { return n.intValue }
        if let s = raw as? String { return Int(s) ?? fallback }
        return fallback
    }
    func asLong(_ fallback: Int64 = 0) -> Int64 {
        if let n = raw as? NSNumber { return n.int64Value }
        if let s = raw as? String { return Int64(s) ?? fallback }
        return fallback
    }
}
