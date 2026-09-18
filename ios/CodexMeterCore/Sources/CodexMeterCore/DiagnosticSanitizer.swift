import Foundation

/// Privacy filters shared by diagnostic logging and its tests.
public enum DiagnosticSanitizer {
    public static let redacted = "[REDACTED]"
    public static let redactedEmail = "[REDACTED_EMAIL]"

    private static let authScheme = try! NSRegularExpression(
        pattern: #"(?i)\b(?:Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+"#
    )
    private static let email = try! NSRegularExpression(
        pattern: #"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"#
    )
    private static let jwt = try! NSRegularExpression(
        pattern: #"\b[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{8,}\b"#
    )
    private static let sensitiveAssignment = try! NSRegularExpression(
        pattern: #"(?i)\b(authorization|proxy-authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|code[_-]?verifier|password|passwd|session(?:id)?|api[_-]?key)\b\s*["']?\s*[:=]\s*["']?([^\s,;&}\]]+)"#
    )
    private static let sensitiveQuery = try! NSRegularExpression(
        pattern: #"(?i)([?&](?:code|state|token|access_token|refresh_token|id_token|code_verifier|client_secret|password|session)=)[^&#\s]+"#
    )

    public static func redact(_ value: String?) -> String {
        guard let value, !value.isEmpty else { return value ?? "" }
        var result = replace(authScheme, in: value, with: redacted)
        result = replace(jwt, in: result, with: redacted)
        result = replace(email, in: result, with: redactedEmail)
        result = replaceAssignments(result)
        return replace(sensitiveQuery, in: result, template: "$1\(redacted)")
    }

    /// Returns scheme, host, optional port, and path only. Query strings and fragments are dropped.
    public static func safeURL(_ value: String?) -> String {
        guard let value, !value.isEmpty else { return "" }
        guard let url = URL(string: value),
              let scheme = url.scheme,
              let host = url.host else {
            return redact(value)
        }
        var safe = "\(scheme.lowercased())://\(host.lowercased())"
        if let port = url.port {
            safe += ":\(port)"
        }
        let path = url.path
        if !path.isEmpty {
            safe += path
        }
        return safe
    }

    private static func replaceAssignments(_ value: String) -> String {
        let range = NSRange(value.startIndex..., in: value)
        var result = value
        let matches = sensitiveAssignment.matches(in: value, range: range).reversed()
        for match in matches {
            guard match.numberOfRanges >= 2,
                  let nameRange = Range(match.range(at: 1), in: result),
                  let fullRange = Range(match.range, in: result) else {
                continue
            }
            let name = String(result[nameRange])
            result.replaceSubrange(fullRange, with: "\(name)=\(redacted)")
        }
        return result
    }

    private static func replace(
        _ expression: NSRegularExpression,
        in value: String,
        with replacement: String
    ) -> String {
        replace(expression, in: value, template: NSRegularExpression.escapedTemplate(for: replacement))
    }

    private static func replace(
        _ expression: NSRegularExpression,
        in value: String,
        template: String
    ) -> String {
        let range = NSRange(value.startIndex..., in: value)
        return expression.stringByReplacingMatches(in: value, range: range, withTemplate: template)
    }
}
