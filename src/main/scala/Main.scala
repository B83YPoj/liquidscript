
import java.net.HttpCookie

import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.json.{JsValue, Json}
import scorex.account.AddressScheme
import scorex.crypto.encode.{Base16, Base58}
import scorex.crypto.hash.Sha256
import scorex.transaction.assets.TransferTransaction
import scorex.utils._
import scorex.wallet.Wallet

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import scalaj.http.Http

object Main extends App with ScorexLogging {

  val formatter: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")
  AddressScheme.current = new AddressScheme {
    val chainId: Byte = 87.toByte
  }
  val liquidProURL = "http://test.liquid.pro"
  val wavesPeer: String = "https://nodes.wavesnodes.com"
  val broadcastedTransactions: ArrayBuffer[TransferTransaction] = ArrayBuffer[TransferTransaction]()

  val seed = args(0)
  val email = args(1)
  val password = args(2)

  val wallet = new Wallet(None, Base58.encode(Sha256(seed)), Some(seed.getBytes))
  val myAddress = wallet.generateNewAccount().get

  log.info("Start script with address " + myAddress.address)

  loop(NTP.correctedTime() - 60000, (login(), NTP.correctedTime()))

  @tailrec
  def loop(lastTimestamp: Long, cookie: (HttpCookie, Long)): Unit = {
    val currentTime = NTP.correctedTime()
    val parsedResp = getLiquidProTransactions(lastTimestamp, cookie, currentTime)
    if ((parsedResp \ "success").as[Boolean]) {
      val transactionJss = (parsedResp \ "data").as[List[JsValue]]
      log.info("Got liquid.pro transactions: " + transactionJss.toString)
      transactionJss.foreach { tjs =>
        val tx = txFromJs(tjs)
        broadcastedTransactions += tx
        broadcastTransaction(tx)
      }
    } else {
      log.error("Incorrect response: " + parsedResp)
    }

    Thread.sleep(60000)

    checkBroadcasted()

    val newCookie = if (NTP.correctedTime() - cookie._2 < 7 * 24 * 60 * 1000) cookie
    else (login(), NTP.correctedTime())
    loop(currentTime, newCookie)
  }

  def txFromJs(tjs: JsValue): TransferTransaction = {
    val hash = (tjs \ "hash").as[String]
    val timestamp = DateTime.parse((tjs \ "time").as[String], formatter.withZone(DateTimeZone.UTC)).getMillis
    val attachment: Array[Byte] = Array(0: Byte) ++ Base16.decode(hash)
    attachmentTransactions(attachment, timestamp)
  }

  def getLiquidProTransactions(lastTimestamp: Long, cookie: (HttpCookie, Long), currentTime: Long): JsValue = {
    val from = new DateTime(lastTimestamp).toDateTime.withZone(DateTimeZone.forID("Europe/Moscow"))
      .toString("yyyy-MM-dd HH:mm:ss")
    val to = new DateTime(currentTime).toDateTime.withZone(DateTimeZone.forID("Europe/Moscow"))
      .toString("yyyy-MM-dd HH:mm:ss")
    val body = "{dateFrom: \"" + from + "\", dateTo: \"" + to + "\"}"
    val resp = liquidProPostRequest("/api/blockchain/GetQuotesLog", body, cookie._1)
    Json.parse(resp.body)
  }

  //keep transactions in local cache until they have enough confirmations
  def checkBroadcasted(): Unit = Try {
    val height = (wavesGetRequest("/blocks/height") \ "height").as[Int]
    broadcastedTransactions.foreach { tx =>
      val resp = wavesGetRequest("/transactions/info/" + Base58.encode(tx.id))
      (resp \ "height").asOpt[Int] match {
        case None =>
          log.info(s"Transaction ${Base58.encode(tx.id)} is not in blockchain")
          broadcastTransaction(tx)
        case Some(h) =>
          log.info(s"Transaction ${Base58.encode(tx.id)} have ${height - h} confirmations")
          if (height - h > 10) {
            broadcastedTransactions -= tx
          }
      }
    }
  }

  def login(): HttpCookie = {
    val loginResponse = liquidProPostRequest("/api/account/login",
      "{\"email\": \"" + email + "\",\"password\": \"" + password + "\"}",
      new HttpCookie("test", "test"))
    require((Json.parse(loginResponse.body) \ "success").as[Boolean], "Login failed: " + loginResponse.body)
    log.info("Successful login")
    loginResponse.cookies.head
  }

  def broadcastTransaction(tx: TransferTransaction) = {
    val resp = wavesPostRequest("/assets/broadcast/transfer", tx.json.toString())
    log.info(s"Transaction ${tx.json} broadcasted: " + resp)
  }

  def attachmentTransactions(attachment: Array[Byte], timestamp: Long): TransferTransaction = {
    TransferTransaction.create(None,
      myAddress,
      myAddress,
      1,
      timestamp: Long,
      None,
      100000,
      attachment: Array[Byte])
  }

  def wavesGetRequest(us: String): JsValue = {
    //todo push to multiple peers
    val response = Http(wavesPeer + us).asString
    Json.parse(response.body)
  }

  def wavesPostRequest(us: String,
                       body: String = ""): JsValue = {
    val response = Http(wavesPeer + us).postData(body).asString
    Json.parse(response.body)
  }

  def liquidProPostRequest(us: String,
                           body: String = "",
                           cookie: HttpCookie) = {
    Http(liquidProURL + us)
      .header("Content-Type", "application/json")
      .cookie(cookie)
      .postForm(Seq("email" -> "blockchain@liquid.pro", "password" -> "nOADUTEE6u"))
      .postData(body)
      .asString
  }
}