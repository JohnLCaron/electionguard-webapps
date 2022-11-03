package webapps.electionguard.decrypt

import com.github.michaelbull.result.getOrThrow
import electionguard.ballot.DecryptionResult
import electionguard.ballot.TallyResult
import electionguard.core.GroupContext
import electionguard.core.getSystemDate
import electionguard.core.getSystemTimeInMillis
import electionguard.core.productionGroup
import electionguard.decrypt.Decryptor
import electionguard.publish.Consumer
import electionguard.publish.Publisher
import electionguard.publish.PublisherMode
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import mu.KotlinLogging

/**
 * Run Trusted Tally Decryption CLI.
 * Read election record from inputDir, write to outputDir.
 * This has access to all the trustees, so is only used for testing, or in a use case of trust.
 */
fun main(args: Array<String>) {
    val parser = ArgParser("RunTrustedTallyDecryption")
    val inputDir by parser.option(
        ArgType.String,
        shortName = "in",
        description = "Directory containing input election record"
    ).required()
    val trusteeDir by parser.option(
        ArgType.String,
        shortName = "trustees",
        description = "Directory to read private trustees"
    ).required()
    val outputDir by parser.option(
        ArgType.String,
        shortName = "out",
        description = "Directory to write output election record"
    ).required()
    val remoteUrl by parser.option(
        ArgType.String,
        shortName = "remoteUrl",
        description = "URL of decrypting trustee app "
    ).required()
    val createdBy by parser.option(
        ArgType.String,
        shortName = "createdBy",
        description = "who created"
    )
    val missing by parser.option(
        ArgType.String,
        shortName = "missing",
        description = "missing guardians' xcoord, comma separated, eg '2,4'"
    )
    parser.parse(args)
    println("RunTrustedTallyDecryption starting\n   input= $inputDir\n   trustees= $trusteeDir\n   output = $outputDir")

    val group = productionGroup()
    runRemoteDecrypt(
        group,
        inputDir,
        outputDir,
        remoteUrl,
        missing,
        createdBy)
}

private val logger = KotlinLogging.logger("runRemoteDecrypt")

fun runRemoteDecrypt(
    group: GroupContext,
    inputDir: String,
    outputDir: String,
    remoteUrl: String,
    missing: String?,
    createdBy: String?
) {
    val starting = getSystemTimeInMillis()

    val consumerIn = Consumer(inputDir, group)
    val tallyResult: TallyResult = consumerIn.readTallyResult().getOrThrow { IllegalStateException(it) }
    val electionInitialized = tallyResult.electionInitialized

    // get the list of missing and present guardians
    val allGuardians = electionInitialized.guardians
    val missingGuardianIds =  if (missing.isNullOrEmpty()) emptyList() else {
        // remove missing guardians
        val missingX = missing.split(",").map { it.toInt() }
        allGuardians.filter { missingX.contains(it.xCoordinate) }.map { it.guardianId }
    }
    val presentGuardians =  allGuardians.filter { !missingGuardianIds.contains(it.guardianId) }
    val presentGuardianIds =  presentGuardians.map { it.guardianId }
    if (presentGuardianIds.size < electionInitialized.config.quorum) {
        logger.atError().log("number of guardians present ${presentGuardianIds.size} < quorum ${electionInitialized.config.quorum}")
        throw IllegalStateException("number of guardians present ${presentGuardianIds.size} < quorum ${electionInitialized.config.quorum}")
    }

    println("runRemoteDecrypt present = $presentGuardianIds missing = $missingGuardianIds")

    val client = HttpClient(Java) {
        install(ContentNegotiation) {
            json()
        }
    }

    val trustees = presentGuardians.map {
        DecryptingTrusteeProxy(client, remoteUrl, it.guardianId, it.xCoordinate, it.publicKey())
    }

    val decryptor = Decryptor(group,
        tallyResult.electionInitialized.cryptoExtendedBaseHash(),
        tallyResult.electionInitialized.jointPublicKey(),
        tallyResult.electionInitialized.guardians,
        trustees,
        missingGuardianIds)
    val decryptedTally = with(decryptor) { tallyResult.encryptedTally.decrypt() }

    val publisher = Publisher(outputDir, PublisherMode.createIfMissing)
    publisher.writeDecryptionResult(
        DecryptionResult(
            tallyResult,
            decryptedTally,
            decryptor.lagrangeCoordinates.values.sortedBy { it.guardianId },
            mapOf(
                Pair("CreatedBy", createdBy ?: "RunTrustedDecryption"),
                Pair("CreatedOn", getSystemDate().toString()),
                Pair("CreatedFromDir", inputDir))
        )
    )

    val took = getSystemTimeInMillis() - starting
    println("runRemoteDecrypt took $took millisecs")
}
