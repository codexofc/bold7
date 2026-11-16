## What and why

<!-- What changes, and why. Link the issue. -->

## Source

<!-- For anything visible or audible: the source of each value and its grade.
     F firmware, C native capture, M RIM manual, S recollection (cross-checked), D deduced for the square screen. -->

## Captures

<!-- Visual changes: the Q25 capture next to the reference capture, both at native size. -->

## Performance

<!-- Hot paths (home, drawer, transitions, lists, typing, inbox): tools/perf.sh before and after, on a Q25. -->

## Android leftovers

<!-- Anything Android this change meets and does not fix. -->

## Checklist

- [ ] `./gradlew test` passes, and new logic was written test first.
- [ ] Nothing new runs on the main thread except rendering.
- [ ] No BlackBerry asset added to the repository.
- [ ] No partition write, no `su` in an APK, exported components protected.
- [ ] Module changes: install and uninstall round trip done, state compared with `tools/state.sh`.
- [ ] New dependencies are Apache 2.0, MIT or BSD, and justified in the commit.
- [ ] Third-party code listed in `NOTICE`.
