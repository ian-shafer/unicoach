import Foundation

protocol CookieStorageProtocol: Sendable {
    var cookies: [HTTPCookie]? { get }
    func deleteCookie(_ cookie: HTTPCookie)
}

extension HTTPCookieStorage: CookieStorageProtocol, @unchecked Sendable {}
