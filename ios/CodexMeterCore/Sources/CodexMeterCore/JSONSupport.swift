import Foundation

public enum CodexMeterParsingError: Error, Sendable, Equatable {
    case invalidRootObject
}

extension CodexMeterParsingError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .invalidRootObject:
            "The response did not contain a JSON object."
        }
    }
}

enum JSONSupport {
    typealias Object = [String: Any]

    static func object(from data: Data) throws -> Object {
        let value = try JSONSerialization.jsonObject(with: data)
        guard let object = value as? Object else {
            throw CodexMeterParsingError.invalidRootObject
        }
        return object
    }

    static func object(_ value: Any?) -> Object? {
        value as? Object
    }

    static func array(_ value: Any?) -> [Any]? {
        value as? [Any]
    }

    static func string(_ value: Any?, default fallback: String = "") -> String {
        guard let value, !(value is NSNull) else {
            return fallback
        }
        if let string = value as? String {
            return string
        }
        if let number = value as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                return number.boolValue ? "true" : "false"
            }
            return number.stringValue
        }
        return fallback
    }

    static func bool(_ value: Any?, default fallback: Bool) -> Bool {
        if let number = value as? NSNumber,
           CFGetTypeID(number) == CFBooleanGetTypeID() {
            return number.boolValue
        }
        if let string = value as? String {
            switch string.lowercased() {
            case "true": return true
            case "false": return false
            default: break
            }
        }
        return fallback
    }

    static func double(_ value: Any?) -> Double? {
        if let number = value as? NSNumber {
            guard CFGetTypeID(number) != CFBooleanGetTypeID() else {
                return nil
            }
            return number.doubleValue
        }
        if let string = value as? String {
            return Double(string)
        }
        return nil
    }

    static func int64(_ value: Any?, default fallback: Int64) -> Int64 {
        guard let value = double(value), value.isFinite else {
            return fallback
        }
        if value >= Double(Int64.max) {
            return Int64.max
        }
        if value <= Double(Int64.min) {
            return Int64.min
        }
        return Int64(value)
    }

    static func int(_ value: Any?, default fallback: Int) -> Int {
        let parsed = int64(value, default: Int64(fallback))
        if parsed >= Int64(Int.max) {
            return Int.max
        }
        if parsed <= Int64(Int.min) {
            return Int.min
        }
        return Int(parsed)
    }
}
