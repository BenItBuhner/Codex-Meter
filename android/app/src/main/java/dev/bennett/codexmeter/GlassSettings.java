package dev.bennett.codexmeter;

import androidx.fragment.app.FragmentManager;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SeslSwitchPreferenceScreen;
import androidx.preference.TwoStatePreference;
import dev.bennett.codexmeter.glass.GlassModel;
import dev.bennett.codexmeter.glass.GlassModel.Row;
import dev.oneuiproject.oneui.preference.HorizontalRadioPreference;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Settings for the Liquid Glass design. Each page's behavior is the app's own settings code
 * ({@link SettingsActivity.SettingsFragment}, the same code the One UI pages run): the fragment
 * is attached out of sight and its preferences are the model. Glassé's list rows, switches and
 * pickers show those preferences and drive them the way their One UI rows would.
 */
final class GlassSettings {
    /** Root rows' icon tiles, iOS Settings style: icon name and tile color. */
    private static final Map<String, String[]> ICONS = new HashMap<>();

    static {
        ICONS.put("settings_accounts", new String[] {"person2", "#007aff"});
        ICONS.put("settings_providers", new String[] {"tabs", "#5856d6"});
        ICONS.put("settings_now_bar", new String[] {"waveform", "#ff9500"});
        ICONS.put("settings_notifications", new String[] {"bell", "#ff3b30"});
        ICONS.put("settings_lock_widgets", new String[] {"lock", "#34c759"});
        ICONS.put("settings_refresh_usage", new String[] {"refresh", "#007aff"});
        ICONS.put("settings_reset_credits", new String[] {"bolt", "#ff9f0a"});
        ICONS.put("settings_appearance", new String[] {"paintbrush", "#5856d6"});
        ICONS.put("settings_updates", new String[] {"download", "#8e8e93"});
        ICONS.put("settings_transfer", new String[] {"arrows", "#34c759"});
        ICONS.put("settings_privacy", new String[] {"hand", "#007aff"});
        ICONS.put("about_codex_meter", new String[] {"info", "#8e8e93"});
    }

    private final androidx.appcompat.app.AppCompatActivity activity;
    private final Map<String, SettingsActivity.SettingsFragment> fragments = new HashMap<>();

    GlassSettings(androidx.appcompat.app.AppCompatActivity activity) {
        this.activity = activity;
    }

    private SettingsActivity.SettingsFragment fragment(String page) {
        String id = SettingsActivity.normalizePage(page);
        SettingsActivity.SettingsFragment f = fragments.get(id);
        if (f != null) {
            return f;
        }
        FragmentManager fm = activity.getSupportFragmentManager();
        f = (SettingsActivity.SettingsFragment) fm.findFragmentByTag("glass:" + id);
        if (f == null) {
            f = SettingsActivity.SettingsFragment.newInstance(id);
            fm.beginTransaction().add(R.id.glass_fragments, f, "glass:" + id).commitNowAllowingStateLoss();
        }
        fragments.put(id, f);
        return f;
    }

    GlassModel.Page page(String id) {
        String page = SettingsActivity.normalizePage(id);
        if (!page.equals(id) && !"root".equals(id)) {
            return null;
        }
        SettingsActivity.SettingsFragment f = fragment(page);
        PreferenceScreen screen = f.getPreferenceScreen();
        GlassModel.Page out = new GlassModel.Page(page, SettingsActivity.pageTitle(page));
        if (screen == null) {
            return out;
        }
        GlassModel.Section loose = null;
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference pref = screen.getPreference(i);
            if (!pref.isVisible()) {
                continue;
            }
            if (pref instanceof PreferenceCategory) {
                GlassModel.Section section = new GlassModel.Section(text(pref.getTitle()));
                addRows((PreferenceGroup) pref, section, page);
                if (section.rows.isEmpty() && pref.getSummary() != null) {
                    section.footer = text(pref.getSummary());
                }
                out.sections.add(section);
                loose = null;
            } else {
                if (loose == null) {
                    loose = new GlassModel.Section(null);
                    out.sections.add(loose);
                }
                Row row = row(pref, page);
                if (row != null) {
                    loose.rows.add(row);
                }
            }
        }
        return out;
    }

    private void addRows(PreferenceGroup group, GlassModel.Section section, String page) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            if (!pref.isVisible()) {
                continue;
            }
            if (pref instanceof PreferenceCategory) {
                addRows((PreferenceGroup) pref, section, page);
                continue;
            }
            Row row = row(pref, page);
            if (row != null) {
                section.rows.add(row);
            }
        }
    }

    private Row row(Preference pref, String page) {
        String key = pref.getKey() != null ? pref.getKey() : "pref" + System.identityHashCode(pref);
        Row r;
        if (pref instanceof HorizontalRadioPreference) {
            r = new Row(Row.Kind.SEGMENTED, key);
            r.entries = activity.getResources().getStringArray(R.array.preferences_darkmode_entries);
            String[] values = activity.getResources().getStringArray(R.array.preferences_darkmode_values);
            String value = ((HorizontalRadioPreference) pref).getValue();
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(value)) {
                    r.selected = i;
                }
            }
        } else if (pref instanceof SeslSwitchPreferenceScreen) {
            r = new Row(Row.Kind.SWITCH_LINK, key);
            r.checked = ((TwoStatePreference) pref).isChecked();
        } else if (pref instanceof TwoStatePreference) {
            r = new Row(Row.Kind.SWITCH, key);
            r.checked = ((TwoStatePreference) pref).isChecked();
        } else if (pref instanceof ListPreference) {
            ListPreference list = (ListPreference) pref;
            r = new Row(Row.Kind.CHOICE, key);
            CharSequence[] entries = list.getEntries();
            if (entries != null) {
                r.entries = new String[entries.length];
                for (int i = 0; i < entries.length; i++) {
                    r.entries[i] = String.valueOf(entries[i]);
                }
            }
            r.selected = list.getValue() == null ? -1 : list.findIndexOfValue(list.getValue());
        } else if (pref instanceof EditTextPreference) {
            EditTextPreference field = (EditTextPreference) pref;
            r = new Row(Row.Kind.FIELD, key);
            r.value = field.getText() == null ? "" : field.getText();
            r.hint = text(pref.getTitle());
            r.onText = value -> {
                if (field.callChangeListener(value)) {
                    field.setText(value);
                }
            };
        } else if (pref instanceof SeekBarPreference) {
            SeekBarPreference bar = (SeekBarPreference) pref;
            r = new Row(Row.Kind.SLIDER, key);
            int min = bar.getMin();
            int span = Math.max(1, bar.getMax() - min);
            r.fraction = (bar.getValue() - min) / (double) span;
            r.onSlide = v -> {
                int value = min + (int) Math.round(Math.max(0, Math.min(1, v)) * span);
                if (bar.isEnabled() && value != bar.getValue() && bar.callChangeListener(value)) {
                    bar.setValue(value);
                }
            };
        } else {
            boolean clickable = pref.getOnPreferenceClickListener() != null || pref.getIntent() != null
                    || pref.getFragment() != null;
            if (!clickable) {
                r = new Row(Row.Kind.INFO, key);
            } else if (isAction(pref)) {
                r = new Row(Row.Kind.ACTION, key);
                r.destructive = isDestructive(pref);
            } else {
                r = new Row(Row.Kind.LINK, key);
            }
        }
        if (r.kind == Row.Kind.LINK) {
            r.link = linkOf(key);
        }
        r.title = text(pref.getTitle());
        if (r.title == null) {
            r.title = "";
        }
        r.summary = text(pref.getSummary());
        if (r.kind == Row.Kind.CHOICE && r.summary != null && r.selected >= 0 && r.entries != null
                && r.selected < r.entries.length && r.summary.equals(r.entries[r.selected])) {
            // The row already shows the choice on its right.
            r.summary = null;
        }
        if (r.kind == Row.Kind.SEGMENTED) {
            r.title = "";
        }
        r.enabled = pref.isEnabled();
        String[] icon = "root".equals(page) ? ICONS.get(key) : null;
        if (icon != null) {
            r.icon = icon[0];
            r.color = icon[1];
            if (r.summary != null && "root".equals(page)) {
                // iOS Settings keeps the first page to one line a row; a short state goes on the right.
                r.value = r.summary.length() <= 18 ? r.summary : null;
                r.summary = null;
            }
        }
        return r;
    }

    /** Rows that do something here (send, start, check, export...) rather than open a page. */
    private static boolean isAction(Preference pref) {
        String title = text(pref.getTitle());
        if (title == null) {
            return false;
        }
        String t = title.toLowerCase(Locale.ROOT);
        for (String verb : new String[] {"send ", "start ", "check ", "export ", "import ", "clear ",
                "sign out", "remove ", "reset ", "use ", "add ", "test ", "stop "}) {
            if (t.startsWith(verb)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDestructive(Preference pref) {
        String t = String.valueOf(pref.getTitle()).toLowerCase(Locale.ROOT);
        return t.startsWith("clear ") || t.startsWith("sign out") || t.startsWith("remove ")
                || t.startsWith("delete ");
    }

    private static String text(CharSequence value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private Preference find(String page, String key) {
        SettingsActivity.SettingsFragment f = fragment(page);
        return f.getPreferenceScreen() == null ? null : f.getPreferenceScreen().findPreference(key);
    }

    void toggle(String page, String key, boolean on) {
        Preference pref = find(page, key);
        if (pref instanceof TwoStatePreference && pref.isEnabled()) {
            TwoStatePreference two = (TwoStatePreference) pref;
            if (two.isChecked() != on && two.callChangeListener(on)) {
                two.setChecked(on);
            }
        }
    }

    void choose(String page, String key, int index) {
        Preference pref = find(page, key);
        if (pref instanceof ListPreference) {
            ListPreference list = (ListPreference) pref;
            CharSequence[] values = list.getEntryValues();
            if (values == null || index < 0 || index >= values.length) {
                return;
            }
            String value = values[index].toString();
            if (!value.equals(list.getValue()) && list.callChangeListener(value)) {
                list.setValue(value);
            }
        } else if (pref instanceof HorizontalRadioPreference) {
            String[] values = activity.getResources().getStringArray(R.array.preferences_darkmode_values);
            if (index < 0 || index >= values.length) {
                return;
            }
            HorizontalRadioPreference radio = (HorizontalRadioPreference) pref;
            if (!values[index].equals(radio.getValue()) && radio.callChangeListener(values[index])) {
                radio.setValue(values[index]);
            }
        }
    }

    /** The glass page a Settings row opens, so search can look inside it; null when unknown. */
    private static String linkOf(String key) {
        if (key == null) {
            return null;
        }
        switch (key) {
            case "settings_providers":
                return GlassScreens.PREFIX + "tabs";
            case "settings_lock_widgets":
                return GlassScreens.PREFIX + "lock_widgets";
            case "about_codex_meter":
                return GlassScreens.PREFIX + "about";
            default:
                break;
        }
        if (key.startsWith("settings_")) {
            String page = key.substring("settings_".length());
            if (page.equals(SettingsActivity.normalizePage(page))) {
                return page;
            }
        }
        return null;
    }

    void press(String page, String key) {
        Preference pref = find(page, key);
        if (pref == null || !pref.isEnabled()) {
            return;
        }
        Preference.OnPreferenceClickListener click = pref.getOnPreferenceClickListener();
        if (click != null) {
            click.onPreferenceClick(pref);
        } else if (pref.getIntent() != null) {
            activity.startActivity(pref.getIntent());
        }
    }
}
