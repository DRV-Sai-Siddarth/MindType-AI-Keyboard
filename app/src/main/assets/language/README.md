# Offline language-model assets

The predictive engine loads all vocabulary and prediction data from this directory;
there are no vocabulary, n-gram, or emoji tables in Kotlin source.

Run `python tools/build_offline_language_assets.py` during a release build to create
the English dictionary, bigram, and CLDR emoji assets. Files may use a `.gz` suffix:
the loader streams them without expanding to disk. Keep each n-gram context to its
best candidates; the runtime independently enforces a 12-candidate cap as a
corruption/memory guard.
