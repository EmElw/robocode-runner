import org.jetbrains.exposed.sql.Table

object DSLRobot : Table() {
    val name = text("name").primaryKey()
    val version = text("version")
    val rating = double("rating").default(1500.0)
    val rd = double("rd").default(350.0)
}

data class Robot constructor(
    val name: String,
    val version: String,
    val rating: Double,
    val rd: Double
)