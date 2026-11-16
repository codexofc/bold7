#!/system/bin/sh
# Bold7, SystemUI crash-loop guard (the S3 failure: SystemUI dying about once a second).
#
#   logcat -b events -v monotonic | sh sysui-guard.sh <module dir> <command>
#
# If SystemUI dies 3 times within 60 s, the module disables itself and <command> runs (service.sh
# gives /system/bin/reboot, by its path; test-sysui-guard.sh gives echo), so the next boot is stock. 3 in 60 s stays below PackageWatchdog's trigger (5
# failures within 1 min) that starts Rescue Party, and a healthy SystemUI never dies twice in a
# minute. Times are seconds since boot (monotonic), immune to the clock being set during boot.
MODDIR=$1
a= b=
while read -r t rest; do
    # am_proc_died: [user,pid,process,adj,procstate]. SystemUI itself, not com.android.systemui:x.
    case $rest in
        *'am_proc_died: ['*,*,com.android.systemui,* | *'am_proc_died: ['*,*,com.android.systemui]*) ;;
        *) continue ;;
    esac
    t=${t%%.*}
    case $t in '' | *[!0-9]*) continue ;; esac
    if [ -n "$a" ] && [ $((t - a)) -le 60 ]; then
        touch "$MODDIR/disable"
        log -p e -t Bold7 "SystemUI guard: 3 deaths in $((t - a)) s, module disabled, rebooting"
        echo "SystemUI guard: 3 deaths in $((t - a)) s, module disabled, rebooting" >>"$MODDIR/bold7.log"
        $2
        exit 0
    fi
    a=$b b=$t
done
