import Foundation

/// A JSON value that keeps object keys in the order the server sent them.
///
/// The added providers' readers walk responses looking for quota-shaped objects and keep the
/// first match of each window length, the same way Android's `org.json` readers do. Foundation's
/// `JSONSerialization` returns unordered dictionaries, so these readers use this small parser
/// instead to pick the same windows as Android.
public indirect enum ProviderJSON: Sendable, Equatable {
    case null
    case bool(Bool)
    /// The number and its literal text, so whole numbers print without a decimal point.
    case number(Double, String)
    case string(String)
    case array([ProviderJSON])
    case object(ProviderJSONObject)

    /// Parses `text`, or returns nil when it isn't JSON.
    public static func parse(_ text: String) -> ProviderJSON? {
        var parser = Parser(Array(text.utf8))
        parser.skipWhitespace()
        guard let value = parser.value() else { return nil }
        parser.skipWhitespace()
        return parser.atEnd ? value : nil
    }

    public static func parse(_ data: Data) -> ProviderJSON? {
        parse(String(decoding: data, as: UTF8.self))
    }

    /// The root object of `text`, or an empty object (like `new JSONObject(body)` failing soft).
    public static func object(_ text: String?) -> ProviderJSONObject {
        guard let text, case let .object(object)? = parse(text.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            return ProviderJSONObject()
        }
        return object
    }

    public var objectValue: ProviderJSONObject? {
        if case let .object(object) = self { return object }
        return nil
    }

    public var arrayValue: [ProviderJSON]? {
        if case let .array(array) = self { return array }
        return nil
    }

    public var isNull: Bool {
        if case .null = self { return true }
        return false
    }

    /// Text form, as `String.valueOf` prints an `org.json` value.
    public var text: String {
        switch self {
        case .null: "null"
        case let .bool(value): value ? "true" : "false"
        case let .number(_, raw): raw
        case let .string(value): value
        case let .array(values): "[" + values.map(\.serialized).joined(separator: ",") + "]"
        case let .object(object): object.serialized
        }
    }

    var serialized: String {
        switch self {
        case let .string(value):
            let data = (try? JSONSerialization.data(withJSONObject: [value], options: [.fragmentsAllowed])) ?? Data()
            let wrapped = String(decoding: data, as: UTF8.self)
            return String(wrapped.dropFirst().dropLast())
        default:
            return text
        }
    }

    // MARK: Parser

    private struct Parser {
        let bytes: [UInt8]
        var index = 0
        var depth = 0

        init(_ bytes: [UInt8]) {
            self.bytes = bytes
        }

        var atEnd: Bool { index >= bytes.count }

        mutating func skipWhitespace() {
            while index < bytes.count, [0x20, 0x09, 0x0A, 0x0D].contains(bytes[index]) {
                index += 1
            }
        }

        mutating func value() -> ProviderJSON? {
            guard index < bytes.count, depth < 256 else { return nil }
            switch bytes[index] {
            case UInt8(ascii: "{"): return objectValue()
            case UInt8(ascii: "["): return arrayValue()
            case UInt8(ascii: "\""): return stringValue().map { .string($0) }
            case UInt8(ascii: "t"): return literal("true", .bool(true))
            case UInt8(ascii: "f"): return literal("false", .bool(false))
            case UInt8(ascii: "n"): return literal("null", .null)
            default: return numberValue()
            }
        }

        mutating func literal(_ word: String, _ result: ProviderJSON) -> ProviderJSON? {
            let expected = Array(word.utf8)
            guard index + expected.count <= bytes.count,
                  Array(bytes[index ..< index + expected.count]) == expected else { return nil }
            index += expected.count
            return result
        }

        mutating func numberValue() -> ProviderJSON? {
            let start = index
            while index < bytes.count {
                let byte = bytes[index]
                let isNumberByte = (byte >= 0x30 && byte <= 0x39)
                    || byte == UInt8(ascii: "-") || byte == UInt8(ascii: "+")
                    || byte == UInt8(ascii: ".") || byte == UInt8(ascii: "e")
                    || byte == UInt8(ascii: "E")
                guard isNumberByte else { break }
                index += 1
            }
            guard index > start else { return nil }
            let raw = String(decoding: bytes[start ..< index], as: UTF8.self)
            guard let number = Double(raw) else { return nil }
            return .number(number, raw)
        }

        mutating func stringValue() -> String? {
            guard index < bytes.count, bytes[index] == UInt8(ascii: "\"") else { return nil }
            index += 1
            var output: [UInt8] = []
            while index < bytes.count {
                let byte = bytes[index]
                index += 1
                if byte == UInt8(ascii: "\"") {
                    return String(decoding: output, as: UTF8.self)
                }
                guard byte == UInt8(ascii: "\\") else {
                    output.append(byte)
                    continue
                }
                guard index < bytes.count else { return nil }
                let escape = bytes[index]
                index += 1
                switch escape {
                case UInt8(ascii: "\""): output.append(0x22)
                case UInt8(ascii: "\\"): output.append(0x5C)
                case UInt8(ascii: "/"): output.append(0x2F)
                case UInt8(ascii: "b"): output.append(0x08)
                case UInt8(ascii: "f"): output.append(0x0C)
                case UInt8(ascii: "n"): output.append(0x0A)
                case UInt8(ascii: "r"): output.append(0x0D)
                case UInt8(ascii: "t"): output.append(0x09)
                case UInt8(ascii: "u"):
                    guard var scalar = hex4() else { return nil }
                    // Surrogate pair.
                    if scalar >= 0xD800, scalar < 0xDC00,
                       index + 1 < bytes.count,
                       bytes[index] == UInt8(ascii: "\\"), bytes[index + 1] == UInt8(ascii: "u") {
                        index += 2
                        guard let low = hex4() else { return nil }
                        scalar = 0x10000 + ((scalar - 0xD800) << 10) + (low - 0xDC00)
                    }
                    let character = Unicode.Scalar(scalar).map(String.init) ?? "\u{FFFD}"
                    output.append(contentsOf: Array(character.utf8))
                default:
                    return nil
                }
            }
            return nil
        }

        mutating func hex4() -> UInt32? {
            guard index + 4 <= bytes.count,
                  let value = UInt32(String(decoding: bytes[index ..< index + 4], as: UTF8.self), radix: 16)
            else { return nil }
            index += 4
            return value
        }

        mutating func arrayValue() -> ProviderJSON? {
            index += 1
            depth += 1
            defer { depth -= 1 }
            var values: [ProviderJSON] = []
            skipWhitespace()
            if index < bytes.count, bytes[index] == UInt8(ascii: "]") {
                index += 1
                return .array(values)
            }
            while true {
                skipWhitespace()
                guard let element = value() else { return nil }
                values.append(element)
                skipWhitespace()
                guard index < bytes.count else { return nil }
                if bytes[index] == UInt8(ascii: ",") {
                    index += 1
                    continue
                }
                if bytes[index] == UInt8(ascii: "]") {
                    index += 1
                    return .array(values)
                }
                return nil
            }
        }

        mutating func objectValue() -> ProviderJSON? {
            index += 1
            depth += 1
            defer { depth -= 1 }
            var object = ProviderJSONObject()
            skipWhitespace()
            if index < bytes.count, bytes[index] == UInt8(ascii: "}") {
                index += 1
                return .object(object)
            }
            while true {
                skipWhitespace()
                guard let key = stringValue() else { return nil }
                skipWhitespace()
                guard index < bytes.count, bytes[index] == UInt8(ascii: ":") else { return nil }
                index += 1
                skipWhitespace()
                guard let element = value() else { return nil }
                object[key] = element
                skipWhitespace()
                guard index < bytes.count else { return nil }
                if bytes[index] == UInt8(ascii: ",") {
                    index += 1
                    continue
                }
                if bytes[index] == UInt8(ascii: "}") {
                    index += 1
                    return .object(object)
                }
                return nil
            }
        }
    }
}

/// A JSON object with its keys in server order. Lookups mirror `org.json`'s `opt*` methods.
public struct ProviderJSONObject: Sendable, Equatable {
    public private(set) var keys: [String] = []
    private var storage: [String: ProviderJSON] = [:]

    public init() {}

    public subscript(key: String) -> ProviderJSON? {
        get { storage[key] }
        set {
            if let newValue {
                if storage[key] == nil { keys.append(key) }
                storage[key] = newValue
            } else if storage.removeValue(forKey: key) != nil {
                keys.removeAll { $0 == key }
            }
        }
    }

    public var isEmpty: Bool { keys.isEmpty }

    public func has(_ key: String) -> Bool { storage[key] != nil }

    public func object(_ key: String) -> ProviderJSONObject? { storage[key]?.objectValue }

    public func array(_ key: String) -> [ProviderJSON]? { storage[key]?.arrayValue }

    /// `optString(key, fallback)`: any non-null value as text.
    public func string(_ key: String, default fallback: String = "") -> String {
        guard let value = storage[key], !value.isNull else { return fallback }
        return value.text
    }

    /// `optBoolean(key, fallback)`: a JSON bool or the strings "true" / "false".
    public func bool(_ key: String, default fallback: Bool) -> Bool {
        switch storage[key] {
        case let .bool(value)?: return value
        case let .string(value)?:
            if value.caseInsensitiveCompare("true") == .orderedSame { return true }
            if value.caseInsensitiveCompare("false") == .orderedSame { return false }
            return fallback
        default: return fallback
        }
    }

    /// `optLong` / `optInt`: a number or numeric string, truncated.
    public func int(_ key: String, default fallback: Int64) -> Int64 {
        switch storage[key] {
        case let .number(value, _)?:
            return value.isFinite ? Int64(value) : fallback
        case let .string(value)?:
            guard let number = Double(value.trimmingCharacters(in: .whitespaces)), number.isFinite else {
                return fallback
            }
            return Int64(number)
        default: return fallback
        }
    }

    var serialized: String {
        "{" + keys.map { key in
            ProviderJSON.string(key).serialized + ":" + (storage[key] ?? .null).serialized
        }.joined(separator: ",") + "}"
    }
}
