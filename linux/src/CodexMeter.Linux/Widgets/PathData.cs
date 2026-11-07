using System.Globalization;

namespace CodexMeter.Linux.Widgets;

/// <summary>One absolute drawing step: move, line, cubic curve or close.</summary>
public readonly record struct PathStep(char Kind, double X1 = 0, double Y1 = 0, double X2 = 0, double Y2 = 0, double X = 0, double Y = 0);

/// <summary>
/// Parses SVG / XAML path markup (the provider marks in ProviderIcons.g.cs) into absolute moves,
/// lines and cubic curves that Cairo can draw. Quadratic curves and elliptical arcs are converted
/// to cubics. A leading <c>F0</c> / <c>F1</c> picks the fill rule (even-odd / nonzero).
/// </summary>
public static class PathData
{
    public sealed record Parsed(bool EvenOdd, IReadOnlyList<PathStep> Steps);

    public static Parsed Parse(string data)
    {
        var tokens = Tokenize(data).ToList();
        var evenOdd = true;
        var i = 0;
        if (tokens.Count > 1 && tokens[0] == "F")
        {
            evenOdd = tokens[1] == "0";
            i = 2;
        }
        var steps = new List<PathStep>();
        double x = 0, y = 0, startX = 0, startY = 0;
        double lastControlX = 0, lastControlY = 0;
        char command = ' ', previous = ' ';
        double Next() => double.Parse(tokens[i++], CultureInfo.InvariantCulture);
        bool HasNumber() => i < tokens.Count && !char.IsLetter(tokens[i][0]);

        while (i < tokens.Count)
        {
            if (char.IsLetter(tokens[i][0])) command = tokens[i++][0];
            else if (command == ' ') throw new FormatException("Path data must start with a command.");
            var relative = char.IsLower(command);
            var ox = relative ? x : 0;
            var oy = relative ? y : 0;
            switch (char.ToUpperInvariant(command))
            {
                case 'M':
                    x = ox + Next(); y = oy + Next();
                    startX = x; startY = y;
                    steps.Add(new PathStep('M', X: x, Y: y));
                    // Extra pairs after a move are implicit lines.
                    command = relative ? 'l' : 'L';
                    break;
                case 'L':
                    x = ox + Next(); y = oy + Next();
                    steps.Add(new PathStep('L', X: x, Y: y));
                    break;
                case 'H':
                    x = (relative ? x : 0) + Next();
                    steps.Add(new PathStep('L', X: x, Y: y));
                    break;
                case 'V':
                    y = (relative ? y : 0) + Next();
                    steps.Add(new PathStep('L', X: x, Y: y));
                    break;
                case 'C':
                {
                    var x1 = ox + Next(); var y1 = oy + Next();
                    var x2 = ox + Next(); var y2 = oy + Next();
                    x = ox + Next(); y = oy + Next();
                    steps.Add(new PathStep('C', x1, y1, x2, y2, x, y));
                    lastControlX = x2; lastControlY = y2;
                    break;
                }
                case 'S':
                {
                    var smooth = "CcSs".Contains(previous);
                    var x1 = smooth ? 2 * x - lastControlX : x;
                    var y1 = smooth ? 2 * y - lastControlY : y;
                    var x2 = ox + Next(); var y2 = oy + Next();
                    x = ox + Next(); y = oy + Next();
                    steps.Add(new PathStep('C', x1, y1, x2, y2, x, y));
                    lastControlX = x2; lastControlY = y2;
                    break;
                }
                case 'Q':
                {
                    var qx = ox + Next(); var qy = oy + Next();
                    var ex = ox + Next(); var ey = oy + Next();
                    steps.Add(Quadratic(x, y, qx, qy, ex, ey));
                    x = ex; y = ey;
                    lastControlX = qx; lastControlY = qy;
                    break;
                }
                case 'T':
                {
                    var smooth = "QqTt".Contains(previous);
                    var qx = smooth ? 2 * x - lastControlX : x;
                    var qy = smooth ? 2 * y - lastControlY : y;
                    var ex = ox + Next(); var ey = oy + Next();
                    steps.Add(Quadratic(x, y, qx, qy, ex, ey));
                    x = ex; y = ey;
                    lastControlX = qx; lastControlY = qy;
                    break;
                }
                case 'A':
                {
                    var rx = Next(); var ry = Next(); var rotation = Next();
                    var large = Next() != 0; var sweep = Next() != 0;
                    var ex = ox + Next(); var ey = oy + Next();
                    steps.AddRange(Arc(x, y, rx, ry, rotation, large, sweep, ex, ey));
                    x = ex; y = ey;
                    break;
                }
                case 'Z':
                    steps.Add(new PathStep('Z'));
                    x = startX; y = startY;
                    break;
                default:
                    throw new FormatException($"Unsupported path command '{command}'.");
            }
            previous = command;
            if (char.ToUpperInvariant(command) == 'Z' && HasNumber())
                throw new FormatException("Numbers after a close command.");
        }
        return new Parsed(evenOdd, steps);
    }

    private static PathStep Quadratic(double x0, double y0, double qx, double qy, double x, double y) =>
        new('C', x0 + 2.0 / 3 * (qx - x0), y0 + 2.0 / 3 * (qy - y0), x + 2.0 / 3 * (qx - x), y + 2.0 / 3 * (qy - y), x, y);

    /// <summary>SVG endpoint arc to cubic Béziers (one per quarter turn or less).</summary>
    internal static IEnumerable<PathStep> Arc(double x0, double y0, double rx, double ry, double rotationDegrees,
        bool largeArc, bool sweep, double x, double y)
    {
        if (x0 == x && y0 == y) yield break;
        rx = Math.Abs(rx); ry = Math.Abs(ry);
        if (rx == 0 || ry == 0)
        {
            yield return new PathStep('L', X: x, Y: y);
            yield break;
        }
        var phi = rotationDegrees * Math.PI / 180;
        var cos = Math.Cos(phi); var sin = Math.Sin(phi);
        var dx = (x0 - x) / 2; var dy = (y0 - y) / 2;
        var x1p = cos * dx + sin * dy;
        var y1p = -sin * dx + cos * dy;
        var lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry);
        if (lambda > 1)
        {
            var s = Math.Sqrt(lambda);
            rx *= s; ry *= s;
        }
        var numerator = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        var denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        var factor = Math.Sqrt(Math.Max(0, numerator / denominator)) * (largeArc == sweep ? -1 : 1);
        var cxp = factor * rx * y1p / ry;
        var cyp = -factor * ry * x1p / rx;
        var cx = cos * cxp - sin * cyp + (x0 + x) / 2;
        var cy = sin * cxp + cos * cyp + (y0 + y) / 2;

        static double Angle(double ux, double uy, double vx, double vy)
        {
            var angle = Math.Atan2(ux * vy - uy * vx, ux * vx + uy * vy);
            return angle;
        }
        var theta = Angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        var delta = Angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && delta > 0) delta -= 2 * Math.PI;
        else if (sweep && delta < 0) delta += 2 * Math.PI;

        var segments = (int)Math.Ceiling(Math.Abs(delta) / (Math.PI / 2) - 1e-9);
        var step = delta / Math.Max(1, segments);
        var k = 4.0 / 3 * Math.Tan(step / 4);
        (double X, double Y) Point(double t) =>
            (cx + rx * Math.Cos(t) * cos - ry * Math.Sin(t) * sin, cy + rx * Math.Cos(t) * sin + ry * Math.Sin(t) * cos);
        (double X, double Y) Derivative(double t) =>
            (-rx * Math.Sin(t) * cos - ry * Math.Cos(t) * sin, -rx * Math.Sin(t) * sin + ry * Math.Cos(t) * cos);
        for (var n = 0; n < segments; n++)
        {
            var t1 = theta + n * step;
            var t2 = t1 + step;
            var p1 = Point(t1); var d1 = Derivative(t1);
            var p2 = n == segments - 1 ? (x, y) : Point(t2);
            var d2 = Derivative(t2);
            yield return new PathStep('C', p1.X + k * d1.X, p1.Y + k * d1.Y, p2.Item1 - k * d2.X, p2.Item2 - k * d2.Y, p2.Item1, p2.Item2);
        }
    }

    private static IEnumerable<string> Tokenize(string data)
    {
        var i = 0;
        while (i < data.Length)
        {
            var c = data[i];
            if (char.IsWhiteSpace(c) || c == ',') { i++; continue; }
            if (char.IsLetter(c) && c is not ('e' or 'E'))
            {
                yield return c.ToString();
                i++;
                continue;
            }
            var start = i;
            if (data[i] is '+' or '-') i++;
            var dot = false;
            var exponent = false;
            while (i < data.Length)
            {
                var d = data[i];
                if (char.IsDigit(d)) { i++; continue; }
                if (d == '.' && !dot && !exponent) { dot = true; i++; continue; }
                if (d is 'e' or 'E' && !exponent)
                {
                    exponent = true;
                    i++;
                    if (i < data.Length && data[i] is '+' or '-') i++;
                    continue;
                }
                break;
            }
            if (i == start) throw new FormatException($"Bad path data near '{data.Substring(start, Math.Min(12, data.Length - start))}'.");
            yield return data[start..i];
        }
    }

    /// <summary>Replays parsed steps on a Cairo context.</summary>
    public static void Append(Cairo.Context cr, Parsed path)
    {
        foreach (var step in path.Steps)
        {
            switch (step.Kind)
            {
                case 'M': cr.MoveTo(step.X, step.Y); break;
                case 'L': cr.LineTo(step.X, step.Y); break;
                case 'C': cr.CurveTo(step.X1, step.Y1, step.X2, step.Y2, step.X, step.Y); break;
                case 'Z': cr.ClosePath(); break;
            }
        }
    }
}
