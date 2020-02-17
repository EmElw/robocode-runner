import org.goochjs.glicko2.Rating
import org.goochjs.glicko2.RatingCalculator
import org.goochjs.glicko2.RatingPeriodResults
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection.TRANSACTION_SERIALIZABLE

// TODO fixa läsbara instruktioner

const val DEVELOP = false

const val BASE_PATH = "/home/magnus/robocode"
const val ROBOT_PATH = "${BASE_PATH}/robots"
const val BATTLE_PATH = "${BASE_PATH}/battles"
const val RESULT_PATH = "${BASE_PATH}/results"
const val REPLAY_PATH = "${BASE_PATH}/replay"
const val DATABASE_PATH = "${BASE_PATH}/robocodedb"

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

fun main() {
    generation()
}

/**
 * Runs a generation:
 *  - gives unrated robots a base rating
 *  - creates match ups using rating by writing to a specified folder
 *  - runs the battles using the command line (?)
 *  - updates the ratings, writing into DB
 *
 *  very side-effect, wow
 */
fun generation() {
    Database.connect("jdbc:sqlite:${DATABASE_PATH}", driver = "org.sqlite.JDBC")
    transaction(TRANSACTION_SERIALIZABLE, 2) {
        SchemaUtils.create(DSLRobot)
        if (DEVELOP) DSLRobot.deleteAll()
    }


    val robotIDs = scanRobots(File(ROBOT_PATH)).take(10)

    robotIDs.forEach { id ->
        if (robotIsUnrated(id)) createBaseRating(id, BASE_RATING, BASE_RD)
    }
    val robots = getRobots()


    val matchups = generateMatchups(robots)

    generateBattles(
        filePath = File(BATTLE_PATH),
        matchups = matchups
    )

    runBattles(
        robocodePath = File(BASE_PATH),
        battlePath = File(BATTLE_PATH),
        resultPath = File(RESULT_PATH),
        replayPath = File(REPLAY_PATH)
    )

    updateRatings(
        robots = robots,
        resultPath = File(RESULT_PATH)
    )
}

fun generateMatchups(robots: List<Robot>): Collection<Pair<Robot, Robot>> {
    val sortedRobots = robots.sortedByDescending { it.rating }
    val m = robots.flatMap { robot ->
        opponents(robot, sortedRobots)
    }
    return m.toSet()
}

fun opponents(robot: Robot, robots: List<Robot>): List<Pair<Robot, Robot>> {
    val opps = mutableSetOf<Robot>() // hello, don' think to much about it
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
// TODO naive implementation, all vs all

fun runBattles(robocodePath: File, battlePath: File, resultPath: File, replayPath: File) {
    if (!resultPath.exists()) {
        println("Creating dir: $resultPath"); resultPath.mkdir()
    } else {
        resultPath.listFiles().forEach { it.delete() }
    }

    val processes = mutableListOf<Process>()
    println("Running ${battlePath.listFiles().size} battles")
    val rt = Runtime.getRuntime()
    battlePath.listFiles()
        .filter { it.extension == "battle" }
        .sorted()
        .forEach { battle ->
            val bName = battle.nameWithoutExtension
            val replay = if (bName.contains("replay")) " -replay $replayPath" else ""
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
    val ratingsBefore = ratings.map { rating ->
        rating.uid to rating.rating
    }
    val period = RatingPeriodResults()
    results.forEach { match ->
        period.addResult(
            ratings.find { it.uid == match.winner },
            ratings.find { it.uid == match.loser }
        )
    }
    calc.updateRatings(period)
    ratings.forEach { rating ->
        val ratingBefore = ratingsBefore.find { (uid, _) -> rating.uid == uid }!!.second
        println(
            "${rating.uid.padEnd(
                40,
                ' '
            )} (${rating.numberOfResults} matches) -- $ratingBefore to ${rating.rating} (diff: ${ratingBefore - rating.rating}"
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
    matchups.forEachIndexed { i, (r1, r2) ->
        val number = i.toString().padStart(digits, '0')

        val content = BATTLE_TEMPLATE
            .replace("{r1}", "${r1.name} ${r1.version}")
            .replace("{r2}", "${r2.name} ${r2.version}")
        val output = File("${filePath}/autobattle${number}.battle")
        output.writeText(content)
    }
}

/**
 * Returns the name of all robots in the folder
 */
fun scanRobots(robotsPath: File): List<RobotID> = robotsPath.listFiles()
    .filter { it.isFile and (it.extension == "jar") } // TODO might need to validate, only select latest version of same named robot
    .map {
        val splitName = it.nameWithoutExtension.split("_")
        val version = splitName.last()
        val name = splitName.dropLast(1).joinToString("_")
        RobotID(name, version)
    }

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
 * Initializes a robot's career (r:1500, rd:350)
 */
fun createBaseRating(robotId: RobotID, baseRating: Double, baseRd: Double) {
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
fun getRobots(): List<Robot> {
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