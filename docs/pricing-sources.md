# Pricing sources for value estimates

The **Estimated value** card on a provider's history page, and the dollar figures on its charts,
come from the tables in `android/shared/src/main/java/dev/bennett/codexmeter/PlanPricing.java`
(Android, and the reference the desktop and iOS ports copy) and
`ios/CodexMeterCore/Sources/CodexMeterCore/PlanPricing.swift` (iOS and macOS, Codex only).

Every figure is **monthly API value at full use**: what a whole month of the plan's allowance
would cost at API list prices if all of it went to one model. It is an estimate, not a billing
statement, and the app labels it that way. Weekly figures are the month divided by four, and the
5-hour figure is one sixth of a week (an app heuristic, not a sourced number).

Rules:

- Where sources disagree, the **newest dated figure** is used. The others are kept below.
- A plan with no published or estimated figure shows **Unknown**. Nothing is extrapolated.
- Each figure in code carries its source URL and data date.
- Cursor is not estimated: the app shows the included usage Cursor itself reports.

Last checked: **2026-11-14**.

## Sources

| Id | Source | URL | Date |
|----|--------|-----|------|
| BL | BenchLeader, "What an AI coding subscription is worth". Allowances from the Real API Pricing dataset (MIT). Assumes 97% cache reads, 2.5% fresh input, 0.5% output. Dates are per row. | https://www.benchleader.com/coding-plans | page data 2026-11-14 |
| AH-X | Adam Holter, "Subscription tier list: how much API usage $1 of plan buys". Dollars of API usage per $1 of plan; figure = ratio × plan price. Plan shares from Priors, 2026-10-28. Confidence: low for Sonnet, Haiku, Gemini 4; medium for Opus, Fable; high for the rest. | https://x.com/AdamHoltererer/status/2115827734223974762 | 2026-10-29 |
| AH-B | Adam Holter, "Token min-maxing" (cites SemiAnalysis, June 2026; marked historical) | https://adam.holter.com/token-min-maxing-how-to-token-max-without-cost-maxing/ | updated 2026-10-02 |
| OAI | OpenAI, Codex plans and pricing | https://learn.chatgpt.com/docs/pricing | read 2026-11-14 |

The X post could not be fetched automatically (x.com blocks it). Its ratios were read from the
post's image and passed on in the request; they are used as given.

## Figures used in the app

### Claude Code

| Plan | Price | Headline (used) | Per-model rows | Source, date |
|------|-------|-----------------|----------------|--------------|
| Pro | $20 | **$1,200** (Opus 5.5) | Opus 5.5 $1,200 · Sonnet 5.5 $1,203 · Haiku 5.5 $877 | BL; Opus 2026-11-07, Sonnet and Haiku 2026-09-27 |
| Max | $100 | **$7,224** (Opus 5.5) | Opus 5.5 $7,224 · Sonnet 5.5 $7,224 · Haiku 5.5 $5,263 | BL, 2026-10-03 |
| Ultra (was Max 20x) | $200 | **$13,200** (Opus 5.5, 66 × $200) | Opus 5.5 $13,200 · Sonnet 5.5 $13,200 · Haiku 5.5 $9,400 · Fable 5.1 $1,280 | AH-X, 2026-10-29 |
| Team, Enterprise, Free | — | no card | — | priced per seat or no allowance |

Alternatives kept, not used (older):

- Ultra, BL 2026-09-27: Opus 5.5 $12,426, Sonnet 5.5 $12,426, Haiku 5.5 $9,054. BL notes Ultra
  has a 10x Pro weekly limit and no 5-hour limit once verified.
- Max 20x ($200), SemiAnalysis via AH-B (June 2026, historical): about $8,000. This was the
  previous app figure for Ultra; Pro $400 and Max $2,000 were derived from it.
- Team Premium seat ($125), BL 2026-10-06: Opus 5.5 $4,966. Not used: the app cannot tell a
  Team seat's tier.

### Codex (ChatGPT)

OpenAI (OAI) sells Pro at **$100, $200 and $500** a month. Verbatim: "Pro plans currently have no
five-hour limit", and "Ultrafast mode access on Pro $500". Fast mode uses included usage at 2.5x,
Ultrafast at 8x. OpenAI publishes no dollar value or Plus multiple for the Pro tiers.

| Plan | Price | Headline (used) | Source, date |
|------|-------|-----------------|--------------|
| Plus | $20 | **$299** (GPT-6.1 Sol) | BL, 2026-11-09 |
| Pro 100 (was Pro 5x, `prolite`) | $100 | **Unknown** | BL: "No usable figure", 2026-11-14 |
| Pro 200 (was Pro 20x, `pro`) | $200 | **$5,604** (GPT-6 Astra) | BL, 2026-09-21, measured on the former Pro 20x tier |
| Pro 500 | $500 | **Unknown** | BL: "No usable figure", 2026-11-14 |
| Free, Go, Business, Enterprise | — | no card | — |

Plus per-model rows (newest per model):

| Model | Value | Source, date | Alternative |
|-------|-------|--------------|-------------|
| GPT-6.1 Sol | $299 | BL, 2026-11-09 | AH-X 2026-10-29: 24 × $20 = $480 |
| GPT-6 Astra | $640 | AH-X 2026-10-29 (32 × $20) | BL 2026-09-11: $234 |
| GPT-6 Luna | $380 | AH-X 2026-10-29 (19 × $20) | BL 2026-09-28: $135 |
| GPT-6.1 Terra | $360 | AH-X 2026-10-29 (18 × $20; his estimate from Codex limits) | — |
| GPT-6 Sol | $323 | BL, 2026-09-24 | — |

Notes from BL (2026-11-14), not confirmed on an OpenAI page this session:

- OpenAI doubled the Pro 100 allowance on 2026-10-29. Because of this, the old Pro 5x figure
  ($3,500) is not carried over, and Pro 100 stays Unknown.
- From December 2026, Plus, Pro and Business share one token-based allowance across ChatGPT and
  Codex. The table will need a recheck then.
- 6 discretionary resets in the 28 days to 2026-11-14. Holter (AH-X) notes resets: OpenAI 12 in
  30 days, Anthropic 0. Resets are not counted in any figure.
- OpenAI's "About ChatGPT Pro tiers" and "How resets work in Codex" help articles
  (help.openai.com/en/articles/9793128, /20001498) could not be fetched from this environment.

Previous app figures (community estimates, undated): Plus $700, Pro 5x $3,500, Pro 20x $14,000.
The $14,000 Pro figure is SemiAnalysis, June 2026 (via AH-B, historical).

### Cursor

The app shows Cursor's own `planUsage.limit` (usage included per cycle at API prices). List prices
for the "x the monthly cost" row: Plus $8, Pro $20, Premium $30, Pro+ $60, Super $100,
Ultra $200. BL has no usable dollar figure for Cursor plans.

### Not in the app (no provider support yet)

For reference only, from AH-X (2026-10-29) and BL:

- Grok 4.7 on X Premium+: $80 per $1 (SuperGrok 1/18).
- Gemini 3.8 Flash on Google AI Pro: $25 per $1 (AH-X); BL $285/month (2026-09-27). Google AI
  Ultra 5x $1,423, Ultra 20x $5,691 (BL, 2026-09-27). Gemini 4 Argon on Google AI Pro: $2.8 per
  $1 (AH-X).
- GLM 5.3 on GLM Coding Pro: $9.6 per $1 (AH-X).
- OpenCode Go ($10): up to $187 depending on model (BL).
- GitHub Copilot (published by GitHub, as credits): Pro $15, Pro+ $70, Max $200.

## API list prices cited (per million tokens, AH-B)

GPT-6.1 Sol $2 in / $10 out, $0.10 cache read. GPT-6 Astra $10 in / $50 out. Opus 5.5 cache read
$0.20. Fable 5.1 cache read $0.25. The app does not compute per-token costs.

## Updating

Change the figure, its source and its data date in both `PlanPricing` files and in this table,
keep the replaced figure under "Alternatives", and update the pricing tests in
`android/tests/ParserSelfTest.java` and
`ios/CodexMeterCore/Tests/CodexMeterCoreTests/HistoryAnalyticsAndDiagnosticsTests.swift`.
The desktop ports (`windows/src/CodexMeter.Core/Models/PlanPricing.cs`, shared by Linux) should
copy the Android table.
