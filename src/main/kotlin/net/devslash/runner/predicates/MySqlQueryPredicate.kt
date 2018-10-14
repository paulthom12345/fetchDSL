package net.devslash.runner.predicates

import net.devslash.runner.RequestData
import net.devslash.runner.post.BaseMySQLSettings
import org.jetbrains.exposed.dao.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.function.Predicate

data class PredicateMySQLSettings<T : Table>(val url: String,
                                             val username: String,
                                             val password: String,
                                             val table: T,
                                             val op: (RequestData) -> Query)

object OtherResults : IntIdTable("result") {
  val media = varchar("media", 50)
  val result = blob("result")
}

class SelectAndCheck<T : Table> : BaseMySQLSettings() {
  var op: ((RequestData) -> Query)? = null
  var table: T? = null

  fun build(): PredicateMySQLSettings<T> {
    return PredicateMySQLSettings(url, username, password, table!!, op!!)
  }
}

class MySqlQueryPredicate<T : Table>(block: SelectAndCheck<T>.() -> Unit) : Predicate<RequestData> {
  private val settings = SelectAndCheck<T>().apply(block).build()

  init {
    Database.connect(settings.url, "com.mysql.jdbc.Driver", settings.username, settings.password)
  }

  override fun test(t: RequestData): Boolean {
    return transaction {
      val x = settings.op(t)
      if(x.count() >0) {
        println("Skipping ${t.getReplacements()}")
      }
      x.count() > 0
    }
  }

}
