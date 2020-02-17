import org.goochjs.glicko2.Rating
import org.goochjs.glicko2.RatingCalculator
import org.goochjs.glicko2.RatingPeriodResults
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection.TRANSACTION_SERIALIZABLE

const val BASE_RATING = 1500.0
const val BASE_RD = 350.0

const val BATTLE_TEMPLATE = "#Battle Properties\n" +
        "robocode.battleField.width=800\n" +
        "robocode.battleField.height=600\n" +
        "robocode.battle.numRounds=3\n" +
        "robocode.battle.gunCoolingRate=0.1\n" +
        "robocode.battle.rules.inactivityTime=450\n" +
        "robocode.battle.initialPositions=(50,50,0),(?,?,?)\n" +
        "robocode.battle.selectedRobots={r1},{r2}"

data class RobotID(val name: String, val version: String)

fun compareVersions(strLeft: String, strRight: String): Int {
    val numLeft = strLeft.split('.').map { it.filter { c -> c.isDigit() } }.map { it.toInt() }
    val numRight = strRight.split('.').map { it.toInt() }
    numLeft.zip(numRight).forEach { (l, r) ->
        if (l.compareTo(r) != 0) return l.compareTo(r)
    }
    return 0
}

fun main(args: Array<String>) {
    val base = args[0]
    generation(
        basePath = base,
        databasePath = "$base/robocodedb",
        robotPath = "$base/robots",
        battlePath = "$base/battles",
        resultPath = "$base/results",
        replayPath = "$base/replay"
    )
}

/**
 * Runs a generation:
 *  - gives unrated robots a base rating
 *  - creates match ups using rating by writing to a specified folder
 *  - runs the battles using the command line (?)
 *  - updates the ratings, writing into DB
 *
 *  very side-effect, wow
 *
 *  Some considerations:
 *   - robots are expected to exist
 *   - robots can only be deleted once obsoleted (higher version number)
 */
fun generation(
    basePath: String,
    databasePath: String,
    robotPath: String,
    battlePath: String,
    resultPath: String,
    replayPath: String
) {
    Database.connect("jdbc:sqlite:${databasePath}", driver = "org.sqlite.JDBC")
    transaction(TRANSACTION_SERIALIZABLE, 2) {
        SchemaUtils.create(DSLRobot)
    }

    val robotIDs = scanRobots(File(robotPath)).take(10)
    robotIDs.forEach { id ->
        if (robotIsUnrated(id)) insertBaseRating(id, BASE_RATING, BASE_RD)
        updateVersion(id)
    }
    val robots = getRatedRobots()

    val matchups = generateMatchups(robots)

    generateBattles(
        filePath = File(battlePath),
        matchups = matchups
    )

    runBattles(
        robocodePath = File(basePath),
        battlePath = File(battlePath),
        resultPath = File(resultPath),
        replayPath = File(replayPath)
    )

    updateRatings(
        robots = robots,
        resultPath = File(resultPath)
    )
}

fun updateVersion(id: RobotID) {
    transaction(TRANSACTION_SERIALIZABLE, 2) {
        DSLRobot.update({ DSLRobot.name eq id.name }) {
            it[DSLRobot.version] = id.version
        }
    }
}

fun generateMatchups(robots: List<Robot>): Collection<Pair<Robot, Robot>> {
    val sortedRobots = robots.sortedByDescending { it.rating }
    return robots.flatMap { robot ->
        opponents(robot, sortedRobots)
    }.toSet()
}

fun opponents(robot: Robot, robots: List<Robot>): List<Pair<Robot, Robot>> {
    val opps = mutableSetOf<Robot>() // hello, don't think to much about it
    if (robot != robots.first())
        opps.add(robots.first())

    if (robot != robots.last())
        opps.add(robots.last())

    while (opps.size < 4 && opps.size < robots.size - 1) {
        val random = robots.random()
        if (random != robot) opps.add(random)
    }
    return opps.map { if (robot.name < it.name) robot to it else it to robot }
}

fun generateMatchupsAllVsAll(robots: List<Robot>): List<Pair<Robot, Robot>> =
    robots.foldIndexed(mutableListOf()) { i, list, robot ->
        list.addAll(robots.drop(i + 1).flatMap { other ->
            listOf(robot to other)
        })
        list
    }

fun runBattles(robocodePath: File, battlePath: File, resultPath: File, replayPath: File) {
    if (!resultPath.exists()) {
        println("Creating dir $resultPath"); resultPath.mkdir()
    } else {
        resultPath.listFiles().forEach { it.delete() }
    }
    if (!replayPath.exists()) {
        println("Creating dir $replayPath"); replayPath.mkdir()
    } else {
        replayPath.listFiles().forEach { it.delete() }
    }

    val processes = mutableListOf<Process>()
    println("Running ${battlePath.listFiles().size} battles")
    val rt = Runtime.getRuntime()
    battlePath.listFiles()
        .filter { it.extension == "battle" }
        .sorted()
        .forEach { battle ->
            val bName = battle.nameWithoutExtension
            val replay = if (bName[1] == 'r') " -record $replayPath/$bName" else ""
            val cmd = "bash ${robocodePath}/robocode.sh -nodisplay " +
                    "-battle $battle " +
                    "-results ${resultPath}/$bName.result$replay"
            val proc = rt.exec(cmd)
            processes.add(proc)
            proc.onExit().run {
                println("Finished $bName")
            }
        }
    for (proc in processes) {
        if (proc.isAlive) println("Waiting for $proc")
        proc.waitFor()
    }
}

/**
 * Read results in result folder and update ratings in database
 */
fun updateRatings(robots: List<Robot>, resultPath: File) {
    println("Updating ratings")
    val files = File(resultPath.path).listFiles()
    val results = files.map { parseMatch(it) }
    val calc = RatingCalculator()
    val ratings = robots.map {
        Rating(
            it.name,
            calc,
            it.rating,
            it.rd,
            calc.defaultVolatility
        )
    }
    val period = RatingPeriodResults()
    results.forEach { match ->
        period.addResult(
            ratings.find { it.uid == match.winner },
            ratings.find { it.uid == match.loser }
        )
    }
    calc.updateRatings(period)
    ratings.sortedBy { it.rating }.forEach { rating ->
        val version = robots.find { it.name == rating.uid }!!.version
        println(
            "${rating.uid.padEnd(
                40,
                ' '
            )}${version.padEnd(
                8,
                ' '
            )}(${rating.numberOfResults} matches) -- r: ${rating.rating}, rd: ${rating.ratingDeviation}"
        )
    }
    transaction(TRANSACTION_SERIALIZABLE, 2) {
        ratings.forEach { rating ->
            DSLRobot.update({ DSLRobot.name eq rating.uid }) {
                it[DSLRobot.rating] = rating.rating
                it[DSLRobot.rd] = rating.ratingDeviation
            }
        }
    }
}

data class MatchResult(val winner: String, val loser: String)

fun parseMatch(match: File): MatchResult {
    val (winner, loser) = match.readLines().drop(2)
        .map {
            val (nameAndID, score, _) = it.split("\t")
            nameAndID.substringAfter(' ').substringBefore(' ') to score.substringBefore(' ').toInt()
        }
        .sortedByDescending { (_, score) -> score }
        .map { (name, _) -> name }
    return MatchResult(winner, loser)
}

/**
 * Populates the battles folder with battles to run
 */
fun generateBattles(filePath: File, matchups: Collection<Pair<Robot, Robot>>) {
    // clean up in battle folder
    filePath.listFiles().forEach {
        if (it.extension == "battle") if (!it.delete())
            println("Failed deleting battle: $it")
    }
    val digits = matchups.size.toString().length
    matchups.shuffled().forEachIndexed { i, (r1, r2) ->
        val number = i.toString().padStart(digits, '0')
        val replay = if (i < 10) "r" else "n"
        val content = BATTLE_TEMPLATE
            .replace("{r1}", "${r1.name} ${r1.version}")
            .replace("{r2}", "${r2.name} ${r2.version}")
        val output = File("${filePath}/b$replay${number}-${r1.name}_${r1.version}-vs-${r2.name}_${r2.version}.battle")
        output.writeText(content)
    }
}

/**
 * Returns the name of all robots in the folder
 */
// TODO might need to validate, only select latest version of same named robot
// TODO some fallback system could be nice if versions dont line up
fun scanRobots(robotsPath: File): Collection<RobotID> = robotsPath.listFiles()
    .filter { it.isFile and (it.extension == "jar") }
    .map {
        val splitName = it.nameWithoutExtension.split("_")
        val version = splitName.last()
        val name = splitName.dropLast(1).joinToString("_")
        RobotID(name, version)
    }
    .sortedByDescending { it.version }
    .distinctBy { it.name }


/**
 * Return true if the robot cant be found in database
 */
fun robotIsUnrated(robotName: RobotID): Boolean {
    var notFound = false
    transaction(TRANSACTION_SERIALIZABLE, 2) {
        val robot = DSLRobot.select { DSLRobot.name eq robotName.name }
        notFound = robot.empty()
    }
    return notFound
}

/**
 * Initializes a robot's career (r:1500, rd:350) in the database
 */
fun insertBaseRating(robotId: RobotID, baseRating: Double, baseRd: Double) {
    transaction(TRANSACTION_SERIALIZABLE, 2) {
        DSLRobot.insert {
            it[name] = robotId.name
            it[version] = robotId.version
            it[rating] = baseRating
            it[rd] = baseRd
        }
    }
}

/**
 * Returns all robots in the database
 */
fun getRatedRobots(): List<Robot> {
    val robots = mutableListOf<Robot>()
    transaction(TRANSACTION_SERIALIZABLE, 2) {
        robots.addAll(DSLRobot.selectAll().map {
            Robot(
                it[DSLRobot.name],
                it[DSLRobot.version],
                it[DSLRobot.rating],
                it[DSLRobot.rd]
            )
        })
    }
    return robots
}