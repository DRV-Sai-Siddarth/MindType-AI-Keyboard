# Offline language-model assets

The predictive engine loads all vocabulary and prediction data from this directory;
there are no vocabulary, n-gram, or emoji tables in Kotlin source.

Production builds should replace the small development assets with locale-specific
exports using the schemas documented in `AssetDataLoader`. Files may be renamed with
a `.gz` suffix: the loader automatically streams GZIP assets without expanding them
to disk. Keep each n-gram context to its best candidates; the runtime independently
enforces a 12-candidate cap as a corruption/memory guard.
