#!/usr/bin/env bash
# Generates the Soft Minimalism stroke icon set (24dp grid, 1.8dp stroke,
# round caps/joins) as Android vector drawables. Stroke color is white;
# icons are always tinted at runtime via Icon(painter, tint = ...).
set -e
cd "$(dirname "$0")/.."
DIR="app/src/main/res/drawable"
mkdir -p "$DIR"

mk() {
  name="$1"; body="$2"
  cat > "$DIR/$name.xml" <<XML
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
$body
</vector>
XML
}

P='android:strokeColor="#FFFFFF" android:strokeWidth="1.8" android:strokeLineCap="round" android:strokeLineJoin="round"'

mk ic_soft_close "    <path android:pathData=\"M18 6L6 18M6 6l12 12\" $P/>"

mk ic_soft_warning "    <path android:pathData=\"M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0Z\" $P/>
    <path android:pathData=\"M12 9v4\" $P/>
    <path android:pathData=\"M12 17h.01\" $P/>"

mk ic_soft_mic "    <path android:pathData=\"M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z\" $P/>
    <path android:pathData=\"M19 10v2a7 7 0 0 1-14 0v-2\" $P/>
    <path android:pathData=\"M12 19v3\" $P/>"

mk ic_soft_mic_off "    <path android:pathData=\"M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z\" $P/>
    <path android:pathData=\"M19 10v2a7 7 0 0 1-14 0v-2\" $P/>
    <path android:pathData=\"M12 19v3\" $P/>
    <path android:pathData=\"M3 3l18 18\" $P/>"

mk ic_soft_volume "    <path android:pathData=\"M11 5 6 9H2v6h4l5 4V5Z\" $P/>
    <path android:pathData=\"M15.54 8.46a5 5 0 0 1 0 7.07\" $P/>
    <path android:pathData=\"M19.07 4.93a10 10 0 0 1 0 14.14\" $P/>"

mk ic_soft_hearing "    <path android:pathData=\"M6 8.5a6.5 6.5 0 1 1 13 0c0 6-6 6-6 10a3.5 3.5 0 1 1-7 0\" $P/>
    <path android:pathData=\"M15 8.5a2.5 2.5 0 0 0-5 0v1a2 2 0 1 1 0 4\" $P/>"

mk ic_soft_play "    <path android:pathData=\"M8 5.5v13l11-6.5L8 5.5Z\" $P/>"

mk ic_soft_siren "    <path android:pathData=\"M7 16v-3.5a5 5 0 0 1 10 0V16\" $P/>
    <path android:pathData=\"M5.5 16h13A1.5 1.5 0 0 1 20 17.5V20a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-2.5A1.5 1.5 0 0 1 5.5 16Z\" $P/>
    <path android:pathData=\"M12 2.5v2\" $P/>
    <path android:pathData=\"M5.6 4.8 7 6.2\" $P/>
    <path android:pathData=\"M18.4 4.8 17 6.2\" $P/>"

mk ic_soft_bell "    <path android:pathData=\"M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9\" $P/>
    <path android:pathData=\"M10.3 21a1.94 1.94 0 0 0 3.4 0\" $P/>
    <path android:pathData=\"M4.6 2.4C3.6 3.6 2.9 5 2.6 6.6\" $P/>
    <path android:pathData=\"M19.4 2.4c1 1.2 1.7 2.6 2 4.2\" $P/>"

mk ic_soft_chevron_down "    <path android:pathData=\"m6 9 6 6 6-6\" $P/>"

mk ic_soft_chevron_up "    <path android:pathData=\"m18 15-6-6-6 6\" $P/>"

mk ic_soft_chevron_right "    <path android:pathData=\"m9 18 6-6-6-6\" $P/>"

mk ic_soft_chevron_left "    <path android:pathData=\"m15 18-6-6 6-6\" $P/>"

mk ic_soft_arrow_right "    <path android:pathData=\"M5 12h14\" $P/>
    <path android:pathData=\"m12 5 7 7-7 7\" $P/>"

mk ic_soft_arrow_left "    <path android:pathData=\"M19 12H5\" $P/>
    <path android:pathData=\"m12 19-7-7 7-7\" $P/>"

mk ic_soft_check "    <path android:pathData=\"M20 6 9 17l-5-5\" $P/>"

mk ic_soft_cancel "    <path android:pathData=\"M12 12m-9 0a9 9 0 1 1 18 0a9 9 0 1 1-18 0\" $P/>
    <path android:pathData=\"m15 9-6 6\" $P/>
    <path android:pathData=\"m9 9 6 6\" $P/>"

mk ic_soft_call_end "    <group android:scaleX=\"0.8\" android:scaleY=\"0.8\" android:pivotX=\"12\" android:pivotY=\"12\">
        <group android:rotation=\"135\" android:pivotX=\"12\" android:pivotY=\"12\">
            <path android:pathData=\"M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92Z\" $P/>
        </group>
    </group>"

mk ic_soft_phone "    <path android:pathData=\"M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92Z\" $P/>"

mk ic_soft_phone_off "    <path android:pathData=\"M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92Z\" $P/>
    <path android:pathData=\"M3 3l18 18\" $P/>"

mk ic_soft_bluetooth "    <path android:pathData=\"m7 7 10 10-5 5V2l5 5L7 17\" $P/>"

mk ic_soft_wifi "    <path android:pathData=\"M2 8.82a15 15 0 0 1 20 0\" $P/>
    <path android:pathData=\"M5 12.29a10 10 0 0 1 14 0\" $P/>
    <path android:pathData=\"M8.5 15.76a5 5 0 0 1 7 0\" $P/>
    <path android:pathData=\"M12 19.5h.01\" $P/>"

mk ic_soft_swap "    <path android:pathData=\"M8 3 4 7l4 4\" $P/>
    <path android:pathData=\"M4 7h16\" $P/>
    <path android:pathData=\"m16 21 4-4-4-4\" $P/>
    <path android:pathData=\"M20 17H4\" $P/>"

mk ic_soft_shield "    <path android:pathData=\"M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1Z\" $P/>"

mk ic_soft_lock "    <path android:pathData=\"M3 13a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7Z\" $P/>
    <path android:pathData=\"M7 11V7a5 5 0 0 1 10 0v4\" $P/>"

mk ic_soft_search "    <path android:pathData=\"M11 11m-7.5 0a7.5 7.5 0 1 1 15 0a7.5 7.5 0 1 1-15 0\" $P/>
    <path android:pathData=\"m21 21-4.7-4.7\" $P/>"

mk ic_soft_radio "    <path android:pathData=\"M8 8h8a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2Z\" $P/>
    <path android:pathData=\"M12 8V3.5\" $P/>
    <path android:pathData=\"M12 2.5h.01\" $P/>
    <path android:pathData=\"M12 14.5m-2.5 0a2.5 2.5 0 1 1 5 0a2.5 2.5 0 1 1-5 0\" $P/>"

mk ic_soft_pause "    <path android:pathData=\"M9.5 5v14\" $P/>
    <path android:pathData=\"M14.5 5v14\" $P/>"

mk ic_soft_headset "    <path android:pathData=\"M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3\" $P/>
    <path android:pathData=\"M18 19v.5a2.5 2.5 0 0 1-2.5 2.5H13\" $P/>"

mk ic_soft_eq "    <path android:pathData=\"M4 10v4\" $P/>
    <path android:pathData=\"M8 7v10\" $P/>
    <path android:pathData=\"M12 4v16\" $P/>
    <path android:pathData=\"M16 7v10\" $P/>
    <path android:pathData=\"M20 10v4\" $P/>"

mk ic_soft_moon "    <path android:pathData=\"M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79Z\" $P/>"

mk ic_soft_sun "    <path android:pathData=\"M12 12m-4 0a4 4 0 1 1 8 0a4 4 0 1 1-8 0\" $P/>
    <path android:pathData=\"M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41\" $P/>"

mk ic_soft_megaphone "    <path android:pathData=\"m3 11 18-5v12L3 14v-3Z\" $P/>
    <path android:pathData=\"M11.6 16.8a3 3 0 1 1-5.8-1.6\" $P/>"

mk ic_soft_sparkle "    <path android:pathData=\"M12 3c.7 4.6 3.4 7.3 8 8-4.6.7-7.3 3.4-8 8-.7-4.6-3.4-7.3-8-8 4.6-.7 7.3-3.4 8-8Z\" $P/>"

mk ic_soft_add "    <path android:pathData=\"M12 5v14\" $P/>
    <path android:pathData=\"M5 12h14\" $P/>"

mk ic_soft_minus "    <path android:pathData=\"M5 12h14\" $P/>"

mk ic_soft_refresh "    <path android:pathData=\"M21 12a9 9 0 1 1-2.64-6.36L21 8\" $P/>
    <path android:pathData=\"M21 3v5h-5\" $P/>"

mk ic_soft_radar "    <path android:pathData=\"M12 12m-9 0a9 9 0 1 1 18 0a9 9 0 1 1-18 0\" $P/>
    <path android:pathData=\"M12 7a5 5 0 1 0 5 5\" $P/>
    <path android:pathData=\"M12 12l6.4-6.4\" $P/>
    <path android:pathData=\"M12 12h.01\" $P/>"

mk ic_soft_power "    <path android:pathData=\"M12 2v10\" $P/>
    <path android:pathData=\"M18.36 6.64a9 9 0 1 1-12.73 0\" $P/>"

mk ic_soft_user "    <path android:pathData=\"M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2\" $P/>
    <path android:pathData=\"M12 3m-4 0a4 4 0 1 1 8 0a4 4 0 1 1-8 0\" $P/>"

mk ic_soft_users "    <path android:pathData=\"M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2\" $P/>
    <path android:pathData=\"M9 3m-4 0a4 4 0 1 1 8 0a4 4 0 1 1-8 0\" $P/>
    <path android:pathData=\"M22 21v-2a4 4 0 0 0-3-3.87\" $P/>
    <path android:pathData=\"M16 3.13a4 4 0 0 1 0 7.75\" $P/>"

mk ic_soft_locate "    <path android:pathData=\"M12 12m-8 0a8 8 0 1 1 16 0a8 8 0 1 1-16 0\" $P/>
    <path android:pathData=\"M12 12m-3 0a3 3 0 1 1 6 0a3 3 0 1 1-6 0\" $P/>
    <path android:pathData=\"M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22\" $P/>
    <path android:pathData=\"M12 12h.01\" $P/>"

mk ic_soft_pin "    <path android:pathData=\"M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 0 1 16 0Z\" $P/>
    <path android:pathData=\"M12 10m-2.5 0a2.5 2.5 0 1 1 5 0a2.5 2.5 0 1 1-5 0\" $P/>"

mk ic_soft_globe "    <path android:pathData=\"M12 12m-9.5 0a9.5 9.5 0 1 1 19 0a9.5 9.5 0 1 1-19 0\" $P/>
    <path android:pathData=\"M12 2.5a14.5 14.5 0 0 0 0 19\" $P/>
    <path android:pathData=\"M12 2.5a14.5 14.5 0 0 1 0 19\" $P/>
    <path android:pathData=\"M2.5 12h19\" $P/>"

mk ic_soft_expand "    <path android:pathData=\"M8 3H5a2 2 0 0 0-2 2v3\" $P/>
    <path android:pathData=\"M21 8V5a2 2 0 0 0-2-2h-3\" $P/>
    <path android:pathData=\"M3 16v3a2 2 0 0 0 2 2h3\" $P/>
    <path android:pathData=\"M16 21h3a2 2 0 0 0 2-2v-3\" $P/>"

mk ic_soft_download "    <path android:pathData=\"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4\" $P/>
    <path android:pathData=\"m7 10 5 5 5-5\" $P/>
    <path android:pathData=\"M12 15V3\" $P/>"

mk ic_soft_trash "    <path android:pathData=\"M3 6h18\" $P/>
    <path android:pathData=\"M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6\" $P/>
    <path android:pathData=\"M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2\" $P/>
    <path android:pathData=\"M10 11v6\" $P/>
    <path android:pathData=\"M14 11v6\" $P/>"

mk ic_soft_id "    <path android:pathData=\"M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z\" $P/>
    <path android:pathData=\"M9 10.5m-2 0a2 2 0 1 1 4 0a2 2 0 1 1-4 0\" $P/>
    <path android:pathData=\"M6 16.5c.6-1.4 1.7-2 3-2s2.4.6 3 2\" $P/>
    <path android:pathData=\"M15.5 9H18\" $P/>
    <path android:pathData=\"M15.5 13H18\" $P/>"

mk ic_soft_eraser "    <path android:pathData=\"m7 21-4.3-4.3a2.4 2.4 0 0 1 0-3.4l9.6-9.6a2.4 2.4 0 0 1 3.4 0l5.6 5.6a2.4 2.4 0 0 1 0 3.4L13 21\" $P/>
    <path android:pathData=\"M22 21H7\" $P/>
    <path android:pathData=\"m5 11 9 9\" $P/>"

mk ic_soft_tower "    <path android:pathData=\"M8.4 9a5 5 0 0 1 7.2 0\" $P/>
    <path android:pathData=\"M5.6 6a9 9 0 0 1 12.8 0\" $P/>
    <path android:pathData=\"M12 11.5 8.5 21\" $P/>
    <path android:pathData=\"M12 11.5 15.5 21\" $P/>
    <path android:pathData=\"M7 21h10\" $P/>
    <path android:pathData=\"M12 11.5h.01\" $P/>"

mk ic_soft_database "    <path android:pathData=\"M12 3C7.58 3 4 4.12 4 5.5S7.58 8 12 8s8-1.12 8-2.5S16.42 3 12 3Z\" $P/>
    <path android:pathData=\"M4 5.5v13C4 19.88 7.58 21 12 21s8-1.12 8-2.5v-13\" $P/>
    <path android:pathData=\"M4 12c0 1.38 3.58 2.5 8 2.5s8-1.12 8-2.5\" $P/>"

mk ic_soft_settings "    <path android:pathData=\"M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z\" $P/>
    <path android:pathData=\"M12 12m-3 0a3 3 0 1 1 6 0a3 3 0 1 1-6 0\" $P/>"

mk ic_soft_send "    <path android:pathData=\"m22 2-7 20-4-9-9-4Z\" $P/>
    <path android:pathData=\"M22 2 11 13\" $P/>"

mk ic_soft_chat "    <path android:pathData=\"M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2Z\" $P/>"

mk ic_soft_clock "    <path android:pathData=\"M12 12m-9.5 0a9.5 9.5 0 1 1 19 0a9.5 9.5 0 1 1-19 0\" $P/>
    <path android:pathData=\"M12 7v5l3 2\" $P/>"

mk ic_soft_info "    <path android:pathData=\"M12 12m-9.5 0a9.5 9.5 0 1 1 19 0a9.5 9.5 0 1 1-19 0\" $P/>
    <path android:pathData=\"M12 11v5\" $P/>
    <path android:pathData=\"M12 8h.01\" $P/>"

mk ic_soft_link "    <path android:pathData=\"M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71\" $P/>
    <path android:pathData=\"M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71\" $P/>"

mk ic_soft_signal "    <path android:pathData=\"M4 18.5v-2\" $P/>
    <path android:pathData=\"M9 18.5v-5\" $P/>
    <path android:pathData=\"M14 18.5v-8\" $P/>
    <path android:pathData=\"M19 18.5v-11\" $P/>"

mk ic_soft_cpu "    <path android:pathData=\"M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z\" $P/>
    <path android:pathData=\"M9.5 9.5h5v5h-5Z\" $P/>
    <path android:pathData=\"M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3\" $P/>"

echo "Generated $(ls "$DIR"/ic_soft_*.xml | wc -l) icons"
