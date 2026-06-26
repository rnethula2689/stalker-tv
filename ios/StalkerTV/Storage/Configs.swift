import Foundation

/// On-device store for one or more IPTV provider configurations.
/// Port of the Android `Configs` object, backed by UserDefaults.
struct Account: Codable, Identifiable, Hashable {
    var name: String
    var portal: String
    var mac: String
    var sn: String

    var id: String { sig }
    var sig: String { "\(portal)|\(mac)|\(sn)" }
}

enum SortMode: Int, CaseIterable {
    case `default` = 0   // provider order
    case az = 1          // name A–Z
    case za = 2          // name Z–A

    var label: String {
        switch self {
        case .default: return "Default"
        case .az: return "A–Z"
        case .za: return "Z–A"
        }
    }
}

@MainActor
final class Configs: ObservableObject {
    static let shared = Configs()

    private let defaults = UserDefaults.standard
    private let accountsKey = "accounts"
    private let activeKey = "active"

    @Published private(set) var accounts: [Account] = []
    @Published var activeIndex: Int = 0

    private init() {
        load()
        activeIndex = defaults.integer(forKey: activeKey)
    }

    var active: Account? {
        guard !accounts.isEmpty else { return nil }
        return accounts.indices.contains(activeIndex) ? accounts[activeIndex] : accounts.first
    }

    func load() {
        if let data = defaults.data(forKey: accountsKey),
           let list = try? JSONDecoder().decode([Account].self, from: data) {
            accounts = list
        } else {
            accounts = []
        }
    }

    func save() {
        if let data = try? JSONEncoder().encode(accounts) {
            defaults.set(data, forKey: accountsKey)
        }
    }

    func add(_ account: Account) {
        accounts.append(account)
        save()
    }

    func update(_ account: Account, at index: Int) {
        guard accounts.indices.contains(index) else { return }
        accounts[index] = account
        save()
    }

    func remove(at index: Int) {
        guard accounts.indices.contains(index) else { return }
        accounts.remove(at: index)
        if activeIndex >= accounts.count { setActive(max(0, accounts.count - 1)) }
        save()
    }

    func setActive(_ index: Int) {
        activeIndex = index
        defaults.set(index, forKey: activeKey)
    }

    // MARK: - Sort mode (shared across folders)

    var sortMode: SortMode {
        get { SortMode(rawValue: defaults.integer(forKey: "sortMode")) ?? .default }
        set { defaults.set(newValue.rawValue, forKey: "sortMode") }
    }

    @discardableResult
    func cycleSortMode() -> SortMode {
        let next = SortMode(rawValue: (sortMode.rawValue + 1) % 3) ?? .default
        sortMode = next
        return next
    }

    // MARK: - Favourites (per active provider)

    private func favKey() -> String { "fav:" + (active?.sig ?? "default") }

    func favorites() -> [String] { defaults.stringArray(forKey: favKey()) ?? [] }

    func isFavorite(_ id: String) -> Bool { favorites().contains(id) }

    /// @return true if it's now a favourite, false if removed.
    @discardableResult
    func toggleFavorite(_ id: String) -> Bool {
        var set = favorites()
        let nowFav: Bool
        if let idx = set.firstIndex(of: id) { set.remove(at: idx); nowFav = false }
        else { set.append(id); nowFav = true }
        defaults.set(set, forKey: favKey())
        return nowFav
    }

    // MARK: - Parental PIN

    var parentalPin: String {
        get { defaults.string(forKey: "parentalPin") ?? "" }
        set { defaults.set(newValue, forKey: "parentalPin") }
    }
}
