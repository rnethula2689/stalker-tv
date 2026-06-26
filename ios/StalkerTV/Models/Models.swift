import Foundation

// Domain models, ported 1:1 from the Android app's Portal.kt data classes.

struct Channel: Identifiable, Hashable {
    let id: String
    let name: String
    let number: String
    let cmd: String
    let logoUrl: String
    let genreId: String
    let censored: Bool
    let archiveDays: Int
}

struct Genre: Identifiable, Hashable {
    let id: String
    let title: String
    let censored: Bool
}

struct VodCat: Identifiable, Hashable {
    let id: String
    let title: String
}

struct VodItem: Identifiable, Hashable {
    let id: String
    let name: String
    let cmd: String
    let posterUrl: String
    let isSeries: Bool
}

struct Season: Identifiable, Hashable {
    let id: String
    let name: String
}

struct Episode: Identifiable, Hashable {
    let id: String
    let name: String
}

struct EpgItem: Identifiable, Hashable {
    var id: String { "\(startTs)-\(name)" }
    let name: String
    let start: String
    let end: String
    let descr: String
    let hasArchive: Bool
    let startTs: Int64
    let stopTs: Int64
}
