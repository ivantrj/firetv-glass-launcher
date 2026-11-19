#!/bin/sh
# Change Glass without rebuilding. Glass reloads immediately.
#   ./configure.sh dock com.stremio.one org.smarttube.beta com.spotify.tv.android com.netflix.ninja
#   ./configure.sh dock reset                 back to the order in res/values/config.xml
#   ./configure.sh hide org.videolan.vlc ...  hide apps from the grid (adds to the built-in list)
#   ./configure.sh hide reset                 show them again
#   ./configure.sh wallpaper ~/Pictures/x.jpg JPG or PNG, shown dimmed behind everything
#   ./configure.sh wallpaper none             back to the sky
#   ./configure.sh location 51.5 -0.13        latitude longitude for the sky's sunrise and sunset
#   ./configure.sh location reset             guess from the time zone
#   ./configure.sh apps                       list installed package names
# Files live in Glass's private folder and are written with run-as (works for debug builds).
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
TV="$DIR/tools/tv"
PKG=com.ivantrj.glasslauncher

# put <local-file> <name-in-files-dir>
put() {
  "$TV" push "$1" "/data/local/tmp/$2"
  "$TV" shell "run-as $PKG sh -c 'mkdir -p files && cat > files/$2' < /data/local/tmp/$2"
  "$TV" shell "rm /data/local/tmp/$2"
}
remove() { "$TV" shell "run-as $PKG rm -f files/$1"; }
reload() { "$TV" shell "am start -n $PKG/.HomeActivity --ez reload true" >/dev/null; echo "Glass reloaded."; }

TMP="${TMPDIR:-/tmp}/glass-config.$$"
trap 'rm -f "$TMP"' EXIT

case "$1" in
  dock|hide)
    file=dock.txt; [ "$1" = hide ] && file=hidden.txt
    shift
    if [ "$1" = reset ] || [ -z "$1" ]; then remove $file; else printf '%s\n' "$@" > "$TMP"; put "$TMP" $file; fi
    reload ;;
  wallpaper)
    "$TV" shell "run-as $PKG rm -f files/wallpaper.jpg files/wallpaper.png"
    if [ "$2" != none ]; then
      case "$2" in *.png|*.PNG) name=wallpaper.png ;; *) name=wallpaper.jpg ;; esac
      put "$2" $name
    fi
    reload ;;
  location)
    if [ "$2" = reset ] || [ -z "$2" ]; then remove location.txt; else printf '%s %s\n' "$2" "$3" > "$TMP"; put "$TMP" location.txt; fi
    reload ;;
  apps)
    "$TV" shell "pm list packages" | sed 's/^package://' | sort ;;
  *)
    sed -n '2,12p' "$0"; exit 1 ;;
esac
