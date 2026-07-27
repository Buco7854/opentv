package com.buco7854.opentv.data

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.XtreamSeries
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.system.measureNanoTime
import kotlin.test.Test

/**
 * Opt-in catalog benchmark. It stays out of the normal test budget; run with:
 *
 * OPENTV_SEARCH_BENCHMARK=1 ./gradlew :data:jvmTest \
 *   --tests com.buco7854.opentv.data.SearchPerformanceBenchmarkTest
 */
class SearchPerformanceBenchmarkTest {
    @Test
    fun realistic_catalog_search() = runTest {
        if (System.getenv("OPENTV_SEARCH_BENCHMARK") != "1") return@runTest

        val dir = Files.createTempDirectory("opentv-search-benchmark")
        val path = dir.resolve("opentv.db")
        val storage = createRoomStorage(path.toString())
        try {
            val playlistId = storage.playlists.insert(Playlist(name = "Benchmark", url = null))
            val channels = fixtureChannels(playlistId, CHANNEL_COUNT)
            val series = fixtureSeries(playlistId, SERIES_COUNT)
            val writeMs = measureNanoTime {
                storage.channels.replaceKinds(
                    playlistId,
                    listOf(ChannelKind.LIVE, ChannelKind.MOVIE, ChannelKind.SERIES),
                    channels,
                )
                storage.xtreamSeries.replaceAll(playlistId, series)
            }.nanosToMillis()

            val queries = listOf(
                "news",
                "central",
                "ntral",
                "aurora",
                "sports",
                "documentary",
                "cinema",
                "needle",
                "zzmissing",
                "world",
            )
            repeat(WARMUP_ROUNDS) {
                queries.forEach { query ->
                    storage.channels.search(playlistId, query)
                    storage.xtreamSeries.search(playlistId, query)
                }
            }

            val samplesByQuery = queries.associateWith { mutableListOf<Double>() }
            val samples = buildList {
                repeat(MEASURED_ROUNDS) {
                    queries.forEach { query ->
                        val elapsed = measureNanoTime {
                            storage.channels.search(playlistId, query)
                            storage.xtreamSeries.search(playlistId, query)
                        }.nanosToMillis()
                        add(elapsed)
                        samplesByQuery.getValue(query) += elapsed
                    }
                }
            }.sorted()

            println(
                "SEARCH_BENCHMARK channels=$CHANNEL_COUNT series=$SERIES_COUNT " +
                    "writeMs=${"%.1f".format(writeMs)} dbMiB=${"%.1f".format(Files.size(path) / MIB)} " +
                    "samples=${samples.size} p50Ms=${"%.3f".format(samples.percentile(0.50))} " +
                    "p95Ms=${"%.3f".format(samples.percentile(0.95))}"
            )
            println(
                samplesByQuery.entries.joinToString(
                    prefix = "SEARCH_BENCHMARK_QUERIES ",
                    separator = " ",
                ) { (query, values) ->
                    "$query=${"%.3f".format(values.sorted().percentile(0.50))}/" +
                        "%.3f".format(values.sorted().percentile(0.95))
                }
            )
        } finally {
            storage.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun fixtureChannels(playlistId: Long, count: Int): List<Channel> {
        val brands = listOf("World", "Metro", "Prime", "Global", "North", "Classic", "Vision", "Ultra")
        val topics = listOf("News", "Sports", "Cinema", "Documentary", "Family", "Central", "Culture", "Action")
        return List(count) { index ->
            val name = buildString {
                append(brands[index % brands.size])
                append(' ')
                append(topics[(index / brands.size) % topics.size])
                if (index % 997 == 0) append(" Aurora")
                if (index % 1231 == 0) append(" Needle")
                append(' ')
                append(index.toString().padStart(6, '0'))
            }
            val kind = index % 3
            Channel(
                playlistId = playlistId,
                name = name,
                url = "https://fixture.invalid/$index",
                logo = null,
                groupTitle = topics[(index / 17) % topics.size],
                tvgId = if (kind == ChannelKind.LIVE) "tvg-$index" else null,
                kind = kind,
                seriesKey = if (kind == ChannelKind.SERIES) "Series ${index / 4}" else null,
                season = if (kind == ChannelKind.SERIES) 1 else null,
                episode = if (kind == ChannelKind.SERIES) index % 24 else null,
                position = index,
            )
        }
    }

    private fun fixtureSeries(playlistId: Long, count: Int): List<XtreamSeries> {
        val adjectives = listOf("Hidden", "Final", "Northern", "Ancient", "Modern", "Secret", "Central", "Wild")
        val subjects = listOf("World", "Stories", "Files", "Cinema", "Newsroom", "Voyage", "Family", "Chronicles")
        return List(count) { index ->
            val name = buildString {
                append(adjectives[index % adjectives.size])
                append(' ')
                append(subjects[(index / adjectives.size) % subjects.size])
                if (index % 877 == 0) append(" Aurora")
                if (index % 1061 == 0) append(" Needle")
                append(' ')
                append(index.toString().padStart(5, '0'))
            }
            XtreamSeries(
                playlistId = playlistId,
                seriesId = index.toLong() + 1,
                name = name,
                categoryName = "Category ${index % 24}",
                cover = null,
                plot = null,
                castNames = null,
                genre = null,
                rating = null,
            )
        }
    }

    private fun Long.nanosToMillis(): Double = this / 1_000_000.0

    private fun List<Double>.percentile(fraction: Double): Double =
        this[((size - 1) * fraction).toInt()]

    private companion object {
        const val CHANNEL_COUNT = 120_000
        const val SERIES_COUNT = 30_000
        const val WARMUP_ROUNDS = 2
        const val MEASURED_ROUNDS = 8
        const val MIB = 1024.0 * 1024.0
    }
}
