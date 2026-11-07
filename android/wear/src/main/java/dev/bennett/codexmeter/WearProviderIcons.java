package dev.bennett.codexmeter;

/**
 * Provider marks on the watch. The drawables are the phone's {@code ic_provider_*} vectors
 * (Simple Icons and vendor marks, see the phone copies for sources) with the theme tint removed,
 * so they render white on the watch's dark surfaces and tint cleanly in complications.
 */
final class WearProviderIcons {
    private WearProviderIcons() {
    }

    /** The mark for {@code providerId}, or the app's own glyph for an id this build lacks. */
    static int forId(String providerId) {
        if (providerId == null) return R.drawable.ic_notification;
        switch (providerId) {
            case "amp": return R.drawable.ic_provider_amp;
            case "antigravity": return R.drawable.ic_provider_antigravity;
            case "augment": return R.drawable.ic_provider_augment;
            case "chutes": return R.drawable.ic_provider_chutes;
            case "claude_code": return R.drawable.ic_provider_claude_code;
            case "cline": return R.drawable.ic_provider_cline;
            case "codebuff": return R.drawable.ic_provider_codebuff;
            case "codex": return R.drawable.ic_provider_codex;
            case "copilot": return R.drawable.ic_provider_copilot;
            case "cursor": return R.drawable.ic_provider_cursor;
            case "devin": return R.drawable.ic_provider_devin;
            case "factory": return R.drawable.ic_provider_factory;
            case "grok": return R.drawable.ic_provider_grok;
            case "kilo": return R.drawable.ic_provider_kilo;
            case "kimi": return R.drawable.ic_provider_kimi;
            case "kiro": return R.drawable.ic_provider_kiro;
            case "minimax": return R.drawable.ic_provider_minimax;
            case "opencode_go": return R.drawable.ic_provider_opencode_go;
            case "openrouter": return R.drawable.ic_provider_openrouter;
            case "synthetic": return R.drawable.ic_provider_synthetic;
            case "v0": return R.drawable.ic_provider_v0;
            case "warp": return R.drawable.ic_provider_warp;
            case "windsurf": return R.drawable.ic_provider_windsurf;
            case "zai": return R.drawable.ic_provider_zai;
            default: return R.drawable.ic_notification;
        }
    }
}
