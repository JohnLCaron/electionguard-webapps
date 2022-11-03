package webapps.electionguard.keyceremony

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.getOrThrow
import com.github.michaelbull.result.unwrap
import electionguard.ballot.ElectionConfig
import electionguard.core.GroupContext
import electionguard.core.getSystemTimeInMillis
import electionguard.core.productionGroup
import electionguard.keyceremony.keyCeremonyExchange
import electionguard.publish.Consumer
import electionguard.publish.Publisher
import electionguard.publish.PublisherMode
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required

import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*

/**
 * Run KeyCeremony CLI.
 * This has access to all the trustees, so is only used for testing, or in a use case of trust.
 */
fun main(args: Array<String>) {
    val parser = ArgParser("RunRemoteKeyCeremony")
    val inputDir by parser.option(
        ArgType.String,
        shortName = "in",
        description = "Directory containing input ElectionConfig record"
    ).required()
    val trusteeDir by parser.option(
        ArgType.String,
        shortName = "trustees",
        description = "Directory to write private trustees"
    ).required()
    val outputDir by parser.option(
        ArgType.String,
        shortName = "out",
        description = "Directory to write output ElectionInitialized record"
    ).required()
    val remoteUrl by parser.option(
        ArgType.String,
        shortName = "remoteUrl",
        description = "URL of keyceremony trustee app "
    ).required()
    val createdBy by parser.option(
        ArgType.String,
        shortName = "createdBy",
        description = "who created"
    )
    parser.parse(args)
    println("RunRemoteKeyCeremony starting\n   input= $inputDir\n   trustees= $trusteeDir\n   output = $outputDir")

    val group = productionGroup()
    runKeyCeremony(group, remoteUrl, inputDir, outputDir, trusteeDir, createdBy)
}

fun runKeyCeremony(
    group: GroupContext,
    remoteUrl: String,
    configDir: String,
    outputDir: String,
    trusteeDir: String,
    createdBy: String?
): Boolean {
    val starting = getSystemTimeInMillis()

    val consumerIn = Consumer(configDir, group)
    val config: ElectionConfig = consumerIn.readElectionConfig().getOrThrow { IllegalStateException(it) }

    val client = HttpClient(Java) {
        install(ContentNegotiation) {
            json()
        }
    }

    val trustees: List<RemoteGuardianProxy> = List(config.numberOfGuardians) {
        val seq = it + 1
        RemoteGuardianProxy(client, remoteUrl,"trustee$seq", seq, config.quorum)
    }

    val exchangeResult = keyCeremonyExchange(trustees)
    if (exchangeResult is Err) {
        println(exchangeResult.error)
        return false
    }

    val keyCeremonyResults = exchangeResult.unwrap()
    val electionInitialized = keyCeremonyResults.makeElectionInitialized(
        config,
        mapOf(
            Pair("CreatedBy", createdBy ?: "runKeyCeremony"),
            Pair("CreatedFromDir", configDir),
        )
    )

    val publisher = Publisher(outputDir, PublisherMode.createIfMissing)
    publisher.writeElectionInitialized(electionInitialized)

    // tell the trustees to save their sate in some private place.
    trustees.forEach { it.saveState() }

    val took = getSystemTimeInMillis() - starting
    println("RunTrustedKeyCeremony took $took millisecs")

    return true
}
