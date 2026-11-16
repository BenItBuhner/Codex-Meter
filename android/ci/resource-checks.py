#!/usr/bin/env python3
"""Structural checks on the manifests and resources, the parts of the app no JVM test can load.

Each rule states a platform constraint or a ban, not the current shape of the code, so a harmless
refactor never trips it. Usage: resource-checks.py ANDROID_DIR
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent)
ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "dev.bennett.codexmeter"
failures = []


def fail(message):
    failures.append(message)


def manifest_components(*manifests):
    names = set()
    for manifest in manifests:
        if not manifest.exists():
            continue
        app = ET.parse(manifest).getroot().find("application")
        for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
            for node in app.findall(tag):
                name = node.get(ANDROID + "name", "")
                names.add(PACKAGE + name if name.startswith(".") else name)
    return names


COMPONENT_BASES = {
    "Activity", "AppCompatActivity", "ComponentActivity", "FragmentActivity", "Service", "JobService",
    "BroadcastReceiver", "AppWidgetProvider", "TileService", "ComplicationDataSourceService",
    "SuspendingComplicationDataSourceService", "WearableListenerService", "ContentProvider",
}


def check_components_declared(module):
    """Every concrete Activity, Service, receiver and widget in a module is in its manifest.

    A component that is not declared compiles and passes every test, then silently never runs.
    """
    src = ROOT / module / "src"
    parents = {}
    abstract = set()
    for java in src.glob("*/java/**/*.java"):
        text = java.read_text(encoding="utf-8")
        for m in re.finditer(r"^(?:public\s+)?((?:abstract|final)\s+)*class\s+(\w+)(?:<[^>]*>)?\s+extends\s+([\w.]+)",
                             text, re.M):
            name, parent = m.group(2), m.group(3).split(".")[-1]
            parents[name] = (parent, java)
            if m.group(1) and "abstract" in m.group(1):
                abstract.add(name)

    def is_component(name, seen=()):
        parent = parents.get(name, (None,))[0]
        if parent is None or name in seen:
            return False
        return parent in COMPONENT_BASES or is_component(parent, seen + (name,))

    declared = manifest_components(src / "main/AndroidManifest.xml", src / "debug/AndroidManifest.xml")
    for name, (_, java) in sorted(parents.items()):
        if name in abstract or not is_component(name):
            continue
        package = re.search(r"^package\s+([\w.]+);", java.read_text(encoding="utf-8"), re.M).group(1)
        if package + "." + name not in declared:
            fail(f"{module}: {package}.{name} is a component but no manifest declares it")


def check_wear_tiles():
    """Samsung's tile picker needs a modular footprint and picker categories on every tile."""
    root = ET.parse(ROOT / "wear/src/main/AndroidManifest.xml").getroot()
    for service in root.findall("application/service"):
        actions = [a.get(ANDROID + "name") for a in service.findall("intent-filter/action")]
        if "androidx.wear.tiles.action.BIND_TILE_PROVIDER" not in actions:
            continue
        name = service.get(ANDROID + "name")
        meta = {m.get(ANDROID + "name"): m.get(ANDROID + "value") or m.get(ANDROID + "resource")
                for m in service.findall("meta-data")}
        if meta.get("com.samsung.android.wearable.tiles.LAYOUT_TYPE") not in ("2x1", "2x2"):
            fail(f"{name}: tile needs a Samsung LAYOUT_TYPE of 2x1 or 2x2")
        if meta.get("com.samsung.android.wearable.tiles.category") != \
                "com.samsung.android.wearable.tiles.category.CONNECTED_DEVICES":
            fail(f"{name}: tile needs the CONNECTED_DEVICES category")
        if meta.get("com.google.android.clockwork.tiles.MULTI_INSTANCES_SUPPORTED") != "true":
            fail(f"{name}: tile should allow multiple instances")
        for suffix in ("CATEGORY_A", "CATEGORY_B", "CATEGORY_C"):
            if "com.samsung.android.wearable.tiles.category." + suffix not in meta:
                fail(f"{name}: tile is missing picker category {suffix}")


def check_lock_widgets():
    """The lock picker offers the two dial widgets; retired ones stay installed but hidden."""
    xml = ROOT / "app/src/main/res/xml"
    for info in sorted(xml.glob("samsung_lock_*_info.xml")):
        if info.name.endswith("_oneui_info.xml"):
            continue  # One UI's own lock-screen picker reads these; the category belongs to AOSP keyguard.
        text = info.read_text(encoding="utf-8")
        if 'android:widgetCategory="0x2000"' not in text:
            fail(f"{info.name}: lock widgets need the keyguard category 0x2000")
    for kept in ("five_hour", "dials_wide"):
        text = (xml / f"samsung_lock_{kept}_info.xml").read_text(encoding="utf-8")
        if "hide_from_picker" in text or "LockWidgetConfigActivity" not in text:
            fail(f"samsung_lock_{kept}_info.xml: must be offered in the picker with its setup screen")
    for retired in ("square", "wide", "rings_square", "rings_wide", "dials_square", "bars_square", "bars_wide",
                    "weekly"):
        if "hide_from_picker" not in (xml / f"samsung_lock_{retired}_info.xml").read_text(encoding="utf-8"):
            fail(f"samsung_lock_{retired}_info.xml: retired widgets stay hidden from the picker")


def check_icons_and_logos():
    """SystemUI loads these vectors with its own parser and draws them on any background."""
    res = ROOT / "app/src/main/res"
    for density in ("mdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        for layer in ("bg", "fg"):
            phone = res / f"drawable-{density}/codex_meter_adaptive_{layer}.png"
            watch = ROOT / f"wear/src/main/res/drawable-{density}/codex_meter_adaptive_{layer}.png"
            if phone.read_bytes() != watch.read_bytes():
                fail(f"{watch.relative_to(ROOT)} differs from the phone launcher icon")
    if (res / "drawable-night/ic_codex_logo.xml").exists():
        fail("drawable-night/ic_codex_logo.xml: Samsung extras can't rely on night-qualified logos")
    for name in ("ic_codex_logo", "ic_codex_logo_dark", "ic_notification", "ic_codex_logo_on_accent"):
        text = (res / f"drawable/{name}.xml").read_text(encoding="utf-8")
        if 'android:pathData="M19.503 0H4.496' in text:
            fail(f"{name}.xml: the logo must stay transparent, not on a baked square")
        # The compact SVG form ("M8.086.457a6.1 6.1 0 01...") breaks SystemUI's VectorDrawable parser.
        if 'android:pathData="M8.086.457' in text:
            fail(f"{name}.xml: keep the logo path in its spaced form so SystemUI can parse it")


BANNED = [
    # Model catalogs are fetched anonymously, so they never see a provider token.
    ("app/src/main/java/dev/bennett/codexmeter/ModelDiscoveryStore.java", "Authorization",
     "model discovery must stay anonymous"),
    ("app/src/main/java/dev/bennett/codexmeter/ModelSpeedStore.java", "Authorization",
     "model speed lookups must stay anonymous"),
    ("app/src/main", "thatjoshguy67/Codex-Meter", "updates come from this repository, not the old fork"),
    ("app/build.gradle.kts", "thatjoshguy67/Codex-Meter", "updates come from this repository, not the old fork"),
    ("app/src/main", "com.samsung.systemui.permission.FACE_WIDGET", "lock widgets don't need FACE_WIDGET"),
    ("wear/src/main", "seedDemoSnapshot", "the watch app never ships demo data"),
    ("wear/src/main", "wear_load_demo", "the watch app never ships demo data"),
]


def check_banned():
    for where, needle, why in BANNED:
        path = ROOT / where
        files = [path] if path.is_file() else [p for p in path.rglob("*") if p.is_file()]
        for f in files:
            try:
                if needle in f.read_text(encoding="utf-8"):
                    fail(f"{f.relative_to(ROOT)}: contains {needle!r}; {why}")
            except UnicodeDecodeError:
                continue


check_components_declared("app")
check_components_declared("wear")
check_wear_tiles()
check_lock_widgets()
check_icons_and_logos()
check_banned()

if failures:
    for message in failures:
        print("resource-checks: " + message, file=sys.stderr)
    sys.exit(1)
print("Manifests, tiles, lock widgets, icons and banned patterns check out.")
