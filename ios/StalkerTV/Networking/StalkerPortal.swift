import Foundation

/// Minimal Stalker (Ministra) portal client — async/await port of the Android
/// app's `Portal.kt`. Same handshake, same MAG250 spoofing, same endpoints.
///
/// An `actor` so its mutable session state (token, captured cookies, base URL)
/// is safe to touch from concurrent SwiftUI tasks.
actor StalkerPortal {

    static let shared = StalkerPortal()

    static let userAgent =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3"

    // Credentials (set from the active Account before connect()).
    var portalUrl = ""
    var mac = ""
    var sn = ""

    private var base = ""
    private var token = ""
    private var host = ""
    private var logosBase = ""
    private(set) var lastError = ""

    // Cloudflare / portal cookies captured from responses, resent on every
    // request so the whole session sticks to one backend node.
    private var extraCookies: [String: String] = [:]

    private let session: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpShouldSetCookies = false          // we manage the Cookie header by hand
        cfg.httpCookieAcceptPolicy = .never
        cfg.timeoutIntervalForRequest = 20
        cfg.timeoutIntervalForResource = 40
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: cfg)
    }()

    func configure(portal: String, mac: String, sn: String) {
        self.portalUrl = portal
        self.mac = mac
        self.sn = sn
    }

    // MARK: - Low-level HTTP

    private func origin(_ u: String) -> String {
        let t = u.trimmingCharacters(in: .whitespacesAndNewlines)
        if let r = t.range(of: #"https?://[^/]+"#, options: .regularExpression) {
            return String(t[r])
        }
        return t.hasSuffix("/") ? String(t.dropLast()) : t
    }

    func resetSession() {
        extraCookies.removeAll()
        token = ""
    }

    private func cookieHeader() -> String {
        let tz = TimeZone.current.identifier
        var sb = "mac=\(mac); stb_lang=en; timezone=\(tz)"
        for (k, v) in extraCookies { sb += "; \(k)=\(v)" }
        return sb
    }

    @discardableResult
    private func get(_ urlString: String, auth: Bool) async -> String {
        guard let url = URL(string: urlString) else { return "" }
        var req = URLRequest(url: url)
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue(cookieHeader(), forHTTPHeaderField: "Cookie")
        req.setValue("Model: MAG250; Link: WiFi", forHTTPHeaderField: "X-User-Agent")
        if auth && !token.isEmpty {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        do {
            let (data, response) = try await session.data(for: req)
            if let http = response as? HTTPURLResponse {
                captureCookies(from: http)
            }
            return String(data: data, encoding: .utf8) ?? ""
        } catch {
            lastError = error.localizedDescription
            return ""
        }
    }

    private func captureCookies(from http: HTTPURLResponse) {
        // Set-Cookie may be folded; pull each "k=v" leading pair like the Kotlin does.
        let header = (http.value(forHTTPHeaderField: "Set-Cookie")) ?? ""
        guard !header.isEmpty else { return }
        // Split conservatively on cookie boundaries.
        for part in header.components(separatedBy: ",") {
            guard let pair = part.components(separatedBy: ";").first else { continue }
            let kv = pair.components(separatedBy: "=")
            guard kv.count >= 2 else { continue }
            let k = kv[0].trimmingCharacters(in: .whitespaces)
            let v = kv[1...].joined(separator: "=").trimmingCharacters(in: .whitespaces)
            if !k.isEmpty, k != "mac", !v.isEmpty { extraCookies[k] = v }
        }
    }

    /// `js` can be a plain array, or an object with a "data" array.
    private func jsArray(_ body: String) -> [JSON]? {
        let js = JSON.parse(body)["js"]
        if let arr = js.array { return arr }
        if let data = js["data"].array { return data }
        return nil
    }

    private func encode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s
    }

    // MARK: - Connect (handshake + profile)

    /// @return nil on success, else an error message.
    func connect() async -> String? {
        let o = origin(portalUrl)
        let candidates = [
            "\(o)/stalker_portal/server/load.php",
            "\(o)/server/load.php",
            "\(o)/portal.php",
            "\(o)/c/server/load.php",
        ]
        resetSession()
        for b in candidates {
            let hs = await get("\(b)?type=stb&action=handshake&JsHttpRequest=1-xml", auth: false)
            if let r = hs.range(of: #""token"\s*:\s*"([^"]+)""#, options: .regularExpression) {
                let match = String(hs[r])
                if let tr = match.range(of: #""([^"]+)"$"#, options: .regularExpression) {
                    let t = String(match[tr]).trimmingCharacters(in: CharacterSet(charactersIn: "\""))
                    if !t.isEmpty { base = b; token = t; break }
                }
            }
        }
        if token.isEmpty { return "Handshake failed — check the portal URL & MAC." }
        host = o
        let prefix = base.contains("server/load.php")
            ? String(base[..<(base.range(of: "server/load.php")!.lowerBound)])
            : "\(host)/stalker_portal/"
        logosBase = prefix + "misc/logos/320/"

        let snEnc = encode(sn)
        let prof = await get(
            "\(base)?type=stb&action=get_profile&sn=\(snEnc)&stb_type=MAG250"
            + "&hw_version=1.7-BD-00&num_banks=2&image_version=218&hd=1&JsHttpRequest=1-xml",
            auth: true)
        if prof.contains("block_msg") || prof.contains("Serial Number mismatch") {
            var msg = "device rejected"
            if let r = prof.range(of: #""msg"\s*:\s*"([^"]+)""#, options: .regularExpression) {
                let m = String(prof[r])
                if let mr = m.range(of: #""([^"]+)"$"#, options: .regularExpression) {
                    msg = String(m[mr]).trimmingCharacters(in: CharacterSet(charactersIn: "\""))
                }
            }
            return "Portal rejected this device: \(msg) — check the Serial Number."
        }
        return nil
    }

    // MARK: - Live TV

    func liveChannels() async -> [Channel] {
        var out: [Channel] = []
        let body = await get("\(base)?type=itv&action=get_all_channels&JsHttpRequest=1-xml", auth: true)
        parseChannels(JSON.parse(body)["js"]["data"].array, into: &out)
        return out
    }

    func liveGenres() async -> [Genre] {
        var out: [Genre] = []
        guard let arr = jsArray(await get("\(base)?type=itv&action=get_genres&JsHttpRequest=1-xml", auth: true))
        else { return out }
        for o in arr {
            let id = o.string("id")
            let title = o.string("title")
            if id == "*" || title.isEmpty { continue }
            out.append(Genre(id: id, title: title, censored: o.int("censored") == 1))
        }
        return out
    }

    /// Channels in one genre via the ordered (paged) list — includes censored channels.
    func itvByGenre(_ genreId: String) async -> [Channel] {
        var out: [Channel] = []
        var page = 1
        while page <= 40 {
            let body = await get("\(base)?type=itv&action=get_ordered_list&genre=\(genreId)&p=\(page)&JsHttpRequest=1-xml", auth: true)
            let js = JSON.parse(body)["js"]
            guard let arr = js["data"].array, !arr.isEmpty else { break }
            parseChannels(arr, into: &out)
            let total = js.int("total_items", arr.count)
            let per = max(js.int("max_page_items", 14), 1)
            if page >= Int(ceil(Double(total) / Double(per))) { break }
            page += 1
        }
        return out
    }

    private func parseChannels(_ arr: [JSON]?, into out: inout [Channel]) {
        guard let arr = arr else { return }
        for c in arr {
            let logo = c.string("logo")
            out.append(Channel(
                id: c.string("id"),
                name: c.string("name"),
                number: c.string("number"),
                cmd: c.string("cmd"),
                logoUrl: (logo.isEmpty || logo == "null") ? "" : logosBase + logo,
                genreId: c.string("tv_genre_id"),
                censored: c.int("censored") == 1,
                archiveDays: c.int("tv_archive_duration")
            ))
        }
    }

    /// Short EPG (now + upcoming) for a channel.
    func shortEpg(_ chId: String) async -> [EpgItem] {
        var out: [EpgItem] = []
        let body = await get("\(base)?type=itv&action=get_short_epg&ch_id=\(chId)&size=24&JsHttpRequest=1-xml", auth: true)
        guard let arr = JSON.parse(body)["js"].array else { return out }
        for o in arr {
            out.append(EpgItem(
                name: o.string("name"),
                start: o.string("t_time"),
                end: o.string("t_time_to"),
                descr: o.string("descr"),
                hasArchive: o.int("mark_archive") == 1,
                startTs: o.long("start_timestamp"),
                stopTs: o.long("stop_timestamp")
            ))
        }
        return out
    }

    /// Format an absolute (UTC) unix timestamp as local wall-clock time, e.g. "1:30 PM".
    nonisolated func localTime(_ ts: Int64) -> String {
        guard ts > 0 else { return "" }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US")
        f.dateFormat = "h:mm a"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(ts)))
    }

    // MARK: - VOD

    func vodCategories() async -> [VodCat] {
        var out: [VodCat] = []
        guard let arr = jsArray(await get("\(base)?type=vod&action=get_categories&JsHttpRequest=1-xml", auth: true))
        else { return out }
        for o in arr {
            let id = o.string("id")
            let title = o.string("title")
            if title.isEmpty { continue }
            out.append(VodCat(id: id, title: title))
        }
        return out
    }

    /// @return (items, totalPages).
    func vodList(_ catId: String, page: Int, sortby: String = "added") async -> ([VodItem], Int) {
        var out: [VodItem] = []
        var pages = 1
        let body = await get("\(base)?type=vod&action=get_ordered_list&category=\(catId)&p=\(page)&sortby=\(sortby)&JsHttpRequest=1-xml", auth: true)
        let js = JSON.parse(body)["js"]
        let total = js.int("total_items")
        let per = max(js.int("max_page_items", 14), 1)
        pages = max(Int(ceil(Double(total) / Double(per))), 1)
        parseVodItems(js["data"].array, into: &out)
        return (out, pages)
    }

    private func parseVodItems(_ arr: [JSON]?, into out: inout [VodItem]) {
        guard let arr = arr else { return }
        for o in arr {
            let ss = o.string("screenshot_uri")
            let poster: String
            if ss.isEmpty || ss == "null" { poster = "" }
            else if ss.hasPrefix("http") { poster = ss }
            else if ss.hasPrefix("/") { poster = host + ss }
            else { poster = "\(host)/\(ss)" }
            out.append(VodItem(
                id: o.string("id"),
                name: o.string("name"),
                cmd: o.string("cmd"),
                posterUrl: poster,
                isSeries: o.string("is_series") == "1"
            ))
        }
    }

    func seriesSeasons(_ seriesId: String) async -> [Season] {
        var out: [Season] = []
        await pagedList("\(base)?type=vod&action=get_ordered_list&movie_id=\(seriesId)") { o in
            let nm = o.string("name").isEmpty ? "Season \(o.string("season_number"))" : o.string("name")
            out.append(Season(id: o.string("id"), name: nm))
        }
        return out
    }

    func seriesEpisodes(_ seriesId: String, seasonId: String) async -> [Episode] {
        var out: [Episode] = []
        await pagedList("\(base)?type=vod&action=get_ordered_list&movie_id=\(seriesId)&season_id=\(seasonId)") { o in
            let nm = o.string("name").isEmpty ? "Episode \(o.string("series_number"))" : o.string("name")
            out.append(Episode(id: o.string("id"), name: nm))
        }
        return out
    }

    private func pagedList(_ urlPrefix: String, onItem: (JSON) -> Void) async {
        var page = 1
        while page <= 60 {
            let body = await get("\(urlPrefix)&p=\(page)&JsHttpRequest=1-xml", auth: true)
            let js = JSON.parse(body)["js"]
            guard let arr = js["data"].array, !arr.isEmpty else { break }
            for o in arr { onItem(o) }
            let total = js.int("total_items", arr.count)
            let per = max(js.int("max_page_items", 14), 1)
            if page >= Int(ceil(Double(total) / Double(per))) { break }
            page += 1
        }
    }

    // MARK: - Stream resolution (create_link)

    func createLink(_ cmd: String) async -> String? { await resolve("itv", cmd) }

    /// VOD play: resolve the movie's file id, then create_link for it.
    func playVodUrl(movieId: String, fallbackCmd: String) async -> String? {
        let body = await get("\(base)?type=vod&action=get_ordered_list&movie_id=\(movieId)&JsHttpRequest=1-xml", auth: true)
        if let arr = JSON.parse(body)["js"]["data"].array, !arr.isEmpty {
            let fileId = arr[0].string("id")
            if !fileId.isEmpty, let url = await resolve("vod", "/media/file_\(fileId).mpg"), !url.isEmpty {
                return url
            }
        }
        return await resolve("vod", fallbackCmd)
    }

    func playEpisodeUrl(seriesId: String, seasonId: String, episodeId: String) async -> String? {
        let body = await get("\(base)?type=vod&action=get_ordered_list&movie_id=\(seriesId)&season_id=\(seasonId)&episode_id=\(episodeId)&JsHttpRequest=1-xml", auth: true)
        let fileId = JSON.parse(body)["js"]["data"][0].string("id")
        if !fileId.isEmpty, let url = await resolve("vod", "/media/file_\(fileId).mpg"), !url.isEmpty {
            return url
        }
        return nil
    }

    /// Catch-up archive link: swap the live HLS playlist for archive-<start>-<dur>.m3u8.
    func archiveLink(channelCmd: String, startTs: Int64, durationSec: Int64) async -> String? {
        guard let live = await resolve("itv", channelCmd) else { return nil }
        let dur = durationSec > 0 ? durationSec : 3600
        let path = live.components(separatedBy: "?").first ?? live
        let query = live.contains("?") ? String(live[live.range(of: "?")!.upperBound...]) : ""
        guard let slash = path.range(of: "/", options: .backwards) else { return nil }
        let archive = String(path[..<slash.upperBound]) + "archive-\(startTs)-\(dur).m3u8"
        return query.isEmpty ? archive : "\(archive)?\(query)"
    }

    private func resolve(_ type: String, _ cmd: String) async -> String? {
        let enc = encode(cmd)
        let u = "\(base)?type=\(type)&action=create_link&cmd=\(enc)"
            + "&series=0&forced_storage=&disable_ad=0&download=0&JsHttpRequest=1-xml"
        for attempt in 0..<3 {
            let js = JSON.parse(await get(u, auth: true))["js"]
            var url = js.string("cmd")
            if !url.isEmpty {
                if let r = url.range(of: "http"), r.lowerBound != url.startIndex {
                    url = String(url[r.lowerBound...])
                }
                return url.trimmingCharacters(in: .whitespaces)
            }
            let err = js.string("error")
            lastError = err.isEmpty ? "no stream returned" : err
            if lastError != "nothing_to_play" { return nil }
            if attempt < 2 { try? await Task.sleep(nanoseconds: 800_000_000) }
        }
        return nil
    }
}
