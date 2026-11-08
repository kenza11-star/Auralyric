#!/usr/bin/env bash
# Removes files of the old "mochy" source that were left in the repo when the Vantra source was
# uploaded on top of it. They define the same strings/classes again and break the build
# ("Duplicate resources", duplicate classes). Safe to run on a clean tree: missing paths are ignored.
cd "$(dirname "$0")/../.."

# old Kotlin packages (main, foss, gms, test, modules)
find . -type d -path "*/kotlin/com/mochyy" -prune -exec rm -rf {} +
find . -type d -path "*/kotlin/com/mochyy" -prune 2>/dev/null

# old string files (one per language)
find app/src/main/res -name "mochy_strings.xml" -delete

# other leftovers
rm -rf "app/schemas/com.mochyy.mochymusic.db.InternalDatabase"
rm -rf "app/src/main/res/drawable-nodpi/mochy_discord.png"
rm -rf "app/src/main/res/drawable-nodpi/modules_bar_mochy.webp"
rm -rf "app/src/main/res/drawable/ic_launcher_anim.xml"
rm -rf "app/src/main/res/drawable/ic_launcher_anim_vector.xml"
rm -rf "app/src/main/res/drawable/ic_mochy_mark.xml"
rm -rf "app/src/main/res/drawable/license_mochy.xml"
rm -rf "app/src/main/res/drawable/mochy_equalizer.xml"
rm -rf "app/src/main/res/drawable/mochy_icon.xml"
rm -rf "app/src/main/res/drawable/mochy_library_circle.xml"
rm -rf "app/src/main/res/drawable/mochy_notification.xml"
rm -rf "app/src/main/res/drawable/mochy_wrapped_v1.png"
rm -rf "app/src/main/res/drawable/mochy_wrapped_v2.png"
rm -rf "app/src/main/res/drawable/nav_mochy_filled.xml"
rm -rf "app/src/main/res/drawable/nav_mochy_outline.xml"
rm -rf "app/src/main/res/drawable/support_mochy.xml"
rm -rf "assets/discord/v1/auralyric.png"
rm -rf "assets/discord/v1/mochy.png"

echo "old mochy leftovers removed"
