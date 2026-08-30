package com.ogenrwotdaniel.wakeproof.verify.water.corpus

/**
 * One photograph in a depth-corpus shooting sequence.
 *
 * The offline water detector's thresholds were every one of them fitted on
 * synthetic scenes — images containing exactly the cues whoever wrote the
 * generator thought to put there. That tests "did I break the algorithm", not
 * "does the algorithm work", and `tools/water/evaluate.py --gate` has therefore
 * never been cleared against a real photograph. The corpus is the only thing
 * that closes that, and it cannot come from the internet: no public dataset
 * contains a before/after pair with a measured pour, and none ever will.
 *
 * What makes it affordable is shooting *sequences* rather than pairs. One
 * bottle photographed repeatedly as measured amounts come out of it yields
 * every ordered pair within the sequence — `n` shots give `n(n-1)/2` labelled
 * pairs, so eight shots give twenty-eight. `tools/water/sequences.py` does the
 * expansion; this class is the row it eats.
 *
 * @property sequence groups shots of one bottle under one lighting. Pairs are
 *   only ever formed *within* a sequence.
 * @property pouredTotalMl CUMULATIVE millilitres removed since the first shot
 *   of the sequence, measured with a jug. Truth for a pair is the difference of
 *   two of these, which is why it is cumulative rather than per-pour: a
 *   mis-recorded single pour would otherwise corrupt every later pair instead
 *   of just two.
 * @property unreadable marks the reject class — an opaque steel flask, where
 *   the honest answer is "I cannot see the line". Without real examples,
 *   `CONFIDENCE_FLOOR` stays fitted to a synthetic guess.
 */
data class CorpusShot(
    val sequence: String,
    val bottle: String,
    val capacityMl: Int,
    val lighting: String,
    val filename: String,
    val pouredTotalMl: Int,
    val crop: String = "",
    val unreadable: Boolean = false
)

/**
 * The header `tools/water/sequences.py` expects, verbatim.
 *
 * Kept as a constant next to the writer rather than assembled from the data
 * class's fields: the Python side is the contract, and a Kotlin refactor
 * renaming a property must not silently rename a CSV column that another
 * language reads.
 */
const val CORPUS_CSV_HEADER = "sequence,bottle,capacity_ml,lighting,filename,poured_total_ml,crop,unreadable"

/**
 * Serialises one shot as a CSV row.
 *
 * Quoting is minimal but real — a bottle called `clear, 750ml` would otherwise
 * shift every later column by one and mislabel the whole sequence silently,
 * which is the worst failure mode a corpus can have: wrong data that still
 * parses.
 */
fun CorpusShot.toCsvRow(): String = listOf(
    sequence,
    bottle,
    capacityMl.toString(),
    lighting,
    filename,
    pouredTotalMl.toString(),
    crop,
    if (unreadable) "1" else ""
).joinToString(",") { it.csvEscaped() }

private fun String.csvEscaped(): String =
    if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + replace("\"", "\"\"") + "\""
    } else {
        this
    }

/**
 * The filename a shot is stored under.
 *
 * Sequence-and-index rather than a timestamp so that the directory sorts into
 * shooting order and a row can be matched to its image by eye when a label
 * looks wrong — which, per `tools/water/README.md`, is the check that separates
 * "the detector is wrong" from "the label is wrong".
 */
fun corpusFilename(sequence: String, index: Int): String =
    "%s_%03d.jpg".format(sequence, index)
