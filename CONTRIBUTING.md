# Working on SoundWaive

Read the build instructions and architecture notes first. Preserve the default drippy three-point crown, fixed symbol color, text-off default and full-frame music-reactive design unless the owner requests a change.

Run `./gradlew check :app:assembleDebug` before proposing changes. Preview and export must use the same renderer and time base. For audio, rendering or lifecycle changes, exercise the affected flow on an Android device or emulator and record what was tested. Keep approximate musical analysis clearly labeled as estimates.

Keep signing material, personal SDK paths, generated builds and unrelated user songs out of Git. The generated demo and crown are intentional project assets. Never add the owner's unreleased recordings. Add new dependencies only when their size, license and maintenance cost are justified. Keep APK size comfortably below the owner's 1 GB ceiling.
