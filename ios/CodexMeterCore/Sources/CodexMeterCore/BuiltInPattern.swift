import Foundation

/// Compiles the regular expressions written into the source. Their patterns are constants covered
/// by tests, so a failure is a programming error; it stops with the pattern named instead of a
/// bare `try!`.
enum BuiltInPattern {
    static func compile(pattern: String, options: NSRegularExpression.Options = []) -> NSRegularExpression {
        do {
            return try NSRegularExpression(pattern: pattern, options: options)
        } catch {
            preconditionFailure("Invalid built-in pattern \(pattern): \(error)")
        }
    }
}
