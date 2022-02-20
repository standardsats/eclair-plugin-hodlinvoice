package fr.acinq.hodlplugin

import com.softwaremill.sttp.Uri
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import net.ceedubs.ficus.Ficus._

import java.io.File

class HodlInvoiceConfig(datadir: File) {
  val resourcesDir: File = new File(datadir, "/plugin-resources/hodlinvoice/")

  val config: TypesafeConfig = ConfigFactory parseFile new File(resourcesDir, "hodlinvoice.conf")

  val password: String = config.as[String]("config.password")
  val apiHost: String = config.as[String]("config.binding-ip")
  val apiPort: Int = config.as[Int]("config.port")
}