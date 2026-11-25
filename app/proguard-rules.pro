# Dependencies ship their own consumer rules (WorkManager, Compose,
# kotlinx-coroutines, OkHttp). Keep missing-class checks enabled.
# App-specific keeps are added only against observed R8 output; the
# db-upgrade-test and screenshots CI jobs boot the app on an emulator, and
# the release build is exercised there to catch boundary breaks.

# R8 full mode repackages Kotlin metadata-driven lookups; the app's own
# reflectively-named entry points declared in the manifest are safe by default
# (activities/receivers/providers are kept automatically by aapt rules).
