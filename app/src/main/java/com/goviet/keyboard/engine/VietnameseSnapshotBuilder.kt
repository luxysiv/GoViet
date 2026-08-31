package com.goviet.keyboard.engine

/**
 * Utility for generating step-by-step composing snapshots from lexical analysis or canonical keystrokes
 * without creating a new [VietnameseComposer] instance per invocation.
 * Reuses a ThreadLocal instance to ensure zero allocation overhead and full thread-safety.
 */
object VietnameseSnapshotBuilder {

    private val threadLocalComposer = object : ThreadLocal<VietnameseComposer>() {
        override fun initialValue(): VietnameseComposer {
            return VietnameseComposer(EngineOptions())
        }
    }

    /**
     * Deconstructs a parsed Vietnamese word into its canonical keystrokes and step-by-step composing snapshots.
     */
    fun generate(
        analysis: VietnameseLexicalParser.AnalysisResult,
        options: EngineOptions
    ): Pair<String, List<VietnameseComposer.ComposerSnapshot>> {
        if (!analysis.isValid || analysis.canonicalRaw.isEmpty()) {
            return Pair("", emptyList())
        }
        val canonical = analysis.canonicalRaw
        val composer = threadLocalComposer.get() ?: VietnameseComposer(options)
        composer.options = options
        composer.reset()
        composer.ownership = CompositionOwnership.ADOPTED_VIETNAMESE

        val snapshots = ArrayList<VietnameseComposer.ComposerSnapshot>(canonical.length)
        for (i in 0 until canonical.length) {
            val c = canonical[i]
            composer.processKey(c)
            val top = composer.getTopSnapshot()
            if (top != null) {
                snapshots.add(top.copy())
            }
        }
        return Pair(canonical, snapshots)
    }

    /**
     * Convenience method to analyze a word and build deconstructed snapshots.
     */
    fun generate(
        word: String,
        options: EngineOptions
    ): Pair<String, List<VietnameseComposer.ComposerSnapshot>> {
        val analysis = VietnameseLexicalParser.analyze(word, options)
        return generate(analysis, options)
    }
}
