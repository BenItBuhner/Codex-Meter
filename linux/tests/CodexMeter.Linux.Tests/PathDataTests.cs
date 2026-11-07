using CodexMeter.App.Assets;
using CodexMeter.Linux.Widgets;

namespace CodexMeter.Linux.Tests;

public class PathDataTests
{
    [Fact]
    public void EveryProviderMarkParses()
    {
        Assert.NotEmpty(ProviderIcons.AllData);
        foreach (var (id, data) in ProviderIcons.AllData)
        {
            var parsed = PathData.Parse(data);
            Assert.True(parsed.Steps.Count > 2, id);
            Assert.Equal('M', parsed.Steps[0].Kind);
            Assert.All(parsed.Steps, s => Assert.True(double.IsFinite(s.X) && double.IsFinite(s.Y) && double.IsFinite(s.X1) && double.IsFinite(s.Y2), id));
        }
    }

    [Fact]
    public void CodexClaudeAndCursorHaveMarks()
    {
        Assert.True(ProviderIcons.Has("codex"));
        Assert.True(ProviderIcons.Has("claude_code"));
        Assert.True(ProviderIcons.Has("cursor"));
    }

    [Fact]
    public void FillRuleFollowsTheXamlPrefix()
    {
        Assert.False(PathData.Parse("F1 M 0 0 L 1 1 Z").EvenOdd);
        Assert.True(PathData.Parse("F0 M 0 0 L 1 1 Z").EvenOdd);
        Assert.True(PathData.Parse("M 0 0 L 1 1 Z").EvenOdd);
    }

    [Fact]
    public void RelativeCommandsAndImplicitLinesBecomeAbsolute()
    {
        var steps = PathData.Parse("m 1 2 3 4 h 5 v -1 l -1 -1 z M 10 10").Steps;
        Assert.Equal(new PathStep('M', X: 1, Y: 2), steps[0]);
        Assert.Equal(new PathStep('L', X: 4, Y: 6), steps[1]);
        Assert.Equal(new PathStep('L', X: 9, Y: 6), steps[2]);
        Assert.Equal(new PathStep('L', X: 9, Y: 5), steps[3]);
        Assert.Equal(new PathStep('L', X: 8, Y: 4), steps[4]);
        Assert.Equal('Z', steps[5].Kind);
        Assert.Equal(new PathStep('M', X: 10, Y: 10), steps[6]);
    }

    [Fact]
    public void CompactNumbersAreSplit()
    {
        // Android-style data: "-.42.726" is two numbers and "1e-2" is one.
        var steps = PathData.Parse("M0 0L-.42.726l1e-2-1").Steps;
        Assert.Equal(-0.42, steps[1].X, 6);
        Assert.Equal(0.726, steps[1].Y, 6);
        Assert.Equal(-0.41, steps[2].X, 6);
        Assert.Equal(-0.274, steps[2].Y, 6);
    }

    [Fact]
    public void SmoothCurvesReflectTheLastControlPoint()
    {
        var steps = PathData.Parse("M 0 0 C 0 1 1 1 1 0 S 2 -1 2 0").Steps;
        Assert.Equal(1, steps[2].X1, 6);
        Assert.Equal(-1, steps[2].Y1, 6);
    }

    [Fact]
    public void QuadraticsBecomeCubicsWithTheSameEnds()
    {
        var step = PathData.Parse("M 0 0 Q 1 1 2 0").Steps[1];
        Assert.Equal('C', step.Kind);
        Assert.Equal(2, step.X, 6);
        Assert.Equal(2.0 / 3, step.X1, 6);
        Assert.Equal(2.0 / 3, step.Y1, 6);
    }

    [Theory]
    [InlineData(false, true)]
    [InlineData(true, true)]
    [InlineData(false, false)]
    public void ArcsEndExactlyAtTheirEndPoint(bool large, bool sweep)
    {
        var steps = PathData.Arc(0, 0, 5, 5, 0, large, sweep, 10, 0).ToList();
        Assert.NotEmpty(steps);
        Assert.All(steps, s => Assert.Equal('C', s.Kind));
        Assert.Equal(10, steps[^1].X, 6);
        Assert.Equal(0, steps[^1].Y, 6);
        // Every segment's end lies on the circle around (5, 0).
        Assert.All(steps, s => Assert.Equal(5, Math.Sqrt((s.X - 5) * (s.X - 5) + s.Y * s.Y), 4));
    }

    [Fact]
    public void ArcsWithTooSmallRadiiAreScaledUp()
    {
        var steps = PathData.Arc(0, 0, 1, 1, 0, false, true, 10, 0).ToList();
        Assert.Equal(10, steps[^1].X, 6);
    }

    [Fact]
    public void BadDataThrows()
    {
        Assert.Throws<FormatException>(() => PathData.Parse("0 0 L 1 1"));
        Assert.Throws<FormatException>(() => PathData.Parse("M 0 0 B 1 1"));
    }
}
